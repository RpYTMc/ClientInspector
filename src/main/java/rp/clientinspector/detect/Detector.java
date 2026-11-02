package rp.clientinspector.detect;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.network.ServerConfigurationNetworkHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.jspecify.annotations.Nullable;
import rp.clientinspector.ClientInspector;
import rp.clientinspector.ClientInspectorConfig;
import rp.clientinspector.PermissionGate;
import rp.clientinspector.action.ActionExecutor;
import rp.clientinspector.action.Placeholders;
import rp.clientinspector.audit.ProfileStore;
import rp.clientinspector.companion.CompanionCheck;
import rp.clientinspector.companion.CompanionPolicy;
import rp.clientinspector.companion.CompanionServer;
import rp.clientinspector.companion.ReportPayload;
import rp.clientinspector.mixin.ServerCommonNetworkHandlerAccessor;
import rp.clientinspector.probe.CanaryCheck;
import rp.clientinspector.probe.ProbeKey;
import rp.clientinspector.probe.ProbeRunner;
import rp.clientinspector.rules.ChannelPattern;
import rp.clientinspector.rules.CompanionState;
import rp.clientinspector.rules.ProbeState;
import rp.clientinspector.rules.Rule;
import rp.clientinspector.rules.RuleAction;
import rp.clientinspector.rules.RuleMatcher;
import rp.clientinspector.rules.RulePhase;
import rp.clientinspector.rules.RuleSet;
import rp.clientinspector.rules.Signals;
import rp.clientinspector.session.BrandNormalizer;
import rp.clientinspector.session.ClientSession;
import rp.clientinspector.session.SessionRegistry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Collects the signals of every connection and evaluates the rules at three points: when the
 * configuration phase is about to finish (brand + configuration channels), at join (play channels
 * announced through c:register) and a short while after join (late registrations + probe reply).
 */
public final class Detector {
	private static final int PROFILE_SAVE_INTERVAL_TICKS = 600;
	private static final int MAX_PROBE_POSTPONES = 5;
	private static final int PROBE_POSTPONE_TICKS = 40;
	private static final int PROBE_RETRY_TICKS = 100;

	private volatile ClientInspectorConfig config;
	private volatile RuleSet rules;
	private final SessionRegistry sessions = new SessionRegistry();
	private final RuleMatcher matcher = new RuleMatcher();
	private final ActionExecutor actions;
	private final ProfileStore profiles;
	private final TickScheduler scheduler = new TickScheduler();
	private final Random random = new Random();
	private final CompanionServer companion;
	private final boolean loadConfigOnStart;
	private volatile ProbeRunner probe;
	private volatile @Nullable MinecraftServer server;
	/** True while an integrated (singleplayer or LAN) server runs: client installs never inspect those. */
	private volatile boolean dormant;
	private int lastRecheckTick = 0;
	private int lastProfileSaveTick = 0;

	/**
	 * @param loadConfigOnStart read the config files when a dedicated server starts instead of now
	 *                          (client installs, where only the client game test starts one)
	 */
	public Detector(ClientInspectorConfig config, RuleSet rules, ProfileStore profiles, ProbeRunner probe, boolean loadConfigOnStart) {
		this.config = config;
		this.rules = rules;
		this.profiles = profiles;
		this.probe = probe;
		this.loadConfigOnStart = loadConfigOnStart;
		this.actions = new ActionExecutor(() -> this.config, profiles);
		this.companion = new CompanionServer(() -> this.config, scheduler, this::onCompanionResult);
	}

	public ClientInspectorConfig config() {
		return config;
	}

	public RuleSet rules() {
		return rules;
	}

	public SessionRegistry sessions() {
		return sessions;
	}

	public ProfileStore profiles() {
		return profiles;
	}

	public ProbeRunner probe() {
		return probe;
	}

	public void setProbe(ProbeRunner probe) {
		this.probe = probe;
	}

	public void reload(ClientInspectorConfig config, RuleSet rules) {
		this.config = config;
		this.rules = rules;
	}

	// ------------------------------------------------------------------ lifecycle

