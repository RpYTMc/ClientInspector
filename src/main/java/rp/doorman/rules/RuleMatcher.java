package rp.doorman.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Pure rule evaluation; no Minecraft classes, so it is unit-testable. Safe to call from any thread. */
public final class RuleMatcher {
	private final Map<String, Optional<Pattern>> brandCache = new ConcurrentHashMap<>();
	private final Map<String, ChannelPattern> channelCache = new ConcurrentHashMap<>();

	/** Rules that match the signals and are allowed in the current phase, in file order. */
	public List<Rule> matching(List<Rule> rules, Signals signals, RulePhase phase) {
		List<Rule> out = new ArrayList<>();
		for (Rule rule : rules) {
			if (rule.enabled && rule.effectivePhase().allows(phase) && matches(rule, signals)) {
				out.add(rule);
			}
		}
		return out;
	}

	public boolean matches(Rule rule, Signals signals) {
		if (!rule.hasCondition()) {
			return false;
		}
		if (rule.brand != null) {
			if (signals.brand() == null) {
				return false;
			}
			Optional<Pattern> p = brandPattern(rule.brand);
			if (p.isEmpty() || !p.get().matcher(signals.brand()).matches()) {
				return false;
			}
		}
		if (!rule.channels_any.isEmpty() && !anyMatch(rule.channels_any, signals)) {
			return false;
		}
		for (String pattern : rule.channels_all) {
			if (!channelPattern(pattern).matchesAny(signals.channels())) {
				return false;
			}
		}
		if (!rule.channels_none.isEmpty() && anyMatch(rule.channels_none, signals)) {
			return false;
		}
		if (rule.no_channels && !signals.channels().isEmpty()) {
			return false;
		}
		if (!rule.translation_keys.isEmpty() || !rule.keybind_keys.isEmpty()) {
			boolean any = false;
			for (String key : rule.translation_keys) {
				if (signals.resolvedKeys().contains(key)) {
					any = true;
					break;
				}
			}
			for (String id : rule.keybind_keys) {
				if (signals.resolvedKeys().contains("keybind:" + id)) {
					any = true;
					break;
				}
			}
			if (!any) {
				return false;
			}
		}
		if (!rule.probe_state.isEmpty() && !rule.probe_state.contains(signals.probe())) {
			return false;
		}
		return true;
	}

	/** True when any of the patterns matches any of the channels. */
	public boolean anyMatch(List<String> patterns, Signals signals) {
		for (String pattern : patterns) {
			if (channelPattern(pattern).matchesAny(signals.channels())) {
				return true;
			}
		}
		return false;
	}

	/** True when the channel matches any of the patterns. */
	public boolean matchesAnyPattern(List<String> patterns, String channel) {
		for (String pattern : patterns) {
			if (channelPattern(pattern).matches(channel)) {
				return true;
			}
		}
		return false;
	}

	private Optional<Pattern> brandPattern(String regex) {
		return brandCache.computeIfAbsent(regex, r -> {
			try {
				return Optional.of(Pattern.compile(r, Pattern.CASE_INSENSITIVE));
			} catch (PatternSyntaxException e) {
				return Optional.empty();
			}
		});
	}

	private ChannelPattern channelPattern(String pattern) {
		return channelCache.computeIfAbsent(pattern, ChannelPattern::of);
	}

	/** Validation for /doorman reload: ids of rules whose brand regex does not compile. */
	public static List<String> invalidBrandRegexes(List<Rule> rules) {
		List<String> bad = new ArrayList<>();
		for (Rule rule : rules) {
			if (rule.brand != null) {
				try {
					Pattern.compile(rule.brand, Pattern.CASE_INSENSITIVE);
				} catch (PatternSyntaxException e) {
					bad.add(rule.id);
				}
			}
		}
		return bad;
	}
}
