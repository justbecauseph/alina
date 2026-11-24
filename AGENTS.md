# AGENTS.md

Alina is a Fabric 26.2 mod on [Haruhi](https://github.com/justbecauseph/haruhi) that runs the Maraha Guild (daily
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
  (`https://justbecauseph.github.io/maven`, the GitHub Pages site of `justbecauseph/maven`), where Haruhi's release
  workflow publishes it. When a Haruhi checkout sits beside this one, `settings.gradle` includes it, so Gradle builds
  against the checkout instead; pass `-Palina.publishedHaruhi=true` to use the published jar anyway. Until
  `haruhi_version` is released there, the build needs the checkout. Changes Alina needs in Haruhi are made in that
  repository, released there, and then `haruhi_version` in `gradle.properties` is raised.
- `AlinaSchemaTest` reads Haruhi's bundle schema from the Haruhi jar (`haruhi/bundle.schema.json`).
- Testcontainers tests need Docker. Annotate them `@Testcontainers(disabledWithoutDocker = true)` so `build` still
  passes without Docker, and say in your report when they were skipped.
- GameTests that activate content wait until `HaruhiServices.content().isStartupComplete()` first.
- Java 25 and Minecraft 26.2 names (`Identifier`, not `ResourceLocation`). When unsure of a 26.2 API, check the
  decompiled jar with `javap -cp ~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar <class>`, not memory.
- **Releasing:** `.github/workflows/release.yml` runs `./gradlew clean build` and attaches `alina-<mod_version>.jar`
  and the sources jar to a GitHub release `v<mod_version>` when it is published. It publishes nothing to Maven. The
  runner has no `../lampas-pipeline/pack/custom`, so the Gateways and Lampas bundle GameTests skip there: run
  `./gradlew clean build -Palina.publishedHaruhi=true` locally, with the pack folder, before tagging. An attached jar
  is never replaced, so bump `mod_version` for every release.

### Dev runtime with the Lampas optional mods

`build.gradle` puts the optional dependencies named in `fabric.mod.json` `suggests` on the dev classpath, for `runClient`
and the GameTests. They are `localRuntime`, never published.

- Farmer's Delight 3.6.30 (`maven.modrinth:7vxePowz:J6sAZl6u`) and Architectury 21.1.12
  (`maven.modrinth:lhGA9TYQ:ohb3PBGZ`) come from Modrinth's Maven repository, so a build needs network access to it.
- Gateways to Eternity, Placebo and Apothic Attributes are not on Modrinth. They are read from
  `../lampas-pipeline/pack/custom` (git-ignored there) by name prefix (`GatewaysToEternity-26.2-`, `Placebo-26.2-`,
  `ApothicAttributes-26.2-`), so a version the pack bumps is still found. Set `-Palina.packDir=<folder>` to read them
  elsewhere. When one is missing, all three are left out, a warning names the missing prefix, and the Gateways tests
  skip.
- GeckoLib 5.5.5 (`geckolib_version`) is `localRuntime` for Haruhi's client renderer. The server does not load it.
- Architectury needs Fabric API 0.160.0 and Fabric Loader 0.19.5, so `gradle.properties` pins the versions the Lampas
  pack runs: Fabric API 0.161.0+26.2 and Loader 0.20.1.

Tests that need these mods (`SpikeGameTests`):
- S5, the Gateways trigger: `s5GatewayIdsAreTheNineOfTheJar`, `s5FinishGatewayIsARegisteredCriterionTrigger`,
  `s5F3ProofParsesAndNamesAGatewayTheRegistryHolds` and `s5FinishGatewayReachesTheWatcherOfTheGatewayItNames` need
  `gateways`.
- D3, Farmer's Delight's dishes: `d3ProofIsMetByTheServedDishesAndNotByAFeastBlockOrAnotherDish` needs
  `farmersdelight`.
- The Lampas bundle (`LampasContentGameTests`): `lampasBundleParsesWithNoProblems`,
  `lampasBundleHoldsEveryCatalogCommissionExceptE3AndF1`, `everyLampasCommissionBelongsToExactlyOneBoard` and
  `everyLampasAchievementCriterionNamingACommissionNamesOneOfItsCommissions` need both mods, because D3 and F3 name
  their items and trigger.

Each of these checks `FabricLoader.isModLoaded` first. Without its mod it logs `is skipped` and succeeds, so `build`
passes without the pack. A skipped test counts as a pass in the report, so look for `is skipped` in the log. F2,
`f2ListIsTheCreeperDiscsNoChestTableDrops`, needs no mod.

The GameTest runner collects `fabric-gametest` entrypoints from every mod. While Apothic Attributes is on the dev
classpath, `runGameTest` also runs its 45 GameTests (`apothic_attributes:` in the report), so a failure there fails
this build.

## Packages

| Package under `town.lampas.alina` | Delivers |
|---|---|
| `storage` | `AlinaStorage` port, in-memory and PostgreSQL (HikariCP, migrations under `alina/migrations/`, trade budgets and purchases), config `config/alina.json` |
| `api` | `AlinaServices`: the storage getter |
| `content` | the `alina:guild`, `alina:achievements` and `alina:trade` bundle extensions: records, readers, validation, `AlinaContent` |
| `criteria` | `CriterionWatchers`: watches one player's criterion trigger events, indexed by trigger and then player; `CriteriaModule` closes a player's watches on leave |
| `settlement` | `SettlementService` (escrow attachment, verified player-data saves, recovery, world id, reconciliation), `SettlementModule` (the attachment, the service's lifecycle and tick), `SettlementTriggers` (the reputation and commission triggers, attached to the service's listeners by `SettlementModule`) |
| `guild` | `GuildService` (each online character's record, the daily board, accept, abandon, turn-in, criterion and descent proof tracking), `GuildModule` (its lifecycle and events), `GuildText` (player-facing text); `guild.board` (board days and the draw), `guild.proof` (the item proof planner), `guild.dialogue` (Haruhi conditions, actions and providers), `guild.block` (the Notice Board), `guild.command` (`/alina guild`) |
| `achievement` | `AchievementService` (each online character's achievement record, counting, tiers, repeat cycles, earns), `AdvancementSync` (Alina achievements in the vanilla advancement screen), `AchievementModule` (their lifecycle and events), the `alina:achievement` condition and `/alina achievement` |
| `trade` | `TradeService` (each online character's trade record, the shop sessions, and the sell, sell junk, buy and experience requests), `TradeModule` (its lifecycle, receivers and the tick), `TradeMessenger` and `NetworkMessenger` (the payloads), `TradeText` (player-facing text), `TradeRefusal` and `TradeOutcome` (what a request came to); `trade.pricing` (wallet, sell planner, inventory fit, experience points), `trade.network` (payloads and views), `trade.dialogue` (`alina:trade/open` and two conditions), `trade.command` (`/alina trade`) |
| `mixin` | `SimpleCriterionTriggerMixin`: hands every simple criterion trigger event to the watchers, before vanilla's early return for players with no unfinished advancement on it; `PlayerStorageAccess`: the server's `playerDataStorage`, for settlement's saves; `PlayerAdvancementsMixin`: holds a join's first advancement packet for the achievement record and adds Alina's holders to every reset packet |
| `client` | `AlinaClient` entrypoint |

Initialisation, in `Alina.onInitialize`: storage, criteria, content, settlement, guild, achievement, trade (§5). Criterion
trigger types register before content, because content parses criteria through `BuiltInRegistries.TRIGGER_TYPES`.
Haruhi's services are read from server events onward, never during init.

## Rules

- **Client code stays in the client package.** Nothing outside `town.lampas.alina.client` references
  `net.minecraft.client`; `ArchitectureTest` fails the build otherwise.
- **No blocking I/O on the server thread.** Storage futures complete off-thread; hop back with `server.execute(...)`.
  The exception is settlement's verified player-data save (`PlayerDataSaves.saveAndVerify`), which runs on the server
  thread and reads the saved `.dat` file back. ALINA-PLAN §7 needs the inventory and the escrow saved together, in one
  step, so the save cannot be left to a background thread.
- **Player-facing text** is translatable with an English fallback (`GuildText`), with its keys in
  `assets/alina/lang/en_us.json`. Staff command feedback is `Component.literal`.
- **The server checks again whatever the client sends.** The client sends only Haruhi's session id, node id and choice
  index.
- **Content follows Haruhi's bundle rules:** snake_case keys, unknown keys are errors at every level, ids are
  `namespace:path`, text fields are text components, and every problem is reported in one pass through the extension
  context.
- **`docs/alina.schema.json` changes with the content format.** `AlinaSchemaTest` fails when it names different keys
  from the readers, refuses the documented examples, or accepts a shape the readers refuse.

## Alina's triggers

`criteria` registers seven criterion triggers in `BuiltInRegistries.TRIGGER_TYPES` (`AlinaTriggers.init()`, called right
after storage and before content). Each is a `SimpleCriterionTrigger` with a public fire method, and every condition is
optional, so `{}` matches every event. `alina:commission_accepted` and `alina:commission_completed` take `commission`
(an id), `category` (A to F) and `kind` (`repeatable` or `feat`), fired as `trigger(player, commission, category, kind)`.
`alina:reputation_changed` takes `reputation` (int bounds), fired as `trigger(player, reputation)`. `alina:volume_entered`
takes `volume` (an id), fired as `trigger(player, volume)`. `alina:achievement_earned` takes `achievement` (an id) and
`tier` (int bounds), fired as `trigger(player, achievement, tier)`. `alina:manual` takes `key` (a string), fired as
`trigger(player, key)`. `alina:trade_completed` takes `shop` (an id), `aur_earned` and `aur_spent` (int bounds), fired as
`trigger(player, shop, aurEarned, aurSpent)` when a trade settles. A misspelt condition key fails content validation.

## Content

Three sections under the Haruhi bundle's `extensions` object. The format, with examples, is in the plan's §6;
`GuildReaderTest.EXAMPLE`, `AchievementsReaderTest.EXAMPLE` and `TradeReaderTest.EXAMPLE` hold the examples the tests check.

- `alina:guild`: `settings` (required; `board_graph` required and must be a graph in the bundle), `commissions`,
  `volumes`. Proof types `item` (`all_of` or `any_of` entries of an item predicate without `count`, Alina item checks
  `alina:bees`, `alina:stew_effect`, `alina:instrument_tag`, `count`, `consume`), `criterion` (`trigger`, vanilla
  `conditions`, `count`) and `descent` (`start` and `finish` volumes).
- `alina:achievements`: `tabs` and `achievements`. Tab `background` is a texture id without `textures/` and `.png`, as
  in vanilla (`minecraft:gui/advancements/backgrounds/stone`).
- `alina:trade`: `currency` (required, with one denomination of value 1), `price_lists` and `shops`. A shop's `sell`
  takes items through rules (`match` or `namespace`, with `price` or `refuse`), `buy` lists offers and `experience`
  trades points. The reader refuses content that would mint Aur: an experience buy cheaper per point than an experience
  sell, or an offer bought back for more per item than it costs, for the offer's stack and for its plain item once the
  modification is stripped. It also refuses an offer whose item is a currency item (whatever its components) or holds
  other items (a non-empty container or bundle), and an offer id over 64 characters, the network's limit
  (`OfferRow.MAX_ID_LENGTH`). A `#tag` in a rule resolves only on a server, so the unit tests name items instead.
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
  that needs Haruhi to let an extension read the extensions parsed before it.

## Settlement

Turn-ins, achievement rewards and shop trades go through `SettlementService` (ALINA-PLAN §7). `begin` runs on the server
thread; `tick` runs every server tick.

- **Escrow.** The attachment `alina:settlement` (persistent, copied on death) holds a JSON array of the player's pending
  operations. An operation keeps its id, kind, subject, tier, revision, world id, reputation delta, effect, the rolled
  reward and grant, the part of it still to deliver, the experience still to give, the removed proof stacks (audit), the
  commission's category and kind when the turn-in's request named them, and, once storage has applied it, the
  `ApplyResult`.
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
  proof goes back into the inventory, the experience is put back as it was, nothing is saved, and the caller keeps its
  own state and tries again later. A loot table that is not on the server throws before anything changes.
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
  with the request's removal, appends the grant, encodes the escrow, checks its size, and saves. `tick` calls
  `AlinaStorage.applyOperation` for the first due operation, off the server thread, after its escrow is on disk. The
  completion puts the stacks into the main inventory (matching stacks first, then empty slots; never the offhand or the
  armour) and gives the experience, then saves the escrow with the storage result recorded. A failed save restores the
  main inventory slots, the experience and the previous escrow, so the next attempt calls storage again and gets `fresh`
  false. When the reward and the experience are fully delivered, the operation leaves the escrow, `markOperation` marks
  it settled (write-behind), and the settled listeners run. One storage call is in flight per player; a failed step
  waits 100 ticks.
- **Grants, experience and trades.** A request may carry fixed stacks (`grant`), delivered after the rolled reward, and
  experience points (`experience`), given once with the delivery. A removal may take experience too; a refused `begin`
  puts it back. `OperationKind.TRADE` carries a `TradeSettled` effect: the Aur the shop paid, the offers bought, and a
  JSON detail for the log. Storage adds the Aur to the shop's budget for the trade day, and each purchase's count to its
  offer's row, in the same transaction as the operation. A `begin` is refused while a pending operation has the same kind,
  subject and tier, so a second trade with one shop waits until the first is settled.
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
  need not be. **Abandon** frees the slot at once, with no cooldown and no reward. When its write fails, the commission
  goes back into the record only if the content still has it and the record is under the cap; otherwise the delete is
  written once more. A failed withdrawal is written once more too.
- **Turn-in** checks the proof, then calls `SettlementService.begin` with the item plan as the removal and the
  commission's category and kind. The commission is `SETTLING` while its operation is in the escrow, including
  operations from an earlier session found at load. The settlement listeners update the record when storage records the
  result and when the reward is delivered.
- **Boards.** The `alina:guild` section's optional `boards` map gives several notice boards, each with its own graph,
  size, reach and pool (`categories` and `commissions`); every commission belongs to exactly one board. Without
  `boards` there is one implicit board, `alina:board`, with `settings.board_graph`. Once Haruhi's startup activation is
  complete, the service holds each board's draw of the current board day: the stored one (keyed by date and board), or
  one drawn from that board's repeatables in rotation, seeded with the board id, and stored. A board with an empty pool
  is not stored and is drawn again after each activation. `board(id)` leaves out ids that are no longer repeatables of
  that board, so a commission moved to another board waits for its new board's next day. A repeatable is accepted
  only from its own board's draw; the active cap is shared by all boards.
- **Content activation** withdraws, for loaded records, each active commission the new content lacks (abandoned in
  storage, with a chat message), and restarts proof tracking from the new content. It withdraws only when the active
  bundle has an `alina:guild` section. A record that loads withdraws the same way, and only once Haruhi's startup is
  complete; Haruhi runs its activation listeners before it reports startup complete, so the startup activation withdraws
  from records loaded before it. A refused or late startup load therefore never deletes players' commissions.
- **Proofs.** Item proofs are planned by `ItemProofPlanner`: main inventory, hotbar and off-hand only; `all_of` is a
  matching, so one item never counts for two entries. Criterion proofs count through `CriterionWatchers` only while
  the commission is active. Progress is written behind every 100 ticks, on leave and on stop. The descent tracker
  arms a dive when the player enters the start volume, disarms it on gliding, Slow Falling, a totem, death or a
  dimension change, and meets the proof when the player reaches the finish volume alive. It also fires
  `alina:volume_entered` when a player enters a content volume; the volumes a player stands in at join are the
  baseline and do not fire.
- **Notice Board** (`alina:notice_board`): survival cannot break it, and the wither and the ender dragon cannot either.
  Each placed board holds the board it shows, in a block entity set with `/alina guild noticeboard`; with none set, it
  shows the content's only board. Using it opens that board's graph with Haruhi's `openAt`, anchored at the block
  within the board's reach.
- **Commands**, for gamemasters: `/alina guild board [board]`, `noticeboard <pos> [<board>|clear]`, `show <player>`, `accept|turn_in|abandon <player>
  <commission>`, `reset <player> <commission>`, `reputation <player> <value>`; `/alina settlement pending <player>` and
  `/alina settlement reconciliation`.

### Dialogue types

Registered by `GuildDialogue`. Every type refuses unknown argument keys; a `commission` argument must name a commission
of the bundle's `alina:guild` section, and `next` must name a node of the same graph. Provider entries show the
commission's name only, so Haruhi can match a pick by position and text.

| Kind | Id | Arguments | Does |
|---|---|---|---|
| provider | `alina:guild/board` | `next` (optional), `board` (optional) | One board's draw of the day, then the feats of that board's pool the player has not completed, by id. Without `board`: the section's only board, else the board whose graph the provider is in (a parse error otherwise). Enabled when the player can accept, grayed with the reason otherwise; picking accepts. |
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

## Achievements

The achievement service (ALINA-PLAN §9) is `AchievementModule.service()` on a running server; the class javadoc of
`AchievementService` and `AdvancementSync` is the contract.

- **Records** load on join like the Guild's, through ordered per-character storage calls, and are never loaded again
  while the player stays online, except when a settlement result for an earn the record did not begin is one storage had
  applied before its call: then the record is loaded again, and counts nothing from that result. Staff operations are
  refused while a record loads or is loaded again.
- **Counting.** Each criterion of an achievement that is not complete in the current cycle counts its events through
  `CriterionWatchers`, up to its count (a tiered achievement: up to the last tier's count). Counts are written behind
  every 100 ticks, on leave and on stop, and written through before an earn. An achievement's own earns never count
  towards its own criteria.
- **Earns.** An achievement without tiers is earned when its requirements (vanilla's AND of ORs) are met; a tiered one
  earns each tier its count reaches, in order. An earn with a reward goes through settlement as an
  `ACHIEVEMENT_REWARD`; one without is written directly. The trigger `alina:achievement_earned`, the chat announcement
  (when `announce` is set and the `show_advancement_messages` game rule is on) and the toast follow once the earn is
  durable. An earn met but not recorded (a crash between the counts and the earn) is found and made at the next load.
- **Repeats.** A repeatable's cycle completes when it is earned (its last tier, when tiered); after the cooldown, and
  once no earn of it is pending or being settled, a new cycle starts: counts reset first, then the stored state
  returns to tier 0. Storage keeps the cooldown of the highest tier, so tier rewards applied out of order cannot clear
  it.
- **The vanilla screen.** Alina's achievements are not in the server's advancement tree. `AdvancementSync` builds
  holders per player (a tab root `alina:tab/<namespace>/<path>`, an achievement `alina:achievement/<namespace>/<path>`,
  later tiers `.../tier_<k>`) and sends them inside vanilla's reset packet, so nothing earned before toasts again. A
  join's first packet waits up to 100 ticks for the record. Later packets carry progress only; just an earn's packet
  sets `showAdvancements`, which is what makes the 26.2 client toast. Vanilla advancements earned while a join's first packet is held show no toast. Content activations and late loads send a full
  packet without toasts. Counts show as vanilla's `x/y`: one segment per event up to 1000, or one per requirements
  group. Positions are Alina's own and do not change between runs. Hidden achievements are withheld until earned once
  and cannot be parents.
- **Condition** `alina:achievement`: `achievement` (an achievement of the bundle's `alina:achievements` section) and
  an optional `tier`. Without `tier`, true once the achievement has been earned in any cycle; with `tier`, once that
  tier has been earned in any cycle.
- **Commands**, for gamemasters: `/alina achievement list <player>`, `grant <player> <achievement> [tier]` (through
  the normal earn path, reward included) and `revoke <player> <achievement> [tier]` (no reward taken back).
- **Seeing it in a client** (the tabs, the `x/y` text and the toasts) needs `./gradlew runClient`; GameTests check
  the packets the server sends.

## Trade

The NPC shop trade is `TradeModule.service()` on a running server; the class javadoc of `TradeService` is the contract.

- **Records.** Each online character's trade record loads on a tick when the character has no trade pending in the
  settlement escrow, so its rows are complete. Until then every request is refused with `LOADING` and no session opens.
  The record holds the Aur paid per shop and trade day, the purchases per offer and period (`character` or the day's
  ISO date), and the trades begun and not yet reported settled. A trade enters the record when `SettlementService.begin`
  accepts it, and leaves it when settlement's settled listener reports it. Nothing else changes it.
- **Trade day** is the Guild's board day: its reset time and zone. Budgets and daily limits count per trade day.
- **Sessions.** `open` closes the player's previous session. It refuses a shop not in the content, a record that has not
  loaded, a trade still settling (`BUSY`), and a client without the Alina mod (`TradeMessenger.canOpen`). A session closes
  when the player dies or leaves, when the shop is gone or the content revision changed, when its interlocutor dies,
  leaves the player's level or is more than 8 blocks away, or when its anchor stops holding the player. `open` and every
  request refuse a dead or removed player with `CLOSED`, and close the player's session, since a lethal hit can land
  between two ticks. Every 10 ticks the view is rebuilt and sent if it differs (stacks compared with `ItemStack.matches`).
  `dismiss` ends a session the player closed and reopens its `then` graph.
- **Requests** `sell`, `sellJunk`, `buy` and `experience` are planned by `TradePlanner` against the live inventory and
  refused, changing nothing, in the order the planner checks them. Each accepted one is a `TRADE` operation with a
  `TradeSettled` effect, begun with a removal that takes the proof or the experience:
  - sell: the stack's count from one slot, paid in coins; the effect's `aurPaid` counts against the budget.
  - sell junk: the junk plan's takes, paid in coins.
  - buy: the payment's takes; the offer's stack times the count, then the change in coins. A limited offer adds one
    `Purchase` of its count; a limit counts purchases, not items.
  - experience: the Aur must be a positive multiple of the rate's `aur`. Points are `(aur / rate.aur) * rate.points`, in
    both directions. Selling takes the points with `giveExperiencePoints(-points)` and pays coins, against the budget;
    buying takes the payment and gives the points when the operation is delivered.
  - The effect's `detailJson` is `{"sold", "bought", "experience_sold", "experience_bought", "aur_spent"}`. The
    `alina:trade_completed` trigger reads `aur_spent` from it.
- **Views and the client.** The view's experience maximums are whole steps of the rate's `aur`, rounded down. The client
  sends `expectedPoints = aur / rateAur * ratePoints`, and a request that differs from the server's figure is `STALE`
  (at a market shop the quote tolerance applies instead; see Experience market).
- **Requirements.** An offer's `requires` reads the player's reputation from `GuildModule` and an achievement from
  `AchievementModule`. A lookup that is still loading locks the offer.
- **Lines.** A shop's own `lines` entry replaces Alina's default for its key. The defaults are `alina.trade.*` keys in
  `en_us.json`. Exception: a market experience trade accepted at points other than its quote is answered with
  `TradeText.soldMoved` or `boughtMoved` (`alina.trade.sold_moved`, `alina.trade.bought_moved`), which no `lines` key replaces.

### Experience market

A bundle with `experience_market` trades experience against one server-wide pool instead of fixed rates; its shops
carry `experienceFees`, never `experience`. The design is EXPERIENCE-MARKET-PLAN in the knowledgebase.

- **Arithmetic** is `trade/pricing/ReserveCurve`, used by the server and the screen. The pool is two reserves, points
  and Aur. Selling for `a` Aur takes `ceil(X * a / (A - a))` points and buying with `a` Aur gives
  `floor(X * a / (A + a))`; the shop's fee is then taken in points. Every figure rounds against the player, the
  reserves move by exactly what the player gives and gets, and so `points * aur` never falls through a trade.
- **Authority.** `ExperienceMarket`, owned by `TradeService`, holds the live reserves, the last restock day, the rate
  after each of the last 32 changes of the pool (`history`, which the view shows as the chart), the trades this run
  began that storage has not reported (`begun`), and the write and reconcile flags, all on the server thread. It loads
  on a tick with `loadOrCreateMarket`; until then a market shop has no experience view and its requests are refused with
  `LOADING`.
- **Writes are deltas.** A market trade's `TradePlan` carries `marketPoints` and `marketAur`, which go into the
  `TradeSettled` effect. Storage adds them to the `experience_market` row in the operation's transaction, once per
  operation id, as exact sums: a reserve may pass below 1 in storage, and the server clamps what it reads into 1 to the
  cap. The memory reserves move when `SettlementService.begin` accepts the trade, and the trade stays in `begun` until
  its applied listener reports it. A trade reported applied fresh that this run did not begin is added to memory then
  and marks a reconcile due. Each change that adds to the row also records a tick of the reserves after it in
  `experience_market_tick`, in the same transaction, and storage keeps the newest 64. An effect with deltas and no
  market row still applies; the deltas are dropped, and no tick is recorded.
- **Restock.** On the first tick of a new trade day each reserve moves `restock_percent` of the way to the content's
  seed, once per day elapsed and at most 60 times. Memory moves at once; `restockMarket` writes the same delta, guarded
  by the stored day, and records a tick of the reserves after it; a restock of several days is one change. Memory
  appends one rate to `history` for each restock call. A restock whose write fails or is not applied marks a reconcile
  due.
- **Reconcile.** A reconcile is due after a foreign fresh apply, a failed or unapplied restock, a failed staff write, or
  a move that clamps. Once no trade is in `begun`, no load, restock or staff write is in flight, the server reads the
  row and its last 32 ticks. It installs them only if no trade began, no foreign apply arrived, and no write started
  while the read ran (`epoch`); otherwise the reconcile stays due and a later tick tries again. A failed read is tried 5
  seconds later.
- **Quote tolerance.** `quote_tolerance_percent` (0 to `ReserveCurve.MAX_QUOTE_TOLERANCE`, default 0) lets a market
  request whose quoted points differ from the live ones go through: `ReserveCurve.acceptsQuote` accepts any move in the
  player's favour above 0, and one against the player up to `ceil(quoted * t / 100)`. The trade settles at the live
  points; `TradeText.soldMoved`/`boughtMoved` name the quote. `ExperienceMarket` counts `requests`, `movedAccepted` and
  `staleRefused` since the server started, which `/alina trade market` prints. The fixed-rate and item requests stay strict.
- **Limits.** One trade may take at most `max_trade_percent` of the reserve it draws from: Aur for a sale, points for a
  purchase. The view's `maxSellAur` and `maxBuyAur` are found by binary search over the same quotes the planner uses.
- **Staff.** `setMarket` writes the difference between the target and the live reserves through `adjustMarket`. It is a
  delta like a trade: the live reserves move at once, storage adds the difference and records a tick, and no request is
  refused for a trade in flight, or for another write. It is refused only while the market has not loaded. A failed
  write marks a reconcile due and fails the future.
- **Seed changes.** The live reserves stay in the database across content revisions. A new seed changes the restock
  target only.

### Dialogue types

Registered by `TradeDialogue`. Every type refuses unknown argument keys.

| Kind | Id | Arguments | Does |
|---|---|---|---|
| action | `alina:trade/open` | `shop` (required, a shop of `alina:trade`), `then` (optional, a graph of the bundle) | Ends the conversation and opens the shop on the next tick, with the same interlocutor and anchor. A refusal is sent as a chat message. |
| condition | `alina:trade/can_afford` | `value` (at least 0) | The player holds at least `value` Aur in the main inventory. |
| condition | `alina:trade/budget_left` | `shop`, `op` (`eq`, `ne`, `gt`, `gte`, `lt`, `lte`), `value` | Compares the Aur the shop can still pay the player today. False while the record loads and for a shop that does not buy. |

### Commands

For gamemasters: `/alina trade open <player> <shop>` (no interlocutor, no anchor), `quote <shop>` (prices the held item
and names its rule's index in the shop's sell rules), `budget <player> [<shop>]`, and `budget <player> reset [<shop>]`,
which deletes today's budget rows in storage and clears them from the record. The reset is refused while a trade of the
player is settling. `market` prints the live reserves, the rate, the seed, the last restock day, the quote tolerance, and the
experience trades since the server started (asked, accepted at a moved price, refused as stale); `market reset`
sets the pool to the seed and `market set <points> <aur>` to the given reserves, each written as the difference from the
live reserves (so no trade is refused for it) and logged with who ran it and the reserves before and after. Feedback is
`Component.literal`.

### Trade tests

`TradeGameTests` builds a `TradeService` per test (`TradeRig`): in-memory storage, its own settlement, a test clock, the
content parsed from `alina/fixtures/trade/`, a recording messenger and players outside the server list. The content
revision is a number the test sets, so the revision path is covered as well as the shop-gone path.

## Lampas content

`content/lampas.bundle.json` is the Lampas bundle: the Receptionist, one graph per board, the `alina:guild` section
(settings, the `lampas:guild/daily` and `lampas:guild/feats` boards, 21 catalog commissions) and a small
`alina:achievements` tab. Deploy it with `/haruhi content import` and `activate` (copy it to `config/haruhi/bundles/`
first). Haruhi activates one revision for the whole server, so this bundle holds every NPC and graph the Lampas server
uses. The reward tables it names are `alina:rewards/guild/<code>`, in this jar.

It is a first pass. The JSON format has no comments, so its placeholders are listed here and in ALINA-PLAN §14:
- the `lampas` id namespace;
- kinds: criterion proofs are feats (A5, B5, E1, E2, F3), item proofs repeatables;
- rewards: one emerald per commission, reputation 1 for a repeatable and 3 for a feat;
- settings: the defaults;
- boards: every repeatable on the Daily Board, every feat on the Feats Board;
- the Receptionist: name, skin (Steve), default model and every line;
- the achievements;
- F2 lists the ten creeper-drop discs no vanilla chest table holds;
- F3 names `gateways:overworldian_nights`, since the catalog names no gateway.

E3 and F1 are left out until builders give their coordinates. `LampasBundleSchemaTest` checks the file against the
schemas, and `LampasContentGameTests` parses it on a server with the mods.

## Lampas knowledgebase (lampas-kb)

`.mcp.json` connects the `lampas-kb` MCP server at `https://kb.lampas.town/mcp`; it needs a token from https://kb.lampas.town/auth/token in the `LAMPAS_KB_TOKEN` environment variable ([kb README](https://github.com/justbecauseph/lampas-two-kb/blob/main/kb/README.md#mcp-server-lampas-kb)). Ask it before stating what is deployed, decided or designed for Lampas 2 outside this repository: take versions from its `status` tool, attribute discussion to the person who said it, and treat plans as intent.
