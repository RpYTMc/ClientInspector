package rp.clientinspector;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.PacketByteBuf;
import org.junit.jupiter.api.Test;
import rp.clientinspector.companion.CompanionProtocol;
import rp.clientinspector.companion.HelloPayload;
import rp.clientinspector.companion.ReportPayload;
import rp.clientinspector.companion.ReportedMod;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionProtocolTest {
	private static final UUID NONCE = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
	private static final String HASH = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

	private static ReportPayload roundTrip(ReportPayload report) {
		PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
		try {
			ReportPayload.CODEC.encode(buf, report);
			ReportPayload decoded = ReportPayload.CODEC.decode(buf);
			assertEquals(0, buf.readableBytes(), "the decoder must consume the whole payload");
			return decoded;
		} finally {
			buf.release();
		}
	}

	private static List<ReportedMod> mods(int count, boolean hashed) {
		List<ReportedMod> mods = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			mods.add(new ReportedMod(String.format("some-mod-%04d", i), "1.2." + i + "+mc1.21.11", hashed ? HASH : null, i % 7 == 0));
		}
		return mods;
	}

	@Test
	void reportSurvivesTheWire() {
		ReportPayload report = new ReportPayload(CompanionProtocol.VERSION, NONCE, "0.18.4", "1.21.11", List.of(
				new ReportedMod("fabric-api", "0.141.3+1.21.11", HASH, false),
				new ReportedMod("fabric-networking-api-v1", "5.1.5+ae1e07683e", HASH, true),
				new ReportedMod("java", "21", null, false)), false);
		assertEquals(report, roundTrip(report));
	}

	@Test
	void helloSurvivesTheWire() {
		HelloPayload hello = new HelloPayload(CompanionProtocol.VERSION, NONCE, "optional", "https://modrinth.com/mod/client-inspector");
		PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
		try {
			HelloPayload.CODEC.encode(buf, hello);
			assertEquals(hello, HelloPayload.CODEC.decode(buf));
		} finally {
			buf.release();
		}
	}

	@Test
	void ordinaryModListKeepsEveryHash() {
		ReportPayload report = CompanionProtocol.fit(NONCE, "0.18.4", "1.21.11", mods(150, true));
		assertEquals(150, report.mods().size());
		assertFalse(report.truncated());
		assertTrue(report.mods().stream().allMatch(m -> HASH.equals(m.sha256())));
		assertTrue(CompanionProtocol.encodedSize(report) <= CompanionProtocol.MAX_REPORT_BYTES);
	}

	@Test
	void bigModListDropsHashesBeforeEntries() {
		// 500 hashed entries of about 66 bytes are over 30 KB; without the 32-byte hashes they fit.
		ReportPayload report = CompanionProtocol.fit(NONCE, "0.18.4", "1.21.11", mods(500, true));
		assertEquals(500, report.mods().size());
		assertFalse(report.truncated());
		assertTrue(report.mods().stream().allMatch(m -> m.sha256() == null));
		assertTrue(CompanionProtocol.encodedSize(report) <= CompanionProtocol.MAX_REPORT_BYTES);
		assertEquals(report, roundTrip(report));
	}

	@Test
	void hugeModListIsCappedAndMarkedTruncated() {
		ReportPayload report = CompanionProtocol.fit(NONCE, "0.18.4", "1.21.11", mods(2000, true));
		assertTrue(report.truncated());
		assertTrue(report.mods().size() <= CompanionProtocol.MAX_MODS);
		assertTrue(CompanionProtocol.encodedSize(report) <= CompanionProtocol.MAX_REPORT_BYTES);
		assertEquals("some-mod-0000", report.mods().get(0).id(), "entries are dropped from the end");
	}

	@Test
	void longEntriesAreDroppedUntilTheReportFits() {
		List<ReportedMod> mods = new ArrayList<>();
		for (int i = 0; i < 300; i++) {
			mods.add(new ReportedMod(String.format("%04d", i) + "m".repeat(60), "v".repeat(128), HASH, false));
		}
		ReportPayload report = CompanionProtocol.fit(NONCE, "0.18.4", "1.21.11", mods);
		assertTrue(report.truncated());
		assertTrue(report.mods().size() > 100 && report.mods().size() < 300, "kept " + report.mods().size());
		assertTrue(report.mods().stream().allMatch(m -> m.sha256() == null));
		assertTrue(CompanionProtocol.encodedSize(report) <= CompanionProtocol.MAX_REPORT_BYTES);
		assertEquals(mods.get(report.mods().size() - 1).id(), report.mods().get(report.mods().size() - 1).id());
	}

	@Test
	void decoderRefusesMoreModsThanTheLimit() {
		ReportPayload tooMany = new ReportPayload(CompanionProtocol.VERSION, NONCE, "0.18.4", "1.21.11",
				mods(CompanionProtocol.MAX_MODS + 1, false), false);
		PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
		try {
			ReportPayload.CODEC.encode(buf, tooMany);
			assertThrows(DecoderException.class, () -> ReportPayload.CODEC.decode(buf));
		} finally {
			buf.release();
		}
	}

	@Test
	void overlongStringsAreClippedBeforeEncoding() {
		ReportedMod mod = new ReportedMod("x".repeat(200), "v".repeat(500), null, false);
		assertEquals(CompanionProtocol.MAX_ID_LENGTH, mod.id().length());
		assertEquals(CompanionProtocol.MAX_VERSION_LENGTH, mod.version().length());
		ReportPayload report = new ReportPayload(CompanionProtocol.VERSION, NONCE, "0.18.4", "1.21.11", List.of(mod), false);
		ReportedMod back = roundTrip(report).mods().get(0);
		assertEquals(mod, back);
		assertNull(back.sha256());
	}
}
