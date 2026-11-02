package rp.clientinspector;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.S2CConfigurationChannelEvents;
import net.fabricmc.fabric.api.networking.v1.S2CPlayChannelEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rp.clientinspector.audit.ProfileStore;
import rp.clientinspector.detect.Detector;
import rp.clientinspector.probe.SignProbe;
import rp.clientinspector.rules.RuleSet;

/**
 * Client Inspector: server-side client fingerprinting. Reads the brand and plugin channels every client
 * leaks on join, runs a translation-key probe for known hack clients, and alerts, kicks or bans
 * by configurable rules. Needs nothing on the client.
 */
public class ClientInspector implements ModInitializer {
	public static final String MOD_ID = "clientinspector";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static Detector detector;

	/** Set during onInitialize; mixins are only applied after that. */
	public static Detector detector() {
		return detector;
	}

	@Override
	public void onInitialize() {
		ClientInspectorConfig config = ClientInspectorConfig.load();
		RuleSet rules = RuleSet.load();
		ProfileStore profiles = new ProfileStore();
		SignProbe probe = new SignProbe(() -> detector, () -> detector.config());
		Detector d = new Detector(config, rules, profiles, probe);
		detector = d;

		ServerLifecycleEvents.SERVER_STARTED.register(d::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> d.onServerStopping());
		ServerTickEvents.END_SERVER_TICK.register(d::onTick);

		ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.register(d::onBeforeConfigure);
		ServerConfigurationConnectionEvents.CONFIGURE.register(d::onConfigure);
		ServerConfigurationConnectionEvents.DISCONNECT.register((handler, server) -> d.onConfigDisconnect(handler));
		S2CConfigurationChannelEvents.REGISTER.register((handler, sender, server, channels) -> d.onConfigChannels(handler, channels));

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> d.onJoin(handler, server));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> d.onPlayDisconnect(handler));
		S2CPlayChannelEvents.REGISTER.register((handler, sender, server, channels) -> d.onPlayRegister(handler, channels));
		S2CPlayChannelEvents.UNREGISTER.register((handler, sender, server, channels) -> d.onPlayUnregister(handler, channels));

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> ClientInspectorCommand.register(dispatcher, d));

		LOGGER.info("Client Inspector initialised: {} rules, probe {}, monitor_only={}", rules.rules().size(),
				config.probe.enabled ? "on" : "off", config.monitor_only);
	}
}
