package rp.clientinspector.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.network.ServerPlayerEntity;
import com.google.gson.GsonBuilder;
import me.lucko.fabric.api.permissions.v0.OfflinePermissionCheckEvent;
import net.fabricmc.fabric.api.util.TriState;
import rp.clientinspector.ClientInspector;
import rp.clientinspector.ClientInspectorConfig;
import rp.clientinspector.PermissionGate;
import rp.clientinspector.companion.ReportPayload;
import rp.clientinspector.companion.ReportedMod;
import rp.clientinspector.probe.ProbeKey;
import rp.clientinspector.rules.CompanionState;
import rp.clientinspector.rules.DefaultRules;
import rp.clientinspector.rules.ProbeState;
import rp.clientinspector.rules.Rule;
import rp.clientinspector.rules.RuleAction;
import rp.clientinspector.session.ClientSession;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * End to end: a real 1.21.11 client joins an in-process dedicated server running Client Inspector. The
 * brand and the Fabric API channels must be captured, the join probe must come back REPLIED with
 * every hack-client key echoed unresolved, and a self-test probe must resolve the vanilla key
 * {@code key.jump} (the client translates it, so the reply differs from the key). The client has the
 * jar too, so the client part must have reported its mod list during the configuration phase.
 */
public final class ProbeClientGameTest implements FabricClientGameTest {
	/** The default rules 0.2.0 adds; a 0.1.x rules.json lacks them. */
	private static final Set<String> CLIENT_PART_RULES = Set.of("reported-hack", "reported-hider", "reported-disallowed", "companion-mismatch");

	@Override
	public void runTest(ClientGameTestContext context) {
		int oldRuleCount = writeVersion1Config();
		// An empty 1.21.2+ server pauses after pause-when-empty-seconds and stops ticking; the client part's
		// configuration task must still finish, so join only after the server has paused.
		// The bypass lookup answers half a second late, like LuckPerms loading a user from storage, so the
		// client is held in the configuration phase by the bypass wait on a paused server.
		OfflinePermissionCheckEvent.EVENT.register((uuid, permission) -> PermissionGate.BYPASS.equals(permission)
				? CompletableFuture.supplyAsync(() -> TriState.DEFAULT, CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS))
				: CompletableFuture.completedFuture(TriState.DEFAULT));
		Properties properties = new Properties();
		properties.setProperty("pause-when-empty-seconds", "1");
		try (TestDedicatedServerContext server = context.worldBuilder().createServer(properties)) {
			// 0. The server upgraded the 0.1.x rules.json in place: the owner's rule stays, the new rules and mod ids arrive.
			List<Rule> upgraded = server.computeOnServer(s -> ClientInspector.detector().rules().rules());
			check(upgraded.size() == oldRuleCount + CLIENT_PART_RULES.size(), "expected " + (oldRuleCount + CLIENT_PART_RULES.size())
					+ " rules after the upgrade, got " + upgraded.size());
			check(upgraded.stream().anyMatch(r -> r.id.equals("owner-rule")), "the owner's own rule must survive the upgrade");
			check(upgraded.stream().map(r -> r.id).toList().containsAll(CLIENT_PART_RULES), "the client part rules must be added");
			check(upgraded.stream().anyMatch(r -> r.id.equals("meteor-client") && "meteor-client".equals(r.mod_id)), "meteor-client must get its mod_id");
			check(server.computeOnServer(s -> ClientInspector.detector().config().rules_version) == DefaultRules.VERSION, "rules_version must be bumped");

			context.waitTicks(60);
			int pausedAt = server.computeOnServer(s -> s.getTicks());
			context.waitTicks(20);
			check(server.computeOnServer(s -> s.getTicks()) == pausedAt, "the empty server should have paused before the join");
			try (TestServerConnection connection = server.connect()) {
				connection.getClientWorld().waitForChunksRender();

				// 1. Join probe: delay_ticks + jitter_ticks is at most 100 ticks by default.
				waitUntil(context, server, "join probe", 600, s -> session(s).probe() != ProbeState.NOT_RUN
						&& session(s).probe() != ProbeState.PENDING);
				ClientSession joined = server.computeOnServer(ProbeClientGameTest::session);
				String brand = joined.brand();
				Set<String> channels = joined.channels();
				Map<String, String> replies = joined.probeReplies();
				ClientInspector.LOGGER.info("[gametest] brand={} channels={} probe={} replies={}", brand, channels, joined.probe(), replies);

				check("fabric".equals(brand), "brand should be 'fabric' but was " + brand);
				check(channels.contains("fabric:registry/sync"), "configuration channels should include fabric:registry/sync: " + channels);
				check(channels.contains("fabric-screen-handler-api-v1:open_screen"), "play channels should include the screen handler channel: " + channels);
				check(joined.probe() == ProbeState.REPLIED, "join probe should be REPLIED but was " + joined.probe());
				check(!replies.isEmpty(), "join probe should have replies");
				for (Map.Entry<String, String> e : replies.entrySet()) {
					if (e.getKey().equals("pack.source.fabricmod")) {
						check("Fabric mod".equals(e.getValue()), "the Fabric API canary must translate on a client with Fabric API, got '" + e.getValue() + "'");
						continue;
					}
					String raw = ProbeKey.parse(e.getKey()).id();
					check(raw.equals(e.getValue()), "plain client must echo the raw id for " + e.getKey() + " but sent '" + e.getValue() + "'");
				}
				check(replies.containsKey("keybind:key.meteor-client.open-gui"), "the keybind probe line should have a reply: " + replies.keySet());
				check(joined.resolvedKeys().equals(java.util.Set.of("pack.source.fabricmod")),
						"only the Fabric API canary may resolve on a plain client: " + joined.resolvedKeys());
				check(joined.matchedRules().isEmpty(), "a plain Fabric client must match no rule: " + joined.matchedRules());

				// 2. Client part: this client has the jar, so it registered the hello channel and reported its mods
				// during the configuration phase, before the player existed.
				Map<String, ReportedMod> reported = joined.reportedMods();
				ReportPayload report = joined.report();
				ClientInspector.LOGGER.info("[gametest] companion={} reported={} mods (hashed: {}), loader={}, game={}, truncated={}",
						joined.companion(), reported.size(), reported.values().stream().filter(m -> m.sha256() != null).count(),
						report == null ? null : report.loaderVersion(), report == null ? null : report.gameVersion(),
						report == null ? null : report.truncated());
				check(channels.contains("clientinspector:hello"), "a client with the jar must register the hello channel: " + channels);
				check(joined.companion() == CompanionState.REPORTED, "companion should be REPORTED but was " + joined.companion());
				check(reported.containsKey("clientinspector"), "the report must list clientinspector: " + reported.keySet());
				check(reported.containsKey("fabric-api"), "the report must list fabric-api: " + reported.keySet());
				check(reported.containsKey("minecraft"), "the report must list minecraft: " + reported.keySet());
				check(report != null && "1.21.11".equals(report.gameVersion()), "the report must carry the game version: " + report);
				check(!report.truncated(), "a dev client's list must not be cut short");
				check(reported.values().stream().anyMatch(m -> m.sha256() != null), "at least one mod jar must be hashed: " + reported.values());
				check(joined.bypassResolved() && !joined.bypass(), "the late bypass answer must have been waited for");
				check(joined.mismatchReasons().isEmpty(), "an honest report must not contradict the probe: " + joined.mismatchReasons());

				// 3. Self-test probe: the vanilla key must come back translated.
				server.runOnServer(s -> {
					ServerPlayerEntity player = s.getPlayerManager().getPlayerList().get(0);
					ClientInspector.detector().startProbe(player, session(s), true);
				});
				waitUntil(context, server, "self-test probe", 400, s -> session(s).probe() == ProbeState.REPLIED
						|| session(s).probe() == ProbeState.NO_REPLY || session(s).probe() == ProbeState.BLANKED);
				ClientSession tested = server.computeOnServer(ProbeClientGameTest::session);
				ClientInspector.LOGGER.info("[gametest] self-test probe={} replies={} resolved={}", tested.probe(), tested.probeReplies(), tested.resolvedKeys());
				check(tested.probe() == ProbeState.REPLIED, "self-test probe should be REPLIED but was " + tested.probe());
				String jump = tested.probeReplies().get("key.jump");
				check(jump != null && !jump.isBlank() && !jump.equals("key.jump"),
						"key.jump should resolve to its translation but the reply was '" + jump + "'");
				check(tested.resolvedKeys().contains("key.jump"), "key.jump should be in the resolved set");
				check(tested.matchedRules().isEmpty(), "self-test must not match a rule: " + tested.matchedRules());
			}
		}

