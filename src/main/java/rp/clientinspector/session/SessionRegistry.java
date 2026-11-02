package rp.clientinspector.session;

import net.minecraft.network.ClientConnection;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sessions keyed by the connection object, which vanilla shares between the configuration handler
 * and the play handler of the same player. Entries are removed on disconnect from either phase.
 */
public final class SessionRegistry {
	private final Map<ClientConnection, ClientSession> sessions = new ConcurrentHashMap<>();

	public @Nullable ClientSession get(ClientConnection connection) {
		return sessions.get(connection);
	}

	/** Returns the existing session for the connection, or creates one (as a placeholder when the profile is unknown). */
	public ClientSession getOrCreate(ClientConnection connection, UUID uuid, String name, String address) {
		return sessions.computeIfAbsent(connection,
				c -> new ClientSession(uuid, name, address, System.currentTimeMillis()));
	}

	public @Nullable ClientSession remove(ClientConnection connection) {
		return sessions.remove(connection);
	}

	public boolean contains(ClientConnection connection) {
		return sessions.containsKey(connection);
	}

	/** The most recently started session for the player, ignoring placeholders. */
	public @Nullable ClientSession byUuid(UUID uuid) {
		ClientSession best = null;
		for (ClientSession s : sessions.values()) {
			if (s.uuid().equals(uuid) && (best == null || s.startedAtMillis > best.startedAtMillis)) {
				best = s;
			}
		}
		return best;
	}

	public List<ClientSession> all() {
		return new ArrayList<>(sessions.values());
	}

	public int size() {
		return sessions.size();
	}
}
