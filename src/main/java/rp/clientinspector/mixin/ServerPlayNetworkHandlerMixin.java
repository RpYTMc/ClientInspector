package rp.clientinspector.mixin;

import net.minecraft.network.packet.BrandCustomPayload;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rp.clientinspector.ClientInspector;

/**
 * Two play-phase hooks. (1) The play handler overrides onCustomPayload with its own empty body, so
 * the common-handler mixin never sees play-phase payloads; some clients re-send their brand after
 * joining, record those too. (2) The probe's sign reply arrives as an ordinary sign update for a
 * position that has no sign server-side; consume it before vanilla looks for one.
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {
	@Inject(method = "onCustomPayload", at = @At("HEAD"))
	private void clientinspector$capturePlayBrand(CustomPayloadC2SPacket packet, CallbackInfo ci) {
		if (packet.payload() instanceof BrandCustomPayload brand) {
			ClientInspector.detector().onBrand(((ServerCommonNetworkHandlerAccessor) this).clientinspector$getConnection(), brand.brand());
		}
	}

	@Inject(method = "onUpdateSign", at = @At("HEAD"), cancellable = true)
	private void clientinspector$captureProbeReply(UpdateSignC2SPacket packet, CallbackInfo ci) {
		if (ClientInspector.detector().probe().onSignUpdate((ServerPlayNetworkHandler) (Object) this, packet)) {
			ci.cancel();
		}
	}
}
