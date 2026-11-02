package rp.doorman.probe;

import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import rp.doorman.session.ClientSession;

import java.util.List;

/**
 * Runs the sign probe for one player. Implementations report back through
 * {@link rp.doorman.detect.Detector#onProbeFinished} once every key has a reply or the timeout hit.
 * Everything except {@link #onSignUpdate} happens on the server thread.
 */
public interface ProbeRunner {
	/**
	 * @param keys probe lines to test; the implementation may need several signs for them
	 */
	void start(ServerPlayerEntity player, ClientSession session, List<ProbeKey> keys);

	/** Called every server tick to expire pending probes. */
	void tick(MinecraftServer server);

	/** Forget everything about a session (disconnect). */
	void forget(ClientSession session);

	/**
	 * Network thread. Returns true when the packet was this player's probe reply and vanilla must
	 * not process it (there is no sign at that position server-side anyway).
	 */
	boolean onSignUpdate(ServerPlayNetworkHandler handler, UpdateSignC2SPacket packet);
}
