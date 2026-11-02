package rp.clientinspector;

import org.junit.jupiter.api.Test;
import rp.clientinspector.probe.ProbeKey;
import rp.clientinspector.rules.ProbeState;
import rp.clientinspector.rules.Rule;
import rp.clientinspector.rules.RuleMatcher;
import rp.clientinspector.rules.RulePhase;
import rp.clientinspector.rules.Signals;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeKeyTest {
	@Test
	void labelsRoundTrip() {
		ProbeKey t = ProbeKey.translation("key.meteor-client.open-gui");
		ProbeKey k = ProbeKey.keybind("key.meteor-client.open-gui");
		assertEquals("key.meteor-client.open-gui", t.label());
		assertEquals("keybind:key.meteor-client.open-gui", k.label());
		assertEquals(t, ProbeKey.parse(t.label()));
		assertEquals(k, ProbeKey.parse(k.label()));
		assertEquals(ProbeKey.Kind.KEYBIND, ProbeKey.parse("keybind:key.xtoxray.toggle").kind());
	}

	@Test
	void resolutionIsAnythingButTheRawIdOrNothing() {
		ProbeKey t = ProbeKey.translation("key.jump");
		assertTrue(t.resolvedBy("Jump"));
		assertTrue(t.resolvedBy("Springen"));
		assertFalse(t.resolvedBy("key.jump"));
		assertFalse(t.resolvedBy(""));
		assertFalse(t.resolvedBy("   "));
		assertFalse(t.resolvedBy(null));
		ProbeKey k = ProbeKey.keybind("key.meteor-client.open-gui");
		assertTrue(k.resolvedBy("Right Shift"));
		assertFalse(k.resolvedBy("key.meteor-client.open-gui"));
	}

	@Test
	void truncatedOrDecoratedEchoesAreNotResolutions() {
		ProbeKey t = ProbeKey.translation("modmenu.descriptionTranslation.meteorist");
		assertFalse(t.resolvedBy("modmenu.descrip"), "a client that shortens sign lines echoes a prefix");
		assertFalse(t.resolvedBy("modmenu.descriptionTranslation.meteorist "), "trailing whitespace");
		assertFalse(t.resolvedBy("[modmenu.descriptionTranslation.meteorist]"), "a wrapper around the raw key");
		assertFalse(t.resolvedBy("m"), "a one-character prefix");
		assertTrue(t.resolvedBy("Add-on for Meteor that adds useful utilities for non-vanilla anarchy servers."));
	}

	@Test
	void keybindRuleMatchesOnlyThroughTheKeybindLabel() {
		Rule rule = new Rule();
		rule.id = "x";
		rule.keybind_keys = List.of("key.xtoxray.toggle");
		RuleMatcher matcher = new RuleMatcher();
		assertEquals(RulePhase.SETTLED, rule.effectivePhase());
		assertTrue(matcher.matches(rule, new Signals("fabric", Set.of(), ProbeState.REPLIED, Set.of("keybind:key.xtoxray.toggle"))));
		assertFalse(matcher.matches(rule, new Signals("fabric", Set.of(), ProbeState.REPLIED, Set.of("key.xtoxray.toggle"))));
	}

	@Test
	void translationAndKeybindListsAreOred() {
		Rule rule = new Rule();
		rule.id = "meteor";
		rule.translation_keys = List.of("key.meteor-client.open-gui");
		rule.keybind_keys = List.of("key.meteor-client.open-gui");
		RuleMatcher matcher = new RuleMatcher();
		assertTrue(matcher.matches(rule, new Signals(null, Set.of(), ProbeState.REPLIED, Set.of("key.meteor-client.open-gui"))));
		assertTrue(matcher.matches(rule, new Signals(null, Set.of(), ProbeState.REPLIED, Set.of("keybind:key.meteor-client.open-gui"))));
		assertFalse(matcher.matches(rule, new Signals(null, Set.of(), ProbeState.REPLIED, Set.of())));
	}
}
