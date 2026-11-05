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

- The build includes `../haruhi` through `includeBuild` in `settings.gradle`, because haruhi has no published artifact
  yet (plan H4). Keep a haruhi checkout beside this one. Changes Alina needs in haruhi are made in that repository.
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
| `client` | `AlinaClient` entrypoint |

Initialisation, in `Alina.onInitialize`: storage, criteria, content, settlement, guild, achievement (§5). Criterion
trigger types register before content, because content parses criteria through `BuiltInRegistries.TRIGGER_TYPES`.
haruhi's services are read from server events onward, never during init.

## Rules

- **Client code stays in the client package.** Nothing outside `town.lampas.alina.client` references
  `net.minecraft.client`; `ArchitectureTest` fails the build otherwise.
- **No blocking I/O on the server thread.** Storage futures complete off-thread; hop back with `server.execute(...)`.
- **The server checks again whatever the client sends.** The client sends only haruhi's session id, node id and choice
  index.
- **Content follows haruhi's bundle rules:** snake_case keys, unknown keys are errors at every level, ids are
  `namespace:path`, text fields are text components, and every problem is reported in one pass through the extension
  context.
- **`docs/alina.schema.json` changes with the content format.** `AlinaSchemaTest` fails when it names different keys
  from the readers, refuses the documented examples, or accepts a shape the readers refuse.

## Content

Two sections under the haruhi bundle's `extensions` object. The format, with examples, is in the plan's §6;
`GuildReaderTest.EXAMPLE` and `AchievementsReaderTest.EXAMPLE` hold the examples the tests check.

- `alina:guild`: `settings` (required; `board_graph` required and must be a graph in the bundle), `commissions`,
  `volumes`. Proof types `item` (`all_of` or `any_of` entries of an item predicate without `count`, Alina item checks
  `alina:bees`, `alina:stew_effect`, `alina:instrument_tag`, `count`, `consume`), `criterion` (`trigger`, vanilla
  `conditions`, `count`) and `descent` (`start` and `finish` volumes).
- `alina:achievements`: `tabs` and `achievements`. Tab `background` is a texture id without `textures/` and `.png`, as
  in vanilla (`minecraft:gui/advancements/backgrounds/stone`).
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