	public void onServerStarted(MinecraftServer server) {
		dormant = !server.isDedicated();
		if (dormant) {
			return;
		}
		if (loadConfigOnStart) {
			ClientInspectorConfig loaded = ClientInspectorConfig.load();
			reload(loaded, RuleSet.load(loaded));
			ClientInspector.logInitialised(config, rules);
		}
		this.server = server;
		profiles.load();
	}

	/**
	 * SERVER_STOPPING fires before vanilla disconnects the players, and their DISCONNECT callbacks
	 * then arrive on the netty thread with the server already gone, so snapshot everyone first.
	 */
	public void onServerStopping() {
		if (config.audit.keep_profiles) {
			for (ClientSession session : sessions.all()) {
				if (session.joined() && !session.isPlaceholder()) {
					profiles.record(session, config.audit.max_profiles);
				}
			}
		}
		profiles.saveIfDirty();
		scheduler.clear();
		server = null;
	}

	public void onTick(MinecraftServer server) {
		int now = server.getTicks();
		scheduler.run(now);
		probe.tick(server);
		if (now - lastProfileSaveTick >= PROFILE_SAVE_INTERVAL_TICKS) {
			lastProfileSaveTick = now;
			profiles.saveIfDirty();
		}
		int recheck = config.probe.recheck_minutes;
		if (recheck > 0 && config.probe.enabled && now - lastRecheckTick >= recheck * 1200) {
			lastRecheckTick = now;
			for (ClientSession session : sessions.all()) {
				if (session.joined() && session.probe() != ProbeState.PENDING) {
					ServerPlayerEntity player = server.getPlayerManager().getPlayer(session.uuid());
					if (player != null) {
						startProbe(player, session, false);
					}
				}
			}
		}
	}

	// ------------------------------------------------------------------ configuration phase

	/** Netty thread. First custom payload of the configuration phase; some clients re-send a brand during play. */
	public void onBrand(ClientConnection connection, String brand) {
		if (dormant || !connection.isOpen()) {
			return;
		}
		// The brand arrives before BEFORE_CONFIGURE; a placeholder session holds it until the profile is known.
		ClientSession session = sessions.getOrCreate(connection, ClientSession.PLACEHOLDER_UUID, "?", address(connection));
		session.offerBrand(brand);
	}

	/** Netty thread. */
	public void onBeforeConfigure(ServerConfigurationNetworkHandler handler, MinecraftServer server) {
		if (dormant) {
			return;
		}
		ClientConnection connection = connection(handler);
		GameProfile profile = handler.getDebugProfile();
		ClientSession session = sessions.getOrCreate(connection, profile.id(), profile.name(), address(connection));
		if (session.isPlaceholder()) {
			session.promote(profile.id(), profile.name());
		}
		if (server.getPlayerManager().isOperator(new PlayerConfigEntry(profile))) {
			session.setBypass(true);
			return;
		}
		CompletableFuture<Boolean> lookup;
		try {
			lookup = PermissionGate.hasBypass(profile);
		} catch (RuntimeException e) {
			lookup = CompletableFuture.completedFuture(false);
		}
		session.setBypassLookup(lookup);
	}

	/** Server thread (Fabric schedules the event); the client's configuration-phase channel list. */
	public void onConfigChannels(ServerConfigurationNetworkHandler handler, List<Identifier> channels) {
		ClientConnection connection = connection(handler);
		if (dormant || !handler.isConnectionOpen()) {
			return;
		}
		ClientSession session = sessions.getOrCreate(connection, ClientSession.PLACEHOLDER_UUID, "?", address(connection));
		session.addConfigChannels(ids(channels));
		if (!session.isPlaceholder()) {
			resolveBypass(session);
			evaluate(session, text -> handler.disconnect(text));
		}
	}

