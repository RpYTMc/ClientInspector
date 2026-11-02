package rp.clientinspector.companion;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import rp.clientinspector.ClientInspector;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Client to server, configuration phase: the answer to {@link HelloPayload}. The client builds it with
 * {@link CompanionProtocol#fit} so it stays within {@link CompanionProtocol#MAX_REPORT_BYTES}; the server
 * refuses (disconnects) a report with more than {@link CompanionProtocol#MAX_MODS} entries.
 *
 * @param protocol      {@link CompanionProtocol#VERSION} of the client
 * @param nonce         the nonce from the hello
 * @param loaderVersion Fabric Loader version on the client
 * @param gameVersion   Minecraft version on the client
 * @param mods          every mod Fabric Loader loaded, sorted by id
 * @param truncated     true when the list had to be cut short to fit the size cap
 */
public record ReportPayload(int protocol, UUID nonce, String loaderVersion, String gameVersion, List<ReportedMod> mods,
		boolean truncated) implements CustomPayload {
	public static final CustomPayload.Id<ReportPayload> ID = new CustomPayload.Id<>(Identifier.of(ClientInspector.MOD_ID, "report"));
	public static final PacketCodec<PacketByteBuf, ReportPayload> CODEC = PacketCodec.of(ReportPayload::write, ReportPayload::read);

	private static final int SHA256_BYTES = 32;

	public ReportPayload {
		loaderVersion = CompanionProtocol.clip(loaderVersion, CompanionProtocol.MAX_VERSION_LENGTH);
		gameVersion = CompanionProtocol.clip(gameVersion, CompanionProtocol.MAX_VERSION_LENGTH);
		mods = List.copyOf(mods);
	}

	private void write(PacketByteBuf buf) {
		buf.writeVarInt(protocol);
		buf.writeUuid(nonce);
		buf.writeString(loaderVersion, CompanionProtocol.MAX_VERSION_LENGTH);
		buf.writeString(gameVersion, CompanionProtocol.MAX_VERSION_LENGTH);
		buf.writeBoolean(truncated);
		buf.writeVarInt(mods.size());
		for (ReportedMod mod : mods) {
			writeMod(buf, mod);
		}
	}

	static void writeMod(PacketByteBuf buf, ReportedMod mod) {
		buf.writeString(mod.id(), CompanionProtocol.MAX_ID_LENGTH);
		buf.writeString(mod.version(), CompanionProtocol.MAX_VERSION_LENGTH);
		buf.writeBoolean(mod.nested());
		buf.writeBoolean(mod.sha256() != null);
		if (mod.sha256() != null) {
			buf.writeBytes(HexFormat.of().parseHex(mod.sha256()));
		}
	}

	private static ReportPayload read(PacketByteBuf buf) {
		int protocol = buf.readVarInt();
		UUID nonce = buf.readUuid();
		String loader = buf.readString(CompanionProtocol.MAX_VERSION_LENGTH);
		String game = buf.readString(CompanionProtocol.MAX_VERSION_LENGTH);
		boolean truncated = buf.readBoolean();
		int count = buf.readVarInt();
		if (count < 0 || count > CompanionProtocol.MAX_MODS) {
			throw new DecoderException("Client Inspector report lists " + count + " mods, the limit is " + CompanionProtocol.MAX_MODS);
		}
		List<ReportedMod> mods = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			String id = buf.readString(CompanionProtocol.MAX_ID_LENGTH);
			String version = buf.readString(CompanionProtocol.MAX_VERSION_LENGTH);
			boolean nested = buf.readBoolean();
			String sha256 = null;
			if (buf.readBoolean()) {
				byte[] hash = new byte[SHA256_BYTES];
				buf.readBytes(hash);
				sha256 = HexFormat.of().formatHex(hash);
			}
			mods.add(new ReportedMod(id, version, sha256, nested));
		}
		return new ReportPayload(protocol, nonce, loader, game, mods, truncated);
	}

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
