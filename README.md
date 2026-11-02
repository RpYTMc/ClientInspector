# Client Inspector

Server-side Fabric mod for Minecraft 1.21.11 that fingerprints every joining client and alerts,
kicks or bans by rules you control. **Nothing is required on the client.** Vanilla players can
join; modded players are inspected on the way in. Players who also put the same jar in their
client report their full mod list while joining (the optional [client part](#client-part-optional)),
and a server can make that mandatory.

It answers one question Grim and the other behaviour anticheats do not: *what client is this
player actually running?*

## What it looks at

Every client leaks three things to the server without any companion mod:

1. **Brand** - the string the client sends during the configuration phase (`vanilla`, `fabric`,
   `lunarclient:v2.23.12-2644,fabric`, ...). Fabric Loader appends `,fabric` to any non-vanilla brand.
2. **Plugin channels** - Fabric API tells the server every channel identifier the client's mods can
   receive (`minecraft:register`). The namespace is usually the mod id, so Lunar, Feather, LabyMod,
   Xaero's, Simple Voice Chat and friends identify themselves here.
3. **Translation keys and key bindings** - the sign probe (MC-265322). A few seconds after join the
   server shows the client an invisible sign whose lines are translatable or keybind components,
   opens and closes the sign editor in the same tick, and reads the client's reply. A client that
   has the mod's language file sends back the translation, and a client that registered the key
   binding sends back the bound key's name ("Right Shift"); vanilla sends back the raw id. The
   fourth sign line carries a random nonce so a reply is only ever read against its own sign.

Client Inspector records all three per connection, evaluates the rules at three points (configuration,
join, settled) and acts. Everything it saw is available with `/clientinspector profile <player>` even after
the player left.

## What it catches, honestly

- **Meteor Client out of the box**, plus the Meteor+ and Meteorist addons: caught by the probe.
  Meteor's own sign filter was removed in March 2026 (PR #6186), so its keybind keys resolve plainly;
  the nested form Client Inspector sends also gets through the older filter still present on 1.21.10 builds.
  Meteor issue #5864 tracks the leak.
- **ThunderHack** (Recode and Reborn): its module descriptions live in a standard language file.
- **Freecam, SeedcrackerX, Advanced XRay, X to Xray**: probeable, shipped as alert-only `DISALLOWED`
  rules. X to Xray has no language file but registers a key binding, which the keybind vector sees.
- **Spoofed brands**: LiquidBounce's and ThunderHack's Lunar spoof modes send hard-coded version
  strings that no real client on 1.21.11 sends. A `vanilla` brand next to Fabric channels is a lie.
- **Legit clients** are recognised and only logged, so staff see "Lunar" instead of a wall of channels.

What it does **not** catch, so you are not surprised:

- Meteor, Wurst, LiquidBounce and every other Fabric hack client register **no** plugin channels and
  keep the brand `fabric` by default. Brand plus channels alone cannot tell them from an honest
  Fabric player. That is true on Paper as well.
- Wurst (since 7.44, July 2024) and LiquidBounce keep their strings outside the vanilla language
  system, so the probe cannot see them at all. Aoba, Shoreline, schizoid, Baritone, BleachHack and the
  Meteor addons Rejects, Trouser Streak, Numby, glazed and Nora Tweaks ship no language file either.
- OpSec, ExploitPreventer and similar mods rewrite the probe reply so every mod key looks
  unresolved. Client Inspector catches that with canaries: keys that must resolve on a client whose channels
  prove the mod is installed (Fabric API's `pack.source.fabricmod`, Simple Voice Chat's
  `key.push_to_talk`; `probe.canaries` in the config). A raw canary records the probe as
  `FILTERED` and fires the `probe-filtered` rule, alert-only by default; promote it to `KICK` if
  hider mods are not allowed on your server. Those mods also tell their user that the server probed
  them. Mods that blank or swallow the sign show up as `probe-blocked` instead.
- The limit of that trick: OpSec's default AUTO mode whitelists every mod that registers channels,
  which is exactly the evidence the canaries rely on, so its users resolve the canaries and still
  hide Meteor. Canaries catch ExploitPreventer and OpSec's manual whitelist mode, not OpSec AUTO.
  Nothing passive beats a hider configured that way. The client part does, for players who
  install it.
- A player can always recompile a client to lie. Client Inspector stops the unsuspecting, not the determined.
  Keep Grim for behaviour.

## Client part (optional)

The same jar also works on the client. A player who puts it in their client's `mods/` folder, next
to Fabric API, reports their mod list to the server while joining: every mod Fabric Loader loaded,
with its version and the SHA-256 of its jar. The list comes from Fabric Loader itself, so OpSec and
the other translation filters cannot hide anything from it. Meteor behind OpSec AUTO, invisible to
the probe, shows up as `meteor-client` in the report, and the `reported-hack` rule kicks it before
the world loads.

How it works:

- During the configuration phase the server sends `clientinspector:hello` to clients that registered
  that channel, which only clients with the jar do. The client answers with `clientinspector:report`.
  Clients without the jar are not asked and not held up.
- The client hashes its mod jars once at startup in a background thread, so the answer is instant.
  It writes one log line and has no settings or screens. On a server without Client Inspector it
  sends nothing.
- The server cross-checks the report against what it saw itself. A report that leaves out a mod the
  probe just proved, or leaves out Fabric API while the client registers Fabric channels (the jar
  depends on Fabric API), was not written by an honest client part: the client is marked `MISMATCH`
  and the `companion-mismatch` rule kicks.
- Players who install the jar are never inspected in their own singleplayer or LAN worlds. The
  server half only runs on dedicated servers, and the client writes no config files.

Modes (`companion.mode` in the config, or `/clientinspector companion <mode>`):

| Mode | What happens |
|---|---|
| `off` | Nobody is asked for a report |
| `optional` (default) | Clients with the jar report; everyone may join |
| `required` | Clients without the jar, or whose report does not arrive within `companion.timeout_ticks`, are refused before they reach the world. The refusal screen shows `companion.kick_message` with `companion.download_url` |

In `required` mode operators and `clientinspector.bypass` holders may join without the jar while
`companion.exempt_bypass` is on, and Geyser players (`probe.skip_brands`) always may.

What it cannot do:

- In `optional` mode it only covers players who chose to install it, and a cheater simply does
  not. `required` is the only mode that affects players who refuse to install it.
- `required` also refuses vanilla, Lunar, Forge and every other client that cannot load a Fabric
  mod. That suits a private Fabric server whose players you know, not a public one.
- The report is put together by the client, and a modified copy of the client part can send any
  list it likes. The cross-check catches a forged list only when the probe can see the hidden mod;
  someone who patches the client part and also hides from the probe gets through. Treat it as a
  strong deterrent among people you know, not as proof.
- Jar hashes are reported and stored in `profiles.json` so you can compare jars; no rule condition
  uses them yet.

## Install

1. Fabric Loader 0.18.4+ and Fabric API for 1.21.11 in the server's `mods/` folder.
2. Drop `clientinspector-<version>.jar` next to them. LuckPerms is optional but recommended for permissions.
3. Start the server once. `config/clientinspector/config.json` and `config/clientinspector/rules.json` are created.
4. Give your staff `clientinspector.alerts` (op level 2 by default) and yourself `clientinspector.admin`.

Optional: set `monitor_only` to `true` for the first days. Every kick and ban is then reported as
"not enforced: monitor_only" so you can read the alerts before letting it act.

For the client part, players put the same jar in their client's `mods/` folder next to Fabric API.
Nothing else to set up; `optional` mode is on by default.

Upgrading from 0.1.x: replace the jar and restart. On the first start `rules.json` gets the new
default rules and the `mod_id` of the probe rules once; rules you edited or added stay as they are.

## Commands (`clientinspector.admin`)

| Command | What it does |
|---|---|
| `/clientinspector` | Rule count, probe state, tracked connections. `/ci` is a short alias for every subcommand |
| `/clientinspector online` | One line per connected player: brand, channel count, probe result, matches, and `[client part: N mods]` when it reported |
| `/clientinspector profile <name>` | Everything observed for an online player, or the stored profile of an offline one, including the reported mod list |
| `/clientinspector companion [off\|optional\|required]` | Show the client part mode, or set it and save `config.json` (applies from the next join) |
| `/clientinspector probe <player>` | Re-run the probe now |
| `/clientinspector probe <player> selftest` | Same, plus a vanilla key (`key.jump`) that every client resolves: proves the probe works on your server |
| `/clientinspector rules` | List the loaded rules with category, confidence and actions |
| `/clientinspector alerts on\|off` | Mute or unmute in-game alerts for yourself |
| `/clientinspector reload` | Re-read both config files |

Permissions: `clientinspector.admin` (commands), `clientinspector.alerts` (receive alerts), `clientinspector.bypass`
(never kicked or banned, still logged and alerted). Server operators get the same exemption
automatically. While LuckPerms has not yet answered the bypass lookup for a joining player, rules
that would kick or ban wait until the player is in the world; everything else runs at once. Clients
with the client part, and every client in `required` mode, are instead held in the configuration
phase until the answer is in (at most `companion.timeout_ticks`), so they are kicked or refused
before they reach the world.

## Rules (`config/clientinspector/rules.json`)

A JSON array. Every condition present must hold; a rule fires once per connection.

```json
{
  "id": "meteor-client",
  "name": "Meteor Client",
  "category": "HACK",
  "confidence": "high",
  "enabled": true,
  "translation_keys": ["key.meteor-client.open-gui", "key.category.meteor-client.meteor-client"],
  "actions": ["LOG", "ALERT", "WEBHOOK", "KICK"],
  "kick_message": "Client Inspector: %client% is not allowed on this server."
}
```

Conditions:

| Field | Meaning |
|---|---|
| `brand` | Java regex, case-insensitive, matched against the whole raw brand |
| `channels_any` | at least one registered channel matches one of these patterns |
| `channels_all` | every pattern is matched by some registered channel |
| `channels_none` | no registered channel matches any pattern (waits for SETTLED) |
| `no_channels` | the client registered nothing at all (waits for SETTLED) |
| `translation_keys` | at least one key resolved in the probe (waits for SETTLED) |
| `keybind_keys` | at least one key-binding id resolved in the probe; OR-ed with `translation_keys` |
| `probe_state` | list of `REPLIED`, `FILTERED`, `BLANKED`, `NO_REPLY`, `NOT_RUN` |
| `reported_mods_any` | client part: at least one reported mod id matches one of these ids or globs (`meteor-*`); can fire while the client is still configuring |
| `companion_state` | client part: list of `NONE`, `REPORTED`, `TIMEOUT`, `MISMATCH` (waits for JOIN) |
| `phase` | optional: delay the rule to `JOIN` or `SETTLED` |

Channel patterns: `lunar` (any `lunar:*`), `lunar:apollo` (exact), `fabric-*:*` (glob).

`mod_id` (optional, not a condition) names the Fabric mod a probe rule proves, as an id or glob.
When the rule's keys resolve but the client part's report does not list a matching id, the
client is marked `MISMATCH`. Leave it out when you are not sure of the id: a wrong one turns an
honest report into a mismatch.

Actions: `LOG`, `ALERT`, `WEBHOOK`, `COMMAND` (runs `commands` from the console), `BAN` (vanilla
ban list, source `ban_source`), `KICK`. Placeholders in messages and commands: `%player%`,
`%uuid%`, `%rule%`, `%client%`, `%brand%`, `%reported%` (the reported mod ids that matched
`reported_mods_any`), `%confidence%`, `%category%`.

Client part rules shipped by default: `reported-hack` (Meteor Client, Meteor Rejects, Meteor+,
Meteorist, ThunderHack, Wurst, LiquidBounce, BleachHack, Aoba, Shoreline; kick),
`reported-hider` (OpSec, ExploitPreventer; alert), `reported-disallowed` (Freecam, Advanced XRay,
SeedcrackerX; alert) and `companion-mismatch` (kick). The mod ids were read from each mod's own
`fabric.mod.json`.

Categories: `HACK`, `SPOOF`, `DISALLOWED`, `LEGIT`, `INFO`. The defaults kick only on
high-confidence `HACK` and `SPOOF` matches; everything else alerts.

## Config (`config/clientinspector/config.json`)

- `probe.delay_ticks` / `jitter_ticks`: when after join the probe runs (default 4-6 s). If the
  player has a container open or is dead at that moment the probe waits a few seconds more, up to
  five times. The probe does close whatever screen the client has open at that instant (inventory,
  chat), which the server cannot see; that is the one visible side effect of the technique.
- `probe.timeout_ticks`: how long to wait for the reply before recording `NO_REPLY`; the first
  no-reply is retried once (`probe.retry_on_no_reply`) in case the client's chunk was not loaded yet.
- `probe.skip_brands`: brands that are never probed (default: Geyser, whose Bedrock players have no
  Java sign editor).
- `probe.canaries`: channel patterns plus the key that must resolve when they are present; a raw echo
  marks the probe `FILTERED`.
- `probe.recheck_minutes`: re-probe online players periodically (0 = only on join).
- `probe.y_offset`: where the invisible sign is placed relative to the player (default 5 blocks below).
- `companion.mode`: `off`, `optional` (default) or `required`; see [Client part](#client-part-optional).
- `companion.download_url`: where players get the jar (default the Modrinth page), shown as `%url%`.
- `companion.kick_message`: the refusal screen in `required` mode; placeholders `%url%`, `%player%`.
- `companion.timeout_ticks`: how long a client with the jar may take to report (default 100 = 5 s).
- `companion.exempt_bypass`: operators and bypass holders may join without the jar (default `true`).
- `rules_version`: which default rules `rules.json` has been brought up to. Leave it alone.
- `alerts.webhook_url`: Discord webhook for the `WEBHOOK` action.
- `alerts.unknown_namespaces`: also alert when a client registers channels from namespaces not in
  `known_namespaces` and not covered by any rule. Good for discovering what your players run.
- `audit.log_joins`: one log line per join with everything observed.

## Testing it on your server

1. Install the jar, start the server, set `monitor_only` to `true` and run `/clientinspector reload`.
2. Join with your normal client, then run `/clientinspector probe <you> selftest`. A few seconds later
   `/clientinspector profile <you>` must show `probe: REPLIED` and `key.jump -> 'Jump'` (or its translation
   in your language). That proves the sign probe works end to end on your setup.
3. Join with a throwaway instance that has Meteor installed, on an account that is **not** an
   operator (operators are exempt from kicks and bans). The log and every player with
   `clientinspector.alerts` get a `Meteor Client (hack, high)` alert marked "not enforced: monitor_only",
   and the profile shows `key.meteor-client.open-gui -> 'Open GUI'`.
4. Set `monitor_only` back to `false`, reload, and join with the Meteor instance again: the join is
   cut with the kick message a few seconds after the world loads.
5. Have everyone else join once. Anything with `(spoof` or `(info` in the alert is worth a look at
   `/clientinspector profile <name>` before you promote any rule to `KICK`.
6. Client part: put the jar in the Meteor instance's `mods/` folder too and join on the non-op
   account. The kick now comes from `reported-hack` within a second of joining, before the probe,
   and `/clientinspector profile <name>` lists `meteor-client` under the client part.
7. Required mode: run `/clientinspector companion required`, remove the jar from that instance and
   join again. The join is refused with the download message.

The automated equivalent of steps 2 and 6 is `./gradlew runClientGameTest`: it starts an in-process
server from a 0.1.x config (which must be upgraded in place), joins it with a real client that has
the jar, and asserts that every hack key echoes unresolved, `key.jump` resolves, and the client
part reported its mods. It ends with a singleplayer world, where nothing may be inspected.

## Where the fingerprints come from

Every default rule is backed by the client's own source or language file, read at the commits
noted in `CHANGELOG.md`. Channel and translation-key names are observable facts; no code or
configuration from other anticheats is included.

## Building

```
./gradlew build
```

Java 21, Fabric Loom. The jar is in `build/libs/`; one jar serves both the server and the client
part. Unit tests cover the rule matcher, channel patterns, brand normalisation, the probe
evaluation, the client part's wire format and size cap, the cross-check, the required-mode
decisions and the `rules.json` upgrade.

## License

MIT.
