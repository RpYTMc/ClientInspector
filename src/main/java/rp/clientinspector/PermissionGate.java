package rp.clientinspector;

import com.mojang.authlib.GameProfile;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * The only class that references the permissions API (jar-in-jarred fabric-permissions-api 0.6.1).
 * Without a provider (LuckPerms etc.) the checks fall back to the given defaults.
 */
public final class PermissionGate {
	/** /clientinspector commands. Default: op level 2. */
	public static final String ADMIN = "clientinspector.admin";
	/** Receives in-game alerts. Default: op level 2. */
	public static final String ALERTS = "clientinspector.alerts";
	/** Never kicked or banned by Client Inspector (still logged and alerted). Default: nobody. */
	public static final String BYPASS = "clientinspector.bypass";

	private PermissionGate() {
	}

	public static Predicate<ServerCommandSource> admin() {
		return Permissions.require(ADMIN, PermissionLevel.GAMEMASTERS);
	}

	public static boolean canSeeAlerts(ServerPlayerEntity player) {
		return Permissions.check(player, ALERTS, PermissionLevel.GAMEMASTERS);
	}

	public static boolean hasBypass(ServerPlayerEntity player) {
		return Permissions.check(player, BYPASS, false);
	}

	/** Offline lookup for the configuration phase (LuckPerms answers from its cache or storage). */
	public static CompletableFuture<Boolean> hasBypass(GameProfile profile) {
		return Permissions.check(profile, BYPASS, false);
	}
}
