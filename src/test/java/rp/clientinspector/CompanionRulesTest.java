package rp.clientinspector;

import org.junit.jupiter.api.Test;
import rp.clientinspector.companion.CompanionCheck;
import rp.clientinspector.rules.CompanionState;
import rp.clientinspector.rules.DefaultRules;
import rp.clientinspector.rules.ProbeState;
import rp.clientinspector.rules.Rule;
import rp.clientinspector.rules.RuleAction;
import rp.clientinspector.rules.RuleMatcher;
import rp.clientinspector.rules.RulePhase;
import rp.clientinspector.rules.Signals;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The client part's rule conditions, the cross-check against the probe, and the rules.json upgrade. */
class CompanionRulesTest {
	private static final Set<String> FABRIC_CHANNELS = Set.of("c:register", "fabric:registry/sync", "clientinspector:hello");
	/** What an honest client with the jar and nothing else reports. */
	private static final Set<String> PLAIN_REPORT = Set.of("clientinspector", "fabric-api", "fabricloader", "java", "minecraft");

	private final RuleMatcher matcher = new RuleMatcher();
	private final List<Rule> defaults = DefaultRules.create();

	private Rule find(List<Rule> rules, String id) {
		return rules.stream().filter(r -> r.id.equals(id)).findFirst().orElseThrow();
	}

	private List<String> matchedIds(Signals signals, RulePhase phase) {
		return matcher.matching(defaults, signals, phase).stream().map(r -> r.id).toList();
	}

	private static Set<String> plus(Set<String> base, String... more) {
		Set<String> out = new java.util.TreeSet<>(base);
		out.addAll(List.of(more));
		return out;
	}

	// ---------------------------------------------------------------- conditions

	@Test
	void honestReportMatchesNothing() {
		Signals signals = new Signals("fabric", FABRIC_CHANNELS, ProbeState.REPLIED, Set.of(), CompanionState.REPORTED, PLAIN_REPORT);
		for (RulePhase phase : RulePhase.values()) {
			assertEquals(List.of(), matchedIds(signals, phase), "phase " + phase);
		}
	}

	@Test
	void reportedMeteorKicksInTheConfigurationPhase() {
		Signals signals = new Signals("fabric", FABRIC_CHANNELS, ProbeState.NOT_RUN, Set.of(), CompanionState.REPORTED,
				plus(PLAIN_REPORT, "meteor-client", "meteor-rejects"));
		assertEquals(List.of("reported-hack"), matchedIds(signals, RulePhase.CONFIGURE));
		assertTrue(find(defaults, "reported-hack").actions.contains(RuleAction.KICK));
	}

	@Test
	void reportedModsAnyTakesGlobsAndIgnoresCase() {
		Rule rule = new Rule();
		rule.id = "test";
		rule.reported_mods_any = new ArrayList<>(List.of("thunderhack*", "Wurst"));
		assertTrue(matcher.matches(rule, new Signals(null, Set.of(), ProbeState.NOT_RUN, Set.of(), CompanionState.REPORTED, Set.of("thunderhack-reborn"))));
		assertTrue(matcher.matches(rule, new Signals(null, Set.of(), ProbeState.NOT_RUN, Set.of(), CompanionState.REPORTED, Set.of("wurst"))));
		assertFalse(matcher.matches(rule, new Signals(null, Set.of(), ProbeState.NOT_RUN, Set.of(), CompanionState.REPORTED, Set.of("thunder"))));
		assertFalse(matcher.matches(rule, Signals.empty()), "no report, no match");
		assertEquals(RulePhase.CONFIGURE, rule.effectivePhase());
	}

	@Test
	void hiderAndUtilitiesOnlyAlert() {
		Signals signals = new Signals("fabric", FABRIC_CHANNELS, ProbeState.REPLIED, Set.of(), CompanionState.REPORTED,
				plus(PLAIN_REPORT, "opsec", "freecam", "xray"));
		assertEquals(List.of("reported-hider", "reported-disallowed"), matchedIds(signals, RulePhase.CONFIGURE));
		assertFalse(find(defaults, "reported-hider").actions.contains(RuleAction.KICK));
		assertFalse(find(defaults, "reported-disallowed").actions.contains(RuleAction.KICK));
	}

