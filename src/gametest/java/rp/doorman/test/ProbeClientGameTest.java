package rp.doorman.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.minecraft.server.network.ServerPlayerEntity;
import rp.doorman.Doorman;
import rp.doorman.probe.ProbeKey;
import rp.doorman.rules.ProbeState;
import rp.doorman.session.ClientSession;

import java.util.Map;
import java.util.Set;

/**
 * End to end: a real 1.21.11 client joins an in-process dedicated server running Doorman. The
 * brand and the Fabric API channels must be captured, the join probe must come back REPLIED with
 * every hack-client key echoed unresolved, and a self-test probe must resolve the vanilla key
 * {@code key.jump} (the client translates it, so the reply differs from the key).
 */
public final class ProbeClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestDedicatedServerContext server = context.worldBuilder().createServer()) {
			try (TestServerConnection connection = server.connect()) {
				connection.getClientWorld().waitForChunksRender();

				// 1. Join probe: delay_ticks + jitter_ticks is at most 100 ticks by default.
				waitUntil(context, server, "join probe", 600, s -> session(s).probe() != ProbeState.NOT_RUN
						&& session(s).probe() != ProbeState.PENDING);
				ClientSession joined = server.computeOnServer(ProbeClientGameTest::session);
				String brand = joined.brand();
				Set<String> channels = joined.channels();
				Map<String, String> replies = joined.probeReplies();
				Doorman.LOGGER.info("[gametest] brand={} channels={} probe={} replies={}", brand, channels, joined.probe(), replies);

				check("fabric".equals(brand), "brand should be 'fabric' but was " + brand);
				check(channels.contains("fabric:registry/sync"), "configuration channels should include fabric:registry/sync: " + channels);
				check(channels.contains("fabric-screen-handler-api-v1:open_screen"), "play channels should include the screen handler channel: " + channels);
				check(joined.probe() == ProbeState.REPLIED, "join probe should be REPLIED but was " + joined.probe());
				check(!replies.isEmpty(), "join probe should have replies");
				for (Map.Entry<String, String> e : replies.entrySet()) {
					if (e.getKey().equals("pack.source.fabricmod")) {
						check("Fabric mod".equals(e.getValue()), "the Fabric API canary must translate on a client with Fabric API, got '" + e.getValue() + "'");
						continue;
					}
					String raw = ProbeKey.parse(e.getKey()).id();
					check(raw.equals(e.getValue()), "plain client must echo the raw id for " + e.getKey() + " but sent '" + e.getValue() + "'");
				}
				check(replies.containsKey("keybind:key.meteor-client.open-gui"), "the keybind probe line should have a reply: " + replies.keySet());
				check(joined.resolvedKeys().equals(java.util.Set.of("pack.source.fabricmod")),
						"only the Fabric API canary may resolve on a plain client: " + joined.resolvedKeys());
				check(joined.matchedRules().isEmpty(), "a plain Fabric client must match no rule: " + joined.matchedRules());

				// 2. Self-test probe: the vanilla key must come back translated.
				server.runOnServer(s -> {
					ServerPlayerEntity player = s.getPlayerManager().getPlayerList().get(0);
					Doorman.detector().startProbe(player, session(s), true);
				});
				waitUntil(context, server, "self-test probe", 400, s -> session(s).probe() == ProbeState.REPLIED
						|| session(s).probe() == ProbeState.NO_REPLY || session(s).probe() == ProbeState.BLANKED);
				ClientSession tested = server.computeOnServer(ProbeClientGameTest::session);
				Doorman.LOGGER.info("[gametest] self-test probe={} replies={} resolved={}", tested.probe(), tested.probeReplies(), tested.resolvedKeys());
				check(tested.probe() == ProbeState.REPLIED, "self-test probe should be REPLIED but was " + tested.probe());
				String jump = tested.probeReplies().get("key.jump");
				check(jump != null && !jump.isBlank() && !jump.equals("key.jump"),
						"key.jump should resolve to its translation but the reply was '" + jump + "'");
				check(tested.resolvedKeys().contains("key.jump"), "key.jump should be in the resolved set");
				check(tested.matchedRules().isEmpty(), "self-test must not match a rule: " + tested.matchedRules());
			}
		}
	}

	private interface ServerCheck {
		boolean test(net.minecraft.server.MinecraftServer server);
	}

	private static void waitUntil(ClientGameTestContext context, TestDedicatedServerContext server, String what, int maxTicks, ServerCheck check) {
		for (int waited = 0; waited < maxTicks; waited += 10) {
			context.waitTicks(10);
			Boolean ok = server.computeOnServer(check::test);
			if (Boolean.TRUE.equals(ok)) {
				return;
			}
		}
		throw new AssertionError("timed out waiting for " + what + " after " + maxTicks + " ticks");
	}

	private static ClientSession session(net.minecraft.server.MinecraftServer server) {
		ServerPlayerEntity player = server.getPlayerManager().getPlayerList().isEmpty() ? null : server.getPlayerManager().getPlayerList().get(0);
		if (player == null) {
			throw new AssertionError("no player on the test server");
		}
		ClientSession session = Doorman.detector().sessions().byUuid(player.getUuid());
		if (session == null || session.isPlaceholder()) {
			throw new AssertionError("no Doorman session for " + player.getName().getString());
		}
		return session;
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
