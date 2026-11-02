package rp.doorman.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.jspecify.annotations.Nullable;
import rp.doorman.Doorman;
import rp.doorman.DoormanConfig;
import rp.doorman.session.ClientSession;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * config/doorman/profiles.json: the last observation per player, so staff can inspect a client that
 * is no longer online, plus per-staff alert preferences. All methods are synchronised; saves are
 * batched by the detector's tick (every 30 s) and on server stop.
 */
public final class ProfileStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Type TYPE = new TypeToken<Data>() {
	}.getType();
	private static final String FILE_NAME = "profiles.json";

	/** Serialised shape. */
	public static final class Data {
		public Map<String, Profile> profiles = new LinkedHashMap<>();
		public List<String> alerts_muted = new ArrayList<>();
	}

	public static final class Profile {
		public String name = "";
		public String last_seen = "";
		public String address = "";
		public int joins = 0;
		public @Nullable String brand = null;
		public List<String> channels = new ArrayList<>();
		public String probe = "NOT_RUN";
		public Map<String, String> probe_replies = new TreeMap<>();
		public List<String> matched_rules = new ArrayList<>();
	}

	private Data data = new Data();
	private boolean dirty;

	public static Path path() {
		return DoormanConfig.dir().resolve(FILE_NAME);
	}

	public synchronized void load() {
		Path file = path();
		if (!Files.exists(file)) {
			data = new Data();
			return;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Data parsed = GSON.fromJson(reader, TYPE);
			data = parsed == null ? new Data() : parsed;
			if (data.profiles == null) data.profiles = new LinkedHashMap<>();
			if (data.alerts_muted == null) data.alerts_muted = new ArrayList<>();
		} catch (IOException | RuntimeException e) {
			Doorman.LOGGER.error("Failed to read {} ({}); starting with an empty profile store", file,
					DoormanConfig.describe(e));
			data = new Data();
		}
	}

	public synchronized void record(ClientSession session, int maxProfiles) {
		Profile p = data.profiles.computeIfAbsent(session.uuid().toString(), k -> new Profile());
		p.name = session.name();
		p.last_seen = Instant.now().toString();
		p.address = session.address;
		p.brand = session.brand();
		p.channels = new ArrayList<>(new TreeSet<>(session.channels()));
		p.probe = session.probe().name();
		p.probe_replies = new TreeMap<>(session.probeReplies());
		p.matched_rules = new ArrayList<>(session.matchedRules());
		trim(maxProfiles);
		dirty = true;
	}

	public synchronized void countJoin(UUID uuid) {
		Profile p = data.profiles.computeIfAbsent(uuid.toString(), k -> new Profile());
		p.joins++;
		dirty = true;
	}

	public synchronized @Nullable Profile get(UUID uuid) {
		return data.profiles.get(uuid.toString());
	}

	public synchronized Map.@Nullable Entry<UUID, Profile> findByName(String name) {
		for (Map.Entry<String, Profile> e : data.profiles.entrySet()) {
			if (e.getValue().name != null && e.getValue().name.equalsIgnoreCase(name)) {
				try {
					return Map.entry(UUID.fromString(e.getKey()), e.getValue());
				} catch (IllegalArgumentException ignored) {
					return null;
				}
			}
		}
		return null;
	}

	public synchronized boolean alertsMuted(UUID uuid) {
		return data.alerts_muted.contains(uuid.toString());
	}

	public synchronized void setAlertsMuted(UUID uuid, boolean muted) {
		String key = uuid.toString();
		boolean changed = muted ? !data.alerts_muted.contains(key) && data.alerts_muted.add(key) : data.alerts_muted.remove(key);
		if (changed) {
			dirty = true;
			saveIfDirty();
		}
	}

	public synchronized int size() {
		return data.profiles.size();
	}

	private void trim(int maxProfiles) {
		if (data.profiles.size() <= maxProfiles) {
			return;
		}
		List<Map.Entry<String, Profile>> entries = new ArrayList<>(data.profiles.entrySet());
		entries.sort(Comparator.comparing(e -> e.getValue().last_seen == null ? "" : e.getValue().last_seen));
		int drop = data.profiles.size() - maxProfiles;
		for (int i = 0; i < drop; i++) {
			data.profiles.remove(entries.get(i).getKey());
		}
	}

	public synchronized void saveIfDirty() {
		if (!dirty) {
			return;
		}
		Path file = path();
		try {
			String json = GSON.toJson(data, TYPE);
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				writer.write(json);
			}
			dirty = false;
		} catch (IOException | RuntimeException e) {
			Doorman.LOGGER.error("Failed to write {}", file, e);
		}
	}
}
