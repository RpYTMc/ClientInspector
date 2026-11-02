package rp.clientinspector.mixin;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.BrandCustomPayload;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rp.clientinspector.ClientInspector;

/**
 * Vanilla throws the client brand away (the handler body is empty) and Fabric API keeps it only in
 * an internal class, so capture it here. The client sends it as the first custom payload of the
 * configuration phase, on the netty thread.
 */
@Mixin(ServerCommonNetworkHandler.class)
public abstract class ServerCommonNetworkHandlerMixin {
	@Shadow
	@Final
	protected ClientConnection connection;

	@Inject(method = "onCustomPayload", at = @At("HEAD"))
	private void clientinspector$captureBrand(CustomPayloadC2SPacket packet, CallbackInfo ci) {
		if (packet.payload() instanceof BrandCustomPayload brand) {
			ClientInspector.detector().onBrand(connection, brand.brand());
		}
	}
}
