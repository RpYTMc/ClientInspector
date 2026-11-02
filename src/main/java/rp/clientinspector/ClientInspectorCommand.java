package rp.clientinspector;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import rp.clientinspector.ClientInspectorConfig.CompanionMode;
import rp.clientinspector.audit.ProfileStore;
import rp.clientinspector.companion.ReportPayload;
import rp.clientinspector.detect.Detector;
import rp.clientinspector.rules.CompanionState;
import rp.clientinspector.rules.Rule;
import rp.clientinspector.rules.RuleSet;
import rp.clientinspector.session.BrandNormalizer;
import rp.clientinspector.session.ClientSession;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** /clientinspector (alias /ci) command tree (Fabric Command API v2 + brigadier). Staff only (clientinspector.admin). */
public final class ClientInspectorCommand {
	private ClientInspectorCommand() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher, Detector detector) {
		LiteralCommandNode<ServerCommandSource> root = dispatcher.register(CommandManager.literal("clientinspector")
				.requires(PermissionGate.admin())
				.executes(ctx -> status(ctx, detector))
				.then(CommandManager.literal("reload").executes(ctx -> reload(ctx, detector)))
				.then(CommandManager.literal("online").executes(ctx -> online(ctx, detector)))
				.then(CommandManager.literal("rules").executes(ctx -> rules(ctx, detector)))
				.then(CommandManager.literal("profile")
						.then(CommandManager.argument("player", StringArgumentType.word())
								.suggests((ctx, builder) -> {
									for (String name : ctx.getSource().getServer().getPlayerManager().getPlayerNames()) {
										builder.suggest(name);
									}
									return builder.buildFuture();
								})
								.executes(ctx -> profile(ctx, detector, StringArgumentType.getString(ctx, "player")))))
				.then(CommandManager.literal("probe")
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.executes(ctx -> probe(ctx, detector, false))
								.then(CommandManager.literal("selftest").executes(ctx -> probe(ctx, detector, true)))))
				.then(CommandManager.literal("alerts")
						.then(CommandManager.literal("on").executes(ctx -> alerts(ctx, detector, true)))
						.then(CommandManager.literal("off").executes(ctx -> alerts(ctx, detector, false))))
				.then(CommandManager.literal("companion")
						.executes(ctx -> companion(ctx, detector, null))
						.then(CommandManager.literal("off").executes(ctx -> companion(ctx, detector, CompanionMode.OFF)))
						.then(CommandManager.literal("optional").executes(ctx -> companion(ctx, detector, CompanionMode.OPTIONAL)))
						.then(CommandManager.literal("required").executes(ctx -> companion(ctx, detector, CompanionMode.REQUIRED)))));
		// /ci is a short alias: same permission, same subcommands.
		dispatcher.register(CommandManager.literal("ci").requires(root.getRequirement()).executes(root.getCommand()).redirect(root));
	}

	private static int status(CommandContext<ServerCommandSource> ctx, Detector detector) {
		ClientInspectorConfig cfg = detector.config();
		String text = "Client Inspector: " + detector.rules().rules().size() + " rules, probe "
				+ (cfg.probe.enabled ? "on" : "off") + ", client part " + cfg.companion.mode.id() + ", monitor_only=" + cfg.monitor_only + ", "
				+ detector.sessions().size() + " tracked connections, " + detector.profiles().size() + " stored profiles";
		ctx.getSource().sendFeedback(() -> Text.literal(text).formatted(Formatting.GRAY), false);
		return 1;
	}

	private static int reload(CommandContext<ServerCommandSource> ctx, Detector detector) {
		ClientInspectorConfig cfg;
		RuleSet rules;
		try {
			cfg = ClientInspectorConfig.loadOrThrow();
			rules = RuleSet.loadOrThrow(cfg);
		} catch (IOException | RuntimeException e) {
			ClientInspector.LOGGER.error("Failed to reload Client Inspector config; keeping the current one", e);
			ctx.getSource().sendError(Text.literal("Reload failed: " + ClientInspectorConfig.describe(e) + " - keeping current config"));
			return 0;
		}
		detector.reload(cfg, rules);
		int count = rules.rules().size();
		ctx.getSource().sendFeedback(() -> Text.literal("Client Inspector reloaded: " + count + " rules").formatted(Formatting.GREEN), true);
		return 1;
	}

	private static int online(CommandContext<ServerCommandSource> ctx, Detector detector) {
		List<ClientSession> sessions = detector.sessions().all();
		if (sessions.isEmpty()) {
			ctx.getSource().sendFeedback(() -> Text.literal("No tracked connections").formatted(Formatting.GRAY), false);
			return 0;
		}
		for (ClientSession s : sessions) {
			String line = s.name() + ": brand=" + (s.brand() == null ? "none" : s.brand())
					+ " channels=" + s.channels().size() + " probe=" + s.probe()
					+ (s.resolvedKeys().isEmpty() ? "" : " resolved=" + s.resolvedKeys())
					+ (s.matchedRules().isEmpty() ? "" : " matched=" + s.matchedRules())
					+ companionMark(s.companion(), s.reportedMods().size())
					+ (s.bypass() ? " [bypass]" : "");
			ctx.getSource().sendFeedback(() -> Text.literal(line).formatted(Formatting.GRAY), false);
		}
		return sessions.size();
	}

	private static int rules(CommandContext<ServerCommandSource> ctx, Detector detector) {
		for (Rule rule : detector.rules().rules()) {
			String line = (rule.enabled ? "" : "(off) ") + rule.id + " [" + rule.category.name().toLowerCase() + ", "
					+ rule.confidence + "] " + rule.actions;
			ctx.getSource().sendFeedback(() -> Text.literal(line).formatted(rule.enabled ? Formatting.GRAY : Formatting.DARK_GRAY), false);
		}
		return detector.rules().rules().size();
	}

	private static int profile(CommandContext<ServerCommandSource> ctx, Detector detector, String name) {
		ServerPlayerEntity online = ctx.getSource().getServer().getPlayerManager().getPlayer(name);
		ClientSession session = online != null ? detector.sessions().byUuid(online.getUuid()) : null;
		if (session != null) {
			send(ctx, session.name() + " (online, " + session.address + ")");
			send(ctx, "  brand: " + (session.brand() == null ? "none" : session.brand()) + "  loader: " + BrandNormalizer.loader(session.brand())
					+ (session.laterBrand() == null ? "" : "  (changed later to: " + session.laterBrand() + ")")
					+ (session.bypass() ? "  [bypass]" : ""));
			send(ctx, "  configuration channels (" + session.configChannels().size() + "): " + String.join(", ", session.configChannels()));
			send(ctx, "  play channels (" + session.playChannels().size() + "): " + String.join(", ", session.playChannels()));
			send(ctx, "  probe: " + session.probe() + "  resolved: " + session.resolvedKeys());
			for (Map.Entry<String, String> e : session.probeReplies().entrySet()) {
				send(ctx, "    " + e.getKey() + " -> '" + e.getValue() + "'");
			}
			send(ctx, "  matched rules: " + session.matchedRules());
			ReportPayload report = session.report();
			send(ctx, "  client part: " + session.companion() + (report == null ? ""
					: "  (loader " + report.loaderVersion() + ", game " + report.gameVersion() + ", " + report.mods().size() + " mods"
					+ (report.truncated() ? ", list cut short" : "") + ")"));
			for (String reason : session.mismatchReasons()) {
				send(ctx, "    mismatch: " + reason);
			}
			if (!session.reportedMods().isEmpty()) {
				send(ctx, "    " + String.join(", ", session.reportedMods().values().stream()
						.map(m -> m.id() + " " + m.version() + (m.nested() ? " (jar-in-jar)" : "")).toList()));
			}
			List<String> unknown = detector.unknownNamespaces(session);
			send(ctx, "  unlisted namespaces: " + (unknown.isEmpty() ? "none" : String.join(", ", unknown)));
			return 1;
		}
		Map.Entry<UUID, ProfileStore.Profile> stored = detector.profiles().findByName(name);
		if (stored == null) {
			ctx.getSource().sendError(Text.literal("No session or stored profile for '" + name + "'"));
			return 0;
		}
		ProfileStore.Profile p = stored.getValue();
		send(ctx, p.name + " (offline, last seen " + p.last_seen + ", " + p.joins + " joins, " + p.address + ")");
		send(ctx, "  brand: " + (p.brand == null ? "none" : p.brand) + "  loader: " + BrandNormalizer.loader(p.brand));
		send(ctx, "  channels (" + p.channels.size() + "): " + String.join(", ", p.channels));
		send(ctx, "  probe: " + p.probe);
		for (Map.Entry<String, String> e : p.probe_replies.entrySet()) {
			send(ctx, "    " + e.getKey() + " -> '" + e.getValue() + "'");
		}
		send(ctx, "  matched rules: " + p.matched_rules);
		send(ctx, "  client part: " + p.companion + (p.reported_mods.isEmpty() ? "" : "  (" + p.reported_mods.size() + " mods)"));
		for (String reason : p.companion_mismatch) {
			send(ctx, "    mismatch: " + reason);
		}
		if (!p.reported_mods.isEmpty()) {
			send(ctx, "    " + String.join(", ", p.reported_mods.stream()
					.map(m -> m.id + " " + m.version + (m.nested ? " (jar-in-jar)" : "")).toList()));
		}
		return 1;
	}

	/** Shows the client part mode, or sets it and saves config.json. Takes effect for the next connection. */
	private static int companion(CommandContext<ServerCommandSource> ctx, Detector detector, CompanionMode mode) {
		ClientInspectorConfig cfg = detector.config();
		if (mode == null) {
			long reported = detector.sessions().all().stream().filter(s -> s.companion() != CompanionState.NONE).count();
			send(ctx, "Client part: " + cfg.companion.mode.id() + " (" + reported + " of " + detector.sessions().size()
					+ " tracked connections have it), download " + cfg.companion.download_url);
			return 1;
		}
		if (!cfg.fromFile()) {
			ctx.getSource().sendError(Text.literal("config.json could not be read at startup; fix it and /clientinspector reload first"));
			return 0;
		}
		cfg.companion.mode = mode;
		cfg.save();
		String note = switch (mode) {
			case OFF -> "clients are not asked for their mod list";
			case OPTIONAL -> "clients with the jar report their mods, everyone may join";
			case REQUIRED -> "clients without the jar are refused" + (cfg.companion.exempt_bypass ? " (operators and bypass holders exempt)" : "");
		};
		ctx.getSource().sendFeedback(() -> Text.literal("Client part mode set to " + mode.id() + " from the next join: " + note)
				.formatted(Formatting.GREEN), true);
		return 1;
	}

	/** The verified mark in /clientinspector online: present only when the client part answered or should have. */
	private static String companionMark(CompanionState state, int reported) {
		return switch (state) {
			case NONE -> "";
			case REPORTED -> " [client part: " + reported + " mods]";
			case TIMEOUT -> " [client part: no report]";
			case MISMATCH -> " [client part: MISMATCH]";
		};
	}

	private static int probe(CommandContext<ServerCommandSource> ctx, Detector detector, boolean selfTest) throws CommandSyntaxException {
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
		ClientSession session = detector.sessions().byUuid(target.getUuid());
		if (session == null) {
			ctx.getSource().sendError(Text.literal("No tracked session for " + target.getName().getString()));
			return 0;
		}
		if (session.probe() == rp.clientinspector.rules.ProbeState.PENDING) {
			ctx.getSource().sendError(Text.literal("A probe is already running for " + target.getName().getString()
					+ "; check /clientinspector profile in a few seconds"));
			return 0;
		}
		List<rp.clientinspector.probe.ProbeKey> keys = detector.probeKeys(selfTest);
		if (keys.isEmpty()) {
			ctx.getSource().sendError(Text.literal("No enabled rule has translation keys and no self-test key is set"));
			return 0;
		}
		detector.startProbe(target, session, selfTest);
		String name = target.getName().getString();
		ctx.getSource().sendFeedback(() -> Text.literal("Probing " + name + " with " + keys.size() + " keys"
				+ (selfTest ? " (self-test included: expect the vanilla key to resolve)" : "")
				+ "; check /clientinspector profile " + name + " in a few seconds").formatted(Formatting.GRAY), false);
		return 1;
	}

	private static int alerts(CommandContext<ServerCommandSource> ctx, Detector detector, boolean on) throws CommandSyntaxException {
		ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
		detector.profiles().setAlertsMuted(player.getUuid(), !on);
		ctx.getSource().sendFeedback(() -> Text.literal("Client Inspector alerts " + (on ? "on" : "off") + " for you").formatted(Formatting.GRAY), false);
		return 1;
	}

	private static void send(CommandContext<ServerCommandSource> ctx, String line) {
		ctx.getSource().sendFeedback(() -> Text.literal(line).formatted(Formatting.GRAY), false);
	}
}
