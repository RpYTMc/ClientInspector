package rp.clientinspector.companion;

import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConfigurationNetworkHandler;
import net.minecraft.server.network.ServerPlayerConfigurationTask;
import org.jspecify.annotations.Nullable;
import rp.clientinspector.ClientInspector;
import rp.clientinspector.ClientInspectorConfig;
import rp.clientinspector.detect.ServerQueue;
import rp.clientinspector.rules.CompanionState;
import rp.clientinspector.session.ClientSession;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Server half of the handshake. At CONFIGURE a client that registered the hello channel gets a
 * configuration task: the task sends the hello and holds the client in the configuration phase until
 * the report arrives or companion.timeout_ticks pass. Clients without the channel stay NONE and are
 * not held at all. Results are applied on the server thread, where vanilla finishes its own tasks.
 */
public final class CompanionServer {
	private static final ServerPlayerConfigurationTask.Key TASK_KEY = new ServerPlayerConfigurationTask.Key(ClientInspector.MOD_ID + ":report");

	/** Server thread; the session has its report (or TIMEOUT) and the configuration task is about to finish. */
	public interface Listener {
		void onCompanionResult(ServerConfigurationNetworkHandler handler, ClientSession session);
	}

	private final Supplier<ClientInspectorConfig> config;
	private final Listener listener;
	private final SecureRandom random = new SecureRandom();
	private final Map<ClientConnection, ReportTask> pending = new ConcurrentHashMap<>();

	public CompanionServer(Supplier<ClientInspectorConfig> config, Listener listener) {
		this.config = config;
		this.listener = listener;
	}

	/** True when the client registered the hello channel, i.e. it has the jar installed. */
	public static boolean hasClientPart(ServerConfigurationNetworkHandler handler) {
		return ServerConfigurationNetworking.canSend(handler, HelloPayload.ID);
	}

	/** CONFIGURE event. Queues the report task for a client with the jar; returns whether it did. */
	public boolean start(ServerConfigurationNetworkHandler handler, ClientConnection connection, ClientSession session, MinecraftServer server) {
		if (config.get().companion.mode == ClientInspectorConfig.CompanionMode.OFF || !hasClientPart(handler)) {
			return false;
		}
		handler.addTask(new ReportTask(handler, connection, session, server, new UUID(random.nextLong(), random.nextLong())));
		return true;
	}

	/** Netty thread. A report only counts while its task is waiting and when it echoes the task's nonce. */
	public void onReport(ClientConnection connection, ReportPayload report, MinecraftServer server) {
		ReportTask task = pending.get(connection);
		if (task == null) {
			ClientInspector.LOGGER.debug("Ignoring a client part report nobody asked for from {}", connection.getAddress());
			return;
		}
		if (!task.nonce.equals(report.nonce())) {
			ClientInspector.LOGGER.warn("{}: client part report with the wrong nonce ignored", task.session.name());
			return;
		}
		if (report.protocol() != CompanionProtocol.VERSION) {
			ClientInspector.LOGGER.info("{}: client part speaks protocol {}, this server {}", task.session.name(),
					report.protocol(), CompanionProtocol.VERSION);
		}
		ServerQueue.later(server, () -> task.finish(report));
	}

	/** The connection closed during the configuration phase. */
	public void forget(ClientConnection connection) {
		ReportTask task = pending.remove(connection);
		if (task != null) {
			task.done.set(true);
		}
	}

	private final class ReportTask implements ServerPlayerConfigurationTask {
		private final ServerConfigurationNetworkHandler handler;
		private final ClientConnection connection;
		private final ClientSession session;
		private final MinecraftServer server;
		private final UUID nonce;
		private final AtomicBoolean done = new AtomicBoolean();

		ReportTask(ServerConfigurationNetworkHandler handler, ClientConnection connection, ClientSession session, MinecraftServer server, UUID nonce) {
			this.handler = handler;
			this.connection = connection;
			this.session = session;
			this.server = server;
			this.nonce = nonce;
		}

		/** Runs when the task becomes current, on whichever thread finished the previous one. */
		@Override
		public void sendPacket(Consumer<Packet<?>> sender) {
			ClientInspectorConfig cfg = config.get();
			pending.put(connection, this);
			sender.accept(ServerConfigurationNetworking.createS2CPacket(
					new HelloPayload(CompanionProtocol.VERSION, nonce, cfg.companion.mode.id(), cfg.companion.download_url)));
			ServerQueue.afterTicks(server, cfg.companion.timeout_ticks, () -> finish(null));
		}

		@Override
		public Key getKey() {
			return TASK_KEY;
		}

		/** Server thread. A null report means the timeout came first. */
		void finish(@Nullable ReportPayload report) {
			if (!done.compareAndSet(false, true)) {
				return;
			}
			pending.remove(connection, this);
			if (!handler.isConnectionOpen()) {
				return;
			}
			if (report != null) {
				session.setReport(report);
			} else {
				session.setCompanion(CompanionState.TIMEOUT);
				ClientInspector.LOGGER.info("{} has the client part but sent no report within {} ticks", session.name(),
						config.get().companion.timeout_ticks);
			}
			listener.onCompanionResult(handler, session);
			if (session.kicked() || !handler.isConnectionOpen()) {
				return;
			}
			try {
				handler.completeTask(TASK_KEY);
			} catch (IllegalStateException e) {
				ClientInspector.LOGGER.warn("{}: could not finish the client part task ({})", session.name(), e.getMessage());
			}
		}
	}
}
