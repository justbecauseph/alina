# Alina

Alina is a Fabric mod for Minecraft 26.2 that runs the Maraha Guild and an achievement system on the Lampas server.
It is built on [Haruhi](https://github.com/justbecauseph/haruhi): the Guild's commissions, boards and achievements are
two extension sections of Haruhi's content bundle, and players reach the Guild through Haruhi conversations with an
NPC or a Notice Board block.

Players accept commissions from a daily board, prove them with items in their inventory, a vanilla criterion event or
a dive between two world volumes, and turn them in for loot-table rewards and reputation. A turn-in is saved in the
player's data before storage records it, so a crash between the two never loses the proof or pays the reward twice.
Achievements are written in the same bundle and appear in the vanilla advancement screen in their own tabs, with
progress counters, tiers, repeats and rewards.

## Features

- Commissions in six categories (A to F), each a repeatable with a cooldown or a feat done once. Each player holds at
  most `active_cap` of them at a time, 3 by default.
- One or more notice boards, each with its own pool, dialogue graph and daily draw. A board's draw changes at a reset
  time in a time zone the content sets (04:00 Asia/Manila by default), is weighted per commission, and is stored, so
  every player and every restart sees the same board that day.
- Three proof types: `item` (vanilla item predicates, plus checks for bees in a nest, suspicious stew effects and goat
  horn instruments), `criterion` (any vanilla or Alina criterion trigger, counted from acceptance) and `descent` (a
  fall from one world volume to another without gliding, Slow Falling or a totem).
- Turn-ins and achievement rewards go through an escrow saved in the player's data and read back from disk before
  storage applies them. Storage keys each operation by its id, so a retried call never applies twice.
- Achievements in the vanilla advancement screen, sent per player: tabs, parents, `x/y` progress, tiers, repeats with
  a cooldown, hidden achievements, chat announcements and toasts.
- Six criterion triggers (`alina:commission_accepted`, `alina:commission_completed`, `alina:reputation_changed`,
  `alina:volume_entered`, `alina:achievement_earned`, `alina:manual`) that achievements and commission proofs can use.
- Haruhi dialogue types: choice providers for the board, turn-ins, abandoning and a status list, conditions on
  reputation and commission standing, and actions for accept, turn-in, abandon and the `alina:manual` trigger.
- The Notice Board block (`alina:notice_board`), which opens a board's conversation and cannot be broken in survival.
- `/alina guild`, `/alina achievement` and `/alina settlement` commands for staff.
- In-memory or PostgreSQL storage, with its own versioned migrations.
- The Lampas bundle in `content/lampas.bundle.json`: the Guild Receptionist, a Daily Board and a Feats Board, 21
  catalog commissions and a small achievement tab.

## Requirements

From `src/main/resources/fabric.mod.json` and `gradle.properties`:

| Dependency | Version | Needed on |
|---|---|---|
| Minecraft | 26.2 (`~26.2`) | server and client |
| Java | 25 or newer | server and client |
| Fabric Loader | 0.19.3 or newer; the build uses 0.20.1 | server and client |
| Fabric API | any version; the build uses 0.161.0+26.2 | server and client |
| Haruhi | 0.2.0 or newer | server and client |

Alina also names Farmer's Delight, Gateways to Eternity, Placebo, Architectury and Apothic Attributes under
`suggests`. None of them is required. The Lampas bundle's D3 commission names Farmer's Delight's dishes and F3 names a
Gateways to Eternity trigger, so that bundle validates only on a server that has both mods, and then every client
needs them too.

Every client needs Alina and Haruhi. The Notice Board's block, item and block entity type are registry entries, and
Fabric API's registry sync keeps a client that lacks them from joining. Haruhi's own `haruhi:npc` entity type needs
Haruhi on the client for the same reason.

## Known limitations

- The default in-memory storage loses every commission, reputation, achievement and operation record when the server
  stops. Only operations still pending in a player's escrow survive, because the escrow is in the player's data.
- One server at a time may use an Alina database. Nothing enforces this yet. The world id in
  `<world>/alina/world_id.txt` catches player data moved into another world, but a copied or restored world keeps its
  id, so two copies on one database are not told apart.
- The format has no comments, and the Lampas bundle is a first pass: its rewards (one emerald per commission), the
  Receptionist's lines and skin, and the achievements are placeholders. E3 and F1 are left out until builders give
  their coordinates.
- An achievement criterion that names a commission or a volume is not checked against the `alina:guild` section, so a
  misspelt id there makes a criterion that never matches. The same holds for an achievement named by a commission
  proof. A commission proof's commission and volume ids, and an achievement criterion's achievement id, are checked.
- The vanilla advancement screen draws Alina's tabs only after the server has sent them. A join's first advancement
  packet waits up to 5 seconds (100 ticks) for the player's achievement record; vanilla advancements earned in that
  time show no toast.

## Build from source

You need JDK 25. The Gradle wrapper downloads everything else, including Haruhi from the JustBecause Maven repository
(`https://justbecauseph.github.io/maven`).

```sh
git clone https://github.com/justbecauseph/alina
cd alina
./gradlew build        # on Windows: .\gradlew.bat build
```

`build` compiles the mod, runs the unit tests and runs the server GameTests. `./gradlew assemble` builds the jar
without running any tests.

When a Haruhi checkout sits beside this one (`../haruhi`), Gradle builds against that checkout in place of the
published jar. Pass `-Palina.publishedHaruhi=true` to use the published jar anyway.

The mod jar is `build/libs/alina-0.1.0.jar`; `build/libs/alina-0.1.0-sources.jar` holds the sources. The PostgreSQL JDBC
driver 42.7.7 and HikariCP 7.1.0 are nested inside the jar.

## Quick start for a server operator

1. Put `alina-0.1.0.jar`, Haruhi and Fabric API in the server's `mods/` folder, with Farmer's Delight, Gateways to
   Eternity and their libraries for the Lampas bundle. Give each player the same jars, plus GeckoLib 5.5.5 for Haruhi's
   NPC models.
2. Start the server. The first start writes `config/alina.json` (in-memory storage) and creates
   `<world>/alina/world_id.txt`. The log shows:

   ```
   Alina storage: in-memory backend. Commissions, achievements and operations are lost when the server stops.
   ```

3. Copy `content/lampas.bundle.json` to `config/haruhi/bundles/lampas.json`, then as an operator:

   ```
   /haruhi content import lampas.json
   /haruhi content activate <id>
   ```

   `import` replies with the new revision id. The bundle needs Farmer's Delight and Gateways to Eternity on the server
   and the clients; without them `import` lists the D3 and F3 problems and stores nothing. With Haruhi's default
   in-memory storage the revision is gone after a restart; see
   [Deploying content](docs/server-guide.md#deploying-content).
4. Spawn the Receptionist, or place a Notice Board and point it at a board:

   ```
   /haruhi npc spawn lampas:guild/receptionist ^ ^ ^3
   /give @s alina:notice_board
   /alina guild noticeboard <pos> lampas:guild/daily
   ```

5. Check today's draw with `/alina guild board`, then right-click the Receptionist or the board to accept a commission.

Switch to PostgreSQL before players depend on their records: see
[Storage](docs/server-guide.md#storage).

## Documentation

| Guide | For | Covers |
|---|---|---|
| [Server guide](docs/server-guide.md) | server operators | installing, `config/alina.json`, PostgreSQL, deploying content, Notice Boards, every `/alina` command, settlement and reconciliation, log messages |
| [Content guide](docs/content-guide.md) | content writers | the `alina:guild` and `alina:achievements` sections, boards, commissions, proofs, dialogue types, triggers, achievements, validation |
| [Alina JSON Schema](docs/alina.schema.json) | content writers | a JSON Schema of a Haruhi bundle with Alina's sections, for editors |
| [Developer guide](docs/developer-guide.md) | mod developers | packages, initialisation, settlement, threading, building and testing, the Lampas pack jars, releasing |

`AGENTS.md` is the brief for coding agents working in this repository. The design is
[ALINA-PLAN](https://github.com/justbecauseph/lampas-two-kb/blob/main/docs/plan/ALINA-PLAN.md) in the Lampas
knowledgebase.

## Licence

Mozilla Public License 2.0. The full text is in [LICENSE](LICENSE).
