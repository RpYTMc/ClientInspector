package rp.clientinspector.detect;

import net.minecraft.network.packet.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConfigurationNetworkHandler;
import net.minecraft.server.network.ServerPlayerConfigurationTask;
import rp.clientinspector.ClientInspector;
import rp.clientinspector.session.ClientSession;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Holds a client in the configuration phase until the bypass lookup (LuckPerms) has answered, so
 * decisions that depend on it, required mode and kick rules, are made before the player exists.
 * Deciding later means kicking at join, which is visible in game and racy (see Detector.kickAfterJoin).
 * Sends nothing; finishes on the lookup's answer or after the timeout, whichever comes first.
 */
final class BypassWaitTask implements ServerPlayerConfigurationTask {
	private static final Key KEY = new Key(ClientInspector.MOD_ID + ":bypass");

	private final ServerConfigurationNetworkHandler handler;
	private final ClientSession session;
	private final MinecraftServer server;
	private final int timeoutTicks;
	private final Runnable onKnown;
	private final AtomicBoolean done = new AtomicBoolean();

	/** @param onKnown server thread, once the session's bypass is final; may kick through the handler */
	BypassWaitTask(ServerConfigurationNetworkHandler handler, ClientSession session, MinecraftServer server, int timeoutTicks,
			Runnable onKnown) {
		this.handler = handler;
		this.session = session;
		this.server = server;
		this.timeoutTicks = timeoutTicks;
		this.onKnown = onKnown;
	}

	/** Finishing always goes through the server's task queue, never from inside vanilla's task start. */
	@Override
	public void sendPacket(Consumer<Packet<?>> sender) {
		CompletableFuture<Boolean> lookup = session.bypassLookup();
		if (lookup != null) {
			lookup.whenComplete((allowed, error) -> ServerQueue.later(server, this::finish));
		}
		ServerQueue.afterTicks(server, timeoutTicks, this::finish);
	}

	@Override
	public Key getKey() {
		return KEY;
	}

	private void finish() {
		if (!done.compareAndSet(false, true) || !handler.isConnectionOpen()) {
			return;
		}
		Detector.resolveBypass(session);
		if (!session.bypassResolved()) {
			ClientInspector.LOGGER.warn("{}: the bypass lookup did not answer within {} ticks; treated as no bypass", session.name(), timeoutTicks);
			session.setBypass(false);
		}
		onKnown.run();
		if (session.kicked() || !handler.isConnectionOpen()) {
			return;
		}
		try {
			handler.completeTask(KEY);
		} catch (IllegalStateException e) {
			ClientInspector.LOGGER.warn("{}: could not finish the bypass wait ({})", session.name(), e.getMessage());
		}
	}
}
