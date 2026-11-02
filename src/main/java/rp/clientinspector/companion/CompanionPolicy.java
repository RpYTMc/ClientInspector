package rp.clientinspector.companion;

import net.minecraft.text.Text;
import rp.clientinspector.ClientInspector;
import rp.clientinspector.ClientInspectorConfig;
import rp.clientinspector.ClientInspectorConfig.CompanionMode;
import rp.clientinspector.rules.CompanionState;
import rp.clientinspector.session.ClientSession;

import java.util.function.Consumer;

/**
 * Required mode: who may join without a client part report. In optional and off mode everyone may;
 * in required mode a client without the jar (NONE) or whose report never came (TIMEOUT) is refused,
 * unless its brand is one the probe skips (Geyser) or it holds the bypass and exempt_bypass is on.
 */
public final class CompanionPolicy {
	public enum Decision {
		ALLOW,
		REFUSE,
		/** The bypass lookup is still pending; decide again at join. */
		WAIT_FOR_BYPASS
	}

	public enum Outcome {
		ALLOWED,
		REFUSED,
		/** Would have been refused, but monitor_only is on: logged only. */
		NOT_ENFORCED,
		WAITING
	}

	private CompanionPolicy() {
	}

	public static Decision decide(CompanionMode mode, CompanionState state, boolean exemptBrand, boolean exemptBypass,
			boolean bypassResolved, boolean bypass) {
		if (mode != CompanionMode.REQUIRED || state == CompanionState.REPORTED || state == CompanionState.MISMATCH || exemptBrand) {
			return Decision.ALLOW;
		}
		if (exemptBypass) {
			if (!bypassResolved) {
				return Decision.WAIT_FOR_BYPASS;
			}
			if (bypass) {
				return Decision.ALLOW;
			}
		}
		return Decision.REFUSE;
	}

	/** Applies the decision to a session; {@code kick} disconnects the client with the refusal screen. */
	public static Outcome enforce(ClientInspectorConfig config, ClientSession session, boolean exemptBrand, Consumer<Text> kick) {
		if (session.kicked()) {
			return Outcome.ALLOWED;
		}
		Decision decision = decide(config.companion.mode, session.companion(), exemptBrand, config.companion.exempt_bypass,
				session.bypassResolved(), session.bypass());
		switch (decision) {
			case ALLOW:
				return Outcome.ALLOWED;
			case WAIT_FOR_BYPASS:
				return Outcome.WAITING;
			default:
				break;
		}
		String why = session.companion() == CompanionState.TIMEOUT ? "its client part sent no report" : "it does not have the client part";
		if (config.monitor_only) {
			ClientInspector.LOGGER.info("{} would be refused (required mode: {}), not enforced: monitor_only", session.name(), why);
			return Outcome.NOT_ENFORCED;
		}
		ClientInspector.LOGGER.info("{} refused: required mode and {}", session.name(), why);
		session.markKicked();
		kick.accept(refusal(config.companion, session.name()));
		return Outcome.REFUSED;
	}

	public static Text refusal(ClientInspectorConfig.Companion companion, String player) {
		return Text.literal(companion.kick_message.replace("%url%", companion.download_url).replace("%player%", player));
	}
}
