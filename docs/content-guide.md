# Content guide

This guide is for writers of Guild commissions, notice boards, achievements and shops. Alina's content lives in a Haruhi
content bundle, in three sections of its top-level `extensions` object:

- `alina:guild`: Guild settings, commissions, world volumes and notice boards;
- `alina:achievements`: achievement tabs and achievements;
- `alina:trade`: the currency, price lists and shops.

Everything else in the bundle (NPC profiles, dialogue graphs, flags, conditions and actions) is Haruhi's format, in
Haruhi's [content guide](https://github.com/justbecauseph/haruhi/blob/main/docs/content-guide.md). Players reach the
Guild through Haruhi conversations, so a Guild needs at least one graph that uses Alina's [dialogue types](#dialogue-types).

- [Rules for every section](#rules-for-every-section)
- [The alina:guild section](#the-alinaguild-section)
- [Dialogue types](#dialogue-types)
- [Alina's criterion triggers](#alinas-criterion-triggers)
- [The alina:achievements section](#the-alinaachievements-section)
- [The alina:trade section](#the-alinatrade-section)
- [Validation](#validation)
- [Worked example](#worked-example)

## Rules for every section

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
| `alina:trade/can_afford` | `value` (0 or more) | the player holds at least `value` Aur in the main inventory |
| `alina:trade/budget_left` | `shop`, `op` (`eq`, `ne`, `gt`, `gte`, `lt`, `lte`), `value` | the Aur the shop can still pay the player today compares true; false for a shop without `sell` |

Every condition is false while the player's record loads. `alina:achievement` needs the bundle to have an
`alina:achievements` section, and `tier` is allowed only for a tiered achievement, from 1 to its number of tiers.
`alina:trade/can_afford` reads the inventory, so it does not wait for a record. A `shop` argument must name a shop of
the bundle's `alina:trade` section.

### Actions

| Action | Arguments | Does |
|---|---|---|
| `alina:guild/accept` | `commission` | Accepts it. A refusal is sent to the player's chat. |
| `alina:guild/turn_in` | `commission` | Turns it in. |
| `alina:guild/abandon` | `commission` | Abandons it. |
| `alina:achievement/trigger` | `key` | Fires `alina:manual` with the key, for achievements and commissions that count conversations. |
| `alina:trade/open` | `shop`, `then` (optional) | Ends the conversation and opens the shop's trade screen. When the player closes the screen, the graph `then` opens with the same NPC; without `then` nothing follows. A refusal is sent to the player's chat. |

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

Alina registers seven criterion triggers. Commission proofs and achievement criteria can use them, and so can a vanilla
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
| `alina:trade_completed` | `shop` (id), `aur_earned` and `aur_spent` (bounds) | a trade has settled: `aur_earned` is the Aur the shop paid the player, `aur_spent` the Aur the player paid the shop |

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

## The alina:trade section

Shops buy items from players for Aur, sell items to players, and trade experience points. The section is read in the
same way as the others: a misspelt key is an error, and every problem is listed in one pass.

```json
"alina:trade": {
  "currency": [
    { "item": "minecraft:gold_nugget", "value": 1 },
    { "item": "minecraft:gold_ingot", "value": 9 }
  ],
  "price_lists": {
    "lampas:common_drops": {
      "rules": [
        { "match": { "items": "minecraft:rotten_flesh" }, "price": { "aur": 1, "per": 4 }, "junk": true },
        { "match": { "items": "#minecraft:planks" }, "price": { "aur": 1, "per": 8 }, "junk": true },
        { "namespace": "farmersdelight", "price": { "aur": 1, "per": 2 }, "junk": true },
        { "match": { "items": "minecraft:nether_star" }, "refuse": true }
      ]
    }
  },
  "shops": {
    "lampas:mira": {
      "name": "Mira's stall",
      "sell": { "daily_budget": 200, "lists": ["lampas:common_drops"], "rules": [] },
      "buy": [
        { "id": "xp_flask", "item": { "id": "minecraft:experience_bottle" }, "price": 10 },
        { "id": "hat", "item": { "id": "minecraft:leather_helmet" }, "price": 250,
          "limit": { "count": 1, "per": "character" }, "requires": { "reputation": 50 } }
      ],
      "experience": { "sell": { "points": 50, "aur": 1 }, "buy": { "points": 50, "aur": 2 } },
      "lines": { "greeting": "Welcome.", "sold": "A fair price.", "bought": "Use it well." }
    }
  }
}
```

The example sits inside the bundle's `extensions` object. `sell` is what a player sells to the shop; `buy` is what a
player buys from the shop.

### Currency

`currency` is a non-empty list of denominations, which the shops pay in. Each entry is an item and the Aur it stands for:

| Key | Default | Meaning |
|---|---|---|
| `item` | required | A registered item id. No two entries may name the same item. |
| `value` | required | The Aur the item stands for. At least 1. No two entries may share a value. |

One denomination must have value 1, so any amount of Aur can be paid out. The server keeps the denominations largest
value first.

### Price lists

`price_lists` are named lists of sell rules. A shop's `sell` names them with `lists`. A price list has one key,
`rules`.

### Sell rules

A rule has exactly one of `match` and `namespace`:

| Key | Default | Meaning |
|---|---|---|
| `match` | none | A vanilla item predicate: `items`, `components`, `predicates`. It must not have `count`. |
| `namespace` | none | An id namespace, such as `farmersdelight`. Every item of that namespace matches. The server checks that the namespace has registered items. |
| `price` | required, unless `refuse` | `aur` and `per`: the shop pays `aur` Aur for every `per` items. `aur` is at least 1; `per` is from 1 to 64. |
| `junk` | `false` | `true` marks the matching items as junk. |
| `refuse` | `false` | `true` makes the shop take none of the matching items. A refuse rule has no `price` and no `junk`. |

A rule matches a stack when its `namespace` is the item's namespace, or its `match` accepts the stack. A stack is
modified when it has component changes, such as an enchantment, a custom name or damage. A modified stack is matched
only by a rule whose `match` has `components` or `predicates`. A `namespace` rule and a `match` without those keys never
take a modified stack.

A shop tries its rules in order and uses the first one that matches. The shop's own `rules` come first, then the rules
of each list it names, in the order it names them. A refuse rule that matches ends the search, so a later price cannot
apply to that item. A `match` may name a tag, such as `"#minecraft:planks"`; tags are resolved on the server only.

### Shops

A shop needs a `name` (text) and at least one of these:

| Key | Meaning |
|---|---|
| `sell` | What players sell to the shop. Needs `daily_budget`: the most Aur the shop pays players in one day, at least 1. Its rules come from `rules` and `lists`, and at least one rule must result, unless the shop's `experience` has a `sell` entry: then `sell` may have no rules, and its budget pays out the experience sales. |
| `buy` | What players buy from the shop: a non-empty list of offers. |
| `experience` | The experience the shop trades: rates, or fees in a bundle with an `experience_market`. See [Experience](#experience) and [Experience market](#experience-market). |
| `lines` | Optional text for the shop's messages. The keys are `greeting`, `sold`, `bought`, `cannot_afford`, `budget_spent`, `no_room`, `nothing_to_sell`, `limit_reached`, `locked` and `busy`. Each is a text component. |

The server checks the keys and the text of `lines`. The trade screen shows `greeting` when the shop opens, `sold` after a
sale of items or experience, and `bought` after a purchase of an offer or of experience. A market trade accepted at points
other than its quote is answered with Alina's own line instead, `Sold N points for A Aur (quoted Q).` or `Bought N points
for A Aur (quoted Q).`, which no `lines` key replaces. The others answer a refused
trade: `cannot_afford`, `budget_spent` (the day's budget is used up), `no_room` (the main inventory cannot hold what the
player would receive), `nothing_to_sell` ("Sell junk" with no junk), `limit_reached`, `locked` (a `requires` the player
does not meet) and `busy` (the player's last trade is still being recorded). A shop without a line uses Alina's default.

### Offers

Each entry of `buy` is an offer:

| Key | Default | Meaning |
|---|---|---|
| `id` | required | Lower-case letters, digits and underscores (`[a-z0-9_]+`), at most 64 characters. Unique within the shop. |
| `item` | required | An item stack: `{ "id": "minecraft:paper", "count": 4 }`. The count is from 2 to 99 and at most the item's stack size. Leave out a count of 1, the default. |
| `price` | required | The Aur the stack's count of the item costs. At least 1. |
| `limit` | none | `{ "count", "per" }`: at most `count` of this offer to one player per `per`. `per` is `day` or `character`. `count` is at least 1. |
| `requires` | none | `reputation` (a whole number) and/or `achievement` (an id). At least one is needed. The achievement is not checked against `alina:achievements`. |

An offer's item may not be a currency item, and may not be a container that holds items.

### Experience

`experience` sets fixed rates for points of experience, in a bundle without an `experience_market`. Give `sell`, `buy` or
both. Each is `{ "points", "aur" }`: the points the rate covers and the Aur for them, both at least 1. `sell` is what a
player sells to the shop. `buy` is what a player buys from the shop. A trade moves whole steps of the rate: the Aur
amount must be a multiple of `aur`, and it moves `points` for each step.

In a bundle with an `experience_market`, each of `sell` and `buy` is `{ "fee_percent" }` instead. See
[Experience market](#experience-market).

### Experience market

A bundle with an `experience_market` section trades experience against one pool, and every shop's `experience` gives
fees instead of rates:

```json
"alina:trade": {
  "currency": [
    { "item": "minecraft:gold_nugget", "value": 1 },
    { "item": "minecraft:gold_ingot", "value": 9 }
  ],
  "experience_market": {
    "reserve": { "points": 500000, "aur": 10000 },
    "restock_percent": 10,
    "max_trade_percent": 10
  },
  "shops": {
    "lampas:mira": {
      "name": "Mira's stall",
      "sell": { "daily_budget": 200 },
      "experience": { "sell": { "fee_percent": 5 }, "buy": { "fee_percent": 5 } }
    }
  }
}
```

| Key | Default | Meaning |
|---|---|---|
| `reserve.points` | required | The points of the seed reserve, from 1000 to 1000000000. |
| `reserve.aur` | required | The Aur of the seed reserve, from 1000 to 1000000000. |
| `restock_percent` | required | From 0 to 100: how far each reserve moves back to the seed once per Guild board day, in percent. |
| `max_trade_percent` | 10 | From 1 to 50: the most of the reserve one trade may take from it, in percent. |
| `quote_tolerance_percent` | 0 | From 0 to 10: how far, in percent of the quoted points, a trade may have moved against the player since the screen's quote and still go through. 0 refuses any difference. |
| `fee_percent` | none | In a shop's `sell` or `buy`: from 0 to 50, the shop's fee in that direction, in percent. A direction with no entry is not traded. |

Every experience broker trades against the same pool. At the seed, the rate is `reserve.points / reserve.aur` points
per Aur. The rate moves as players trade: selling experience makes it worth less at every broker, and buying makes it
worth more. A trade is priced along the pool's curve and rounded against the player, so selling and buying straight
back always loses.

The fee is taken in points. A fee of n percent takes n percent of the points the curve gives for the trade: a sale costs
the player that many more points, and a purchase gives the player that many fewer.

Once per Guild board day, each reserve moves `restock_percent` of the way back to the seed. One trade may take at most
`max_trade_percent` of the reserve it draws from.

With `quote_tolerance_percent` above 0, a trade still goes through when the live points have moved against the player by
up to that percent of the quoted points, rounded up to a whole point, and a move in the player's favour always goes
through. The trade settles at the live points, and the line the screen shows gives the points traded, which are the live
points, and the points quoted. At 0, any difference from the quote refuses the trade as stale. Above 0, the market panel
shows `Quote tolerance N%` on its own line under the fee.

The live reserves are kept in the database. Changing the seed in a new revision changes where the pool heads, not where
it is.

A bundle uses either rates in every shop or the market in every shop, never both. With an `experience_market`, each
`sell` and `buy` is `{ "fee_percent" }`; without one, each is `{ "points", "aur" }`.

A shop whose `experience` has a `sell` entry, in either form, needs a `sell` block with a `daily_budget`: the shop pays
the experience out of that budget, and the entry is refused without the block. Its `sell` may have no rules, since it
takes no items. A shop that only buys experience needs no `sell` block.

### Aur must not be minted

Content may not let a player make Aur by trading. The server refuses a bundle when:

- a shop's experience `buy` costs less per point than any shop's experience `sell` pays, the same shop included (equal
  rates are allowed; fees in an experience market are not compared); or
- a shop buys back an offer, by a sell rule that is not `refuse`, for more per item than the offer costs.

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
- a trade rule's `namespace` has no registered item on the server;
- the currency has no denomination of value 1;
- a shop's `sell` names a price list the section lacks;
- a shop's `experience` has a rate in a bundle with an `experience_market`, or a fee in a bundle without one;
- an `experience_market` lacks `reserve` or `restock_percent`, or a value in it or a `fee_percent` is outside its range;
- a trade rule would let a player mint Aur, as described under [Aur must not be minted](#aur-must-not-be-minted);
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
