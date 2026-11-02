package rp.clientinspector.rules;

/** What the client part (the same jar installed on the client) told the server during the configuration phase. */
public enum CompanionState {
	/** The client did not register the hello channel: no client part installed (or the mode is off). */
	NONE,
	/** The client sent its mod list; see the reported mods. */
	REPORTED,
	/** The client registered the hello channel but sent no report within companion.timeout_ticks. */
	TIMEOUT,
	/** The client reported, but its list contradicts what the server saw (a probe hit for a mod it left out). */
	MISMATCH
}