	@Test
	void companionStateWaitsForJoin() {
		Rule mismatch = find(defaults, "companion-mismatch");
		assertEquals(RulePhase.JOIN, mismatch.effectivePhase(), "the state is only final after the configuration phase");
		Signals signals = new Signals("fabric", FABRIC_CHANNELS, ProbeState.REPLIED, Set.of(), CompanionState.MISMATCH, PLAIN_REPORT);
		assertFalse(matchedIds(signals, RulePhase.CONFIGURE).contains("companion-mismatch"));
		assertTrue(matchedIds(signals, RulePhase.JOIN).contains("companion-mismatch"));
		assertTrue(mismatch.actions.contains(RuleAction.KICK));

		Rule timeout = new Rule();
		timeout.id = "no-report";
		timeout.companion_state = new ArrayList<>(List.of(CompanionState.TIMEOUT, CompanionState.NONE));
		assertTrue(matcher.matches(timeout, new Signals("fabric", Set.of(), ProbeState.NOT_RUN, Set.of())));
		assertFalse(matcher.matches(timeout, new Signals("fabric", Set.of(), ProbeState.NOT_RUN, Set.of(), CompanionState.REPORTED, PLAIN_REPORT)));
	}

	// ---------------------------------------------------------------- cross-check

	@Test
	void probeHitMissingFromTheReportIsAMismatch() {
		List<String> reasons = CompanionCheck.probeContradictions(defaults, Set.of("key.meteor-client.open-gui"), PLAIN_REPORT, matcher);
		assertEquals(1, reasons.size(), reasons.toString());
		assertTrue(reasons.get(0).contains("meteor-client"));
	}

	@Test
	void probeHitThatTheReportListsIsFine() {
		assertEquals(List.of(), CompanionCheck.probeContradictions(defaults,
				Set.of("key.meteor-client.open-gui", "key.freecam.toggle", "xray.mod_name"),
				plus(PLAIN_REPORT, "meteor-client", "freecam", "xray"), matcher));
	}

	@Test
	void keybindHitCountsAndRulesWithoutModIdAreSkipped() {
		assertEquals(1, CompanionCheck.probeContradictions(defaults, Set.of("keybind:key.meteor-client.open-gui"), PLAIN_REPORT, matcher).size());
		assertNull(find(defaults, "x-to-xray").mod_id);
		assertEquals(List.of(), CompanionCheck.probeContradictions(defaults, Set.of("keybind:key.xtoxray.toggle"), PLAIN_REPORT, matcher));
	}

	@Test
	void disabledRuleIsNotCrossChecked() {
		List<Rule> rules = DefaultRules.create();
		find(rules, "meteor-client").enabled = false;
		assertEquals(List.of(), CompanionCheck.probeContradictions(rules, Set.of("key.meteor-client.open-gui"), PLAIN_REPORT, matcher));
	}

	@Test
	void reportWithoutFabricApiFromAFabricClientIsAMismatch() {
		Set<String> noApi = Set.of("clientinspector", "minecraft");
		assertEquals(1, CompanionCheck.fabricApiContradiction(FABRIC_CHANNELS, noApi, matcher).size());
		assertEquals(List.of(), CompanionCheck.fabricApiContradiction(FABRIC_CHANNELS, PLAIN_REPORT, matcher));
		assertEquals(List.of(), CompanionCheck.fabricApiContradiction(Set.of("clientinspector:hello"), noApi, matcher));
	}

	// ---------------------------------------------------------------- rules.json upgrade

	/** A rules.json as 0.1.x wrote it: no client part rules, no mod_id. */
	private static List<Rule> version1Rules() {
		List<Rule> rules = DefaultRules.create();
		rules.removeIf(r -> r.id.startsWith("reported-") || r.id.equals("companion-mismatch"));
		rules.forEach(r -> r.mod_id = null);
		return rules;
	}

	@Test
	void upgradeAddsTheNewRulesAndModIdsOnce() {
		List<Rule> rules = version1Rules();
		find(rules, "freecam").actions = new ArrayList<>(List.of(RuleAction.LOG, RuleAction.KICK)); // an owner's own edit
		int before = rules.size();
		List<String> changes = DefaultRules.upgrade(rules, 0);
		assertEquals(before + 4, rules.size(), changes.toString());
		assertTrue(changes.contains("added reported-hack"));
		assertEquals("meteor-client", find(rules, "meteor-client").mod_id);
		assertEquals("*xray*", find(rules, "advanced-xray").mod_id);
		assertEquals(List.of(RuleAction.LOG, RuleAction.KICK), find(rules, "freecam").actions, "owner edits survive");
		assertEquals(List.of(), DefaultRules.upgrade(rules, 0), "a second run changes nothing");
	}

	@Test
	void upgradeKeepsARuleTheOwnerAlreadyHasAndSkipsCurrentFiles() {
		List<Rule> rules = version1Rules();
		Rule own = new Rule();
		own.id = "reported-hack";
		own.reported_mods_any = new ArrayList<>(List.of("wurst"));
		rules.add(own);
		DefaultRules.upgrade(rules, 1);
		assertEquals(1, rules.stream().filter(r -> r.id.equals("reported-hack")).count());
		assertEquals(List.of("wurst"), find(rules, "reported-hack").reported_mods_any);

		List<Rule> current = version1Rules();
		assertEquals(List.of(), DefaultRules.upgrade(current, DefaultRules.VERSION));
	}
}
