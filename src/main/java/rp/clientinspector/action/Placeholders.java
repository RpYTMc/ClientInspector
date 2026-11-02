package rp.clientinspector.action;

import org.jspecify.annotations.Nullable;
import rp.clientinspector.rules.ModPattern;
import rp.clientinspector.rules.Rule;
import rp.clientinspector.session.ClientSession;

import java.util.ArrayList;
import java.util.List;

/** %player% %uuid% %rule% %client% %brand% %reported% %confidence% %category% substitution for messages and commands. */
public final class Placeholders {
	/** Longest brand shown anywhere; the string is client-controlled. */
	public static final int MAX_BRAND_DISPLAY = 64;
	/** Most reported mod ids named in one message. */
	private static final int MAX_REPORTED_DISPLAY = 8;

	private Placeholders() {
	}

	/** For kick/ban screens and alerts: the brand is shortened but otherwise shown as sent. */
	public static String apply(String template, ClientSession session, Rule rule) {
		return apply(template, session, rule, displayBrand(session.brand()), reported(session, rule));
	}

	/** For console commands: client-sent text is reduced to a safe character set so it cannot smuggle arguments. */
	public static String applyForCommand(String template, ClientSession session, Rule rule) {
		return apply(template, session, rule, commandSafe(displayBrand(session.brand())), commandSafe(reported(session, rule)));
	}

	private static String apply(String template, ClientSession session, Rule rule, String brand, String reported) {
		return template
				.replace("%player%", session.name())
				.replace("%uuid%", session.uuid().toString())
				.replace("%rule%", rule.id)
				.replace("%client%", rule.name)
				.replace("%brand%", brand)
				.replace("%reported%", reported)
				.replace("%confidence%", rule.confidence)
				.replace("%category%", rule.category.name());
	}

	/** The reported mod ids that matched the rule's reported_mods_any; the rule name when there are none. */
	public static String reported(ClientSession session, Rule rule) {
		List<String> hits = new ArrayList<>();
		for (String id : session.reportedMods().keySet()) {
			if (rule.reported_mods_any.stream().noneMatch(pattern -> ModPattern.of(pattern).matches(id))) {
				continue;
			}
			if (hits.size() == MAX_REPORTED_DISPLAY) {
				hits.add("...");
				break;
			}
			hits.add(displayBrand(id));
		}
		return hits.isEmpty() ? rule.name : String.join(", ", hits);
	}

	public static String displayBrand(@Nullable String brand) {
		if (brand == null || brand.isBlank()) {
			return "none";
		}
		String oneLine = brand.replaceAll("[\\p{Cntrl}]", "?");
		return oneLine.length() > MAX_BRAND_DISPLAY ? oneLine.substring(0, MAX_BRAND_DISPLAY) + "..." : oneLine;
	}

	/** Keeps letters, digits and a few punctuation characters; everything else becomes an underscore. */
	public static String commandSafe(String value) {
		return value.replaceAll("[^A-Za-z0-9._:/,+()-]", "_");
	}

	public static String firstNonBlank(@Nullable String preferred, String fallback) {
		return preferred == null || preferred.isBlank() ? fallback : preferred;
	}
}
