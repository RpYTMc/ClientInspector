# Changelog

## 0.1.0 - 2026-11-02

First version.

- Brand capture (configuration and play phase), configuration- and play-phase channel registration
  tracking through Fabric API's channel events.
- Translation-key sign probe (MC-265322) with per-key replies, blank/no-reply detection, self-test.
- Rule engine: brand regex, channel patterns (any/all/none), no-channels, translation keys, probe
  state; phases CONFIGURE / JOIN / SETTLED; actions LOG, ALERT, WEBHOOK, COMMAND, BAN, KICK.
- Default rules: Meteor Client, Meteor+, Meteorist and ThunderHack (probe, kick), Freecam,
  SeedcrackerX, Advanced XRay and X to Xray (probe, alert), LiquidBounce and ThunderHack Lunar-spoof brands,
  CheatBreaker brand, vanilla-brand-with-Fabric-channels, Lunar/Feather brand without their
  channels, Fabric-without-channels, probe-blocked, probe-filtered, and LEGIT entries for Lunar, Feather, LabyMod,
  Badlion, Essential, Replay Mod, Xaero's, Simple Voice Chat.
- Client game test (`./gradlew runClientGameTest`): a real client joins an in-process server; the
  join probe must echo every hack key unresolved and a self-test probe must translate `key.jump`.
- Canary keys: a client that provably has Fabric API or Simple Voice Chat but echoes their keys raw is
  recorded as `FILTERED` (OpSec, ExploitPreventer) and matched by the `probe-filtered` rule.
- Profiles store, `/doorman` command, LuckPerms-compatible permissions, Discord webhook.

Fingerprint sources (read 2026-11-01/02): Meteor Client branch 1.21.11 at 48030717dc0d,
MeteorPlus branch 1.21.11 at cab06373, Meteorist tag v1.21.11-2, Wurst 7 branch 1.21.11 at
d27a338c8d30, LiquidBounce nextgen at 670fff667aa4, ThunderHack-Recode at 49c76bcc5a5e and
ThunderHack-Reborn at 4389c8b7c288, Freecam main (1.5.x) and beta/1.21.11, SeedcrackerX tag 2.15.6,
AdvancedXRay/XRay-Mod main, Fabric API branch 1.21.11 at 28e4019ae9dc.