		// 4. Singleplayer: a player who installed the jar for the client part is never inspected in their own world.
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getClientWorld().waitForChunksRender();
			context.waitTicks(40);
			Boolean quiet = singleplayer.getServer().computeOnServer(s -> ClientInspector.detector().sessions().size() == 0
					&& s.getCommandManager().getDispatcher().getRoot().getChild("clientinspector") == null);
			check(Boolean.TRUE.equals(quiet), "the integrated server must track no session and register no /clientinspector command");
		}
	}

	/**
	 * Loom wipes the game test run folder every run, so write config files as 0.1.x left them: the
	 * default rules without the client part rules and without mod_id, an owner's own rule, and a
	 * config.json without rules_version. Returns the number of rules written.
	 */
	private static int writeVersion1Config() {
		List<Rule> rules = DefaultRules.create();
		rules.removeIf(r -> CLIENT_PART_RULES.contains(r.id));
		rules.forEach(r -> r.mod_id = null);
		Rule own = new Rule();
		own.id = "owner-rule";
		own.name = "Owner rule";
		own.brand = "^never-sent$";
		own.actions = new ArrayList<>(List.of(RuleAction.LOG));
		rules.add(own);
		Path dir = ClientInspectorConfig.dir();
		try {
			Files.createDirectories(dir);
			Files.writeString(dir.resolve("rules.json"), new GsonBuilder().setPrettyPrinting().create().toJson(rules));
			Files.writeString(dir.resolve("config.json"), "{\"monitor_only\": false}");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return rules.size();
	}

	private interface ServerCheck {
		boolean test(net.minecraft.server.MinecraftServer server);
	}

	private static void waitUntil(ClientGameTestContext context, TestDedicatedServerContext server, String what, int maxTicks, ServerCheck check) {
		for (int waited = 0; waited < maxTicks; waited += 10) {
			context.waitTicks(10);
			Boolean ok = server.computeOnServer(check::test);
			if (Boolean.TRUE.equals(ok)) {
				return;
			}
		}
		throw new AssertionError("timed out waiting for " + what + " after " + maxTicks + " ticks");
	}

	private static ClientSession session(net.minecraft.server.MinecraftServer server) {
		ServerPlayerEntity player = server.getPlayerManager().getPlayerList().isEmpty() ? null : server.getPlayerManager().getPlayerList().get(0);
		if (player == null) {
			throw new AssertionError("no player on the test server");
		}
		ClientSession session = ClientInspector.detector().sessions().byUuid(player.getUuid());
		if (session == null || session.isPlaceholder()) {
			throw new AssertionError("no Client Inspector session for " + player.getName().getString());
		}
		return session;
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
