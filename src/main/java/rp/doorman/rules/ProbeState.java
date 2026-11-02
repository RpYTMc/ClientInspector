package rp.doorman.rules;

/** Outcome of the translation-key probe for one session. */
public enum ProbeState {
	/** Probe disabled, or not run yet. */
	NOT_RUN,
	/** Sent, waiting for the client's sign update. */
	PENDING,
	/** The client answered; see the resolved keys. */
	REPLIED,
	/** The client answered with blank lines: a protection mod wiped the sign text. */
	BLANKED,
	/** No answer within the timeout: the client swallowed the sign editor. */
	NO_REPLY
}
