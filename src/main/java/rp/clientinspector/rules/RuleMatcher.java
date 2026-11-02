package rp.clientinspector.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Pure rule evaluation; no Minecraft classes, so it is unit-testable. Safe to call from any thread. */
public final class RuleMatcher {
	private final Map<String, Optional<Pattern>> brandCache = new ConcurrentHashMap<>();
	private final Map<String, ChannelPattern> channelCache = new ConcurrentHashMap<>();
	private final Map<String, ModPattern> modCache = new ConcurrentHashMap<>();

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
		if ((!rule.translation_keys.isEmpty() || !rule.keybind_keys.isEmpty()) && !probeHit(rule, signals.resolvedKeys())) {
			return false;
		}
		if (!rule.probe_state.isEmpty() && !rule.probe_state.contains(signals.probe())) {
			return false;
		}
		if (!rule.reported_mods_any.isEmpty() && reportedMatches(rule.reported_mods_any, signals.reportedMods()).isEmpty()) {
			return false;
		}
		if (!rule.companion_state.isEmpty() && !rule.companion_state.contains(signals.companion())) {
			return false;
		}
		return true;
	}

	/** Reported mod ids matched by any of the patterns, in id order. */
	public List<String> reportedMatches(List<String> patterns, Iterable<String> reportedMods) {
		List<String> out = new ArrayList<>();
		for (String id : reportedMods) {
			for (String pattern : patterns) {
				if (modPattern(pattern).matches(id)) {
					out.add(id);
					break;
				}
			}
		}
		return out;
	}

	public boolean modMatches(String pattern, String modId) {
		return modPattern(pattern).matches(modId);
	}

	private ModPattern modPattern(String pattern) {
		return modCache.computeIfAbsent(pattern, ModPattern::of);
	}

	/** True when one of the rule's translation keys or keybind ids resolved in the probe. */
	public static boolean probeHit(Rule rule, Set<String> resolvedKeys) {
		for (String key : rule.translation_keys) {
			if (resolvedKeys.contains(key)) {
				return true;
			}
		}
		for (String id : rule.keybind_keys) {
			if (resolvedKeys.contains("keybind:" + id)) {
				return true;
			}
		}
		return false;
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

	/** Validation for /clientinspector reload: ids of rules whose brand regex does not compile. */
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
