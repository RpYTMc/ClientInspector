package rp.doorman;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * config/doorman/config.json. Unknown fields are ignored by Gson; missing fields keep the defaults
 * assigned at construction. Rules live in a separate file (config/doorman/rules.json).
 */
public final class DoormanConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	public static final String DIR = "doorman";
	private static final String FILE_NAME = "config.json";

	/** When true, KICK and BAN are downgraded to alerts; everything else runs. Good for the first days. */
	public boolean monitor_only = false;
	public Probe probe = new Probe();
	public Alerts alerts = new Alerts();
	public Audit audit = new Audit();
	public Messages messages = new Messages();
	/** Source name written into banned-players.json for bans issued by Doorman. */
	public String ban_source = "Doorman";
	/**
	 * Channel namespaces never reported as "unknown mod" in alerts or profiles. Rule pattern syntax
	 * (namespace, ns:*, globs). Fabric API's own channels are always known.
	 */
	public List<String> known_namespaces = new ArrayList<>(List.of(
			"minecraft", "c", "fabric", "fabric-*", "fabricloader",
			"lunar", "lunarclient", "feather", "labymod", "labymod3", "badlion", "essential",
			"replaymod", "xaerominimap", "xaeroworldmap", "voicechat", "vc", "servux", "jade",
			"journeymap", "appleskin", "modmenu", "sodium", "iris", "cardinal-components",
			"architectury", "owo", "cloth-config", "yacl", "fabrication", "waila", "wthit",
			"emi", "jei", "rei", "roughlyenoughitems", "carpet", "worldedit", "axiom", "flashback",
			"noxesium", "vivecraft", "litematica", "minihud", "tweakeroo", "itemscroller",
			"inventoryprofilesnext", "simple-voice-chat", "controlify",
			"balm", "distant_horizons", "forgeconfigapiport", "fzzy_config", "itemswapper",
			"libjf-config-network-v0", "pickupnotifier", "puzzleslib", "shulkerboxtooltip", "speedrunigt",
			"supermartijn642configlib", "whereisit", "xaerolib"));

	public static final class Probe {
		/** Run the translation-key sign probe on every join. */
		public boolean enabled = true;
		/** Ticks after join before the probe (and the SETTLED evaluation) run. 20 ticks = 1 s. */
		public int delay_ticks = 80;
		/** Random extra delay, so the probe is not trivially predictable. */
		public int jitter_ticks = 40;
		/** Ticks to wait for the client's reply before recording NO_REPLY. */
		public int timeout_ticks = 100;
		/** Re-probe online players every N minutes; 0 disables periodic checks. */
		public int recheck_minutes = 0;
		/** Vertical offset from the player's feet for the invisible probe sign (negative = below). */
		public int y_offset = -5;
		/** A key every client resolves (vanilla), used by /doorman probe to prove the mechanism works. */
		public String self_test_key = "key.jump";
		/** When the first probe gets no reply at all, try once more a few seconds later before recording NO_REPLY. */
		public boolean retry_on_no_reply = true;
		/** Brand regexes (find, case-insensitive) that are never probed: Bedrock players behind Geyser have no Java sign editor. */
		public List<String> skip_brands = new ArrayList<>(List.of("^geyser"));
		/**
		 * Keys that must resolve on a client whose channels prove the owning mod is installed. A raw
		 * echo for one of them means a translation filter (OpSec, ExploitPreventer) rewrote the reply,
		 * and the probe is recorded as FILTERED instead of REPLIED.
		 */
		public List<Canary> canaries = new ArrayList<>(List.of(
				new Canary(List.of("fabric:*", "fabric-*:*"), "pack.source.fabricmod"),
				new Canary(List.of("voicechat:*", "vc:*"), "key.push_to_talk")));
	}

	public static final class Canary {
		public List<String> channels_any = new ArrayList<>();
		public String key = "";

		public Canary() {
		}

		public Canary(List<String> channelsAny, String key) {
			this.channels_any = new ArrayList<>(channelsAny);
			this.key = key;
		}
	}

	public static final class Alerts {
		public boolean in_game = true;
		public boolean console = true;
		/** Discord webhook URL; empty disables the WEBHOOK action. */
		public String webhook_url = "";
		/** Seconds before the same rule may alert again for the same player (0 = every time). */
		public int cooldown_seconds = 300;
		/** Alert staff about channel namespaces not in known_namespaces and not covered by a rule. */
		public boolean unknown_namespaces = false;
	}

	public static final class Audit {
		/** One INFO log line per join with brand, channels and the probe result. */
		public boolean log_joins = true;
		/** Persist the last observation per player to config/doorman/profiles.json. */
		public boolean keep_profiles = true;
		/** Oldest profiles are dropped beyond this count. */
		public int max_profiles = 1000;
	}

	public static final class Messages {
		public String alert_prefix = "[Doorman] ";
		public String kick = "Doorman: %client% is not allowed on this server.";
		public String ban = "Doorman: %client% detected";
	}

	private DoormanConfig sanitize() {
		if (probe == null) probe = new Probe();
		if (alerts == null) alerts = new Alerts();
		if (audit == null) audit = new Audit();
		if (messages == null) messages = new Messages();
		if (ban_source == null || ban_source.isBlank()) ban_source = "Doorman";
		if (known_namespaces == null) known_namespaces = new ArrayList<>();
		known_namespaces.removeIf(s -> s == null || s.isBlank());
		probe.delay_ticks = Math.max(1, probe.delay_ticks);
		probe.jitter_ticks = Math.max(0, probe.jitter_ticks);
		probe.timeout_ticks = Math.max(20, probe.timeout_ticks);
		probe.recheck_minutes = Math.max(0, probe.recheck_minutes);
		if (probe.self_test_key == null || probe.self_test_key.isBlank()) probe.self_test_key = "key.jump";
		if (probe.skip_brands == null) probe.skip_brands = new ArrayList<>();
		probe.skip_brands.removeIf(s -> s == null || s.isBlank());
		if (probe.canaries == null) probe.canaries = new ArrayList<>();
		probe.canaries.removeIf(c -> c == null || c.key == null || c.key.isBlank() || c.channels_any == null || c.channels_any.isEmpty());
		if (alerts.webhook_url == null) alerts.webhook_url = "";
		alerts.cooldown_seconds = Math.max(0, alerts.cooldown_seconds);
		audit.max_profiles = Math.max(10, audit.max_profiles);
		if (messages.alert_prefix == null) messages.alert_prefix = "";
		if (messages.kick == null || messages.kick.isBlank()) messages.kick = "Doorman: %client% is not allowed on this server.";
		if (messages.ban == null || messages.ban.isBlank()) messages.ban = "Doorman: %client% detected";
		return this;
	}

	public static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve(DIR);
	}

	public static Path path() {
		return dir().resolve(FILE_NAME);
	}

	/** Strict load for /doorman reload: writes defaults on first run, otherwise parses, sanitises and re-saves. */
	public static DoormanConfig loadOrThrow() throws IOException {
		Path file = path();
		if (!Files.exists(file)) {
			DoormanConfig defaults = new DoormanConfig();
			defaults.save();
			return defaults;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			DoormanConfig cfg = GSON.fromJson(reader, DoormanConfig.class);
			if (cfg == null) {
				throw new JsonSyntaxException("file is empty (delete it to regenerate defaults)");
			}
			cfg.sanitize();
			cfg.save();
			return cfg;
		}
	}

	/** Startup path: never throws, falls back to defaults and leaves the broken file untouched. */
	public static DoormanConfig load() {
		try {
			return loadOrThrow();
		} catch (IOException | RuntimeException e) {
			Doorman.LOGGER.error("Failed to read {} ({}); using defaults. Fix the file and run /doorman reload",
					path(), describe(e), e);
			return new DoormanConfig();
		}
	}

	/** Root-cause message for admin feedback (Gson wraps the useful part in the cause). */
	public static String describe(Throwable e) {
		Throwable root = e;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		String msg = root.getMessage();
		return msg == null || msg.isBlank() ? root.getClass().getSimpleName() : root.getClass().getSimpleName() + ": " + msg;
	}

	public void save() {
		Path file = path();
		try {
			String json = GSON.toJson(this);
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				writer.write(json);
			}
		} catch (IOException | RuntimeException e) {
			Doorman.LOGGER.error("Failed to write {}", file, e);
		}
	}
}