	/**
	 * Netty thread. Brand and configuration channels are both known here; kick before the player exists.
	 * A client with the jar then gets the client part's report task.
	 */
	public void onConfigure(ServerConfigurationNetworkHandler handler, MinecraftServer server) {
		if (dormant) {
			return;
		}
		ClientConnection connection = connection(handler);
		ClientSession session = sessions.get(connection);
		if (session == null || session.isPlaceholder()) {
			return;
		}
		resolveBypass(session);
		evaluate(session, text -> handler.disconnect(text));
		if (session.kicked()) {
			return;
		}
		ClientInspectorConfig.CompanionMode mode = config.companion.mode;
		boolean hasClientPart = mode != ClientInspectorConfig.CompanionMode.OFF && CompanionServer.hasClientPart(handler);
		if (!session.bypassResolved() && (hasClientPart || mode == ClientInspectorConfig.CompanionMode.REQUIRED)) {
			// Configuration tasks run in order, so the report task below starts once the lookup has answered,
			// and the report's kick rules and the required-mode decision can act before the player exists.
			handler.addTask(new BypassWaitTask(handler, session, server, scheduler, config.companion.timeout_ticks, () -> {
				if (!hasClientPart) {
					requireClientPart(session, text -> handler.disconnect(text));
				}
				evaluate(session, text -> handler.disconnect(text));
			}));
		} else if (!hasClientPart) {
			requireClientPart(session, text -> handler.disconnect(text));
		}
		if (hasClientPart) {
			companion.start(handler, connection, session, server);
		}
	}

	/** Netty thread; the client part's answer to the hello. */
	public void onReport(ServerConfigurationNetworkHandler handler, ReportPayload report, MinecraftServer server) {
		if (dormant) {
			return;
		}
		companion.onReport(connection(handler), report, server);
	}

	/** Server thread; the report arrived (or timed out) while the client is still in the configuration phase. */
	private void onCompanionResult(ServerConfigurationNetworkHandler handler, ClientSession session) {
		resolveBypass(session);
		ReportPayload report = session.report();
		if (session.companion() == CompanionState.REPORTED && report != null && !report.truncated()) {
			List<String> reasons = CompanionCheck.fabricApiContradiction(session.channels(), session.reportedMods().keySet(), matcher);
			if (!reasons.isEmpty()) {
				session.markMismatch(reasons);
				ClientInspector.LOGGER.warn("{}: client part report does not add up: {}", session.name(), reasons);
			}
		}
		if (session.companion() == CompanionState.TIMEOUT) {
			requireClientPart(session, text -> handler.disconnect(text));
		}
		evaluate(session, text -> handler.disconnect(text));
	}

	/** Required mode: refuse a client without a report, or note that the decision waits for the bypass lookup. */
	private void requireClientPart(ClientSession session, Consumer<Text> kick) {
		CompanionPolicy.Outcome outcome = CompanionPolicy.enforce(config, session, skipProbe(session), kick);
		session.setRequiredCheckPending(outcome == CompanionPolicy.Outcome.WAITING);
	}

	/** After the probe: a report that leaves out a mod the probe just proved was forged. */
	private void crossCheck(ClientSession session) {
		ReportPayload report = session.report();
		if (report == null || report.truncated()) {
			return;
		}
		List<String> reasons = CompanionCheck.probeContradictions(rules.rules(), session.resolvedKeys(), session.reportedMods().keySet(), matcher);
		if (!reasons.isEmpty()) {
			session.markMismatch(reasons);
			ClientInspector.LOGGER.warn("{}: client part report contradicts the probe: {}", session.name(), reasons);
		}
	}

	public void onConfigDisconnect(ServerConfigurationNetworkHandler handler) {
		ClientConnection connection = connection(handler);
		companion.forget(connection);
		ClientSession session = sessions.remove(connection);
		if (session != null) {
			probe.forget(session);
		}
	}

	// ---------------------------------- play phase (server thread; DISCONNECT may arrive on the netty thread)

