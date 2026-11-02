package rp.doorman.mixin;

import net.minecraft.network.ClientConnection;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The connection object is shared by the configuration and play handlers; sessions are keyed by it. */
@Mixin(ServerCommonNetworkHandler.class)
public interface ServerCommonNetworkHandlerAccessor {
	@Accessor("connection")
	ClientConnection doorman$getConnection();
}
