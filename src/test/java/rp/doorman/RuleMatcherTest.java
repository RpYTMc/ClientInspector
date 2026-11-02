package rp.doorman;

import org.junit.jupiter.api.Test;
import rp.doorman.rules.ChannelPattern;
import rp.doorman.rules.DefaultRules;
import rp.doorman.rules.ProbeState;
import rp.doorman.rules.Rule;
import rp.doorman.rules.RuleAction;
import rp.doorman.rules.RuleCategory;
import rp.doorman.rules.RuleMatcher;
import rp.doorman.rules.RulePhase;
import rp.doorman.rules.Signals;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleMatcherTest {
	/** What a stock Fabric API 0.141.x client registers, per the Fabric API 1.21.11 sources. */
	private static final Set<String> FABRIC_API_BASELINE = Set.of(
			"c:version", "c:register", "fabric:registry/sync", "fabric:custom_ingredient_sync",
			"fabric:extended_block_state_particle_effect_sync", "fabric:accepted_attachments_v1",
			"fabric:attachment_sync_v1", "fabric:recipe_sync", "fabric-screen-handler-api-v1:open_screen");

	private final RuleMatcher matcher = new RuleMatcher();
	private final List<Rule> defaults = DefaultRules.create();

	private Rule find(String id) {
		return defaults.stream().filter(r -> r.id.equals(id)).findFirst().orElseThrow();
	}

	@Test
	void stockFabricPlayerMatchesNothingAtAnyPhase() {
		Signals signals = new Signals("fabric", FABRIC_API_BASELINE, ProbeState.REPLIED, Set.of());
		for (RulePhase phase : RulePhase.values()) {
			assertEquals(List.of(), matcher.matching(defaults, signals, phase).stream().map(r -> r.id).toList(),
					"phase " + phase);
		}
	}

	@Test
	void vanillaPlayerMatchesNothing() {
		Signals signals = new Signals("vanilla", Set.of(), ProbeState.REPLIED, Set.of());
		assertEquals(List.of(), matcher.matching(defaults, signals, RulePhase.SETTLED));
	}

	@Test
	void meteorProbeHitFiresOnlyWhenSettled() {
		Signals signals = new Signals("fabric", Set.of(), ProbeState.REPLIED, Set.of("key.meteor-client.open-gui"));
		assertFalse(matcher.matching(defaults, signals, RulePhase.CONFIGURE).contains(find("meteor-client")));
		assertFalse(matcher.matching(defaults, signals, RulePhase.JOIN).contains(find("meteor-client")));
		assertTrue(matcher.matching(defaults, signals, RulePhase.SETTLED).contains(find("meteor-client")));
		assertTrue(find("meteor-client").actions.contains(RuleAction.KICK));
	}

	@Test
	void liquidBounceSpoofBrandFiresAtConfigure() {
		Signals signals = new Signals("lunarclient:v2.21.33-2612", FABRIC_API_BASELINE, ProbeState.NOT_RUN, Set.of());
		List<Rule> matched = matcher.matching(defaults, signals, RulePhase.CONFIGURE);
		assertTrue(matched.contains(find("liquidbounce-lunar-spoof")));
	}

	@Test
	void realLunarBrandWithLoaderSuffixDoesNotMatchTheSpoofRule() {
		Signals signals = new Signals("lunarclient:v2.23.12-2644,fabric", Set.of("lunar:apollo"), ProbeState.REPLIED, Set.of());
		List<Rule> matched = matcher.matching(defaults, signals, RulePhase.SETTLED);
		assertFalse(matched.contains(find("liquidbounce-lunar-spoof")));
		assertFalse(matched.contains(find("lunar-brand-without-lunar-channels")));
		assertTrue(matched.contains(find("lunar-client")));
	}

	@Test
	void lunarBrandWithoutLunarChannelsIsASpoofOnlyOnceSettled() {
		Signals signals = new Signals("lunarclient:v2.23.12-2644,fabric", FABRIC_API_BASELINE, ProbeState.REPLIED, Set.of());
		assertFalse(matcher.matching(defaults, signals, RulePhase.JOIN).contains(find("lunar-brand-without-lunar-channels")));
		assertTrue(matcher.matching(defaults, signals, RulePhase.SETTLED).contains(find("lunar-brand-without-lunar-channels")));
	}

	@Test
	void vanillaBrandWithFabricChannelsIsContradiction() {
		Signals signals = new Signals("vanilla", Set.of("fabric:registry/sync"), ProbeState.NOT_RUN, Set.of());
		assertTrue(matcher.matching(defaults, signals, RulePhase.CONFIGURE).contains(find("vanilla-brand-with-fabric-channels")));
	}

	@Test
	void fabricWithoutChannelsIsWeakSignalOnlyWhenSettled() {
		Signals signals = new Signals("fabric", Set.of(), ProbeState.REPLIED, Set.of());
		assertFalse(matcher.matching(defaults, signals, RulePhase.JOIN).contains(find("fabric-without-channels")));
		assertTrue(matcher.matching(defaults, signals, RulePhase.SETTLED).contains(find("fabric-without-channels")));
		assertFalse(find("fabric-without-channels").actions.contains(RuleAction.KICK));
	}

	@Test
	void blockedProbeMatchesInfoRule() {
		for (ProbeState state : List.of(ProbeState.BLANKED, ProbeState.NO_REPLY)) {
			Signals signals = new Signals("fabric", FABRIC_API_BASELINE, state, Set.of());
			assertTrue(matcher.matching(defaults, signals, RulePhase.SETTLED).contains(find("probe-blocked")), state.name());
		}
		Signals ok = new Signals("fabric", FABRIC_API_BASELINE, ProbeState.REPLIED, Set.of());
		assertFalse(matcher.matching(defaults, ok, RulePhase.SETTLED).contains(find("probe-blocked")));
	}

	@Test
	void brandRegexIsCaseInsensitiveAndWholeString() {
		Rule rule = new Rule();
		rule.id = "cb";
		rule.brand = "^CB$";
		assertTrue(matcher.matches(rule, new Signals("cb", Set.of(), ProbeState.NOT_RUN, Set.of())));
		assertFalse(matcher.matches(rule, new Signals("CBx", Set.of(), ProbeState.NOT_RUN, Set.of())));
		assertFalse(matcher.matches(rule, new Signals(null, Set.of(), ProbeState.NOT_RUN, Set.of())));
	}

	@Test
	void invalidBrandRegexNeverMatchesAndIsReported() {
		Rule rule = new Rule();
		rule.id = "broken";
		rule.brand = "([";
		assertFalse(matcher.matches(rule, new Signals("anything", Set.of(), ProbeState.NOT_RUN, Set.of())));
		assertEquals(List.of("broken"), RuleMatcher.invalidBrandRegexes(List.of(rule)));
	}

	@Test
	void ruleWithoutConditionNeverMatches() {
		Rule rule = new Rule();
		rule.id = "empty";
		assertFalse(rule.hasCondition());
		assertFalse(matcher.matches(rule, new Signals("fabric", FABRIC_API_BASELINE, ProbeState.REPLIED, Set.of("x"))));
	}

	@Test
	void channelsAllRequiresEveryPattern() {
		Rule rule = new Rule();
		rule.id = "all";
		rule.channels_all = List.of("lunar:*", "lunarclient:pm");
		assertTrue(matcher.matches(rule, new Signals(null, Set.of("lunar:apollo", "lunarclient:pm"), ProbeState.NOT_RUN, Set.of())));
		assertFalse(matcher.matches(rule, new Signals(null, Set.of("lunar:apollo"), ProbeState.NOT_RUN, Set.of())));
	}

	@Test
	void explicitPhaseCanOnlyDelayNotAdvance() {
		Rule rule = new Rule();
		rule.id = "p";
		rule.translation_keys = List.of("key.x");
		rule.phase = RulePhase.CONFIGURE;
		assertEquals(RulePhase.SETTLED, rule.effectivePhase());
		Rule brandRule = new Rule();
		brandRule.brand = "^x$";
		brandRule.phase = RulePhase.JOIN;
		assertEquals(RulePhase.JOIN, brandRule.effectivePhase());
	}

	@Test
	void channelPatternForms() {
		assertTrue(ChannelPattern.of("lunar").matches("lunar:apollo"));
		assertTrue(ChannelPattern.of("lunar:*").matches("LUNAR:APOLLO"));
		assertFalse(ChannelPattern.of("lunar").matches("lunarclient:pm"));
		assertTrue(ChannelPattern.of("fabric-*:*").matches("fabric-screen-handler-api-v1:open_screen"));
		assertFalse(ChannelPattern.of("fabric-*:*").matches("fabric:registry/sync"));
		assertTrue(ChannelPattern.of("xaerominimap:main").matches("xaerominimap:main"));
		assertFalse(ChannelPattern.of("xaerominimap:main").matches("xaerominimap:main2"));
		assertEquals("xaerominimap", ChannelPattern.namespace("xaerominimap:main"));
		assertEquals("LMC", ChannelPattern.namespace("LMC"));
	}

	@Test
	void defaultRulesHaveUniqueIdsAndConditions() {
		Set<String> ids = new java.util.HashSet<>();
		for (Rule rule : defaults) {
			assertTrue(ids.add(rule.id), "duplicate id " + rule.id);
			assertTrue(rule.hasCondition(), rule.id + " has no condition");
			if (rule.category == RuleCategory.LEGIT || rule.category == RuleCategory.INFO) {
				assertFalse(rule.actions.contains(RuleAction.KICK), rule.id + " must not kick");
				assertFalse(rule.actions.contains(RuleAction.BAN), rule.id + " must not ban");
			}
		}
		assertEquals(List.of(), RuleMatcher.invalidBrandRegexes(defaults));
	}
}
