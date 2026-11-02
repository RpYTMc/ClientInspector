package rp.clientinspector.action;

import org.jspecify.annotations.Nullable;
import rp.clientinspector.rules.Rule;
import rp.clientinspector.session.ClientSession;

/** %player% %uuid% %rule% %client% %brand% %confidence% %category% substitution for messages and commands. */
public final class Placeholders {
	/** Longest brand shown anywhere; the string is client-controlled. */
	public static final int MAX_BRAND_DISPLAY = 64;

	private Placeholders() {
	}

	/** For kick/ban screens and alerts: the brand is shortened but otherwise shown as sent. */
	public static String apply(String template, ClientSession session, Rule rule) {
		return apply(template, session, rule, displayBrand(session.brand()));
	}

	/** For console commands: the brand is reduced to a safe character set so it cannot smuggle arguments. */
	public static String applyForCommand(String template, ClientSession session, Rule rule) {
		return apply(template, session, rule, commandSafe(displayBrand(session.brand())));
	}

	private static String apply(String template, ClientSession session, Rule rule, String brand) {
		return template
				.replace("%player%", session.name())
				.replace("%uuid%", session.uuid().toString())
				.replace("%rule%", rule.id)
				.replace("%client%", rule.name)
				.replace("%brand%", brand)
				.replace("%confidence%", rule.confidence)
				.replace("%category%", rule.category.name());
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
