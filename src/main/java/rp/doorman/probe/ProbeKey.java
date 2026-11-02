package rp.doorman.probe;

import net.minecraft.text.Text;

/**
 * One probe line. A TRANSLATION key resolves through the client's language files; a KEYBIND id
 * resolves through the client's registered key bindings (a client that registered the binding
 * answers with the bound key's name, e.g. "Right Shift"). Vanilla echoes the raw id in both cases.
 *
 * @param kind which client table the id is looked up in
 * @param id   the translation key or key-binding id as the mod registers it
 */
public record ProbeKey(Kind kind, String id) {
	public enum Kind {
		TRANSLATION,
		KEYBIND
	}

	public static final String KEYBIND_PREFIX = "keybind:";

	public static ProbeKey translation(String key) {
		return new ProbeKey(Kind.TRANSLATION, key);
	}

	public static ProbeKey keybind(String id) {
		return new ProbeKey(Kind.KEYBIND, id);
	}

	/** Stable identifier used in replies, rules and profiles: the key itself, or "keybind:" + id. */
	public String label() {
		return kind == Kind.KEYBIND ? KEYBIND_PREFIX + id : id;
	}

	public static ProbeKey parse(String label) {
		if (label.startsWith(KEYBIND_PREFIX)) {
			return keybind(label.substring(KEYBIND_PREFIX.length()));
		}
		return translation(label);
	}

	/** The sign line: the lookup wrapped in a "%s" argument so filters that only inspect top-level content miss it. */
	public Text toText() {
		Text inner = kind == Kind.KEYBIND ? Text.keybind(id) : Text.translatable(id);
		return Text.translatable("%s", inner);
	}

	/**
	 * True when the client answered with a real translation. A blank reply, the raw id, a truncated
	 * echo (the reply is a prefix of the id) or a wrapped echo (the reply still contains the id) all
	 * count as "not installed": old clients behind a protocol translator may shorten or decorate
	 * sign lines, and that must never read as a hack client.
	 */
	public boolean resolvedBy(String reply) {
		if (reply == null) {
			return false;
		}
		String r = reply.strip();
		if (r.isEmpty() || r.equals(id) || r.contains(id) || id.startsWith(r)) {
			return false;
		}
		return true;
	}
}
