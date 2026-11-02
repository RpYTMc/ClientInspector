package rp.doorman.rules;

import java.util.ArrayList;
import java.util.List;

/**
 * The rules written to config/doorman/rules.json on first run. Every translation key below was
 * read from the mod's own language file on its 1.21.11 branch (see README "Where the fingerprints
 * come from"); the server owner is expected to prune, extend and promote actions.
 *
 * <p>Probe keys are sent four per sign, so keep this list lean: every four keys cost one more
 * open/close cycle on the client.
 */
public final class DefaultRules {
	private DefaultRules() {
	}

	public static List<Rule> create() {
		List<Rule> rules = new ArrayList<>();

		// ---- hack clients and addons caught by the translation-key probe ----
		Rule meteor = probe("meteor-client", "Meteor Client", RuleCategory.HACK, "high",
				List.of("key.meteor-client.open-gui", "key.category.meteor-client.meteor-client"),
				List.of(RuleAction.LOG, RuleAction.ALERT, RuleAction.WEBHOOK, RuleAction.KICK));
		// Second vector: Meteor registers this as a real key binding, so a keybind component resolves to the bound key.
		meteor.keybind_keys = new ArrayList<>(List.of("key.meteor-client.open-gui"));
		rules.add(meteor);
		rules.add(probe("meteor-plus", "Meteor+ (Meteor addon)", RuleCategory.HACK, "high",
				List.of("item.meteorplus.logo"),
				List.of(RuleAction.LOG, RuleAction.ALERT, RuleAction.WEBHOOK, RuleAction.KICK)));
		rules.add(probe("meteorist", "Meteorist (Meteor addon)", RuleCategory.HACK, "high",
				List.of("modmenu.descriptionTranslation.meteorist"),
				List.of(RuleAction.LOG, RuleAction.ALERT, RuleAction.WEBHOOK, RuleAction.KICK)));
		rules.add(probe("thunderhack", "ThunderHack", RuleCategory.HACK, "high",
				List.of("descriptions.combat.aura", "descriptions.combat.autocrystal"),
				List.of(RuleAction.LOG, RuleAction.ALERT, RuleAction.WEBHOOK, RuleAction.KICK)));

		// ---- disallowed utilities caught by the probe (alert only; promote to KICK if you ban them) ----
		rules.add(probe("freecam", "Freecam", RuleCategory.DISALLOWED, "high",
				List.of("key.freecam.toggle"),
				List.of(RuleAction.LOG, RuleAction.ALERT)));
		rules.add(probe("seedcrackerx", "SeedcrackerX", RuleCategory.DISALLOWED, "medium",
				List.of("settings.antiXrayMode"),
				List.of(RuleAction.LOG, RuleAction.ALERT)));
		rules.add(probe("advanced-xray", "Advanced XRay", RuleCategory.DISALLOWED, "medium",
				List.of("xray.mod_name"),
				List.of(RuleAction.LOG, RuleAction.ALERT)));
		Rule xToXray = new Rule();
		xToXray.id = "x-to-xray";
		xToXray.name = "X to Xray";
		xToXray.category = RuleCategory.DISALLOWED;
		xToXray.confidence = "medium";
		xToXray.keybind_keys = new ArrayList<>(List.of("key.xtoxray.toggle")); // no language file; key binding only
		xToXray.actions = new ArrayList<>(List.of(RuleAction.LOG, RuleAction.ALERT));
		rules.add(xToXray);

		// ---- hard-coded spoof strings: no real client on 1.21.11 sends these ----
		rules.add(brand("liquidbounce-lunar-spoof", "LiquidBounce (Lunar spoof)", RuleCategory.SPOOF, "high",
				"^lunarclient:v2\\.21\\.33-2612$",
				List.of(RuleAction.LOG, RuleAction.ALERT, RuleAction.WEBHOOK, RuleAction.KICK)));
		rules.add(brand("thunderhack-lunar-spoof", "ThunderHack (Lunar spoof)", RuleCategory.SPOOF, "high",
				"^lunarclient:1\\.20\\.(4|1)$",
				List.of(RuleAction.LOG, RuleAction.ALERT, RuleAction.WEBHOOK, RuleAction.KICK)));
		rules.add(brand("cheatbreaker-spoof", "CheatBreaker brand (LiquidBounce spoof mode)", RuleCategory.SPOOF, "medium",
				"^CB$",
				List.of(RuleAction.LOG, RuleAction.ALERT, RuleAction.WEBHOOK)));

		// ---- contradictions: a brand that cannot go with those channels ----
		Rule vanillaSpoof = new Rule();
		vanillaSpoof.id = "vanilla-brand-with-fabric-channels";
		vanillaSpoof.name = "Spoofed vanilla brand";
		vanillaSpoof.category = RuleCategory.SPOOF;
		vanillaSpoof.confidence = "high";
		vanillaSpoof.brand = "^(vanilla|minecraft)$";
		vanillaSpoof.channels_any = new ArrayList<>(List.of("fabric:*", "fabric-*:*", "c:*"));
		vanillaSpoof.actions = new ArrayList<>(List.of(RuleAction.LOG, RuleAction.ALERT, RuleAction.WEBHOOK));
		rules.add(vanillaSpoof);

		Rule lunarNoChannels = new Rule();
		lunarNoChannels.id = "lunar-brand-without-lunar-channels";
		lunarNoChannels.name = "Lunar brand without Lunar channels";
		lunarNoChannels.category = RuleCategory.SPOOF;
		lunarNoChannels.confidence = "medium";
		lunarNoChannels.brand = "^lunarclient:.*";
		lunarNoChannels.channels_none = new ArrayList<>(List.of("lunar:*", "lunarclient:*"));
		lunarNoChannels.actions = new ArrayList<>(List.of(RuleAction.LOG, RuleAction.ALERT));
		rules.add(lunarNoChannels);

		Rule featherNoChannels = new Rule();
		featherNoChannels.id = "feather-brand-without-feather-channels";
		featherNoChannels.name = "Feather brand without Feather channels";
		featherNoChannels.category = RuleCategory.SPOOF;
		featherNoChannels.confidence = "medium";
		featherNoChannels.brand = "^feather.*";
		featherNoChannels.channels_none = new ArrayList<>(List.of("feather:*"));
		featherNoChannels.actions = new ArrayList<>(List.of(RuleAction.LOG, RuleAction.ALERT));
		rules.add(featherNoChannels);

		// ---- weak signals: worth a look, never a kick ----
		Rule hidden = new Rule();
		hidden.id = "fabric-without-channels";
		hidden.name = "Fabric brand but no channels (no Fabric API, or channels hidden)";
		hidden.category = RuleCategory.INFO;
		hidden.confidence = "low";
		hidden.brand = "^fabric$";
		hidden.no_channels = true;
		hidden.actions = new ArrayList<>(List.of(RuleAction.LOG, RuleAction.ALERT));
		rules.add(hidden);

		Rule blocked = new Rule();
		blocked.id = "probe-blocked";
		blocked.name = "Probe blocked (sign protection mod)";
		blocked.category = RuleCategory.INFO;
		blocked.confidence = "low";
		blocked.probe_state = new ArrayList<>(List.of(ProbeState.BLANKED, ProbeState.NO_REPLY));
		blocked.actions = new ArrayList<>(List.of(RuleAction.LOG, RuleAction.ALERT));
		rules.add(blocked);

		// ---- legitimate clients that identify themselves: logged for the profile, never alerted ----
		rules.add(legit("lunar-client", "Lunar Client", List.of("lunar:*", "lunarclient:*")));
		rules.add(legit("feather-client", "Feather Client", List.of("feather:*")));
		rules.add(legit("labymod", "LabyMod", List.of("labymod:*", "labymod3:*")));
		rules.add(legit("badlion", "Badlion Client", List.of("badlion:*")));
		rules.add(legit("essential", "Essential", List.of("essential:*")));
		rules.add(legit("replay-mod", "Replay Mod", List.of("replaymod:*")));
		rules.add(legit("xaeros-maps", "Xaero's Minimap / World Map", List.of("xaerominimap:*", "xaeroworldmap:*")));
		rules.add(legit("simple-voice-chat", "Simple Voice Chat", List.of("voicechat:*", "vc:*")));
		return rules;
	}

	private static Rule probe(String id, String name, RuleCategory category, String confidence, List<String> keys, List<RuleAction> actions) {
		Rule rule = new Rule();
		rule.id = id;
		rule.name = name;
		rule.category = category;
		rule.confidence = confidence;
		rule.translation_keys = new ArrayList<>(keys);
		rule.actions = new ArrayList<>(actions);
		return rule;
	}

	private static Rule brand(String id, String name, RuleCategory category, String confidence, String regex, List<RuleAction> actions) {
		Rule rule = new Rule();
		rule.id = id;
		rule.name = name;
		rule.category = category;
		rule.confidence = confidence;
		rule.brand = regex;
		rule.actions = new ArrayList<>(actions);
		return rule;
	}

	private static Rule legit(String id, String name, List<String> channels) {
		Rule rule = new Rule();
		rule.id = id;
		rule.name = name;
		rule.category = RuleCategory.LEGIT;
		rule.confidence = "high";
		rule.channels_any = new ArrayList<>(channels);
		rule.actions = new ArrayList<>(List.of(RuleAction.LOG));
		return rule;
	}
}
