package rp.clientinspector.probe;

import rp.clientinspector.ClientInspectorConfig;
import rp.clientinspector.rules.RuleMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A canary is a translation key of a mod whose presence the client already proved through its
 * registered channels (Fabric API, Simple Voice Chat, ...). An honest client therefore resolves
 * it; a raw echo means something rewrote the sign reply on the way back.
 */
public final class CanaryCheck {
	private CanaryCheck() {
	}

	/** Keys of applicable canaries (owning mod present by channel evidence) that did not resolve. */
	public static List<String> unresolvedCanaries(List<ClientInspectorConfig.Canary> canaries, Set<String> channels,
			Set<String> resolvedKeys, RuleMatcher matcher) {
		List<String> out = new ArrayList<>();
		for (ClientInspectorConfig.Canary canary : canaries) {
			if (canary == null || canary.key == null || canary.key.isBlank() || canary.channels_any == null) {
				continue;
			}
			boolean present = false;
			for (String channel : channels) {
				if (matcher.matchesAnyPattern(canary.channels_any, channel)) {
					present = true;
					break;
				}
			}
			if (present && !resolvedKeys.contains(canary.key)) {
				out.add(canary.key);
			}
		}
		return out;
	}
}
