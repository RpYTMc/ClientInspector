package rp.doorman.action;

import net.minecraft.server.BannedPlayerEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import rp.doorman.Doorman;
import rp.doorman.DoormanConfig;
import rp.doorman.PermissionGate;
import rp.doorman.audit.ProfileStore;
import rp.doorman.rules.Rule;
import rp.doorman.rules.RuleAction;
import rp.doorman.rules.RuleCategory;
import rp.doorman.session.BrandNormalizer;
import rp.doorman.session.ClientSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Turns a matched rule into log lines, staff alerts, webhook posts, commands, bans and kicks.
 * The kick itself runs on the calling thread through the supplied callback (configuration-phase
 * disconnects are legal from the netty thread); everything else is handed to the server thread.
 */
public final class ActionExecutor {
	private final Supplier<DoormanConfig> config;
	private final ProfileStore profiles;
	private final Map<String, Long> lastAlert = new ConcurrentHashMap<>();

	public ActionExecutor(Supplier<DoormanConfig> config, ProfileStore profiles) {
		this.config = config;
		this.profiles = profiles;
	}

	/**
	 * @param kick   disconnects the client with the given text; may be invoked on the calling thread
	 * @param server the server, used to hop to the main thread for everything but the kick
	 */
	public void execute(ClientSession session, Rule rule, MinecraftServer server, Consumer<Text> kick) {
		DoormanConfig cfg = config.get();
		boolean wantsKick = rule.actions.contains(RuleAction.KICK);
		boolean wantsBan = rule.actions.contains(RuleAction.BAN);
		boolean wantsCommand = rule.actions.contains(RuleAction.COMMAND);
		boolean protectedPlayer = session.bypass();
		boolean enforce = (wantsKick || wantsBan) && !cfg.monitor_only && !protectedPlayer && !session.kicked();
		String suppressed = null;
		if ((wantsKick || wantsBan || wantsCommand) && !enforce && !session.kicked()) {
			suppressed = protectedPlayer ? "bypass (op or permission)" : cfg.monitor_only ? "monitor_only" : null;
		}

		String kickText = Placeholders.apply(Placeholders.firstNonBlank(rule.kick_message, cfg.messages.kick), session, rule);
		String banReason = Placeholders.apply(Placeholders.firstNonBlank(rule.ban_reason, cfg.messages.ban), session, rule);

		if (enforce) {
			session.markKicked();
			if (wantsBan) {
				// The ban entry must be written on the server thread; vanilla then refuses the next login.
				server.execute(() -> ban(server, session, banReason, cfg.ban_source));
				kick.accept(Text.literal(banReason));
			} else {
				kick.accept(Text.literal(kickText));
			}
		}

		final String suppressedReason = suppressed;
		final boolean enforced = enforce;
		Runnable rest = () -> {
			boolean cooldown = onCooldown(session, rule, cfg);
			boolean notified = false;
			if (rule.actions.contains(RuleAction.LOG) || (rule.actions.contains(RuleAction.ALERT) && cfg.alerts.console)) {
				log(session, rule, enforced, wantsBan, suppressedReason);
			}
			if (rule.actions.contains(RuleAction.ALERT) && cfg.alerts.in_game && !cooldown) {
				alert(server, session, rule, cfg, enforced, wantsBan, suppressedReason);
				notified = true;
			}
			if (rule.actions.contains(RuleAction.WEBHOOK) && !cfg.alerts.webhook_url.isBlank() && !cooldown) {
				webhook(session, rule, cfg, enforced, wantsBan, suppressedReason);
				notified = true;
			}
			if (wantsCommand && !protectedPlayer && !cfg.monitor_only) {
				for (String command : rule.commands) {
					String resolved = Placeholders.applyForCommand(command, session, rule);
					try {
						server.getCommandManager().parseAndExecute(server.getCommandSource(), resolved);
					} catch (RuntimeException e) {
						Doorman.LOGGER.error("Rule '{}' command failed: {}", rule.id, resolved, e);
					}
				}
			} else if (wantsCommand && !rule.commands.isEmpty()) {
				Doorman.LOGGER.info("Rule '{}' commands not run for {} ({})", rule.id, session.name(),
						suppressedReason == null ? "no reason" : suppressedReason);
			}
			if (notified) {
				markAlerted(session, rule);
			}
		};
		if (server.isOnThread()) {
			rest.run();
		} else {
			server.execute(rest);
		}
	}

	private static void ban(MinecraftServer server, ClientSession session, String reason, String source) {
		PlayerConfigEntry entry = new PlayerConfigEntry(session.uuid(), session.name());
		if (server.getPlayerManager().getUserBanList().contains(entry)) {
			return;
		}
		server.getPlayerManager().getUserBanList().add(new BannedPlayerEntry(entry, null, source, null, reason));
		ServerPlayerEntity online = server.getPlayerManager().getPlayer(session.uuid());
		if (online != null) {
			online.networkHandler.disconnect(Text.translatable("multiplayer.disconnect.banned"));
		}
	}

