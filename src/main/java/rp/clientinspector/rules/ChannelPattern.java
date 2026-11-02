package rp.clientinspector.rules;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Matches a plugin-channel identifier. Forms: exact {@code ns:path}; {@code ns:*} (any path in the
 * namespace); a bare {@code ns} (same as {@code ns:*}); or any string with {@code *} wildcards,
 * e.g. {@code fabric-*:*}. Matching is case-insensitive: identifiers are lower-case anyway and
 * rule files are hand-edited.
 */
public final class ChannelPattern {
	private final String source;
	private final Pattern regex;

	private ChannelPattern(String source, Pattern regex) {
		this.source = source;
		this.regex = regex;
	}

	public static ChannelPattern of(String pattern) {
		String p = pattern.trim().toLowerCase(Locale.ROOT);
		if (!p.contains(":") && !p.contains("*")) {
			p = p + ":*";
		}
		StringBuilder sb = new StringBuilder("^");
		for (char c : p.toCharArray()) {
			if (c == '*') {
				sb.append(".*");
			} else {
				sb.append(Pattern.quote(String.valueOf(c)));
			}
		}
		sb.append('$');
		return new ChannelPattern(pattern, Pattern.compile(sb.toString()));
	}

	public boolean matches(String channel) {
		return regex.matcher(channel.toLowerCase(Locale.ROOT)).matches();
	}

	public boolean matchesAny(Iterable<String> channels) {
		for (String c : channels) {
			if (matches(c)) {
				return true;
			}
		}
		return false;
	}

	/** The namespace of an identifier string, or the whole string when it has no colon. */
	public static String namespace(String channel) {
		int i = channel.indexOf(':');
		return i < 0 ? channel : channel.substring(0, i);
	}

	@Override
	public String toString() {
		return source;
	}
}