	public void onJoin(ServerPlayNetworkHandler handler, MinecraftServer server) {
		ClientConnection connection = connection(handler);
		ServerPlayerEntity player = handler.player;
		if (dormant || !connection.isOpen()) {
			return;
		}
		ClientSession session = sessions.getOrCreate(connection, player.getUuid(), player.getName().getString(), address(connection));
		if (session.isPlaceholder()) {
			// Configuration events were skipped (e.g. a transfer); keep whatever the placeholder saw.
			session.promote(player.getUuid(), player.getName().getString());
		}
		session.markJoined(server.getTicks());
		session.advance(RulePhase.JOIN);
		resolveBypass(session);
		if (!session.bypassResolved() || !session.bypass()) {
			session.setBypass(PermissionGate.hasBypass(player));
		}
		Consumer<Text> kick = kickAfterJoin(player, server);
		if (session.requiredCheckPending()) {
			requireClientPart(session, kick);
		}
		try {
			session.addPlayChannels(ids(ServerPlayNetworking.getSendable(handler)));
		} catch (RuntimeException e) {
			ClientInspector.LOGGER.debug("getSendable failed at join for {}", session.name(), e);
		}
		if (config.audit.keep_profiles) {
			profiles.countJoin(session.uuid());
		}
		evaluate(session, kick);
		int delay = config.probe.delay_ticks + (config.probe.jitter_ticks > 0 ? random.nextInt(config.probe.jitter_ticks + 1) : 0);
		ClientSession scheduled = session;
		scheduler.schedule(server.getTicks() + delay, () -> settle(scheduled));
	}

	public void onPlayRegister(ServerPlayNetworkHandler handler, List<Identifier> channels) {
		ClientSession session = sessions.get(connection(handler));
		if (session == null) {
			return;
		}
		session.addPlayChannels(ids(channels));
		MinecraftServer srv = server;
		if (session.joined() && handler.player != null && srv != null) {
			evaluate(session, kickAfterJoin(handler.player, srv));
		}
	}

	/**
	 * Kicks decided in the JOIN event and the channel registrations that arrive with it wait one tick.
	 * Fabric fires JOIN while vanilla is still adding the player: a disconnect at that point removes a
	 * player that is not listed yet, vanilla then adds it anyway, and a ghost entity stays in the world.
	 */
	private Consumer<Text> kickAfterJoin(ServerPlayerEntity player, MinecraftServer server) {
		return text -> scheduler.schedule(server.getTicks() + 1, () -> {
			if (player.networkHandler.isConnectionOpen()) {
				player.networkHandler.disconnect(text);
			}
		});
	}

	public void onPlayUnregister(ServerPlayNetworkHandler handler, List<Identifier> channels) {
		ClientSession session = sessions.get(connection(handler));
		if (session != null) {
			session.removePlayChannels(ids(channels));
		}
	}

	public void onPlayDisconnect(ServerPlayNetworkHandler handler) {
		ClientSession session = sessions.remove(connection(handler));
		if (session == null) {
			return;
		}
		probe.forget(session);
		if (session.probe() == ProbeState.PENDING) {
			session.setProbe(ProbeState.NOT_RUN);
		}
		if (session.joined() && config.audit.keep_profiles) {
			onServerThread(() -> profiles.record(session, config.audit.max_profiles));
		}
	}

	// ------------------------------------------------------------------ settle + probe

	private void settle(ClientSession session) {
		MinecraftServer srv = server;
		if (srv == null || !session.joined() || session.kicked()) {
			return;
		}
		ServerPlayerEntity player = srv.getPlayerManager().getPlayer(session.uuid());
		if (player == null || sessions.byUuid(session.uuid()) != session) {
			return;
		}
		session.advance(RulePhase.SETTLED);
		if (config.probe.enabled && !skipProbe(session)) {
			startProbe(player, session, false);
		} else {
			evaluate(session, text -> player.networkHandler.disconnect(text));
			finish(session);
		}
	}

	/** Brands the probe must not be sent to (Bedrock players behind Geyser do not have a Java sign editor). */
	private boolean skipProbe(ClientSession session) {
		String brand = session.brand();
		if (brand == null) {
			return false;
		}
		for (String regex : config.probe.skip_brands) {
			try {
				if (Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(brand).find()) {
					return true;
				}
			} catch (PatternSyntaxException ignored) {
				// reported at reload
			}
		}
		return false;
	}

	/** Probe lines from every enabled rule (translation keys and keybind ids), plus the self-test key when asked. */
	public List<ProbeKey> probeKeys(boolean selfTest) {
		Set<ProbeKey> keys = new LinkedHashSet<>();
		if (selfTest) {
			keys.add(ProbeKey.translation(config.probe.self_test_key));
		}
		for (Rule rule : rules.rules()) {
			if (!rule.enabled) {
				continue;
			}
			for (String key : rule.translation_keys) {
				keys.add(ProbeKey.translation(key));
			}
			for (String id : rule.keybind_keys) {
				keys.add(ProbeKey.keybind(id));
			}
		}
		for (ClientInspectorConfig.Canary canary : config.probe.canaries) {
			keys.add(ProbeKey.translation(canary.key));
		}
		return new ArrayList<>(keys);
	}

