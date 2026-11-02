package rp.clientinspector.companion;

import rp.clientinspector.rules.Rule;
import rp.clientinspector.rules.RuleMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Cross-checks a client part report against what the server saw on its own. A report that leaves out
 * a mod the probe just proved, or leaves out Fabric API (which the jar itself depends on), was not
 * produced by an honest client part. Pure logic; callers skip reports marked truncated.
 */
public final class CompanionCheck {
	public static final String FABRIC_API = "fabric-api";
	private static final List<String> FABRIC_CHANNELS = List.of("fabric:*", "fabric-*:*");

	private CompanionCheck() {
	}

	/** One reason per enabled rule with a mod_id whose probe keys resolved while no reported id matches it. */
	public static List<String> probeContradictions(List<Rule> rules, Set<String> resolvedKeys, Set<String> reportedIds, RuleMatcher matcher) {
		List<String> reasons = new ArrayList<>();
		for (Rule rule : rules) {
			if (!rule.enabled || rule.mod_id == null || !RuleMatcher.probeHit(rule, resolvedKeys)) {
				continue;
			}
			boolean reported = false;
			for (String id : reportedIds) {
				if (matcher.modMatches(rule.mod_id, id)) {
					reported = true;
					break;
				}
			}
			if (!reported) {
				reasons.add("the probe proved " + rule.mod_id + " (rule " + rule.id + ") but the report leaves it out");
			}
		}
		return reasons;
	}

	/** The jar depends on Fabric API, so a report from a client with Fabric channels must list it. */
	public static List<String> fabricApiContradiction(Set<String> channels, Set<String> reportedIds, RuleMatcher matcher) {
		if (reportedIds.contains(FABRIC_API)) {
			return List.of();
		}
		for (String channel : channels) {
			if (matcher.matchesAnyPattern(FABRIC_CHANNELS, channel)) {
				return List.of("Fabric API channels are registered but the report leaves out " + FABRIC_API);
			}
		}
		return List.of();
	}
}
