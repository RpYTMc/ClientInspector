package rp.clientinspector.session;

import org.jspecify.annotations.Nullable;
import rp.clientinspector.companion.ReportPayload;
import rp.clientinspector.companion.ReportedMod;
import rp.clientinspector.rules.CompanionState;
import rp.clientinspector.rules.ProbeState;
import rp.clientinspector.rules.RulePhase;
import rp.clientinspector.rules.Signals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything observed about one connection, from the first configuration packet until disconnect.
 * Written from the netty thread (brand, configuration events) and the server thread (play events),
 * so every collection is concurrent and scalar fields are volatile. A session may start as a
 * placeholder (brand or channels arrive before the profile is known) and is promoted in place.
 */
public final class ClientSession {
	public static final UUID PLACEHOLDER_UUID = new UUID(0L, 0L);

	private volatile UUID uuid;
	private volatile String name;
	public final String address;
	public final long startedAtMillis;

	private volatile @Nullable String brand;
	private volatile @Nullable String laterBrand;
	private final Set<String> configChannels = ConcurrentHashMap.newKeySet();
	private final Set<String> playChannels = ConcurrentHashMap.newKeySet();
	private volatile ProbeState probe = ProbeState.NOT_RUN;
	private final Map<String, String> probeReplies = new ConcurrentHashMap<>();
	private final Set<String> resolvedKeys = ConcurrentHashMap.newKeySet();
	private final Set<String> firedRules = ConcurrentHashMap.newKeySet();
	private final Set<String> matchedRules = Collections.synchronizedSet(new LinkedHashSet<>());
	private volatile RulePhase phase = RulePhase.CONFIGURE;
	private volatile boolean kicked;
	private volatile boolean bypass;
	private volatile boolean bypassResolved;
	private volatile boolean joined;
	private volatile int joinTick = -1;
	private volatile int probeRetries;
	private volatile int probePostpones;
	private volatile @Nullable CompletableFuture<Boolean> bypassLookup;
	private volatile CompanionState companion = CompanionState.NONE;
	private volatile Map<String, ReportedMod> reportedMods = Map.of();
	private volatile @Nullable ReportPayload report;
	private final List<String> mismatchReasons = new ArrayList<>();
	private volatile boolean requiredCheckPending;

	public ClientSession(UUID uuid, String name, String address, long startedAtMillis) {
		this.uuid = uuid;
		this.name = name;
		this.address = address;
		this.startedAtMillis = startedAtMillis;
	}

	public UUID uuid() {
		return uuid;
	}

	public String name() {
		return name;
	}

	public boolean isPlaceholder() {
		return PLACEHOLDER_UUID.equals(uuid);
	}

	/** Gives a placeholder its identity once the game profile is known. Keeps everything observed so far. */
	public void promote(UUID uuid, String name) {
		this.uuid = uuid;
		this.name = name;
	}

	public @Nullable String brand() {
		return brand;
	}

	/** The first brand wins; a client that changes its brand later only gets that noted. */
	public void offerBrand(@Nullable String brand) {
		if (this.brand == null) {
			this.brand = brand;
		} else if (brand != null && !brand.equals(this.brand)) {
			laterBrand = brand;
		}
	}

	public @Nullable String laterBrand() {
		return laterBrand;
	}

	public Set<String> configChannels() {
		return Collections.unmodifiableSet(configChannels);
	}

	public Set<String> playChannels() {
		return Collections.unmodifiableSet(playChannels);
	}

	/** Union of configuration and play channels. */
	public Set<String> channels() {
		Set<String> all = new LinkedHashSet<>(configChannels);
		all.addAll(playChannels);
		return all;
	}

	public void addConfigChannels(Iterable<String> ids) {
		for (String id : ids) {
			configChannels.add(id);
		}
	}

	public void addPlayChannels(Iterable<String> ids) {
		for (String id : ids) {
			playChannels.add(id);
		}
	}

	public void removePlayChannels(Iterable<String> ids) {
		for (String id : ids) {
			playChannels.remove(id);
		}
	}

	public ProbeState probe() {
		return probe;
	}

