package rp.clientinspector.companion;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import rp.clientinspector.ClientInspector;

import java.util.UUID;

/**
 * Server to client, configuration phase: "this server runs Client Inspector, send me your mod list".
 * Only sent to clients that registered this channel, i.e. clients with the jar installed.
 *
 * @param protocol    {@link CompanionProtocol#VERSION} of the server
 * @param nonce       random per connection; the report must echo it
 * @param mode        the server's companion.mode (off, optional, required), informational
 * @param downloadUrl where players get the jar, informational
 */
public record HelloPayload(int protocol, UUID nonce, String mode, String downloadUrl) implements CustomPayload {
	public static final CustomPayload.Id<HelloPayload> ID = new CustomPayload.Id<>(Identifier.of(ClientInspector.MOD_ID, "hello"));
	public static final PacketCodec<PacketByteBuf, HelloPayload> CODEC = PacketCodec.of(HelloPayload::write, HelloPayload::read);

	public HelloPayload {
		mode = CompanionProtocol.clip(mode, CompanionProtocol.MAX_MODE_LENGTH);
		downloadUrl = CompanionProtocol.clip(downloadUrl, CompanionProtocol.MAX_URL_LENGTH);
	}

	private void write(PacketByteBuf buf) {
		buf.writeVarInt(protocol);
		buf.writeUuid(nonce);
		buf.writeString(mode, CompanionProtocol.MAX_MODE_LENGTH);
		buf.writeString(downloadUrl, CompanionProtocol.MAX_URL_LENGTH);
	}

	private static HelloPayload read(PacketByteBuf buf) {
		return new HelloPayload(buf.readVarInt(), buf.readUuid(), buf.readString(CompanionProtocol.MAX_MODE_LENGTH),
				buf.readString(CompanionProtocol.MAX_URL_LENGTH));
	}

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
