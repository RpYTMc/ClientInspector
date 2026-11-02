package rp.clientinspector.rules;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * One entry of config/clientinspector/rules.json. Plain fields for Gson; every condition present must hold
 * (AND). A rule with no condition never matches. Channel lists use {@link ChannelPattern} syntax:
 * exact {@code ns:path}, {@code ns:*}, a bare namespace, or a glob with {@code *}.
 */
public final class Rule {
	/** Unique id, used for de-duplication and in alert text. */
	public String id = "";
	/** Human name shown in alerts, e.g. "Meteor Client". */
	public String name = "";
	public RuleCategory category = RuleCategory.INFO;
	/** high, medium or low; shown in alerts so staff know how much to trust a kick. */
	public String confidence = "medium";
	public boolean enabled = true;
	/** Earliest phase the rule may fire in; null means "derived from the conditions". */
	public @Nullable RulePhase phase = null;

	// ---- conditions ----
	/** Java regex matched (case-insensitive, whole string) against the raw client brand. */
	public @Nullable String brand = null;
	/** At least one registered channel must match one of these patterns. */
	public List<String> channels_any = new ArrayList<>();
	/** Every pattern must be matched by at least one registered channel. */
	public List<String> channels_all = new ArrayList<>();
	/** No registered channel may match any of these patterns. Forces phase SETTLED. */
	public List<String> channels_none = new ArrayList<>();
	/** Fires only when the client registered no channel at all. Forces phase SETTLED. */
	public boolean no_channels = false;
	/**
	 * Probe conditions: at least one entry of translation_keys OR keybind_keys must have resolved.
	 * translation_keys resolve through the client's language files; keybind_keys are key-binding ids
	 * the client registered (the reply is the bound key's name). Either list forces phase SETTLED.
	 */
	public List<String> translation_keys = new ArrayList<>();
	public List<String> keybind_keys = new ArrayList<>();
	/** Fires on these probe outcomes (e.g. BLANKED, NO_REPLY). Forces phase SETTLED. */
	public List<ProbeState> probe_state = new ArrayList<>();
	/**
	 * Client part: at least one reported mod id matches one of these ids or globs ({@code meteor-*}).
	 * Can fire in the configuration phase, as soon as the report is in.
	 */
	public List<String> reported_mods_any = new ArrayList<>();
	/** Client part: its state is one of these (NONE, REPORTED, TIMEOUT, MISMATCH). Forces phase JOIN at least. */
	public List<CompanionState> companion_state = new ArrayList<>();

	/**
	 * Not a condition: the Fabric mod id this rule's probe keys prove (glob allowed). When the keys
	 * resolve but a client part report leaves the id out, the client is recorded as MISMATCH.
	 */
	public @Nullable String mod_id = null;

	// ---- actions ----
	public List<RuleAction> actions = new ArrayList<>(List.of(RuleAction.LOG, RuleAction.ALERT));
	/** Kick screen text; placeholders %client% %rule% %brand% %player%. Null = config default. */
	public @Nullable String kick_message = null;
	/** Ban reason; same placeholders. Null = config default. */
	public @Nullable String ban_reason = null;
	/** Console commands for the COMMAND action, same placeholders plus %uuid%. */
	public List<String> commands = new ArrayList<>();

	public boolean hasCondition() {
		return brand != null || !channels_any.isEmpty() || !channels_all.isEmpty() || !channels_none.isEmpty()
				|| no_channels || !translation_keys.isEmpty() || !keybind_keys.isEmpty() || !probe_state.isEmpty()
				|| !reported_mods_any.isEmpty() || !companion_state.isEmpty();
	}

	/** The phase this rule needs: conditions that depend on late data wait for SETTLED. */
	public RulePhase effectivePhase() {
		RulePhase min = minimumPhase();
		if (phase != null && phase.ordinal() > min.ordinal()) {
			return phase;
		}
		return min;
	}

	private RulePhase minimumPhase() {
		if (!channels_none.isEmpty() || no_channels || !translation_keys.isEmpty() || !keybind_keys.isEmpty() || !probe_state.isEmpty()) {
			return RulePhase.SETTLED;
		}
		// The client part's state is final only once the configuration phase is over.
		if (!companion_state.isEmpty()) {
			return RulePhase.JOIN;
		}
		return RulePhase.CONFIGURE;
	}

	/** Re-establish invariants after Gson (null lists from a hand-edited file). */
	public Rule sanitize() {
		if (id == null) id = "";
		if (name == null || name.isBlank()) name = id;
		if (category == null) category = RuleCategory.INFO;
		if (confidence == null || confidence.isBlank()) confidence = "medium";
		if (channels_any == null) channels_any = new ArrayList<>();
		if (channels_all == null) channels_all = new ArrayList<>();
		if (channels_none == null) channels_none = new ArrayList<>();
		if (translation_keys == null) translation_keys = new ArrayList<>();
		if (keybind_keys == null) keybind_keys = new ArrayList<>();
		if (probe_state == null) probe_state = new ArrayList<>();
		if (reported_mods_any == null) reported_mods_any = new ArrayList<>();
		if (companion_state == null) companion_state = new ArrayList<>();
		if (mod_id != null && mod_id.isBlank()) mod_id = null;
		if (actions == null) actions = new ArrayList<>();
		if (commands == null) commands = new ArrayList<>();
		channels_any.removeIf(s -> s == null || s.isBlank());
		channels_all.removeIf(s -> s == null || s.isBlank());
		channels_none.removeIf(s -> s == null || s.isBlank());
		translation_keys.removeIf(s -> s == null || s.isBlank());
		keybind_keys.removeIf(s -> s == null || s.isBlank());
		probe_state.removeIf(s -> s == null);
		reported_mods_any.removeIf(s -> s == null || s.isBlank());
		companion_state.removeIf(s -> s == null);
		actions.removeIf(a -> a == null);
		commands.removeIf(s -> s == null || s.isBlank());
		return this;
	}
}