	public void setProbe(ProbeState state) {
		this.probe = state;
	}

	/** probe label to what the client sent back for it (raw text), insertion-ordered copy. */
	public Map<String, String> probeReplies() {
		return Collections.unmodifiableMap(new LinkedHashMap<>(probeReplies));
	}

	public void recordProbeReply(String label, String reply, boolean resolved) {
		probeReplies.put(label, reply);
		if (resolved) {
			resolvedKeys.add(label);
		} else {
			resolvedKeys.remove(label);
		}
	}

	public void clearProbe() {
		probeReplies.clear();
		resolvedKeys.clear();
		probe = ProbeState.NOT_RUN;
	}

	public Set<String> resolvedKeys() {
		return Collections.unmodifiableSet(resolvedKeys);
	}

	public int probeRetries() {
		return probeRetries;
	}

	public void countProbeRetry() {
		probeRetries++;
	}

	public int probePostpones() {
		return probePostpones;
	}

	public void countProbePostpone() {
		probePostpones++;
	}

	public RulePhase phase() {
		return phase;
	}

	public void advance(RulePhase next) {
		if (next.ordinal() > phase.ordinal()) {
			phase = next;
		}
	}

	/** True the first time a rule id is recorded; false if it already fired for this session. */
	public boolean markFired(String ruleId) {
		boolean first = firedRules.add(ruleId);
		if (first) {
			matchedRules.add(ruleId);
		}
		return first;
	}

	public Set<String> matchedRules() {
		synchronized (matchedRules) {
			return new LinkedHashSet<>(matchedRules);
		}
	}

	public boolean kicked() {
		return kicked;
	}

	public void markKicked() {
		kicked = true;
	}

	public boolean bypass() {
		return bypass;
	}

	/** Whether the bypass answer (op status or permission lookup) is final. */
	public boolean bypassResolved() {
		return bypassResolved;
	}

	public void setBypass(boolean bypass) {
		this.bypass = bypass;
		this.bypassResolved = true;
	}

	public @Nullable CompletableFuture<Boolean> bypassLookup() {
		return bypassLookup;
	}

	public void setBypassLookup(CompletableFuture<Boolean> lookup) {
		this.bypassLookup = lookup;
	}

	public boolean joined() {
		return joined;
	}

	public void markJoined(int tick) {
		joined = true;
		joinTick = tick;
	}

	public int joinTick() {
		return joinTick;
	}

	public CompanionState companion() {
		return companion;
	}

	public void setCompanion(CompanionState state) {
		this.companion = state;
	}

	/** Stores the client part's report and marks the session REPORTED. */
	public void setReport(ReportPayload report) {
		Map<String, ReportedMod> byId = new TreeMap<>();
		for (ReportedMod mod : report.mods()) {
			byId.putIfAbsent(mod.id(), mod);
		}
		this.reportedMods = Collections.unmodifiableMap(byId);
		this.report = report;
		if (companion != CompanionState.MISMATCH) {
			this.companion = CompanionState.REPORTED;
		}
	}

	/** Reported mods by id, sorted; empty unless the client part reported. */
	public Map<String, ReportedMod> reportedMods() {
		return reportedMods;
	}

	public @Nullable ReportPayload report() {
		return report;
	}

	/** Records why the report cannot be trusted and marks the session MISMATCH. */
	public void markMismatch(List<String> reasons) {
		synchronized (mismatchReasons) {
			for (String reason : reasons) {
				if (!mismatchReasons.contains(reason)) {
					mismatchReasons.add(reason);
				}
			}
		}
		this.companion = CompanionState.MISMATCH;
	}

	public List<String> mismatchReasons() {
		synchronized (mismatchReasons) {
			return List.copyOf(mismatchReasons);
		}
	}

	/** Required mode could not decide in the configuration phase (bypass lookup pending); decide at join. */
	public boolean requiredCheckPending() {
		return requiredCheckPending;
	}

	public void setRequiredCheckPending(boolean pending) {
		this.requiredCheckPending = pending;
	}

	public Signals signals() {
		return new Signals(brand, channels(), probe, resolvedKeys, companion, reportedMods.keySet());
	}
}
