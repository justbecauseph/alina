# Developer guide

This guide is for developers who change Alina. Each service's class javadoc is its contract; this guide is the map.
`AGENTS.md` holds the same rules in the form coding agents read, with more detail on settlement, the Guild and
achievements. The design and its open questions are in
[ALINA-PLAN](https://github.com/justbecauseph/lampas-two-kb/blob/main/docs/plan/ALINA-PLAN.md); section numbers in the
javadoc (§7, §8.2 and so on) refer to it.

- [Architecture](#architecture)
- [Haruhi integration](#haruhi-integration)
- [Settlement](#settlement)
- [Threading rules](#threading-rules)
- [Building and testing](#building-and-testing)
- [Releasing](#releasing)
- [Repository layout](#repository-layout)

## Architecture

### Packages

| Package under `town.lampas.alina` | Delivers |
|---|---|
| `storage` | the `AlinaStorage` port, `InMemoryStorage` and `PostgresStorage` (HikariCP, migrations under `alina/migrations/`), `StorageModule` and `config/alina.json` |
| `api` | `AlinaServices`, which holds the storage |
| `criteria` | Alina's six criterion triggers (`AlinaTriggers`), and `CriterionWatchers`, which watches one player's trigger events by trigger and player |
| `content` | the `alina:guild` and `alina:achievements` extensions: records, readers, validation, `AlinaContent` |
| `settlement` | `SettlementService` (escrow, verified saves, recovery, world id, reconciliation), `SettlementModule`, `SettlementTriggers` |
| `guild` | `GuildService` (records, boards, accept, abandon, turn-in, proof tracking), `GuildModule`, `GuildText`; `guild.board` (board days and the draw), `guild.proof` (the item proof planner), `guild.dialogue` (Haruhi types), `guild.block` (the Notice Board), `guild.command` (`/alina guild`) |
| `achievement` | `AchievementService` (records, counting, tiers, repeats, earns), `AdvancementSync` (the vanilla screen), `AchievementModule`, the `alina:achievement` condition and `/alina achievement` |
| `mixin` | `SimpleCriterionTriggerMixin`, `PlayerStorageAccess`, `PlayerAdvancementsMixin` |
| `client` | `AlinaClient`, an empty client entrypoint |

### Initialisation order

`Alina.onInitialize` initialises, in order: storage, the criterion triggers (`AlinaTriggers.init()`), criteria,
content, settlement, guild, achievement. The triggers register in `BuiltInRegistries.TRIGGER_TYPES` before content,
because content parses criteria through that registry. Haruhi's services are read from server events onward, never
during init.

Each module that has a service exposes it from its module class once the server has started:
`SettlementModule.service()`, `GuildModule.service()`, `AchievementModule.service()`. `AlinaContent.guild()` and
`AlinaContent.achievements()` return the active bundle's parsed sections, or an empty section when the bundle has none.

### Mixins

- `SimpleCriterionTriggerMixin` hands every simple criterion trigger event to `CriterionWatchers` before vanilla's
  early return for a player with no unfinished advancement on that trigger. Commission proofs and achievements count
  through it, so they work for triggers no vanilla advancement watches.
- `PlayerStorageAccess` exposes the server's `playerDataStorage` for settlement's verified saves.
- `PlayerAdvancementsMixin` holds a join's first advancement packet until the player's achievement record has loaded
  (at most 100 ticks), and adds Alina's holders to every reset packet.

### Client and server split

Nothing outside `town.lampas.alina.client` may reference `net.minecraft.client`; `ArchitectureTest` fails the build
otherwise. Alina's achievements are advancement holders built per player and sent in vanilla's advancement packets, so
the client needs no code of its own. The client still needs the mod, for the Notice Board's registry entries and the
language file.

## Haruhi integration

Alina uses Haruhi's extension API:

- `ContentExtensions.register` for `alina:guild` and `alina:achievements` (`AlinaContent.init()`). Haruhi parses
  them after flags, NPCs and graphs, so the readers check graph ids through `ExtensionContext.graphIds()`, which
  includes graphs that failed to parse.
- `DialogueRegistries` for the providers, conditions and actions in `guild.dialogue` and `AchievementConditions`.
  Provider entries carry only the commission's name as text, so Haruhi can match a pick to its entry by position and
  text.
- `HaruhiServices.dialogue().openAt(...)` for the Notice Board, with a `DialogueAnchor` at the block's centre and the
  board's reach.
- Haruhi's content activation listeners, for withdrawing commissions and rebuilding watches and advancement tabs, and
  `HaruhiServices.content().isStartupComplete()` before any withdrawal at a record load.

A change Alina needs in Haruhi is made in the Haruhi repository and released there; then `haruhi_version` in
`gradle.properties` and the `haruhi` range in `fabric.mod.json` are raised.

### Firing Alina's triggers

Each trigger in `AlinaTriggers` is a `SimpleCriterionTrigger` with a public fire method:

| Field | Call |
|---|---|
| `COMMISSION_ACCEPTED` | `trigger(player, commission, category, kind)` |
| `COMMISSION_COMPLETED` | `trigger(player, commission, category, kind)` |
| `REPUTATION_CHANGED` | `trigger(player, reputation)` |
| `VOLUME_ENTERED` | `trigger(player, volume)` |
| `ACHIEVEMENT_EARNED` | `trigger(player, achievement, tier)` |
| `MANUAL` | `trigger(player, key)` |

Alina is not published to a Maven repository, so these are not an API for other mods to compile against. Data packs
can use the trigger ids in their own advancements.

## Settlement

Every turn-in and achievement reward goes through `SettlementService` (ALINA-PLAN §7). The aim is that a crash at any
point neither loses the proof nor pays the reward twice.

1. `begin`, on the server thread, rolls the reward (seeded from the operation id), takes the proof with the request's
   removal, encodes the escrow and refuses it over 60000 bytes, writes it into the player's escrow attachment
   `alina:settlement`, and saves the player through `PlayerDataSaves.saveAndVerify`, which reads
   `playerdata/<uuid>.dat` back. Vanilla catches
   save failures, so the read-back is the proof that the escrow is on disk.
2. `tick` calls `AlinaStorage.applyOperation` for the first due operation, off the server thread. Storage applies
   each operation id once and answers `fresh` false for a repeat.
3. The completion puts the stacks into the main inventory and saves the escrow with the storage result. A failed save
   restores the slots and the previous escrow.
4. When the reward is fully delivered, the operation leaves the escrow, `markOperation` marks it settled, and the
   settled listeners run.

Rules that keep this safe:

- One storage call is in flight per player. A failed step waits 100 ticks.
- A completion is applied only to the entity that made the call, while it is alive and still the player's online
  entity, compared with `==`, because a respawned entity keeps the old id and `Entity.equals` compares ids.
- Stacks are encoded with `ItemStack.CODEC` as SNBT text, so each number keeps its type. They are cut to their max
  stack size, and to at most 99, before they are written, because the codec refuses counts above 99.
- An escrow over 60000 modified-UTF-8 bytes is refused at `begin`, because vanilla fails every save of a player whose
  escrow string is over 65535 bytes.
- An operation whose world id is not the running world's is never applied, only listed by `reconciliation()`.

`SettlementTriggers.register(service)` fires `alina:reputation_changed` when a verified save first records a storage
result with a reputation change, and `alina:commission_completed` when a turn-in's reward has reached the player.

The constructor takes the storage, the online players and a `SaveProof`, so GameTests build their own services over
`InMemoryStorage`, make the storage or the save fail on purpose and call `tick()` themselves.

## Threading rules

- Storage futures complete off the server thread. Hop back with `server.execute(...)` before touching game state.
  Completions that arrive after the server has stopped running tasks are dropped, because from `SERVER_STOPPING` on
  `server.execute` would run them inline on the storage thread.
- Storage calls for one character run one after another, loads included (`PlayerWrites`, `AchievementWrites`),
  because the storage executor is a thread pool and does not order them.
- During ticks, the one blocking call on the server thread is `PlayerDataSaves.saveAndVerify`. ALINA-PLAN §7 needs
  the inventory and the escrow saved together in one step, so it cannot be left to a background thread. Outside ticks,
  the PostgreSQL migrations block `SERVER_STARTING`, the world id file is read or written at `SERVER_STARTED`, and the
  storage executor's close waits up to 10 seconds at `SERVER_STOPPED`.
- The server checks again whatever the client sends. The client sends only Haruhi's session id, node id and choice
  index.

## Building and testing

### Gradle tasks

```
./gradlew build          # compile, unit tests (JUnit 5, Testcontainers), and runGameTest (server GameTests)
./gradlew test           # unit tests only
./gradlew runGameTest    # server GameTests only
./gradlew runClient      # dev client, for the advancement tabs, x/y text and toasts
```

You need JDK 25. `settings.gradle` includes `../haruhi` as a composite build when that folder exists, so the build
uses a Haruhi checkout beside this one in place of `town.lampas:haruhi:<haruhi_version>`. Pass
`-Palina.publishedHaruhi=true` to build against the published jar.

### Unit tests

Under `src/test/java/town/lampas/alina/`. Content readers, the board draw and board days, the item planner, stack
encoding, the world id, storage records and the storage contract run here. Unit tests have no server, so loot table,
tag and dimension checks and vanilla's validation of criterion conditions are skipped; `ContentGameTests` covers them.

- `AlinaSchemaTest` keeps `docs/alina.schema.json` in step with the readers: it fails when the schema names different
  keys, refuses the documented examples (`GuildReaderTest.EXAMPLE`, `AchievementsReaderTest.EXAMPLE`) or accepts a
  shape the readers refuse. It reads Haruhi's schema from the Haruhi jar (`haruhi/bundle.schema.json`). Change the
  schema with any change to the format.
- `LampasBundleSchemaTest` checks `content/lampas.bundle.json` against the schemas.
- `ArchitectureTest` reads the compiled classes and fails on a client reference outside the client package.

### PostgreSQL tests and Docker

`PostgresStorageTest` (which runs the shared storage contract against PostgreSQL) and the nested
`PostgresMigrationsTest.V1DataUpgradedByV2` use Testcontainers and need Docker. They are annotated `@Testcontainers(disabledWithoutDocker = true)`, so `build` passes without Docker and
skips them.

### Server GameTests

Under `src/gametest/java/town/lampas/alina/gametest/`, with fixtures in `src/gametest/resources/alina/fixtures/`.
GameTests that activate content wait until `HaruhiServices.content().isStartupComplete()` first. A multi-step test uses
`helper.startSequence()`; `succeedWhen` succeeds on the first tick its runnable returns.

`GuildGameTests` builds its own `GuildService` per test through `GuildRig` (in-memory storage, its own settlement
service, `GuildTestClock`, content parsed from a fixture, players outside the server list) and never activates server
content. `GuildDialogueGameTests` activates its fixture on the server and uses the server's own service.
`CatalogGameTests` checks each catalog item proof on the item as the game makes it.

### The Lampas pack jars

`build.gradle` puts the mods named in `suggests` on the dev runtime as `localRuntime`, which is never published:

- Farmer's Delight 3.6.30 and Architectury 21.1.12 from Modrinth's Maven repository, so a build needs network access
  to it;
- Gateways to Eternity, Placebo and Apothic Attributes from `../lampas-pipeline/pack/custom`, found by name prefix
  (`GatewaysToEternity-26.2-`, `Placebo-26.2-`, `ApothicAttributes-26.2-`). Set `-Palina.packDir=<folder>` to read
  them elsewhere. When one is missing, all three are left out; if the folder exists, a warning names the missing
  prefix;
- GeckoLib 5.5.5, for Haruhi's client renderer in `runClient`.

The tests that need these mods (the Gateways and Farmer's Delight spikes in `SpikeGameTests`, and
`LampasContentGameTests`) check `FabricLoader.isModLoaded` first. Without the mod a test logs `is skipped` and passes,
so search the log for `is skipped` to see what did not run. The GameTest runner collects every mod's `fabric-gametest`
entrypoints, so while Apothic Attributes is on the dev runtime `runGameTest` also runs its GameTests
(`apothic_attributes:` in the report), and a failure there fails this build.

## Releasing

`.github/workflows/release.yml` runs `./gradlew clean build` and attaches `alina-<mod_version>.jar` and the sources jar
to a GitHub release `v<mod_version>` when the release is published. It refuses a tag that is not `v<mod_version>`, and
it never replaces a jar already attached, so bump `mod_version` for every release. For a release whose run failed,
run the workflow by hand (`workflow_dispatch`) with the `v<mod_version>` tag as `ref`; it skips jars already attached.
Nothing is published to Maven; the Lampas pack takes the jar from the release.

The runner has no Haruhi checkout and no `../lampas-pipeline/pack/custom`, so it builds against the published Haruhi
and the Gateways and Lampas bundle GameTests skip there. Before tagging, run locally with the pack folder:

```sh
./gradlew clean build -Palina.publishedHaruhi=true
```

## Repository layout

| Path | Holds |
|---|---|
| `src/main/java/town/lampas/alina/` | the mod, by package as above |
| `src/main/resources/fabric.mod.json`, `alina.mixins.json` | mod metadata and the mixin config |
| `src/main/resources/alina/migrations/` | `V1__init.sql`, `V2__boards.sql` |
| `src/main/resources/assets/alina/` | the Notice Board's blockstate, item and model, and `lang/en_us.json` |
| `src/main/resources/data/alina/loot_table/rewards/` | the Lampas reward tables, `guild/<code>.json`, and `placeholder.json` |
| `src/main/resources/data/minecraft/tags/block/` | `wither_immune` and `dragon_immune`, which add the Notice Board |
| `src/test/java/town/lampas/alina/` | unit tests |
| `src/gametest/` | server GameTests, their fixtures and test data |
| `content/lampas.bundle.json` | the Lampas bundle |
| `docs/` | these guides and `alina.schema.json` |
| `.github/workflows/release.yml` | the release workflow |
