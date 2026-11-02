package rp.doorman;

import org.junit.jupiter.api.Test;
import rp.doorman.probe.CanaryCheck;
import rp.doorman.rules.RuleMatcher;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CanaryCheckTest {
	private final List<DoormanConfig.Canary> canaries = List.of(
			new DoormanConfig.Canary(List.of("fabric:*", "fabric-*:*"), "pack.source.fabricmod"),
			new DoormanConfig.Canary(List.of("voicechat:*", "vc:*"), "key.push_to_talk"));
	private final RuleMatcher matcher = new RuleMatcher();

	@Test
	void honestFabricClientResolvesItsCanary() {
		assertEquals(List.of(), CanaryCheck.unresolvedCanaries(canaries,
				Set.of("fabric:registry/sync"), Set.of("pack.source.fabricmod"), matcher));
	}

	@Test
	void filteredClientEchoesTheCanaryRaw() {
		assertEquals(List.of("pack.source.fabricmod"), CanaryCheck.unresolvedCanaries(canaries,
				Set.of("fabric:registry/sync", "fabric-screen-handler-api-v1:open_screen"), Set.of("key.jump"), matcher));
	}

	@Test
	void canaryOnlyAppliesWhenItsModIsPresent() {
		assertEquals(List.of(), CanaryCheck.unresolvedCanaries(canaries, Set.of(), Set.of(), matcher));
		assertEquals(List.of("key.push_to_talk"), CanaryCheck.unresolvedCanaries(canaries,
				Set.of("fabric:registry/sync", "voicechat:secret"), Set.of("pack.source.fabricmod"), matcher));
	}
}