	/**
	 * Starts the probe now, unless the player has a container open (the probe would close it): then
	 * it is retried a little later, a few times, before going ahead anyway.
	 */
	public void startProbe(ServerPlayerEntity player, ClientSession session, boolean selfTest) {
		MinecraftServer srv = server;
		if (session.probe() == ProbeState.PENDING) {
			return;
		}
		List<ProbeKey> keys = probeKeys(selfTest);
		if (keys.isEmpty()) {
			session.setProbe(ProbeState.NOT_RUN);
			evaluate(session, text -> player.networkHandler.disconnect(text));
			finish(session);
			return;
		}
		boolean busy = player.currentScreenHandler != player.playerScreenHandler || player.isDead();
		if (srv != null && busy && session.probePostpones() < MAX_PROBE_POSTPONES) {
			session.countProbePostpone();
			scheduler.schedule(srv.getTicks() + PROBE_POSTPONE_TICKS, () -> {
				ServerPlayerEntity again = srv.getPlayerManager().getPlayer(session.uuid());
				if (again != null && sessions.byUuid(session.uuid()) == session && !session.kicked()) {
					startProbe(again, session, selfTest);
				}
			});
			return;
		}
		session.clearProbe();
		session.setProbe(ProbeState.PENDING);
		probe.start(player, session, keys);
	}

	/** Called by the probe on the server thread with every reply (probe label to raw line) or what it has on timeout. */
	public void onProbeFinished(ClientSession session, ProbeState state, Map<String, String> replies) {
		for (Map.Entry<String, String> e : replies.entrySet()) {
			String label = e.getKey();
			String reply = e.getValue();
			boolean resolved = ProbeKey.parse(label).resolvedBy(reply);
			session.recordProbeReply(label, reply, resolved);
		}
		MinecraftServer srv = server;
		if (state == ProbeState.NO_REPLY && replies.isEmpty() && srv != null && config.probe.retry_on_no_reply
				&& session.probeRetries() == 0 && !session.kicked()) {
			// An honest client whose chunk had not arrived yet ignores the fake sign; give it one more chance.
			session.countProbeRetry();
			session.setProbe(ProbeState.NOT_RUN);
			scheduler.schedule(srv.getTicks() + PROBE_RETRY_TICKS, () -> {
				ServerPlayerEntity again = srv.getPlayerManager().getPlayer(session.uuid());
				if (again != null && sessions.byUuid(session.uuid()) == session && !session.kicked()) {
					startProbe(again, session, false);
				}
			});
			return;
		}
		if (state == ProbeState.REPLIED) {
			List<String> rawCanaries = CanaryCheck.unresolvedCanaries(config.probe.canaries, session.channels(), session.resolvedKeys(), matcher);
			if (!rawCanaries.isEmpty()) {
				state = ProbeState.FILTERED;
				ClientInspector.LOGGER.info("{}: canary keys {} came back raw although their mods are present: a translation filter is active",
						session.name(), rawCanaries);
			}
		}
		session.setProbe(state);
		session.advance(RulePhase.SETTLED);
		crossCheck(session);
		if (srv == null) {
			return;
		}
		ServerPlayerEntity player = srv.getPlayerManager().getPlayer(session.uuid());
		if (player != null && !session.kicked()) {
			evaluate(session, text -> player.networkHandler.disconnect(text));
		}
		finish(session);
	}

