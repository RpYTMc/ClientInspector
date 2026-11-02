package rp.doorman.rules;

/** What to do when a rule matches. Executed in this order: LOG, ALERT, WEBHOOK, COMMAND, BAN, KICK. */
public enum RuleAction {
	/** Write a line to the server log. */
	LOG,
	/** Message online staff with the doorman.alerts permission. */
	ALERT,
	/** Post to the configured Discord webhook. */
	WEBHOOK,
	/** Run the rule's console commands. */
	COMMAND,
	/** Add the player to the vanilla ban list, then disconnect. */
	BAN,
	/** Disconnect the player with the kick message. */
	KICK
}
