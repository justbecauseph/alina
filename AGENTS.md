# AGENTS.md

Alina is a Fabric 26.2 mod on [haruhi](https://github.com/justbecauseph/haruhi) that runs the Maraha Guild (daily
commission board, active cap, cooldowns, proofs, crash-safe turn-ins, rewards and reputation) and a configurable
achievement system shown in the vanilla advancement screen. Mod id `alina`, package `town.lampas.alina`, licence
MPL-2.0.

The design is [ALINA-PLAN](https://github.com/justbecauseph/lampas-two-kb/blob/main/docs/plan/ALINA-PLAN.md) in the
Lampas knowledgebase; the decisions behind it are in its 5 Nov 2026 decision record. Section numbers below (§6.1 and
so on) refer to that plan.

## Build and verify

```
./gradlew build          # compile, unit tests (JUnit 5, Testcontainers), and runGameTest (server GameTests)
./gradlew test           # unit tests only
./gradlew runGameTest    # server GameTests only
```

- Alina depends on `town.lampas:haruhi` from the JustBecause Maven repository
  (`https://justbecauseph.github.io/maven`, the GitHub Pages site of `justbecauseph/maven`), where haruhi's release
  workflow publishes it. When a haruhi checkout sits beside this one, `settings.gradle` includes it, so Gradle builds
  against the checkout instead; pass `-Palina.publishedHaruhi=true` to use the published jar anyway. Until
  `haruhi_version` is released there, the build needs the checkout. Changes Alina needs in haruhi are made in that
  repository, released there, and then `haruhi_version` in `gradle.properties` is raised.
- `AlinaSchemaTest` reads haruhi's bundle schema from the haruhi jar (`haruhi/bundle.schema.json`).
- Testcontainers tests need Docker. Annotate them `@Testcontainers(disabledWithoutDocker = true)` so `build` still
  passes without Docker, and say in your report when they were skipped.
- GameTests that activate content wait until `HaruhiServices.content().isStartupComplete()` first.
- Java 25 and Minecraft 26.2 names (`Identifier`, not `ResourceLocation`). When unsure of a 26.2 API, check the
  decompiled jar with `javap -cp ~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar <class>`, not memory.

## Packages

| Package under `town.lampas.alina` | Delivers |
|---|---|
| `storage` | `AlinaStorage` port, in-memory and PostgreSQL (HikariCP, migrations under `alina/migrations/`), config `config/alina.json` |
| `api` | `AlinaServices`: the storage getter |
| `content` | the `alina:guild` and `alina:achievements` bundle extensions: records, readers, validation, `AlinaContent` |
| `criteria` | `CriterionWatchers`: watches one player's criterion trigger events, indexed by trigger and then player; `CriteriaModule` closes a player's watches on leave |
| `settlement` | `SettlementService` (escrow attachment, verified player-data saves, recovery, world id, reconciliation), `SettlementModule` (the attachment, the service's lifecycle and tick), `SettlementTriggers` (the reputation and commission triggers, attached to the service's listeners by `SettlementModule`) |
| `guild` | `GuildService` (each online character's record, the daily board, accept, abandon, turn-in, criterion and descent proof tracking), `GuildModule` (its lifecycle and events), `GuildText` (player-facing text); `guild.board` (board days and the draw), `guild.proof` (the item proof planner), `guild.dialogue` (haruhi conditions, actions and providers), `guild.block` (the Notice Board), `guild.command` (`/alina guild`) |
| `mixin` | `SimpleCriterionTriggerMixin`: hands every simple criterion trigger event to the watchers, before vanilla's early return for players with no unfinished advancement on it; `PlayerStorageAccess`: the server's `playerDataStorage`, for settlement's saves |
| `client` | `AlinaClient` entrypoint |

Initialisation, in `Alina.onInitialize`: storage, criteria, content, settlement, guild, achievement (§5). Criterion
trigger types register before content, because content parses criteria through `BuiltInRegistries.TRIGGER_TYPES`.
haruhi's services are read from server events onward, never during init.

## Rules

- **Client code stays in the client package.** Nothing outside `town.lampas.alina.client` references
  `net.minecraft.client`; `ArchitectureTest` fails the build otherwise.
- **No blocking I/O on the server thread.** Storage futures complete off-thread; hop back with `server.execute(...)`.
  The exception is settlement's verified player-data save (`PlayerDataSaves.saveAndVerify`), which runs on the server
  thread and reads the saved `.dat` file back. ALINA-PLAN §7 needs the inventory and the escrow saved together, in one
  step, so the save cannot be left to a background thread.
- **Player-facing text** is translatable with an English fallback (`GuildText`), with its keys in
  `assets/alina/lang/en_us.json`. Staff command feedback is `Component.literal`.
- **The server checks again whatever the client sends.** The client sends only haruhi's session id, node id and choice
  index.
- **Content follows haruhi's bundle rules:** snake_case keys, unknown keys are errors at every level, ids are
  `namespace:path`, text fields are text components, and every problem is reported in one pass through the extension
  context.
- **`docs/alina.schema.json` changes with the content format.** `AlinaSchemaTest` fails when it names different keys
  from the readers, refuses the documented examples, or accepts a shape the readers refuse.

## Alina's triggers

`criteria` registers six criterion triggers in `BuiltInRegistries.TRIGGER_TYPES` (`AlinaTriggers.init()`, called right
after storage and before content). Each is a `SimpleCriterionTrigger` with a public fire method, and every condition is
optional, so `{}` matches every event. `alina:commission_accepted` and `alina:commission_completed` take `commission`
(an id), `category` (A to F) and `kind` (`repeatable` or `feat`), fired as `trigger(player, commission, category, kind)`.
`alina:reputation_changed` takes `reputation` (int bounds), fired as `trigger(player, reputation)`. `alina:volume_entered`
takes `volume` (an id), fired as `trigger(player, volume)`. `alina:achievement_earned` takes `achievement` (an id) and
`tier` (int bounds), fired as `trigger(player, achievement, tier)`. `alina:manual` takes `key` (a string), fired as
`trigger(player, key)`. A misspelt condition key fails content validation.

## Content

Two sections under the haruhi bundle's `extensions` object. The format, with examples, is in the plan's §6;
`GuildReaderTest.EXAMPLE` and `AchievementsReaderTest.EXAMPLE` hold the examples the tests check.

- `alina:guild`: `settings` (required; `board_graph` required and must be a graph in the bundle), `commissions`,
  `volumes`. Proof types `item` (`all_of` or `any_of` entries of an item predicate without `count`, Alina item checks
  `alina:bees`, `alina:stew_effect`, `alina:instrument_tag`, `count`, `consume`), `criterion` (`trigger`, vanilla
  `conditions`, `count`) and `descent` (`start` and `finish` volumes).
- `alina:achievements`: `tabs` and `achievements`. Tab `background` is a texture id without `textures/` and `.png`, as
  in vanilla (`minecraft:gui/advancements/backgrounds/stone`).
- Durations (`cooldown`, `default_cooldown`, an achievement's `repeat.cooldown`) are ISO-8601, positive and at most
  3650 days, so adding one to the current time cannot overflow.
- A counted criterion (count above 1, or tiers above 1) needs an event trigger: the vanilla ones in
  `CriterionReader.COUNTED_VANILLA_TRIGGERS`, or any `alina` trigger.
- Vanilla JSON inside the sections (criterion `conditions`, item predicates, icons, bounds) parses with vanilla's own
  codec through `StrictCodec`, which refuses any key the codec would ignore, at any depth, so a misspelt `entity`
  cannot drop a gate. A key that only repeats its default (an icon `"count": 1`) is refused the same way.
- Loot tables, tags and dimensions are checked against the running server through `ServerLookups`, which also runs
  vanilla's own validation of criterion conditions (missing predicate references, wrong loot context). Unit tests have
  no server and skip those checks; `ContentGameTests` covers them.
- References to NPCs and graphs are checked against `ExtensionContext.npcIds()` and `graphIds()`, which include
  entries that failed to parse.
- Ids inside Alina trigger conditions are checked within their own extension (`TriggerReferences`): a commission
  proof's `volume` and `commission`, and an achievement criterion's `achievement`. A commission or volume named by an
  achievement criterion is not checked, because it lives in `alina:guild`, which the achievements parser cannot see;
  that needs haruhi to let an extension read the extensions parsed before it.

## Settlement

Turn-ins and achievement rewards go through `SettlementService` (ALINA-PLAN §7). `begin` runs on the server thread;
`tick` runs every server tick.

- **Escrow.** The attachment `alina:settlement` (persistent, copied on death) holds a JSON array of the player's pending
  operations. An operation keeps its id, kind, subject, tier, revision, world id, reputation delta, effect, the rolled
  reward, the part of it still to deliver, the removed proof stacks (audit), the commission's category and kind when
  the turn-in's request named them, and, once storage has applied it, the `ApplyResult`.
- **Stack encoding.** Each stack is written with `ItemStack.CODEC` through the server's registries, as NBT, and the NBT
  is stored as its SNBT text, so each number keeps its type (a `long` stays a `long`). The rolled stacks are stored
  once. The part still to deliver is one count per rolled stack. Stacks are cut to their max stack size, and to at most
  99, before they are written, because the codec refuses counts above 99. An escrow written with the earlier stack
  encoding (JSON through `JsonOps`) does not read back.
- **Size limit.** `begin` refuses an operation whose escrow would be over 60000 modified-UTF-8 bytes. Vanilla writes the
  escrow as one string, and a string over 65535 bytes fails every save of that player, vanilla's included. A refusal
  changes nothing and logs a WARN with the player and the size. A storage result adds about 50 bytes to its operation
  later, so the 5535-byte margin covers about 100 operations pending at once.
- **Refused and failed begins.** `begin` changes nothing when it refuses: the player already has an operation of the
  same kind, subject and tier; the escrow would be over the limit; or the removal or the encoding throws (logged). The
  proof goes back into the inventory, nothing is saved, and the caller keeps its own state and tries again later. A
  loot table that is not on the server throws before anything changes.
- **Dead players and respawn.** A player who is dead, or whose entity was removed, is not served. A storage completion
  is applied only to the entity that made the call, and only while that entity is alive and is still the player's online
  entity. The comparison is `==`, never `equals`: `Entity.equals` compares ids, and a respawn gives the new entity the
  old id. Otherwise the completion changes nothing, and the online entity is served from its own escrow: storage is
  called again and answers `fresh` false. Fabric copies the escrow to the new entity on respawn.
- **Verified saves.** Every change is saved with `PlayerDataSaves.saveAndVerify`, which saves the player and reads
  `playerdata/<uuid>.dat` back. Vanilla catches save failures, so the read-back is the proof. A failed save keeps the
  operation in the escrow and is retried. The proof is never refunded. A step whose result is already in the escrow and
  whose stacks did not move saves nothing, so a partial delivery that waits for room costs no save.
- **World id.** `<world>/alina/world_id.txt` holds a UUID, made on the first start: written, forced to disk, and moved
  into place. An operation whose world id is not the running world's is never applied; it is listed by
  `reconciliation()` and logged once. The id catches player data moved into another world, and a lost or recreated id
  file. It does not catch a copied or restored world: that keeps its id file, so a copy that shares the database with
  the original has the original's id. Only the one-writer rule (ALINA-PLAN §7, not enforced yet) prevents that.
- **Steps.** `begin` rolls the reward (seed: the operation id's most and least significant bits XOR), takes the proof
  with the request's removal, encodes the escrow, checks its size, and saves. `tick` calls
  `AlinaStorage.applyOperation` for the first due operation, off the server thread, after its escrow is on disk. The
  completion puts the stacks into the main inventory (matching stacks first, then empty slots; never the offhand or the
  armour), then saves the escrow with the storage result recorded. A failed save restores the main inventory slots and
  the previous escrow, so the next attempt calls storage again and gets `fresh` false. When the reward is fully delivered
  the operation leaves the escrow, `markOperation` marks it settled (write-behind), and the settled listeners run. One
  storage call is in flight per player; a failed step waits 100 ticks.
- **Triggers.** `SettlementTriggers.register(service)` is the one entry point. `alina:reputation_changed` fires when a
  verified save first records the storage result, for an operation whose reputation delta is not zero, with the
  reputation after the change. The reward may still be pending then. `alina:commission_completed` fires when the reward
  has reached the player, for a turn-in whose request named its category and kind, and it takes them from the request,
  not from the content active at settle time. A turn-in without them fires nothing, and its reward is still delivered.
  `alina:achievement_earned` is fired by the achievement service, not by settlement.
- **Injection.** The constructor takes the `AlinaStorage`, the online players (the entity served for a player is the one
  in this collection), and a `SaveProof` for the save step (the default is `PlayerDataSaves.saveAndVerify`). GameTests
  build their own services over `InMemoryStorage`, wrap the storage or the save to fail on purpose, and call `tick()`
  themselves. Their players are not in the server's player list, so the server's own service never serves them. The one
  exception is the test of `SettlementModule.service()`, whose player is in the list, because that test is about the
  server's own service.

## Guild

The Guild service (ALINA-PLAN §8) is `GuildModule.service()` on a running server; its class javadoc is the contract.

- **Records.** Each online character's record loads on join and is dropped on leave. Until it loads, the providers
  show one disabled "still loading" row, the conditions are false and every operation is refused with `LOADING`.
  Storage calls for one character run one after another, loads included (`PlayerWrites`), because the storage
  executor is a thread pool and does not order them. Every storage completion runs on the server thread, and is dropped
  when the server has stopped running tasks (from `SERVER_STOPPING` on, `server.execute` would run it inline on the
  storage thread).
- **Accept** is write-through and optimistic: the commission is active in the record at once and is `BUSY` until the
  write finishes; a failed write takes it out again and tells the player. A repeatable must be on today's board; a feat
  need not be. **Abandon** is optimistic the same way, with no cooldown and no reward, and an abandon still being
  written counts towards the active cap.
- **Turn-in** checks the proof, then calls `SettlementService.begin` with the item plan as the removal and the
  commission's category and kind. The commission is `SETTLING` while its operation is in the escrow, including
  operations from an earlier session found at load. The settlement listeners update the record when storage records the
  result and when the reward is delivered.
- **Board.** Once haruhi's startup activation is complete, the service holds the board of the current board day: the
  stored one, or one drawn from the repeatables in rotation and stored. A board with an empty pool is not stored and is
  drawn again after each activation. `board()` leaves out ids the active content no longer has as repeatables.
- **Content activation** withdraws, for loaded records, each active commission the new content lacks (abandoned in
  storage, with a chat message), and restarts proof tracking from the new content. Withdrawal, at activation or at
  load, happens only when startup is complete and the active bundle has an `alina:guild` section, so a refused or late
  startup load never deletes players' commissions.
- **Proofs.** Item proofs are planned by `ItemProofPlanner`: main inventory, hotbar and off-hand only; `all_of` is a
  matching, so one item never counts for two entries. Criterion proofs count through `CriterionWatchers` only while
  the commission is active. Progress is written behind every 100 ticks, on leave and on stop. The descent tracker
  arms a dive when the player enters the start volume, disarms it on gliding, Slow Falling, a totem, death or a
  dimension change, and meets the proof when the player reaches the finish volume alive. It also fires
  `alina:volume_entered` when a player enters a content volume; the volumes a player stands in at join are the
  baseline and do not fire.
- **Notice Board** (`alina:notice_board`): survival cannot break it, and the wither and the ender dragon cannot either.
  Using it opens `settings.board_graph` with haruhi's `openAt`, anchored at the block within `board_reach`.
- **Commands**, for gamemasters: `/alina guild board`, `show <player>`, `accept|turn_in|abandon <player>
  <commission>`, `reset <player> <commission>`, `reputation <player> <value>`; `/alina settlement pending <player>` and
  `/alina settlement reconciliation`.

### Dialogue types

Registered by `GuildDialogue`. Every type refuses unknown argument keys; a `commission` argument must name a commission
of the bundle's `alina:guild` section, and `next` must name a node of the same graph. Provider entries show the
commission's name only, so haruhi can match a pick by position and text.

| Kind | Id | Arguments | Does |
|---|---|---|---|
| provider | `alina:guild/board` | `next` (optional) | Today's board commissions, then the feats the player has not completed, by id. Enabled when the player can accept, grayed with the reason otherwise; picking accepts. |
| provider | `alina:guild/turn_in` | `next` (optional) | Each active or settling commission, by id. Enabled when the proof is met now, grayed with the hint otherwise; picking turns in. |
| provider | `alina:guild/abandon` | `next` (optional) | Each active commission, by id; picking abandons. |
| provider | `alina:guild/status` | none | Disabled rows: reputation, then each commission's standing (progress for a counted criterion, time left on a cooldown). |
| condition | `alina:guild/has_active` | none | The player has an active commission. |
| condition | `alina:guild/can_accept` | none | The player is under the active cap. |
| condition | `alina:guild/reputation` | `op` (`eq`, `ne`, `gt`, `gte`, `lt`, `lte`), `value` | Compares the player's reputation. |
| condition | `alina:guild/commission` | `commission`, `state` (`available`, `active`, `cooldown`, `completed`) | The commission's standing; a commission being settled matches none. |
| action | `alina:guild/accept`, `alina:guild/turn_in`, `alina:guild/abandon` | `commission` | The operation; a refusal is sent to the player. |
| action | `alina:achievement/trigger` | `key` | Fires `alina:manual` with the key. |

A node whose only entries come from `alina:guild/status` has no enabled choice, so give it a static choice to leave.

### Guild tests

`GuildGameTests` builds its own `GuildService` per test (`GuildRig`: in-memory storage, its own settlement service, a
test clock, content parsed from a fixture, players outside the server list) and never activates server content.
`GuildDialogueGameTests` uses the server's own service and activates its fixture on the server, so its tests wait for
the player's record and a board before acting. `CatalogGameTests` holds spike S4: each catalog item proof, checked on
the item as the game makes it. A multi-step GameTest uses `helper.startSequence()`; `succeedWhen` succeeds on the
first tick its runnable returns.