	/** Audit log line, unknown-namespace alert and profile snapshot, once the session has settled. */
	private void finish(ClientSession session) {
		MinecraftServer srv = server;
		List<String> unknown = unknownNamespaces(session);
		if (config.audit.log_joins) {
			ClientInspector.LOGGER.info("{} ({}) brand='{}' loader={} channels={} {} probe={} resolved={} companion={} reported={} matched={} unknown={}{}",
					session.name(), session.uuid(), Placeholders.displayBrand(session.brand()), BrandNormalizer.loader(session.brand()),
					session.channels().size(), session.channels(), session.probe(), session.resolvedKeys(),
					session.companion(), session.reportedMods().size(), session.matchedRules(), unknown,
					session.laterBrand() == null ? "" : " laterBrand='" + Placeholders.displayBrand(session.laterBrand()) + "'");
		}
		if (srv != null && config.alerts.unknown_namespaces && !unknown.isEmpty() && config.alerts.in_game) {
			Text text = Text.literal(config.messages.alert_prefix + session.name() + " has unlisted mod channels: "
					+ String.join(", ", unknown)).formatted(Formatting.GRAY);
			for (ServerPlayerEntity staff : srv.getPlayerManager().getPlayerList()) {
				if (PermissionGate.canSeeAlerts(staff) && !profiles.alertsMuted(staff.getUuid())) {
					staff.sendMessage(text);
				}
			}
		}
		if (config.audit.keep_profiles) {
			profiles.record(session, config.audit.max_profiles);
		}
	}

	/** Channel namespaces that neither the known list nor any rule's channel patterns cover. */
	public List<String> unknownNamespaces(ClientSession session) {
		Set<String> out = new TreeSet<>();
		List<String> known = config.known_namespaces;
		List<String> rulePatterns = new ArrayList<>();
		for (Rule rule : rules.rules()) {
			rulePatterns.addAll(rule.channels_any);
			rulePatterns.addAll(rule.channels_all);
		}
		for (String channel : session.channels()) {
			if (matcher.matchesAnyPattern(known, channel) || matcher.matchesAnyPattern(rulePatterns, channel)
					|| ChannelPattern.namespace(channel).equals(ClientInspector.MOD_ID)) {
				continue;
			}
			out.add(ChannelPattern.namespace(channel));
		}
		return new ArrayList<>(out);
	}

	// ------------------------------------------------------------------ evaluation

	/**
	 * Runs every rule allowed in the session's phase. While the bypass answer is still pending
	 * (configuration phase, LuckPerms lookup not back yet) rules that would kick or ban are left
	 * unfired, so they get their turn at join when the answer is known.
	 */
	private void evaluate(ClientSession session, Consumer<Text> kick) {
		MinecraftServer srv = server;
		if (srv == null || session.kicked()) {
			return;
		}
		boolean enforcementAllowed = session.bypassResolved() || session.joined();
		Signals signals = session.signals();
		List<Rule> matched = matcher.matching(rules.rules(), signals, session.phase());
		for (Rule rule : matched) {
			boolean enforces = rule.actions.contains(RuleAction.KICK) || rule.actions.contains(RuleAction.BAN);
			if (enforces && !enforcementAllowed) {
				continue;
			}
			if (!session.markFired(rule.id)) {
				continue;
			}
			actions.execute(session, rule, srv, kick);
			if (session.kicked()) {
				break;
			}
		}
	}

	static void resolveBypass(ClientSession session) {
		if (session.bypassResolved()) {
			return;
		}
		CompletableFuture<Boolean> lookup = session.bypassLookup();
		if (lookup != null && lookup.isDone()) {
			boolean allowed = false;
			try {
				allowed = Boolean.TRUE.equals(lookup.getNow(false));
			} catch (RuntimeException ignored) {
				// a failed lookup means no bypass
			}
			session.setBypass(allowed);
		}
	}

	private void onServerThread(Runnable task) {
		MinecraftServer srv = server;
		if (srv == null || srv.isOnThread()) {
			task.run();
		} else {
			srv.execute(task);
		}
	}

	private static ClientConnection connection(Object handler) {
		return ((ServerCommonNetworkHandlerAccessor) handler).clientinspector$getConnection();
	}

	private static String address(ClientConnection connection) {
		Object addr = connection.getAddress();
		String s = addr == null ? "" : addr.toString();
		return s.startsWith("/") ? s.substring(1) : s;
	}

	private static List<String> ids(Iterable<Identifier> identifiers) {
		List<String> out = new ArrayList<>();
		for (Identifier id : identifiers) {
			out.add(id.toString());
		}
		return out;
	}
}
