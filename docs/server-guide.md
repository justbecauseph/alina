# Server guide

This guide is for operators who install Alina on a Fabric server, choose its storage and deploy its content. Alina runs
on Haruhi, so the Haruhi [server guide](https://github.com/justbecauseph/haruhi/blob/main/docs/server-guide.md) applies
too: content revisions, `/haruhi` commands and Haruhi's own storage are documented there.

- [Install](#install)
- [Storage](#storage)
- [Deploying content](#deploying-content)
- [Notice Boards](#notice-boards)
- [Commands](#commands)
- [How a turn-in is settled](#how-a-turn-in-is-settled)
- [Board days](#board-days)
- [Troubleshooting](#troubleshooting)

## Install

Put these in the server's `mods/` folder:

- `alina-<version>.jar`;
- Haruhi 0.2.0 or newer;
- Fabric API.

Give every player the same three jars, and GeckoLib 5.5.5 for Haruhi's NPC models. A client without Alina cannot join:
the Notice Board's block, item and block entity type are registry entries, and Fabric API's registry sync disconnects a
client that lacks them. Alina's client entrypoint is empty: achievements use the vanilla advancement screen, and the
jar's language file names the block and translates the Guild's messages.

The Lampas bundle in `content/lampas.bundle.json` also needs Farmer's Delight and Gateways to Eternity (with Placebo,
Architectury and Apothic Attributes, which they depend on), on the server and on every client: they register items
and an entity, so registry sync refuses a client without them. Alina itself runs without them.

In singleplayer, Alina runs inside the integrated server like any other mod. Storage is built when the mod initialises
and closed when the world stops, and the next world start builds it again.

## Storage

`config/alina.json` picks the storage backend. The file written on first start:

```json
{
  "storage": {
    "type": "memory",
    "jdbc_url": "jdbc:postgresql://localhost:5432/alina",
    "username": "alina",
    "password_env": "ALINA_DB_PASSWORD",
    "pool_size": 4,
    "schema": "alina"
  }
}
```

| Key | Default | Rule |
|---|---|---|
| `type` | `"memory"` | `"memory"` or `"postgres"` |
| `jdbc_url` | `"jdbc:postgresql://localhost:5432/alina"` | must start with `jdbc:postgresql:` when `type` is `postgres` |
| `username` | `"alina"` | must not be blank when `type` is `postgres` |
| `password_env` | `"ALINA_DB_PASSWORD"` | the name of the environment variable that holds the password; must not be blank when `type` is `postgres` |
| `pool_size` | `4` | 1 to 100: the number of PostgreSQL connections and of storage threads |
| `schema` | `"alina"` | 1 to 63 lowercase letters, digits or underscores, not starting with a digit |

The only top-level key is `storage`, and a file without it uses every default. A missing key takes its default. An
unknown key, at either level, is an error, and so is a `password` key: the password never goes in the file. An invalid
file stops the game from starting with `Alina storage configuration <path> is invalid: <reason>`.

This file is separate from Haruhi's `config/haruhi.json`. The two mods can use different databases, or the same
database and even the same schema: Alina records its migrations in `alina_schema_version` and Haruhi in
`schema_version`, and their table names do not overlap.

### In-memory backend

`"type": "memory"` logs at startup:

```
Alina storage: in-memory backend. Commissions, achievements and operations are lost when the server stops.
```

Reputation, accepted commissions, cooldowns, stored board draws, achievements, criterion counts and the operation log
are gone after a restart. Operations still in a player's escrow survive, because the escrow is part of the player's
data. One that storage had not applied yet is applied to the new, empty storage when the player is next online. One
whose storage result is already in the escrow only finishes delivering its reward, so its reputation change and its
cooldown or completion are not in the new storage. Use this backend for testing.

### PostgreSQL backend

`"type": "postgres"` reads the password from the variable `password_env` names, when the mod initialises. If it is
unset or empty, the game does not start: `Alina storage is set to PostgreSQL, but the environment variable
ALINA_DB_PASSWORD is not set`. The log names the schema: `Alina storage: PostgreSQL backend, schema alina`.

```sh
export ALINA_DB_PASSWORD='change-me'
```

Connection settings:

- A HikariCP pool named `alina-storage` with at most `pool_size` connections, and the same number of storage threads.
  Storage calls during play never run on the server thread; the migrations run on it at startup.
- Every connection uses `schema` as its default schema.
- `socketTimeout=10` (seconds) and `tcpKeepAlive=true`, with the server-side limits `lock_timeout=5s` and
  `statement_timeout=8s` sent as the connection's `options`. The PostgreSQL driver gives a parameter in `jdbc_url`
  priority over these, so a `socketTimeout`, `tcpKeepAlive` or `options` there replaces Alina's value.
- The pool is built when the mod initialises, so a database that cannot be reached then stops the game from starting.

A role that owns the schema needs no other rights. As a superuser, in `psql`:

```sql
CREATE ROLE alina LOGIN PASSWORD 'change-me';
CREATE DATABASE alina;
\c alina
CREATE SCHEMA alina AUTHORIZATION alina;
```

If the schema does not exist, the migration step creates it, which needs `CREATE` on the database.

### Migrations

The SQL migrations ship in the jar under `alina/migrations/`: `V1__init.sql` and `V2__boards.sql`. They run when the
server starts, before Alina's other modules read the database, and the start waits for them. Each runs in its own
transaction with its row in `alina_schema_version`, under an advisory lock, so two servers starting on one database
apply each once. A failure stops the start with `Alina storage could not migrate schema <schema>: <reason>`. A schema
that a newer Alina build migrated is refused with `Schema <schema> is at version <n>, newer than this build's <m>`.

| Table | Key | Holds |
|---|---|---|
| `character_state` | `character_id` | `reputation`; a character with no row has 0 |
| `commission_state` | `character_id`, `commission_id` | `status` (`active`, `cooldown`, `completed`), `progress`, `accepted_at`, `completed_at`, `cooldown_until`, `revision_id`. Abandoning deletes the row. A row whose cooldown has passed stays, shown to the player as available, until the commission is accepted again or `/alina guild reset` deletes it. |
| `operation` | `operation_id` | every turn-in and achievement reward: character, kind, subject, tier, revision, world id, rolled reward, reputation, effect, `step` (`recorded`, `settled`, `reconcile`), times and a note |
| `board` | `board_date`, `board_id` | one stored draw per board and day: `revision_id`, `seed`, `commission_ids` in board order |
| `achievement` | `character_id`, `achievement_id` | the highest `tier` earned (0 without tiers), `earn_count`, `last_earned_at`, `available_at` |
| `criterion_progress` | `character_id`, `achievement_id`, `criterion` | `count`; a count of 0 deletes the row |
| `alina_schema_version` | `version` | the applied migrations |

Only one server may use an Alina database at a time. Nothing enforces this yet; see
[World id](#world-id).

## Deploying content

Alina's content is two sections of a Haruhi content bundle, `alina:guild` and `alina:achievements`, under the
bundle's `extensions` object. The [content guide](content-guide.md) describes them. Haruhi activates one revision for
the whole server, so the bundle that holds Alina's sections must also hold every NPC and graph the server uses.

To deploy a bundle, copy it into `config/haruhi/bundles/` and run, as an operator:

```
/haruhi content validate lampas.json
/haruhi content import lampas.json
/haruhi content activate <id>
```

`validate` and `import` list every problem in Alina's sections together with Haruhi's own, one per line, each starting
with where it is. On a server without Gateways to Eternity, the Lampas bundle gives `extension alina:guild, commission
lampas:guild/f3_gateway_incursion, proof: trigger gateways:finish_gateway is not a registered criterion trigger`.
Alina checks loot
tables, tags, dimensions and criterion conditions against the running server, so a bundle that validates on one server
can fail on another with different mods or data packs.

With Haruhi's in-memory storage, imported revisions are lost at each restart and Haruhi activates
`config/haruhi/bundle.json`, or its seed bundle, again. Put the bundle at `config/haruhi/bundle.json` in that case, or
use Haruhi's PostgreSQL backend.

An activation reaches Alina at once:

- Each loaded player's active commissions that the new content no longer has are withdrawn: abandoned in storage, with
  the chat message `The Guild withdrew <name>, so it is no longer active.` Nothing is withdrawn when the new bundle has
  no `alina:guild` section, so activating a bundle without one, or a failed startup load, never deletes commissions.
- Criterion and descent tracking restart from the new content.
- Each board keeps its stored draw for the day, less commissions its pool no longer holds. A board whose pool was empty
  draws again at once.
- Every online player gets a fresh set of achievement tabs, without toasts. Stored achievements that the new content
  lacks are kept, untouched.

### The Lampas bundle

`content/lampas.bundle.json` holds the Guild Receptionist (`lampas:guild/receptionist`), one graph per board, the
`lampas:guild/daily` and `lampas:guild/feats` boards, 21 catalog commissions and a `lampas:guild` achievement tab. Its
reward tables, `alina:rewards/guild/<code>`, are in the Alina jar. It needs Farmer's Delight (D3) and Gateways to
Eternity (F3) on the server. Its rewards, text, skin and achievements are placeholders; `AGENTS.md` lists them.

## Notice Boards

`alina:notice_board` is a block that opens a board's conversation when a player uses it, with an empty hand or holding
any item. A player who sneaks while holding an item uses the item instead, as with any vanilla block. Get one with `/give @s alina:notice_board`; it has no recipe and no creative tab entry.

Survival players cannot break it, pistons cannot move it, explosions do not break it, and the wither and the ender
dragon cannot destroy it. A creative player can break it, and it drops nothing.

Each placed board stores which board it shows. Set it with `/alina guild noticeboard <pos> <board>`. A Notice Board with
no board set shows the content's only board when the content has exactly one, and otherwise tells the player `This
notice board has nothing pinned to it yet.` A board id the active content lacks, or a graph missing from it, gives
`Nothing is posted on the notice board.` So does any Notice Board while the active bundle has no `alina:guild`
section.

The conversation is anchored at the block's centre. A player farther away than the board's `reach` (6 blocks by
default) is told `Step closer to the notice board to read it.`, and a conversation ends when the player moves beyond
the reach.

## Commands

Every `/alina` command needs permission level 2 (gamemaster). `<player>` must be online. Until the player's Guild or
achievement record has loaded, the commands that read or change it answer with a "still loading" message, except
`/alina guild reputation`, which writes storage at once and reloads the record.

### /alina guild

| Command | What it does |
|---|---|
| `/alina guild board` | Lists every board of the active content with today's draw. |
| `/alina guild board <board>` | Shows one board. |
| `/alina guild noticeboard <pos>` | Shows which board the Notice Board at `<pos>` opens. |
| `/alina guild noticeboard <pos> <board>` | Makes that Notice Board open `<board>`, which must be in the active content. |
| `/alina guild noticeboard <pos> clear` | Removes the board from that Notice Board. |
| `/alina guild show <player>` | The player's reputation, then each commission that is active, settling, cooling down or completed: standing, progress and cooldown left. |
| `/alina guild accept <player> <commission>` | Accepts for the player, with the same checks as the dialogue. |
| `/alina guild turn_in <player> <commission>` | Turns in for the player, taking the proof from their inventory. |
| `/alina guild abandon <player> <commission>` | Abandons for the player: no cooldown, no reward. |
| `/alina guild reset <player> <commission>` | Deletes the player's row for the commission, whatever its standing, so it is available again: a cooldown ends and a completed feat can be taken again. Refused while the commission is being settled or a write for it is in flight (`The Guild is still recording <name>.`). |
| `/alina guild reputation <player> <value>` | Sets the player's reputation to `<value>`. |

A board line reads `<name> (<id>), <date>, revision <n>: <commission ids>`, with `(not stored)` after the revision when
the draw came from an empty pool, and `still loading` before the board has loaded. `accept`, `turn_in` and `abandon`
reply `Started accepting`, `Started turning in` or `Started abandoning <commission> for <player>. The player is told the
result.`: the storage write is still in flight when the command returns, and only the player sees whether it
succeeded. A refusal (not on today's board, at the
cap, proof not met, and so on) is the reply.

### /alina achievement

| Command | What it does |
|---|---|
| `/alina achievement list <player>` | One line per achievement: earned or the tier reached, the earn count, each criterion's count and target, and when a repeat is available again. |
| `/alina achievement grant <player> <achievement> [tier]` | Earns it through the normal path: the reward is paid, the trigger fires and the toast shows. |
| `/alina achievement revoke <player> <achievement> [tier]` | Takes it away and lowers the counts. Rewards already paid stay with the player. |

`grant` and `revoke` are refused while the player's record loads.

### /alina settlement

| Command | What it does |
|---|---|
| `/alina settlement pending <player>` | Each operation in the player's escrow: id, kind, subject, tier, whether storage has applied it, and what is still to deliver. |
| `/alina settlement reconciliation` | Each operation seen in this run whose world id is not this world's, with its player. See [World id](#world-id). |

## How a turn-in is settled

A turn-in or an achievement reward is an operation with its own id. Alina:

1. rolls the reward's loot table, seeded from the operation id, and takes the proof from the player's main inventory,
   hotbar and off hand;
2. writes the operation into the player's escrow (the attachment `alina:settlement`, saved with the player) and saves
   the player, then reads `playerdata/<uuid>.dat` back to confirm the escrow is on disk;
3. asks storage to apply the operation: the reputation change, the commission's cooldown or completion, or the
   achievement's tier. Storage keys the operation by its id, so a call made again after a crash applies nothing twice;
4. puts the reward into the main inventory, matching stacks first, then empty slots, and saves again;
5. removes the operation from the escrow when everything is delivered.

A failed save keeps the operation in the escrow and retries it; the proof is never refunded. A reward that does not fit
waits in the escrow until the player has room. A failed step waits 100 ticks before the next try. A dead player is not
served until they respawn, and the escrow is copied to the respawned player.

`begin` refuses an operation whose escrow would be over 60000 bytes, because vanilla cannot save a player whose escrow
string is over 65535 bytes. The refusal changes nothing, puts the proof back, and logs a WARN that starts
`Alina refused operation <id> for <uuid>: its escrow would be <n> bytes, over the 60000 byte limit`.

### World id

The first start writes a UUID to `<world>/alina/world_id.txt`. Every operation carries it. An operation whose world id
is not the running world's is never applied: it stays in the escrow, is logged once (`Alina operation <id> for <player>
was made in world <a>, not <b>; it is not applied and is listed for reconciliation`), and is listed by
`/alina settlement reconciliation`. This catches player data copied into another world and a lost or recreated id
file. It does not catch a copied or restored world, which keeps its id file, so two copies of a world must not share
one database.

## Board days

A board day starts at `settings.reset.time` in `settings.reset.zone` (04:00 in Asia/Manila by default) and lasts until
the next reset. At the first tick of a new board day, each board loads its stored draw for that date, or draws one and
stores it. The draw is seeded with SHA-256 over the content's `salt`, the date, the active revision id and the board
id, so two boards with the same pool draw differently and a different `salt` gives another server different boards.

A commission cooling down with `board_reset` becomes available at the next reset. Fixed cooldowns, such as `PT20H`,
count from the turn-in.

## Troubleshooting

| Symptom or log line | Cause |
|---|---|
| `Alina storage configuration <path> is invalid: <reason>` | `config/alina.json` has an unknown key, a `password` key or a value out of range. |
| `Alina storage is set to PostgreSQL, but the environment variable <name> is not set` | The password variable is missing from the server's environment. |
| Players see `Your Guild record is still loading.` for a long time | Storage cannot load the record. The log has `Alina could not load the Guild record of <uuid>; it is tried again in 5 seconds`. |
| `Today's board is still being posted.` | The board has not loaded or been drawn yet, which waits until Haruhi's startup activation is complete. |
| The Notice Board says `Nothing is posted on the notice board.` | Its board id, or that board's graph, is not in the active content. Check with `/alina guild noticeboard <pos>` and `/alina guild board`. |
| A board shows `none` | No repeatable in rotation is in its pool. Feats are not drawn; they are listed by the provider separately. |
| Achievement tabs are missing for one player | The record had not loaded 100 ticks after the join. The log has `Alina achievements of <uuid> had not loaded 100 ticks after the join; the first advancement packet goes without them, and they follow when they load`, then `Alina loaded the achievements of <player> after the first advancement packet` when they arrive in a full packet. If the second line never comes, storage cannot load the record. |
| `<file> does not hold a world id (a UUID). Fix it or remove it before the server starts; ...` | `<world>/alina/world_id.txt` is damaged. The server does not start. | Restore the file. Removing it makes a new id, and every operation pending in an escrow with the old id is then never applied. |
| `Alina could not verify the player data save of <player>` | The save or the read-back failed. The operation stays in the escrow and is retried. |
| `/haruhi content import` fails with problems in `extension alina:guild` | Fix the bundle. A loot table, tag, dimension or mod item missing from this server is a common cause. |
