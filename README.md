# Doorman

Server-side Fabric mod for Minecraft 1.21.11 that fingerprints every joining client and alerts,
kicks or bans by rules you control. **Nothing is required on the client.** Vanilla players can
join; modded players are inspected on the way in.

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

Doorman records all three per connection, evaluates the rules at three points (configuration,
join, settled) and acts. Everything it saw is available with `/doorman profile <player>` even after
the player left.

## What it catches, honestly

- **Meteor Client out of the box**, plus the Meteor+ and Meteorist addons: caught by the probe.
  Meteor's own sign filter was removed in March 2026 (PR #6186), so its keybind keys resolve plainly;
  the nested form Doorman sends also gets through the older filter still present on 1.21.10 builds.
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
  unresolved. Doorman catches that with canaries: keys that must resolve on a client whose channels
  prove the mod is installed (Fabric API's `pack.source.fabricmod`, Simple Voice Chat's
  `key.push_to_talk`; `probe.canaries` in the config). A raw canary records the probe as
  `FILTERED` and fires the `probe-filtered` rule, alert-only by default; promote it to `KICK` if
  hider mods are not allowed on your server. Those mods also tell their user that the server probed
  them. Mods that blank or swallow the sign show up as `probe-blocked` instead.
- A player can always recompile a client to lie. Doorman stops the unsuspecting, not the determined.
  Keep Grim for behaviour.

## Install

1. Fabric Loader 0.18.4+ and Fabric API for 1.21.11 in the server's `mods/` folder.
2. Drop `doorman-<version>.jar` next to them. LuckPerms is optional but recommended for permissions.
3. Start the server once. `config/doorman/config.json` and `config/doorman/rules.json` are created.
4. Give your staff `doorman.alerts` (op level 2 by default) and yourself `doorman.admin`.

Optional: set `monitor_only` to `true` for the first days. Every kick and ban is then reported as
"not enforced (monitor_only)" so you can read the alerts before letting it act.

## Commands (`doorman.admin`)

| Command | What it does |
|---|---|
| `/doorman` | Rule count, probe state, tracked connections |
| `/doorman online` | One line per connected player: brand, channel count, probe result, matches |
| `/doorman profile <name>` | Everything observed for an online player, or the stored profile of an offline one |
| `/doorman probe <player>` | Re-run the probe now |
| `/doorman probe <player> selftest` | Same, plus a vanilla key (`key.jump`) that every client resolves: proves the probe works on your server |
| `/doorman rules` | List the loaded rules with category, confidence and actions |
| `/doorman alerts on\|off` | Mute or unmute in-game alerts for yourself |
| `/doorman reload` | Re-read both config files |

Permissions: `doorman.admin` (commands), `doorman.alerts` (receive alerts), `doorman.bypass`
(never kicked or banned, still logged and alerted). Server operators get the same exemption
automatically. While LuckPerms has not yet answered the bypass lookup for a joining player, rules
that would kick or ban wait until the player is in the world; everything else runs at once.

## Rules (`config/doorman/rules.json`)

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
  "kick_message": "Doorman: %client% is not allowed on this server."
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
| `probe_state` | list of `REPLIED`, `BLANKED`, `NO_REPLY`, `NOT_RUN` |
| `phase` | optional: delay the rule to `JOIN` or `SETTLED` |

Channel patterns: `lunar` (any `lunar:*`), `lunar:apollo` (exact), `fabric-*:*` (glob).

Actions: `LOG`, `ALERT`, `WEBHOOK`, `COMMAND` (runs `commands` from the console), `BAN` (vanilla
ban list, source `ban_source`), `KICK`. Placeholders in messages and commands: `%player%`,
`%uuid%`, `%rule%`, `%client%`, `%brand%`, `%confidence%`, `%category%`.

Categories: `HACK`, `SPOOF`, `DISALLOWED`, `LEGIT`, `INFO`. The defaults kick only on
high-confidence `HACK` and `SPOOF` matches; everything else alerts.

## Config (`config/doorman/config.json`)

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
- `alerts.webhook_url`: Discord webhook for the `WEBHOOK` action.
- `alerts.unknown_namespaces`: also alert when a client registers channels from namespaces not in
  `known_namespaces` and not covered by any rule. Good for discovering what your players run.
- `audit.log_joins`: one log line per join with everything observed.

## Testing it on your server

1. Install the jar, start the server, set `monitor_only` to `true` and run `/doorman reload`.
2. Join with your normal client, then run `/doorman probe <you> selftest`. A few seconds later
   `/doorman profile <you>` must show `probe: REPLIED` and `key.jump -> 'Jump'` (or its translation
   in your language). That proves the sign probe works end to end on your setup.
3. Join with a throwaway instance that has Meteor installed, on an account that is **not** an
   operator (operators are exempt from kicks and bans). The log and every player with
   `doorman.alerts` get a `Meteor Client (hack, high)` alert marked "not enforced (monitor_only)",
   and the profile shows `key.meteor-client.open-gui -> 'Open GUI'`.
4. Set `monitor_only` back to `false`, reload, and join with the Meteor instance again: the join is
   cut with the kick message a few seconds after the world loads.
5. Have everyone else join once. Anything with `(spoof` or `(info` in the alert is worth a look at
   `/doorman profile <name>` before you promote any rule to `KICK`.

The automated equivalent of step 2 is `./gradlew runClientGameTest`: it starts an in-process server,
joins it with a real client, and asserts that every hack key echoes unresolved while `key.jump` resolves.

## Where the fingerprints come from

Every default rule is backed by the client's own source or language file, read at the commits
noted in `CHANGELOG.md`. Channel and translation-key names are observable facts; no code or
configuration from other anticheats is included.

## Building

```
./gradlew build
```

Java 21, Fabric Loom. The jar is in `build/libs/`. Unit tests cover the rule matcher, channel
patterns, brand normalisation and the probe evaluation.

## License

MIT.
