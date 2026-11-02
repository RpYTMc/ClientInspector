package rp.clientinspector.companion;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The handshake between the server half and the client half of the jar: the server sends
 * {@link HelloPayload} during the configuration phase to clients that registered its channel, the
 * client answers with {@link ReportPayload}. Vanilla limits a client-to-server custom payload to
 * 32767 bytes, so the report is capped well below that.
 */
public final class CompanionProtocol {
	public static final int VERSION = 1;
	/** At most this many mods are reported; a report with more is refused by the server. */
	public static final int MAX_MODS = 512;
	/** Encoded size the client aims for. Above it hashes are left out, then entries are dropped. */
	public static final int MAX_REPORT_BYTES = 30 * 1024;
	public static final int MAX_ID_LENGTH = 64;
	public static final int MAX_VERSION_LENGTH = 128;
	public static final int MAX_MODE_LENGTH = 16;
	public static final int MAX_URL_LENGTH = 256;

	private CompanionProtocol() {
	}

	/** Both sides need both codecs: the server decodes the report, the client decodes the hello. */
	public static void registerPayloads() {
		PayloadTypeRegistry.configurationS2C().register(HelloPayload.ID, HelloPayload.CODEC);
		PayloadTypeRegistry.configurationC2S().register(ReportPayload.ID, ReportPayload.CODEC);
	}

	/**
	 * Builds the report the client sends. Up to {@link #MAX_MODS} entries with hashes when that fits
	 * in {@link #MAX_REPORT_BYTES}; otherwise ids and versions only, cut short if even that is too big.
	 */
	public static ReportPayload fit(UUID nonce, String loaderVersion, String gameVersion, List<ReportedMod> mods) {
		List<ReportedMod> capped = mods.size() > MAX_MODS ? mods.subList(0, MAX_MODS) : mods;
		ReportPayload full = new ReportPayload(VERSION, nonce, loaderVersion, gameVersion, capped, capped.size() < mods.size());
		if (encodedSize(full) <= MAX_REPORT_BYTES) {
			return full;
		}
		ReportPayload header = new ReportPayload(VERSION, nonce, loaderVersion, gameVersion, List.of(), true);
		// The count varint grows by at most two bytes over the empty header's.
		int budget = MAX_REPORT_BYTES - encodedSize(header) - 2;
		List<ReportedMod> bare = new ArrayList<>();
		for (ReportedMod mod : capped) {
			ReportedMod withoutHash = mod.withoutHash();
			int size = encodedSize(withoutHash);
			if (size > budget) {
				break;
			}
			budget -= size;
			bare.add(withoutHash);
		}
		return new ReportPayload(VERSION, nonce, loaderVersion, gameVersion, bare, bare.size() < mods.size());
	}

	public static int encodedSize(ReportPayload report) {
		PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
		try {
			ReportPayload.CODEC.encode(buf, report);
			return buf.readableBytes();
		} finally {
			buf.release();
		}
	}

	private static int encodedSize(ReportedMod mod) {
		PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
		try {
			ReportPayload.writeMod(buf, mod);
			return buf.readableBytes();
		} finally {
			buf.release();
		}
	}

	/** Strings are client-controlled on one side and length-checked by the codec on the other; clip before encoding. */
	static String clip(String value, int max) {
		if (value == null) {
			return "";
		}
		return value.length() > max ? value.substring(0, max) : value;
	}
}