	private boolean onCooldown(ClientSession session, Rule rule, DoormanConfig cfg) {
		if (cfg.alerts.cooldown_seconds <= 0) {
			return false;
		}
		Long last = lastAlert.get(session.uuid() + "|" + rule.id);
		return last != null && System.currentTimeMillis() - last < cfg.alerts.cooldown_seconds * 1000L;
	}

	private void markAlerted(ClientSession session, Rule rule) {
		lastAlert.put(session.uuid() + "|" + rule.id, System.currentTimeMillis());
		if (lastAlert.size() > 5000) {
			long cutoff = System.currentTimeMillis() - 24L * 3600_000L;
			lastAlert.entrySet().removeIf(e -> e.getValue() < cutoff);
		}
	}

	private static String outcome(boolean enforced, boolean ban, String suppressed) {
		if (enforced) {
			return ban ? "BANNED" : "KICKED";
		}
		return suppressed != null ? "not enforced (" + suppressed + ")" : "alert only";
	}

	private void log(ClientSession session, Rule rule, boolean enforced, boolean ban, String suppressed) {
		Doorman.LOGGER.warn("{} ({}) matched rule '{}' [{} {}, {}]: brand='{}', channels={}, resolved={} -> {}",
				session.name(), session.uuid(), rule.id, rule.category, rule.name, rule.confidence,
				Placeholders.displayBrand(session.brand()), session.channels().size(), session.resolvedKeys(),
				outcome(enforced, ban, suppressed));
	}

	private void alert(MinecraftServer server, ClientSession session, Rule rule, DoormanConfig cfg,
			boolean enforced, boolean ban, String suppressed) {
		Formatting colour = colour(rule.category);
		Text text = Text.literal(cfg.messages.alert_prefix).formatted(Formatting.DARK_AQUA)
				.append(Text.literal(session.name()).formatted(Formatting.WHITE))
				.append(Text.literal(" -> ").formatted(Formatting.GRAY))
				.append(Text.literal(rule.name).formatted(colour))
				.append(Text.literal(" (" + rule.category.name().toLowerCase() + ", " + rule.confidence + ")").formatted(Formatting.GRAY))
				.append(Text.literal(" brand=" + Placeholders.displayBrand(session.brand())).formatted(Formatting.GRAY))
				.append(Text.literal(" " + outcome(enforced, ban, suppressed)).formatted(enforced ? Formatting.RED : Formatting.YELLOW));
		for (ServerPlayerEntity staff : server.getPlayerManager().getPlayerList()) {
			if (PermissionGate.canSeeAlerts(staff) && !profiles.alertsMuted(staff.getUuid())) {
				staff.sendMessage(text);
			}
		}
	}

	private void webhook(ClientSession session, Rule rule, DoormanConfig cfg, boolean enforced, boolean ban, String suppressed) {
		List<DiscordWebhook.Field> fields = new ArrayList<>();
		fields.add(new DiscordWebhook.Field("Player", "`" + session.name() + "`", true));
		fields.add(new DiscordWebhook.Field("Rule", rule.name + " (" + rule.id + ")", true));
		fields.add(new DiscordWebhook.Field("Confidence", rule.confidence, true));
		fields.add(new DiscordWebhook.Field("Brand", "`" + Placeholders.displayBrand(session.brand()).replace("`", "'") + "`", true));
		fields.add(new DiscordWebhook.Field("Loader", BrandNormalizer.loader(session.brand()), true));
		fields.add(new DiscordWebhook.Field("Outcome", outcome(enforced, ban, suppressed), true));
		if (!session.resolvedKeys().isEmpty()) {
			fields.add(new DiscordWebhook.Field("Resolved keys", clip(String.join(", ", session.resolvedKeys()), 1000), false));
		}
		String channels = clip(String.join(", ", session.channels()), 1000);
		fields.add(new DiscordWebhook.Field("Channels (" + session.channels().size() + ")", channels, false));
		DiscordWebhook.send(cfg.alerts.webhook_url, "Doorman: " + rule.category.name().toLowerCase() + " detected",
				session.name() + " matched **" + rule.name + "**", webhookColour(rule.category), fields);
	}

	private static String clip(String value, int max) {
		return value.length() > max ? value.substring(0, max - 3) + "..." : value;
	}

	private static Formatting colour(RuleCategory category) {
		return switch (category) {
			case HACK -> Formatting.RED;
			case SPOOF -> Formatting.GOLD;
			case DISALLOWED -> Formatting.YELLOW;
			case LEGIT -> Formatting.GREEN;
			case INFO -> Formatting.GRAY;
		};
	}

	private static int webhookColour(RuleCategory category) {
		return switch (category) {
			case HACK -> 0xE53935;
			case SPOOF -> 0xFB8C00;
			case DISALLOWED -> 0xFDD835;
			case LEGIT -> 0x43A047;
			case INFO -> 0x9E9E9E;
		};
	}
}
