package rp.clientinspector.client;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import org.jspecify.annotations.Nullable;
import rp.clientinspector.ClientInspector;
import rp.clientinspector.companion.ReportedMod;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The client's mod list as Fabric Loader sees it, with the SHA-256 of every jar a mod came from.
 * Built once at startup on a background thread, so answering a server's hello costs nothing.
 *
 * @param loaderVersion Fabric Loader version
 * @param gameVersion   Minecraft version
 * @param mods          every loaded mod, sorted by id
 */
public record ModInventory(String loaderVersion, String gameVersion, List<ReportedMod> mods) {
	/** Jar-in-jar chains deeper than this are not followed to the outer jar. */
	private static final int MAX_NESTING = 8;

	public static CompletableFuture<ModInventory> scanAsync() {
		CompletableFuture<ModInventory> future = new CompletableFuture<>();
		Thread thread = new Thread(() -> {
			try {
				future.complete(scan());
			} catch (RuntimeException | Error e) {
				ClientInspector.LOGGER.error("Client Inspector could not list the installed mods", e);
				future.completeExceptionally(e);
			}
		}, "Client Inspector mod scan");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		thread.start();
		return future;
	}

	static ModInventory scan() {
		long start = System.nanoTime();
		FabricLoader loader = FabricLoader.getInstance();
		Map<Path, Optional<String>> hashes = new HashMap<>();
		List<ReportedMod> mods = new ArrayList<>();
		for (ModContainer mod : loader.getAllMods()) {
			ModOrigin origin = mod.getOrigin();
			boolean nested = origin.getKind() == ModOrigin.Kind.NESTED;
			Path jar = jarOf(loader, mod);
			String hash = jar == null ? null : hashes.computeIfAbsent(jar, ModInventory::sha256).orElse(null);
			mods.add(new ReportedMod(mod.getMetadata().getId(), mod.getMetadata().getVersion().getFriendlyString(), hash, nested));
		}
		mods.sort(Comparator.comparing(ReportedMod::id));
		long millis = (System.nanoTime() - start) / 1_000_000L;
		ClientInspector.LOGGER.info("Client Inspector client part: {} mods ready to report to servers that ask ({} jars hashed in {} ms)",
				mods.size(), hashes.size(), millis);
		return new ModInventory(version(loader, "fabricloader"), version(loader, "minecraft"), mods);
	}

	/** The jar file a mod was loaded from; for a jar-in-jar mod the outermost jar. Null for folders and built-ins. */
	private static @Nullable Path jarOf(FabricLoader loader, ModContainer mod) {
		ModContainer current = mod;
		for (int depth = 0; depth <= MAX_NESTING; depth++) {
			ModOrigin origin = current.getOrigin();
			switch (origin.getKind()) {
				case PATH -> {
					List<Path> paths = origin.getPaths();
					return paths.size() == 1 && Files.isRegularFile(paths.get(0)) ? paths.get(0) : null;
				}
				case NESTED -> {
					Optional<ModContainer> parent = loader.getModContainer(origin.getParentModId());
					if (parent.isEmpty()) {
						return null;
					}
					current = parent.get();
				}
				default -> {
					return null;
				}
			}
		}
		return null;
	}

	private static Optional<String> sha256(Path jar) {
		try (InputStream in = Files.newInputStream(jar)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[64 * 1024];
			int read;
			while ((read = in.read(buffer)) > 0) {
				digest.update(buffer, 0, read);
			}
			return Optional.of(HexFormat.of().formatHex(digest.digest()));
		} catch (IOException | NoSuchAlgorithmException e) {
			ClientInspector.LOGGER.debug("Could not hash {}", jar, e);
			return Optional.empty();
		}
	}

	private static String version(FabricLoader loader, String modId) {
		return loader.getModContainer(modId).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
	}
}
