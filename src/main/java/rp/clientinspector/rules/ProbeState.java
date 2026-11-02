package rp.clientinspector.rules;

/** Outcome of the sign probe for one session. */
public enum ProbeState {
	/** Probe disabled, or not run yet. */
	NOT_RUN,
	/** Sent, waiting for the client's sign update. */
	PENDING,
	/** The client answered; see the resolved keys. */
	REPLIED,
	/**
	 * The client answered, but a canary key that must resolve on it (its channels prove the owning
	 * mod is installed) came back raw: a translation filter such as OpSec or ExploitPreventer rewrote
	 * the reply, so every other raw echo in this probe is unreliable.
	 */
	FILTERED,
	/** The client answered with blank lines: a protection mod wiped the sign text. */
	BLANKED,
	/** No answer within the timeout: the client swallowed the sign editor. */
	NO_REPLY
}
