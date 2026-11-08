# Content guide

This guide is for writers of Guild commissions, notice boards and achievements. Alina's content lives in a Haruhi
content bundle, in two sections of its top-level `extensions` object:

- `alina:guild`: Guild settings, commissions, world volumes and notice boards;
- `alina:achievements`: achievement tabs and achievements.

Everything else in the bundle (NPC profiles, dialogue graphs, flags, conditions and actions) is Haruhi's format, in
Haruhi's [content guide](https://github.com/justbecauseph/haruhi/blob/main/docs/content-guide.md). Players reach the
Guild through Haruhi conversations, so a Guild needs at least one graph that uses Alina's [dialogue types](#dialogue-types).

- [Rules for both sections](#rules-for-both-sections)
- [The alina:guild section](#the-alinaguild-section)
- [Dialogue types](#dialogue-types)
- [Alina's criterion triggers](#alinas-criterion-triggers)
- [The alina:achievements section](#the-alinaachievements-section)
- [Validation](#validation)
- [Worked example](#worked-example)

## Rules for both sections

Alina follows Haruhi's bundle rules:

- Keys are snake_case, and an unknown key is an error at every level, so a misspelt key never drops a gate silently.
- Ids are `namespace:path`. Two keys that parse to the same id are an error.
- Text fields are Minecraft text components: a plain string, a component object or an array.
- Validation lists every problem it finds in one pass.

Vanilla JSON inside Alina's sections (criterion `conditions`, item predicates, icons, number bounds) is parsed with
vanilla's own codecs, with one difference: a key the codec would ignore is refused, at any depth. A key that only
repeats its default, such as `"count": 1` in an icon, is refused the same way.

Durations (`cooldown`, `default_cooldown`, an achievement's `repeat.cooldown`) are ISO-8601 durations, positive and at
most 3650 days: `PT20H` is 20 hours, `P7D` seven days, `PT1H30M` an hour and a half.

[`alina.schema.json`](alina.schema.json) is a JSON Schema of a whole bundle with Alina's sections, built on Haruhi's
`bundle.schema.json`. Point an editor at it to get completion and catch shape errors as you type; the server still
checks what a schema cannot, listed under [Validation](#validation).

## The alina:guild section

```json
"extensions": {
  "alina:guild": {
    "settings": { ... },
    "boards": { ... },
    "commissions": { ... },
    "volumes": { ... }
  }
}
```

`settings` is required. `commissions`, `volumes` and `boards` are optional maps keyed by id.

### Settings

| Key | Default | Meaning |
|---|---|---|
| `board_size` | `5` | Commissions drawn per board each day, unless a board sets its own `size`. At least 1. |
| `active_cap` | `3` | The most commissions one player may hold active at once, across all boards. At least 1. |
| `reset` | `{ "time": "04:00", "zone": "Asia/Manila" }` | When a board day starts. `time` is `HH:MM` or `HH:MM:SS`; `zone` is a time zone id. |
| `default_cooldown` | `"PT20H"` | The cooldown of a repeatable that sets none. A duration, or `"board_reset"`. |
| `salt` | `""` | Mixed into each day's draw, so two servers with the same content draw different boards. |
| `board_graph` | none | The graph Notice Boards open when the section has no `boards`. Required then, and not allowed with `boards`. |
| `board_reach` | `6` | How far, in blocks, a player may be from a Notice Board. Above 0 and at most 64. The default `reach` of every board. |

### Boards

Each board has its own pool of commissions, its own dialogue graph and its own daily draw. Without `boards`, the
section has one implicit board, `alina:board`, whose graph is `settings.board_graph` and whose pool is every
commission.

```json
"boards": {
  "lampas:guild/daily": {
    "name": "Daily Board",
    "graph": "lampas:guild/board_daily",
    "categories": ["A", "B"],
    "commissions": ["lampas:guild/f4_heavy_core"]
  },
  "lampas:guild/feats": {
    "name": "Feats Board",
    "graph": "lampas:guild/board_feats",
    "size": 3
  }
}
```

| Key | Default | Meaning |
|---|---|---|
| `graph` | required | The graph its Notice Boards open. Must be in the bundle, and no two boards may share one. |
| `name` | the board's id | The name staff commands show. |
| `size` | `settings.board_size` | Commissions drawn each day. |
| `reach` | `settings.board_reach` | Above 0 and at most 64. |
| `categories` | `[]` | Letters A to F: every commission of those categories is in the pool. |
| `commissions` | `[]` | Commission ids in the pool, whatever their category. |

Every commission must belong to exactly one board, through its category or its id. A commission that two boards hold,
or that no board holds, is a validation error. `boards` must list at least one board.

Each day, a board draws up to `size` commissions from its pool's repeatables that are `in_rotation`, by `weight`,
without repeats. Feats are not drawn: the board's provider lists the feats in its pool that the player has not
completed after the draw. The draw is stored, so it stays the same all day and across restarts. A board whose pool
has no repeatables in rotation draws nothing.

A player can accept a repeatable only from its own board's draw of the day. The active cap is shared by all boards.

### Commissions

```json
"lampas:guild/a1_untouchable_grain": {
  "category": "A",
  "kind": "repeatable",
  "name": "The Untouchable Grain",
  "hint": "Bring a block of suspicious sand or suspicious gravel.",
  "proof": { "type": "item", "any_of": [
    { "item": { "items": "minecraft:suspicious_sand" } },
    { "item": { "items": "minecraft:suspicious_gravel" } }
  ] },
  "reward": { "loot_table": "alina:rewards/guild/a1", "reputation": 1 }
}
```

| Key | Default | Meaning |
|---|---|---|
| `category` | required | `A`, `B`, `C`, `D`, `E` or `F`. Boards and triggers can select by it. |
| `kind` | required | `repeatable`: taken again after its cooldown. `feat`: completed once. |
| `name` | required | Text. Shown as the commission's choice in the board, turn-in and abandon lists. |
| `hint` | required | Text. Shown when the proof is not met. |
| `proof` | required | What the player must show. See [Proofs](#proofs). |
| `cooldown` | `settings.default_cooldown` | Repeatables only: a duration counted from the turn-in, or `"board_reset"` for the next board reset. Not allowed on a feat. |
| `weight` | `1` | The commission's weight in the daily draw. At least 1. |
| `in_rotation` | `true` | `false` keeps a repeatable out of the draw from the next board day, so players cannot accept it. A draw already stored for today keeps it. |
| `reward` | none | `loot_table` (an id that must exist on the server) and `reputation` (a whole number, 0 or more, default 0). |

A commission's standing for a player is one of:

- available: the player holds no active, settling, cooldown or completed state for it. Accepting also needs a free
  slot under `active_cap`, and for a repeatable a place on today's draw;
- active: accepted, with progress tracked;
- settling: turned in, and its reward is still being delivered;
- cooldown: a repeatable turned in, until its cooldown ends;
- completed: a feat turned in.

Abandoning an active commission frees its slot with no cooldown and no reward.

### Proofs

A proof has a `type`: `item`, `criterion` or `descent`.

#### item

The player hands over items from their main inventory, hotbar and off hand. The proof has exactly one of `all_of`
(every entry) and `any_of` (one entry), each a non-empty list of entries.

| Entry key | Default | Meaning |
|---|---|---|
| `item` | required | A vanilla item predicate: `items`, `components`, `predicates`. It must not have `count`. |
| `count` | `1` | How many matching items. |
| `consume` | `true` | `false` checks for the items without taking them. |
| `checks` | `[]` | Alina item checks, below. Every check must pass. |

For `all_of`, one item never counts for two entries, so `[paper x2, paper x1]` needs three paper.

Item checks test component data the vanilla predicate cannot:

| Check | Argument | Passes when |
|---|---|---|
| `{ "type": "alina:bees", "count": 3 }` | `count`: a whole number or `{ "min", "max" }` | the number of bees stored in the item is within `count` |
| `{ "type": "alina:stew_effect", "effect": "minecraft:wither" }` | `effect`: a registered effect | the suspicious stew's effects include it |
| `{ "type": "alina:instrument_tag", "tag": "minecraft:screaming_goat_horns" }` | `tag`: an instrument tag on the server | the item's instrument is in the tag |

#### criterion

The player triggers a criterion while the commission is active. Events before acceptance never count.

```json
"proof": {
  "type": "criterion",
  "trigger": "minecraft:player_killed_entity",
  "conditions": {
    "entity": [
      {
        "condition": "minecraft:entity_properties",
        "entity": "this",
        "predicate": { "minecraft:entity_type": "minecraft:skeleton" }
      }
    ]
  },
  "count": 5
}
```

`trigger` is any registered criterion trigger that fires for a player, vanilla's or [Alina's](#alinas-criterion-triggers).
`conditions` is that trigger's conditions, exactly as in a vanilla advancement, default `{}`. `count` (default 1) is the
number of matching events.

A count above 1 needs a trigger that fires once per real event: one of Alina's triggers, or one of these vanilla
triggers: `player_killed_entity`, `entity_killed_player`, `cured_zombie_villager`, `fishing_rod_hooked`,
`bred_animals`, `brewed_potion`, `consume_item`, `enchanted_item`, `filled_bucket`, `recipe_crafted`,
`summoned_entity`, `tame_animal`, `villager_trade`, `used_totem`, `channeled_lightning`, `lightning_strike`. State
triggers such as `inventory_changed` and `location` fire again and again for one state, so they take only count 1.

Progress is saved every 100 ticks, when the player leaves and when the server stops.

#### descent

The player falls from one volume into another.

```json
"proof": { "type": "descent", "start": "lampas:guild/high_dive", "finish": "lampas:guild/dive_basin" }
```

Entering `start` while the commission is active arms a dive. Gliding, Slow Falling, using a totem, dying or changing to
another dimension disarms it. Reaching `finish` alive while armed meets the proof. `start` and `finish` must be
different volumes of this section. A dive in progress is kept in memory only, so a server restart disarms it.

### Volumes

A volume is a box of blocks in one dimension, used by descent proofs and the `alina:volume_entered` trigger.

```json
"volumes": {
  "lampas:guild/high_dive": { "dimension": "minecraft:overworld", "from": [500, 200, 500], "to": [503, 201, 503] }
}
```

`from` and `to` are opposite corners, both included, in either order. The dimension must exist on the server.

Alina checks which volumes hold a player every 5 ticks (every tick while one of the player's dives is armed), and
only once the player's Guild record has loaded. A player moving fast can pass through a thin volume between two
checks, so it fires no `alina:volume_entered` and arms no dive. Make volumes, and a descent's `start` above all, at least a few blocks deep along the path players take.

## Dialogue types

Alina registers these Haruhi types. Every type refuses argument keys it does not take. A `commission` argument must
name a commission of the bundle's `alina:guild` section, and `next` must name a node of the same graph.

### Choice providers

A provider fills a node's choice list with one entry per commission. Each entry's text is the commission's `name`.
After a pick the conversation moves to `next`; without `next` it ends.

| Provider | Arguments | Entries |
|---|---|---|
| `alina:guild/board` | `next`, `board` (both optional) | One board's draw of the day, then the feats in that board's pool the player has not completed. Grayed out, with the reason, when the player cannot accept it. Picking accepts. |
| `alina:guild/turn_in` | `next` (optional) | Each active or settling commission. Grayed out, with the hint, until its proof is met. Picking turns it in. |
| `alina:guild/abandon` | `next` (optional) | Each active commission. Picking abandons it. |
| `alina:guild/status` | none | Disabled rows: the player's reputation, then each commission's standing, with progress for a counted criterion and the time left on a cooldown. |

`alina:guild/board` without `board` shows the section's only board, or, with several boards, the board whose `graph`
is the graph the provider is in. If neither applies, the bundle fails validation. A Receptionist graph that lists
several boards names each one with `board`.

While the player's Guild record is still loading, every provider shows one disabled row saying so.

A node whose only entries come from `alina:guild/status` has no enabled choice, so give it a static choice to leave:

```json
"status": {
  "text": "Your standing with the Guild.",
  "choices": [
    { "provider": { "type": "alina:guild/status" } },
    { "text": "Back.", "next": "hello" }
  ]
}
```

### Conditions

| Condition | Arguments | True when |
|---|---|---|
| `alina:guild/has_active` | none | the player has an active commission |
| `alina:guild/can_accept` | none | the player is under `active_cap` |
| `alina:guild/reputation` | `op` (`eq`, `ne`, `gt`, `gte`, `lt`, `lte`), `value` | the player's reputation compares true |
| `alina:guild/commission` | `commission`, `state` (`available`, `active`, `cooldown`, `completed`) | the commission is in that standing; a commission being settled matches none |
| `alina:achievement` | `achievement`, `tier` (optional) | the player has earned the achievement (or that tier) in any cycle |

Every condition is false while the player's record loads. `alina:achievement` needs the bundle to have an
`alina:achievements` section, and `tier` is allowed only for a tiered achievement, from 1 to its number of tiers.

### Actions

| Action | Arguments | Does |
|---|---|---|
| `alina:guild/accept` | `commission` | Accepts it. A refusal is sent to the player's chat. |
| `alina:guild/turn_in` | `commission` | Turns it in. |
| `alina:guild/abandon` | `commission` | Abandons it. |
| `alina:achievement/trigger` | `key` | Fires `alina:manual` with the key, for achievements and commissions that count conversations. |

### What the player is told

Accepting, turning in and abandoning are written to storage in the background. The player gets a chat message for
each result:

| When | Message |
|---|---|
| accepted | `Commission accepted: <name>` |
| a criterion or descent proof is met | `Ready to turn in: <name>` |
| turned in | `Turned in: <name>. Your reward is on its way.` |
| the reward is delivered | `Commission complete: <name>` |
| abandoned | `Commission abandoned: <name>` |
| withdrawn by a content activation | `The Guild withdrew <name>, so it is no longer active.` |
| at the cap | `You already hold <n> commissions, the most the Guild allows.` |
| on cooldown | `<name> is available again in <time>.` |

The messages are translatable, with their English text in `assets/alina/lang/en_us.json`.

## Alina's criterion triggers

Alina registers six criterion triggers. Commission proofs and achievement criteria can use them, and so can a vanilla
advancement in a data pack. Every condition is optional, so `{}` matches every event, and each also takes vanilla's
`player` condition.

| Trigger | Conditions | Fires when |
|---|---|---|
| `alina:commission_accepted` | `commission` (id), `category` (`A` to `F`), `kind` (`repeatable` or `feat`) | an accept has been written to storage |
| `alina:commission_completed` | `commission`, `category`, `kind` | a turn-in's reward has reached the player |
| `alina:reputation_changed` | `reputation` (bounds, such as `{ "min": 10 }`) | storage has recorded a turn-in or reward that changes reputation; `reputation` is the value after the change. `/alina guild reputation` does not fire it. |
| `alina:volume_entered` | `volume` (id) | the player enters a volume of the `alina:guild` section. The volumes a player stands in at join do not fire. |
| `alina:achievement_earned` | `achievement` (id), `tier` (bounds, 1-based) | an achievement earn is durable |
| `alina:manual` | `key` (string) | the `alina:achievement/trigger` action runs with that key |

Ids in these conditions are checked: a commission proof's `commission` and `volume` must be in the `alina:guild`
section, and an achievement criterion's `achievement` must be in the `alina:achievements` section. A `commission` or
`volume` named by an achievement criterion, and an `achievement` named by a commission proof, are not checked yet, so
check those ids by hand.

## The alina:achievements section

Achievements appear in the vanilla advancement screen, in tabs of their own. The server builds them per player and
sends them in vanilla's advancement packets, so they never mix with data-pack advancements and survive `/reload`.

```json
"alina:achievements": {
  "tabs": {
    "lampas:guild": {
      "title": "The Guild",
      "icon": "minecraft:writable_book",
      "background": "minecraft:block/spruce_planks"
    }
  },
  "achievements": {
    "lampas:guild/first_commission": {
      "tab": "lampas:guild",
      "title": "Signed On",
      "description": "Complete a Guild commission.",
      "icon": "minecraft:paper",
      "criteria": { "deliver": { "trigger": "alina:commission_completed" } }
    }
  }
}
```

### Tabs

| Key | Meaning |
|---|---|
| `title` | Required. Text. |
| `icon` | Required. An item id, or `{ "id", "count", "components" }`. Leave out a count of 1. |
| `background` | Optional. A texture id without `textures/` and `.png`, as in vanilla: `minecraft:gui/advancements/backgrounds/stone`, or any block texture such as `minecraft:block/spruce_planks`. |

### Achievements

| Key | Default | Meaning |
|---|---|---|
| `tab` | required | A tab of this section. |
| `parent` | none | Another achievement in the same tab. It must not be hidden, and parents must not loop. Without one, the achievement hangs from the tab's root. |
| `title`, `description` | required | Text. |
| `icon` | required | As for tabs. |
| `frame` | `"task"` | `task`, `goal` or `challenge`. |
| `hidden` | `false` | Withheld from the player until earned once. A hidden achievement cannot be a parent. |
| `announce` | `true` | Announce the earn in chat, when the `show_advancement_messages` game rule is on. |
| `toast` | `true` | Show the toast. |
| `criteria` | required | At least one named criterion: `trigger`, `conditions` and `count`, as for a [criterion proof](#criterion). |
| `requirements` | every criterion | Vanilla's AND of ORs over criterion names: `[["a", "b"], ["c"]]` is (a or b) and c. Every criterion must appear. |
| `tiers` | none | Rising count thresholds on the one criterion. See below. |
| `repeat` | none | `{ "cooldown": "<duration>" }` makes it repeatable. |
| `reward` | none | `loot_table` and `reputation`, as for a commission. Paid through the same settlement as a turn-in. |

The screen shows a criterion's count as vanilla's `x/y`: one segment per event, up to 1000, or one segment per
requirements group when there are several groups.

### Tiers

An achievement with `tiers` has exactly one criterion, which must not set a `count`: the tiers give the counts. Each
tier is earned, in order, when the count reaches it:

```json
"tiers": [
  { "count": 10 },
  { "count": 50, "title": "Fifty Commissions", "frame": "goal", "reward": { "reputation": 5 } },
  { "count": 100, "frame": "challenge" }
]
```

Counts must rise. A tier may set its own `title`, `frame` and `reward`; without its own reward, it pays the
achievement's. Each tier after the first is drawn as a child of the one before it. The criterion's trigger must be one
that fires once per event when the last tier's count is above 1.

### Repeats

A repeatable's cycle completes when it is earned (its last tier, if it has tiers). Once its cooldown has passed, a new
cycle starts: its counts reset to 0 and it can be earned again. The description gains `Earned once` or
`Earned <n> times` (`Completed once` or `Completed <n> times` for a tiered one). The `alina:achievement` condition is true once an achievement has been earned
in any cycle.

An achievement's own earns never count towards its own criteria.

## Validation

Besides the shapes the schema checks, the server refuses a bundle when:

- a board's `graph`, or `settings.board_graph`, is not a graph in the bundle;
- `settings.board_graph` is missing without `boards`, or present with them;
- two boards share a graph, or a commission belongs to no board or to several;
- a descent proof names a volume the section lacks, or the same volume twice;
- a criterion trigger is not registered, or never fires for a player, or its conditions do not parse or fail vanilla's
  checks (a missing predicate reference, a wrong loot context);
- a count above 1 uses a state trigger;
- a loot table, instrument tag, effect or dimension does not exist on the server;
- an achievement names a tab or parent the section lacks, a parent in another tab, a hidden parent, or a parent chain
  that loops;
- tier counts do not rise, an achievement with tiers has more than one criterion or a criterion `count`, or
  `requirements` names a criterion the achievement lacks or leaves one out;
- an id in an Alina trigger's conditions is not in its section, as described under
  [Alina's criterion triggers](#alinas-criterion-triggers);
- a feat has a `cooldown`;
- an `alina:guild/*` dialogue type names a commission the section lacks, or `alina:achievement` an achievement the
  section lacks, a `tier` on an achievement without tiers or out of its range, or is used in a bundle with no
  `alina:achievements` section.

Loot tables, tags, effects, dimensions and mod items are checked against the running server, so a bundle can pass on
a server with a mod and fail on one without it.

## Worked example

A small Guild with one implicit board, an NPC who runs it, three commissions and one achievement. Save it as
`config/haruhi/bundles/guild.json`, then `/haruhi content import guild.json` and `/haruhi content activate <id>`.

```json
{
  "schema": 1,
  "npcs": {
    "example:clerk": {
      "name": "Guild Clerk",
      "texture": "minecraft:textures/entity/player/wide/steve.png",
      "dialogue": "example:clerk"
    }
  },
  "dialogues": {
    "example:clerk": {
      "start": "hello",
      "nodes": {
        "hello": {
          "text": "The Guild has work for you.",
          "choices": [
            { "text": "Show me the board.", "next": "board" },
            { "text": "I'm done with one.", "next": "turn_in",
              "only_if": [ { "type": "alina:guild/has_active" } ] },
            { "text": "My record.", "next": "status" },
            { "text": "Goodbye.", "next": "bye" }
          ]
        },
        "board": {
          "text": "Pick one.",
          "choices": [
            { "provider": { "type": "alina:guild/board", "next": "hello" } },
            { "text": "Back.", "next": "hello" }
          ]
        },
        "turn_in": {
          "text": "Which one?",
          "choices": [
            { "provider": { "type": "alina:guild/turn_in", "next": "hello" } },
            { "text": "Back.", "next": "hello" }
          ]
        },
        "status": {
          "text": "Your record.",
          "choices": [
            { "provider": { "type": "alina:guild/status" } },
            { "text": "Back.", "next": "hello" }
          ]
        },
        "bye": { "text": "Safe travels." }
      }
    }
  },
  "extensions": {
    "alina:guild": {
      "settings": {
        "board_size": 2,
        "active_cap": 2,
        "salt": "example",
        "board_graph": "example:clerk"
      },
      "commissions": {
        "example:paper_run": {
          "category": "C",
          "kind": "repeatable",
          "name": "Paper Run",
          "hint": "Bring eight paper.",
          "proof": { "type": "item", "all_of": [ { "item": { "items": "minecraft:paper" }, "count": 8 } ] },
          "reward": { "loot_table": "alina:rewards/placeholder", "reputation": 1 }
        },
        "example:fresh_fish": {
          "category": "C",
          "kind": "repeatable",
          "name": "Fresh Fish",
          "hint": "Bring three raw cod or three raw salmon.",
          "proof": { "type": "item", "any_of": [
            { "item": { "items": "minecraft:cod" }, "count": 3 },
            { "item": { "items": "minecraft:salmon" }, "count": 3 }
          ] },
          "cooldown": "board_reset",
          "reward": { "reputation": 1 }
        },
        "example:zombie_cure": {
          "category": "B",
          "kind": "feat",
          "name": "Second Chance",
          "hint": "After accepting, cure a zombie villager.",
          "proof": { "type": "criterion", "trigger": "minecraft:cured_zombie_villager" },
          "reward": { "loot_table": "alina:rewards/placeholder", "reputation": 3 }
        }
      }
    },
    "alina:achievements": {
      "tabs": {
        "example:guild": { "title": "The Guild", "icon": "minecraft:writable_book" }
      },
      "achievements": {
        "example:regular": {
          "tab": "example:guild",
          "title": "Regular",
          "description": "Complete Guild commissions.",
          "icon": "minecraft:paper",
          "criteria": { "deliver": { "trigger": "alina:commission_completed" } },
          "tiers": [ { "count": 1 }, { "count": 10, "frame": "goal" } ]
        }
      }
    }
  }
}
```

The clerk's graph is also the board graph, so the `alina:guild/board` provider needs no `board` argument and a Notice
Board with no board set opens the same conversation. Both repeatables are on the board every day, because the pool has
two repeatables and `board_size` is 2; the feat is listed after them until the player completes it.
