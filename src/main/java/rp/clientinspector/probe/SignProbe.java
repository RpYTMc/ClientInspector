package rp.clientinspector.probe;

import com.mojang.serialization.DataResult;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.CloseScreenS2CPacket;
import net.minecraft.network.packet.s2c.play.SignEditorOpenS2CPacket;
import net.minecraft.registry.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.jspecify.annotations.Nullable;
import rp.clientinspector.ClientInspector;
import rp.clientinspector.ClientInspectorConfig;
import rp.clientinspector.detect.Detector;
import rp.clientinspector.rules.ProbeState;
import rp.clientinspector.session.ClientSession;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The sign probe (MC-265322). For each batch of up to three keys the server sends, in one tick: a
 * block update placing an oak sign a few blocks below the player, the sign's text (one line per
 * key, each {@code translate:"%s"} wrapping the lookup, plus a random nonce on the last line), the
 * open-sign-editor packet, a close-screen packet, and a block update restoring the real block. The
 * client builds the sign editor from its own translation of the text and, when the screen is closed
 * in the same tick, sends the four lines back. The nonce ties a reply to its batch, so a late or
 * repeated reply can never be read against the wrong keys. Nothing is placed in the server world
 * and no block entity of the real world is touched.
 */
public final class SignProbe implements ProbeRunner {
	private static final int LINES = 4;
	private static final int KEYS_PER_SIGN = LINES - 1;
	private static final String NONCE_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
	private static final int NONCE_LENGTH = 8;
	private static final int MAX_KEY_LENGTH = 100;

	private final Supplier<Detector> detector;
	private final Supplier<ClientInspectorConfig> config;
	private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
	private final SecureRandom random = new SecureRandom();
	private volatile @Nullable MinecraftServer server;

	private static final class Pending {
		final ClientSession session;
		final Deque<List<ProbeKey>> batches = new ArrayDeque<>();
		final Map<String, String> replies = new LinkedHashMap<>();
		volatile @Nullable List<ProbeKey> currentKeys;
		volatile @Nullable String currentNonce;
		volatile int deadlineTick;
		volatile boolean finished;

		Pending(ClientSession session) {
			this.session = session;
		}
	}

	public SignProbe(Supplier<Detector> detector, Supplier<ClientInspectorConfig> config) {
		this.detector = detector;
		this.config = config;
	}

	// ------------------------------------------------------------------ server thread

	@Override
	public void start(ServerPlayerEntity player, ClientSession session, List<ProbeKey> keys) {
		MinecraftServer srv = player.getEntityWorld().getServer();
		server = srv;
		Pending previous = pending.get(player.getUuid());
		if (previous != null && !previous.finished) {
			// Never run two probes at once: the in-flight batch keeps its nonce and finishes on its own.
			ClientInspector.LOGGER.debug("Probe already pending for {}; ignoring new request", session.name());
			return;
		}
		Pending p = new Pending(session);
		List<ProbeKey> batch = new ArrayList<>(KEYS_PER_SIGN);
		for (ProbeKey key : keys) {
			if (key == null || key.id().isBlank() || key.id().length() > MAX_KEY_LENGTH) {
				continue;
			}
			batch.add(key);
			if (batch.size() == KEYS_PER_SIGN) {
				p.batches.add(batch);
				batch = new ArrayList<>(KEYS_PER_SIGN);
			}
		}
		if (!batch.isEmpty()) {
			p.batches.add(batch);
		}
		if (p.batches.isEmpty()) {
			detector.get().onProbeFinished(session, ProbeState.NOT_RUN, Map.of());
			return;
		}
		pending.put(player.getUuid(), p);
		sendNextBatch(player, p);
	}

	private void sendNextBatch(ServerPlayerEntity player, Pending p) {
		List<ProbeKey> keys = p.batches.poll();
		if (keys == null) {
			finish(player.getUuid(), p, false);
			return;
		}
		ServerWorld world = player.getEntityWorld();
		BlockPos pos = choosePosition(player, world);
		BlockState original = world.getBlockState(pos);
		BlockState signState = Blocks.OAK_SIGN.getDefaultState();
		String nonce = nonce();

		SignText text = new SignText();
		for (int i = 0; i < keys.size(); i++) {
			text = text.withMessage(i, keys.get(i).toText());
		}
		text = text.withMessage(LINES - 1, Text.literal(nonce));

		p.currentKeys = keys;
		p.currentNonce = nonce;
		p.deadlineTick = world.getServer().getTicks() + config.get().probe.timeout_ticks;

		ServerPlayNetworkHandler nh = player.networkHandler;
		try {
			BlockEntityUpdateS2CPacket textPacket = signPacket(world, pos, signState, text);
			nh.sendPacket(new BlockUpdateS2CPacket(pos, signState));
			nh.sendPacket(textPacket);
			nh.sendPacket(new SignEditorOpenS2CPacket(pos, true));
			// Closing in the same tick removes the editor before a frame is drawn and makes the client send the lines.
			nh.sendPacket(new CloseScreenS2CPacket(0));
			nh.sendPacket(new BlockUpdateS2CPacket(pos, original));
			BlockEntity realEntity = world.getBlockEntity(pos);
			if (realEntity != null) {
				Packet<?> update = realEntity.toUpdatePacket();
				if (update != null) {
					nh.sendPacket(update);
				}
			}
		} catch (RuntimeException e) {
			ClientInspector.LOGGER.error("Probe send failed for {}", p.session.name(), e);
			finish(player.getUuid(), p, true);
		}
	}

