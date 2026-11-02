package rp.clientinspector.rules;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * Immutable snapshot of everything the server has observed about one client, handed to the matcher.
 *
 * @param brand        raw brand string exactly as the client sent it, or null when none arrived yet
 * @param channels     every channel identifier the client registered so far (configuration + play)
 * @param probe        probe outcome
 * @param resolvedKeys translation keys the client resolved (the probe reply differed from the key)
 * @param companion    what the client part reported, if anything
 * @param reportedMods mod ids from the client part's report; empty unless it reported
 */
public record Signals(@Nullable String brand, Set<String> channels, ProbeState probe, Set<String> resolvedKeys,
		CompanionState companion, Set<String> reportedMods) {
	public Signals {
		channels = Collections.unmodifiableSet(new TreeSet<>(channels));
		resolvedKeys = Collections.unmodifiableSet(new TreeSet<>(resolvedKeys));
		reportedMods = Collections.unmodifiableSet(new TreeSet<>(reportedMods));
	}

	/** A client without the client part. */
	public Signals(@Nullable String brand, Set<String> channels, ProbeState probe, Set<String> resolvedKeys) {
		this(brand, channels, probe, resolvedKeys, CompanionState.NONE, Set.of());
	}

	public static Signals empty() {
		return new Signals(null, Set.of(), ProbeState.NOT_RUN, Set.of());
	}
}
