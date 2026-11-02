package rp.doorman.rules;

/**
 * Earliest connection phase at which a rule may fire. Later phases re-evaluate every rule that has
 * not fired yet, so a rule allowed at CONFIGURE is also checked at JOIN and SETTLED.
 */
public enum RulePhase {
	/** Brand and configuration-phase channels are known; the player is not in the world yet. */
	CONFIGURE,
	/** The player entity exists; play channels announced through Fabric's c:register are known. */
	JOIN,
	/** A short delay after join: late channel registrations and the probe result are in. */
	SETTLED;

	public boolean allows(RulePhase current) {
		return current.ordinal() >= ordinal();
	}
}
