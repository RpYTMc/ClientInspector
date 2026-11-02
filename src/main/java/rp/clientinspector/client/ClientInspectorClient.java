package rp.clientinspector.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import rp.clientinspector.ClientInspector;
import rp.clientinspector.companion.CompanionProtocol;
import rp.clientinspector.companion.HelloPayload;
import rp.clientinspector.companion.ReportPayload;

import java.util.concurrent.CompletableFuture;

/**
 * The client part. Registering the hello receiver is what tells a Client Inspector server that this
 * client has the jar; the server then sends a hello during the configuration phase and this answers
 * with the mod list. On any other server nothing is sent and nothing happens. No GUI, no settings.
 */
public final class ClientInspectorClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		CompletableFuture<ModInventory> inventory = ModInventory.scanAsync();
		ClientConfigurationNetworking.registerGlobalReceiver(HelloPayload.ID, (hello, context) -> {
			PacketSender sender = context.responseSender();
			// Normally long done by the time a server is joined; otherwise the answer goes out when the scan ends.
			inventory.thenAccept(inv -> {
				ReportPayload report = CompanionProtocol.fit(hello.nonce(), inv.loaderVersion(), inv.gameVersion(), inv.mods());
				sender.sendPacket(report);
				ClientInspector.LOGGER.debug("Reported {} mods to the server (truncated={})", report.mods().size(), report.truncated());
			});
		});
	}
}
