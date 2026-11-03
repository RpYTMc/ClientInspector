package rp.clientinspector.detect;

import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Server-thread work for players who are not in the world yet. A 1.21.2+ server that has been empty
 * for pause-when-empty-seconds stops ticking: tick events and getTicks() stand still, and a client in
 * the configuration phase does not count as a player, so anything waiting on the tick scheduler for
 * it would wait forever. The server's task queue keeps running while paused, so use that, with
 * wall-clock delays.
 */
public final class ServerQueue {
	private static final long MILLIS_PER_TICK = 50L;

	private ServerQueue() {
	}

	/** Queues the task; it runs on the server thread after whatever is running now, never inside it. */
	public static void later(MinecraftServer server, Runnable task) {
		server.send(server.createTask(task));
	}

	/** Queues the task once the given number of ticks has passed on the clock, paused or not. */
	public static void afterTicks(MinecraftServer server, int ticks, Runnable task) {
		CompletableFuture.delayedExecutor(ticks * MILLIS_PER_TICK, TimeUnit.MILLISECONDS).execute(() -> later(server, task));
	}
}