	/**
	 * Builds the sign's update packet from NBT we encode ourselves. The block entity only lends its
	 * type, position and registry access; its text is never set, so no listener or chunk is touched.
	 */
	private static BlockEntityUpdateS2CPacket signPacket(ServerWorld world, BlockPos pos, BlockState signState, SignText front) {
		SignBlockEntity carrier = new SignBlockEntity(pos, signState);
		carrier.setWorld(world);
		RegistryOps<NbtElement> ops = world.getRegistryManager().getOps(NbtOps.INSTANCE);
		NbtCompound nbt = new NbtCompound();
		nbt.put("front_text", encode(ops, front));
		nbt.put("back_text", encode(ops, new SignText()));
		nbt.putBoolean("is_waxed", false);
		return BlockEntityUpdateS2CPacket.create(carrier, (be, registries) -> nbt);
	}

	private static NbtElement encode(RegistryOps<NbtElement> ops, SignText text) {
		DataResult<NbtElement> result = SignText.CODEC.encodeStart(ops, text);
		return result.getOrThrow();
	}

	private String nonce() {
		StringBuilder sb = new StringBuilder(NONCE_LENGTH);
		for (int i = 0; i < NONCE_LENGTH; i++) {
			sb.append(NONCE_ALPHABET.charAt(random.nextInt(NONCE_ALPHABET.length())));
		}
		return sb.toString();
	}

	/** A block in the player's own chunk column, below their feet, preferably without a block entity. */
	private BlockPos choosePosition(ServerPlayerEntity player, ServerWorld world) {
		BlockPos feet = player.getBlockPos();
		int offset = config.get().probe.y_offset;
		int minY = world.getBottomY() + 1;
		int maxY = world.getTopYInclusive() - 1;
		BlockPos fallback = null;
		for (int attempt = 0; attempt < 12; attempt++) {
			int delta = attempt % 2 == 0 ? -(attempt / 2) : (attempt + 1) / 2;
			int y = Math.max(minY, Math.min(maxY, feet.getY() + offset + delta));
			BlockPos candidate = new BlockPos(feet.getX(), y, feet.getZ());
			if (candidate.getY() == feet.getY() || candidate.getY() == feet.getY() + 1) {
				continue;
			}
			if (fallback == null) {
				fallback = candidate;
			}
			if (world.getBlockEntity(candidate) == null) {
				return candidate;
			}
		}
		return fallback != null ? fallback : new BlockPos(feet.getX(), Math.max(minY, Math.min(maxY, feet.getY() + offset)), feet.getZ());
	}

	@Override
	public void tick(MinecraftServer server) {
		this.server = server;
		if (pending.isEmpty()) {
			return;
		}
		int now = server.getTicks();
		for (Map.Entry<UUID, Pending> e : pending.entrySet()) {
			Pending p = e.getValue();
			if (!p.finished && p.currentKeys != null && now > p.deadlineTick) {
				finish(e.getKey(), p, true);
			}
		}
	}

	@Override
	public void forget(ClientSession session) {
		pending.entrySet().removeIf(e -> e.getValue().session == session);
	}

	private void finish(UUID playerUuid, Pending p, boolean timedOut) {
		if (p.finished) {
			return;
		}
		p.finished = true;
		pending.remove(playerUuid, p);
		Map<String, String> replies;
		synchronized (p.replies) {
			replies = new LinkedHashMap<>(p.replies);
		}
		ProbeState state;
		if (timedOut) {
			state = ProbeState.NO_REPLY;
		} else if (!replies.isEmpty() && replies.values().stream().allMatch(String::isBlank)) {
			state = ProbeState.BLANKED;
		} else {
			state = ProbeState.REPLIED;
		}
		detector.get().onProbeFinished(p.session, state, replies);
	}

	// ------------------------------------------------------------------ network thread

	@Override
	public boolean onSignUpdate(ServerPlayNetworkHandler handler, UpdateSignC2SPacket packet) {
		ServerPlayerEntity player = handler.player;
		if (player == null) {
			return false;
		}
		Pending p = pending.get(player.getUuid());
		if (p == null || p.finished) {
			return false;
		}
		List<ProbeKey> keys = p.currentKeys;
		String nonce = p.currentNonce;
		String[] lines = packet.getText();
		if (keys == null || nonce == null || lines == null || lines.length < LINES) {
			return false;
		}
		String last = lines[LINES - 1] == null ? "" : lines[LINES - 1].strip();
		if (!nonce.equals(last)) {
			// Not our sign (or a stale reply from an earlier batch): leave it to vanilla.
			return false;
		}
		synchronized (p.replies) {
			for (int i = 0; i < keys.size() && i < KEYS_PER_SIGN; i++) {
				p.replies.put(keys.get(i).label(), lines[i] == null ? "" : lines[i]);
			}
		}
		p.currentKeys = null;
		p.currentNonce = null;
		MinecraftServer srv = server;
		if (srv != null) {
			UUID uuid = player.getUuid();
			srv.execute(() -> {
				ServerPlayerEntity online = srv.getPlayerManager().getPlayer(uuid);
				if (online == null || p.finished) {
					finish(uuid, p, false);
					return;
				}
				sendNextBatch(online, p);
			});
		}
		return true;
	}
}
