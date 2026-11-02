# Changelog

## 0.2.0 - 2026-11-02

The client part: the same jar, installed on a client, reports that client's mod list while it joins.

- Configuration-phase handshake: the server sends `clientinspector:hello` to clients that registered
  that channel; the client answers with `clientinspector:report` (mod id, version, SHA-256 of the
  jar, jar-in-jar flag; at most 512 mods and 30 KB, hashes dropped first when it is bigger). Clients
  without the jar are not asked and not held up. The client hashes its jars once at startup in the
  background, logs one line, and does nothing on servers without Client Inspector.
- `companion.mode`: `off`, `optional` (default) or `required`. Required mode refuses clients without
  the jar, or whose report does not arrive within `companion.timeout_ticks`, before they reach the
  world, with `companion.kick_message` and `companion.download_url`. Operators and bypass holders
  are exempt while `companion.exempt_bypass` is on; Geyser players always are.
- Cross-check: a report that leaves out a mod the probe just proved (new optional rule field
  `mod_id`), or leaves out Fabric API while the client registers Fabric channels, marks the client
  `MISMATCH`.
- Rule conditions `reported_mods_any` (ids or globs) and `companion_state`; placeholder `%reported%`.
- Default rules `reported-hack` (kick), `reported-hider` (OpSec, ExploitPreventer; alert),
  `reported-disallowed` (Freecam, Advanced XRay, SeedcrackerX; alert) and `companion-mismatch`
  (kick). An existing `rules.json` gets them, and the `mod_id` of the default probe rules, once on
  the first start (`rules_version` in `config.json`); edited and added rules are kept.
- `/clientinspector companion [off|optional|required]`. `/clientinspector online` marks clients
  that reported; `/clientinspector profile` lists the reported mods and any mismatch. The join log
  line gains `companion=... reported=N`, profiles store the reported list, and Discord embeds show
  the client part state.
- The server half now runs only on dedicated servers: singleplayer and LAN worlds are never
  inspected, `/clientinspector` is not registered there, and a client writes no config files.

Mod ids for the new rules were read from each mod's own `fabric.mod.json` (the jars of Meteor
Client 1.21.11-76, Meteor Rejects 0.3, Freecam 1.3.6, Advanced XRay 21.11.0, OpSec 1.1.7.1 and
Fabric API 0.141.3; GitHub for ThunderHack-Recode, SeedcrackerX, Aoba and ExploitPreventer; the
sources read for 0.1.0 for Meteor+, Wurst, LiquidBounce and BleachHack). Meteorist's id follows
from its Mod Menu key; Shoreline's is unconfirmed, so it only appears in `reported-hack`, where a
wrong id misses a detection instead of causing a false mismatch.

## 0.1.1 - 2026-11-02

- `forge`, `neoforge`, `collective` and `maceattackassistance` join the default known namespaces, so a
  Forge client or a Collective-based mod no longer shows up as unknown in alerts and profiles.
- Rules whose only action is LOG (the LEGIT entries) write an INFO line ending in "logged" instead of a
  WARN line ending in "alert only".
- A suppressed kick or ban reads "not enforced: bypass (op or permission)" or "not enforced: monitor_only".

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
- Profiles store, `/clientinspector` command, LuckPerms-compatible permissions, Discord webhook.

Fingerprint sources (read 2026-11-01/02): Meteor Client branch 1.21.11 at 48030717dc0d,
MeteorPlus branch 1.21.11 at cab06373, Meteorist tag v1.21.11-2, Wurst 7 branch 1.21.11 at
d27a338c8d30, LiquidBounce nextgen at 670fff667aa4, ThunderHack-Recode at 49c76bcc5a5e and
ThunderHack-Reborn at 4389c8b7c288, Freecam main (1.5.x) and beta/1.21.11, SeedcrackerX tag 2.15.6,
AdvancedXRay/XRay-Mod main, Fabric API branch 1.21.11 at 28e4019ae9dc.
