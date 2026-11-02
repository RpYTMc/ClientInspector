package rp.clientinspector.rules;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Matches a Fabric mod id from the client part's report: an exact id ({@code meteor-client}) or a
 * glob with {@code *} wildcards ({@code meteor-*}). Case-insensitive like {@link ChannelPattern}.
 */
public final class ModPattern {
	private final String source;
	private final Pattern regex;

	private ModPattern(String source, Pattern regex) {
		this.source = source;
		this.regex = regex;
	}

	public static ModPattern of(String pattern) {
		StringBuilder sb = new StringBuilder("^");
		for (char c : pattern.trim().toLowerCase(Locale.ROOT).toCharArray()) {
			if (c == '*') {
				sb.append(".*");
			} else {
				sb.append(Pattern.quote(String.valueOf(c)));
			}
		}
		sb.append('$');
		return new ModPattern(pattern, Pattern.compile(sb.toString()));
	}

	public boolean matches(String modId) {
		return regex.matcher(modId.toLowerCase(Locale.ROOT)).matches();
	}

	public boolean matchesAny(Iterable<String> modIds) {
		for (String id : modIds) {
			if (matches(id)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public String toString() {
		return source;
	}
}
