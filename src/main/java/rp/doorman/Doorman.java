package rp.doorman;

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
import rp.doorman.audit.ProfileStore;
import rp.doorman.detect.Detector;
import rp.doorman.probe.SignProbe;
import rp.doorman.rules.RuleSet;

/**
 * Doorman: server-side client fingerprinting. Reads the brand and plugin channels every client
 * leaks on join, runs a translation-key probe for known hack clients, and alerts, kicks or bans
 * by configurable rules. Needs nothing on the client.
 */
public class Doorman implements ModInitializer {
	public static final String MOD_ID = "doorman";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static Detector detector;

	/** Set during onInitialize; mixins are only applied after that. */
	public static Detector detector() {
		return detector;
	}

	@Override
	public void onInitialize() {
		DoormanConfig config = DoormanConfig.load();
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

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> DoormanCommand.register(dispatcher, d));

		LOGGER.info("Doorman initialised: {} rules, probe {}, monitor_only={}", rules.rules().size(),
				config.probe.enabled ? "on" : "off", config.monitor_only);
	}
}
