package rp.clientinspector;

import net.minecraft.text.Text;
import org.junit.jupiter.api.Test;
import rp.clientinspector.ClientInspectorConfig.CompanionMode;
import rp.clientinspector.companion.CompanionPolicy;
import rp.clientinspector.companion.CompanionPolicy.Decision;
import rp.clientinspector.companion.CompanionPolicy.Outcome;
import rp.clientinspector.rules.CompanionState;
import rp.clientinspector.session.ClientSession;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Required mode decisions, applied to real sessions with a fake kick handler. */
class CompanionPolicyTest {
	private final List<Text> kicks = new ArrayList<>();

	private static ClientSession session(CompanionState state, Boolean bypass) {
		ClientSession session = new ClientSession(UUID.randomUUID(), "Rpipi", "127.0.0.1:50000", 0L);
		session.setCompanion(state);
		if (bypass != null) {
			session.setBypass(bypass);
		}
		return session;
	}

	private static ClientInspectorConfig config(CompanionMode mode) {
		ClientInspectorConfig cfg = new ClientInspectorConfig();
		cfg.companion.mode = mode;
		return cfg;
	}

	private Outcome enforce(ClientInspectorConfig cfg, ClientSession session, boolean geyser) {
		return CompanionPolicy.enforce(cfg, session, geyser, kicks::add);
	}

	@Test
	void optionalAndOffLetEveryoneIn() {
		for (CompanionMode mode : List.of(CompanionMode.OFF, CompanionMode.OPTIONAL)) {
			for (CompanionState state : CompanionState.values()) {
				assertEquals(Decision.ALLOW, CompanionPolicy.decide(mode, state, false, true, true, false), mode + " " + state);
			}
		}
	}

	@Test
	void requiredRefusesNoneAndTimeoutButNotReporters() {
		assertEquals(Decision.REFUSE, CompanionPolicy.decide(CompanionMode.REQUIRED, CompanionState.NONE, false, true, true, false));
		assertEquals(Decision.REFUSE, CompanionPolicy.decide(CompanionMode.REQUIRED, CompanionState.TIMEOUT, false, true, true, false));
		assertEquals(Decision.ALLOW, CompanionPolicy.decide(CompanionMode.REQUIRED, CompanionState.REPORTED, false, true, true, false));
		// A forged report is the companion-mismatch rule's business, not a missing client part.
		assertEquals(Decision.ALLOW, CompanionPolicy.decide(CompanionMode.REQUIRED, CompanionState.MISMATCH, false, true, true, false));
	}

	@Test
	void requiredExemptions() {
		assertEquals(Decision.ALLOW, CompanionPolicy.decide(CompanionMode.REQUIRED, CompanionState.NONE, true, true, true, false), "Geyser brand");
		assertEquals(Decision.ALLOW, CompanionPolicy.decide(CompanionMode.REQUIRED, CompanionState.NONE, false, true, true, true), "op / bypass");
		assertEquals(Decision.REFUSE, CompanionPolicy.decide(CompanionMode.REQUIRED, CompanionState.NONE, false, false, true, true), "exempt_bypass off");
		assertEquals(Decision.WAIT_FOR_BYPASS, CompanionPolicy.decide(CompanionMode.REQUIRED, CompanionState.NONE, false, true, false, false));
		assertEquals(Decision.REFUSE, CompanionPolicy.decide(CompanionMode.REQUIRED, CompanionState.NONE, false, false, false, false),
				"without exempt_bypass the lookup does not matter");
	}

	@Test
	void refusalKicksWithTheDownloadUrl() {
		ClientInspectorConfig cfg = config(CompanionMode.REQUIRED);
		cfg.companion.download_url = "https://example.org/ci";
		ClientSession session = session(CompanionState.NONE, false);
		assertEquals(Outcome.REFUSED, enforce(cfg, session, false));
		assertEquals(1, kicks.size());
		assertTrue(kicks.get(0).getString().contains("https://example.org/ci"), kicks.get(0).getString());
		assertTrue(session.kicked());
		assertEquals(Outcome.ALLOWED, enforce(cfg, session, false), "a kicked session is not kicked twice");
		assertEquals(1, kicks.size());
	}

	@Test
	void pendingBypassWaitsAndMonitorOnlyOnlyLogs() {
		ClientInspectorConfig cfg = config(CompanionMode.REQUIRED);
		assertEquals(Outcome.WAITING, enforce(cfg, session(CompanionState.NONE, null), false));
		cfg.monitor_only = true;
		ClientSession session = session(CompanionState.TIMEOUT, false);
		assertEquals(Outcome.NOT_ENFORCED, enforce(cfg, session, false));
		assertFalse(session.kicked());
		assertTrue(kicks.isEmpty());
	}

	@Test
	void reportersAndOptionalModeAreNeverKicked() {
		assertEquals(Outcome.ALLOWED, enforce(config(CompanionMode.REQUIRED), session(CompanionState.REPORTED, false), false));
		assertEquals(Outcome.ALLOWED, enforce(config(CompanionMode.OPTIONAL), session(CompanionState.NONE, false), false));
		assertEquals(Outcome.ALLOWED, enforce(config(CompanionMode.REQUIRED), session(CompanionState.NONE, false), true));
		assertTrue(kicks.isEmpty());
	}
}
