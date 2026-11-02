package rp.doorman.rules;

/** What a matching rule says about the client. Drives alert colours and the profile summary. */
public enum RuleCategory {
	/** A known hack client or hack addon. */
	HACK,
	/** The client is lying about what it is (brand/channel contradiction, hard-coded spoof strings). */
	SPOOF,
	/** Not a hack client, but a mod the server owner may not allow (freecam, xray, ...). */
	DISALLOWED,
	/** A legitimate launcher/client/mod that identifies itself (Lunar, Feather, Xaero's, ...). */
	LEGIT,
	/** Informational: weak signals such as hidden channels or a blocked probe. */
	INFO
}
