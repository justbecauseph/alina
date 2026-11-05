package town.lampas.alina.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static town.lampas.alina.content.ContentFixtures.guild;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The {@code alina:guild} extension. Expected values come from ALINA-PLAN §6.1: field defaults, the proof types, the
 * validation rules and the counted-trigger allowlist of §6.3.
 */
class GuildReaderTest {

    @BeforeAll
    static void boot() {
        ContentFixtures.boot();
    }

    /** The §6.1 example, with the settings in full. */
    static final String EXAMPLE = """
            {
              "settings": {
                "board_size": 5, "active_cap": 3,
                "reset": { "time": "04:00", "zone": "Asia/Manila" },
                "default_cooldown": "PT20H", "salt": "maraha",
                "board_graph": "alina:guild/board", "board_reach": 6
              },
              "commissions": {
                "alina:guild/b3_screaming_horn": {
                  "category": "B", "kind": "repeatable",
                  "name": "Horn of the Screaming Goat",
                  "hint": "Bring a goat horn that plays Admire, Call, Yearn or Dream.",
                  "proof": {
                    "type": "item",
                    "any_of": [
                      { "item": { "items": "minecraft:goat_horn" },
                        "checks": [ { "type": "alina:instrument_tag", "tag": "minecraft:screaming_goat_horns" } ],
                        "count": 1 }
                    ]
                  },
                  "cooldown": "PT20H",
                  "reward": { "loot_table": "alina:rewards/placeholder", "reputation": 1 }
                },
                "alina:guild/e1_longshot": {
                  "category": "E", "kind": "feat",
                  "name": "The Longshot Marksman",
                  "hint": "Kill a skeleton from more than 50 blocks away, after accepting.",
                  "proof": {
                    "type": "criterion",
                    "trigger": "minecraft:player_killed_entity",
                    "conditions": {
                      "entity": [
                        { "condition": "minecraft:entity_properties", "entity": "this",
                          "predicate": { "minecraft:entity_type": "minecraft:skeleton",
                                         "minecraft:distance": { "horizontal": { "min": 50 } } } }
                      ],
                      "killing_blow": { "tags": [ { "id": "minecraft:is_projectile", "expected": true } ] }
                    },
                    "count": 1
                  },
                  "reward": { "loot_table": "alina:rewards/placeholder", "reputation": 3 }
                },
                "alina:guild/e3_descent": {
                  "category": "E", "kind": "feat", "name": "Terminal Descent", "hint": "Dive.",
                  "proof": { "type": "descent", "start": "alina:guild/high_dive", "finish": "alina:guild/dive_basin" }
                }
              },
              "volumes": {
                "alina:guild/high_dive": { "dimension": "minecraft:overworld", "from": [4, 320, 4], "to": [0, 319, 0] },
                "alina:guild/dive_basin": { "dimension": "minecraft:overworld", "from": [0, -64, 0], "to": [4, -62, 4] }
              }
            }
            """;

    private static Identifier id(String text) {
        return Identifier.parse(text);
    }

    @Test
    void theExampleParses() {
        GuildContent content = guild(EXAMPLE).orThrow();

        GuildSettings settings = content.settings();
        assertEquals(5, settings.boardSize());
        assertEquals(3, settings.activeCap());
        assertEquals(LocalTime.of(4, 0), settings.resetTime());
        assertEquals(ZoneId.of("Asia/Manila"), settings.zone());
        assertEquals(new Cooldown.Fixed(Duration.ofHours(20)), settings.defaultCooldown());
        assertEquals("maraha", settings.salt());
        assertEquals(6.0, settings.boardReach());

        Commission horn = content.commissions().get(id("alina:guild/b3_screaming_horn"));
        assertEquals('B', horn.category());
        assertEquals(Commission.Kind.REPEATABLE, horn.kind());
        assertEquals("Horn of the Screaming Goat", horn.name().getString());
        Proof.Items items = assertInstanceOf(Proof.Items.class, horn.proof());
        assertFalse(items.allOf());
        assertEquals(1, items.entries().size());
        assertEquals(1, items.entries().get(0).count());
        assertTrue(items.entries().get(0).consume());
        assertInstanceOf(ItemCheck.InstrumentTag.class, items.entries().get(0).checks().get(0));
        assertEquals(new Reward(Optional.of(id("alina:rewards/placeholder")), 1), horn.reward());
        assertEquals(1, horn.weight());
        assertTrue(horn.inRotation());

        Commission longshot = content.commissions().get(id("alina:guild/e1_longshot"));
        assertEquals(Commission.Kind.FEAT, longshot.kind());
        assertEquals(Optional.empty(), longshot.cooldown());
        Proof.Criterion criterion = assertInstanceOf(Proof.Criterion.class, longshot.proof());
        assertEquals(id("minecraft:player_killed_entity"), criterion.criterion().trigger());
        assertEquals(1, criterion.criterion().count());

        Volume dive = content.volumes().get(id("alina:guild/high_dive"));
        assertEquals(new BlockPos(0, 319, 0), dive.min());
        assertEquals(new BlockPos(4, 320, 4), dive.max());
        assertEquals(new Proof.Descent(id("alina:guild/high_dive"), id("alina:guild/dive_basin")),
                content.commissions().get(id("alina:guild/e3_descent")).proof());
    }

    @Test
    void repeatableWithoutCooldownTakesTheDefault() {
        GuildContent content = guild("""
                { "settings": { "board_graph": "alina:guild/board", "default_cooldown": "board_reset" },
                  "commissions": { "alina:c": { "category": "A", "kind": "repeatable", "name": "n", "hint": "h",
                    "proof": { "type": "item", "all_of": [ { "item": { "items": "minecraft:spore_blossom" } } ] } } } }
                """).orThrow();
        Commission commission = content.commissions().get(id("alina:c"));
        assertEquals(Optional.of(new Cooldown.UntilBoardReset()), commission.cooldown());
        assertEquals(Reward.NONE, commission.reward());
        assertEquals(GuildSettings.DEFAULT_BOARD_SIZE, content.settings().boardSize());
        assertEquals(GuildSettings.DEFAULT_ZONE, content.settings().zone());
    }

    private static List<String> problems(String commissionsJson) {
        return guild("{ \"settings\": { \"board_graph\": \"alina:guild/board\" }, \"commissions\": " + commissionsJson
                + ", \"volumes\": { \"alina:v\": { \"dimension\": \"minecraft:overworld\", \"from\": [0,0,0], \"to\": [1,1,1] } } }")
                .problems();
    }

    private static final String ITEM_PROOF =
            "{ \"type\": \"item\", \"all_of\": [ { \"item\": { \"items\": \"minecraft:heavy_core\" } } ] }";

    private static String commission(String extraFields, String proof) {
        return "{ \"alina:c\": { \"category\": \"F\", \"kind\": \"repeatable\", \"name\": \"n\", \"hint\": \"h\", "
                + extraFields + " \"proof\": " + proof + " } }";
    }

    @Test
    void unknownKeysAreProblems() {
        assertEquals(List.of("extension alina:guild, commission alina:c: unknown key 'only_if'"),
                problems(commission("\"only_if\": [],", ITEM_PROOF)));
    }

    @Test
    void aFeatHasNoCooldown() {
        List<String> problems = problems("{ \"alina:c\": { \"category\": \"F\", \"kind\": \"feat\", \"name\": \"n\","
                + " \"hint\": \"h\", \"cooldown\": \"PT1H\", \"proof\": " + ITEM_PROOF + " } }");
        assertEquals(List.of("extension alina:guild, commission alina:c: cooldown is not allowed on a feat, which is "
                + "completed once"), problems);
    }

    @Test
    void categoryMustBeAToF() {
        assertEquals(List.of("extension alina:guild, commission alina:c: category must be one of A, B, C, D, E or F"),
                problems(commission("", ITEM_PROOF).replace("\"F\"", "\"G\"")));
    }

    @Test
    void boardGraphMustBeInTheBundle() {
        List<String> problems = guild("{ \"settings\": { \"board_graph\": \"alina:elsewhere\" } }").problems();
        assertEquals(List.of("extension alina:guild, settings: board_graph 'alina:elsewhere' is not a dialogue in this "
                + "bundle"), problems);
    }

    @Test
    void settingsAreRequired() {
        assertEquals(List.of("extension alina:guild: settings is required"), guild("{}").problems());
    }

    @Test
    void badZoneAndTimeAreProblems() {
        List<String> problems = guild("""
                { "settings": { "board_graph": "alina:guild/board", "reset": { "time": "4am", "zone": "Mars/Base" } } }
                """).problems();
        assertEquals(2, problems.size(), () -> String.join("\n", problems));
        assertTrue(problems.get(0).contains("time '4am' is not a time of day"), problems.get(0));
        assertTrue(problems.get(1).contains("zone 'Mars/Base' is not a time zone id"), problems.get(1));
    }

    @Test
    void aMisspeltBoundsKeyIsAProblem() {
        String proof = "{ \"type\": \"item\", \"all_of\": [ { \"item\": { \"items\": \"minecraft:bee_nest\" },"
                + " \"checks\": [ { \"type\": \"alina:bees\", \"count\": { \"mni\": 3 } } ] } ] }";
        List<String> problems = problems(commission("", proof));
        assertEquals(1, problems.size(), () -> String.join("\n", problems));
        assertTrue(problems.get(0).contains("count key 'mni' has no effect"), problems.get(0));
    }

    @Test
    void anItemEntryHasNoCountInsideItsPredicate() {
        List<String> problems = problems(commission("",
                "{ \"type\": \"item\", \"any_of\": [ { \"item\": { \"items\": \"minecraft:heavy_core\", \"count\": 2 } } ] }"));
        assertEquals(List.of("extension alina:guild, commission alina:c, proof, any_of entry 1: item must not have a "
                + "count; give the entry's count instead"), problems);
    }

    @Test
    void anItemProofHasExactlyOneOfAllOfAndAnyOf() {
        String both = "{ \"type\": \"item\", \"all_of\": [ { \"item\": { \"items\": \"minecraft:heavy_core\" } } ],"
                + " \"any_of\": [ { \"item\": { \"items\": \"minecraft:heavy_core\" }, \"count\": 0 } ] }";
        assertEquals(List.of(
                "extension alina:guild, commission alina:c, proof: needs exactly one of all_of and any_of",
                "extension alina:guild, commission alina:c, proof, any_of entry 1: count must be at least 1"),
                problems(commission("", both)));
    }

    @Test
    void aMisspeltConditionKeyIsAProblem() {
        // Vanilla's codec ignores unknown keys, so "entitiy" would drop the skeleton check and let any kill count.
        String misspelt = "{ \"type\": \"criterion\", \"trigger\": \"minecraft:player_killed_entity\", \"conditions\": {"
                + " \"entitiy\": [ { \"condition\": \"minecraft:entity_properties\", \"entity\": \"this\","
                + " \"predicate\": { \"minecraft:entity_type\": \"minecraft:skeleton\" } } ] } }";
        assertEquals(List.of("extension alina:guild, commission alina:c, proof: conditions key 'entitiy' has no effect: "
                + "it is not a key at that place, or only repeats its default; remove it"),
                problems(commission("", misspelt)));
    }

    @Test
    void aMisspeltNestedConditionKeyIsAProblem() {
        String misspelt = "{ \"type\": \"criterion\", \"trigger\": \"minecraft:player_killed_entity\", \"conditions\": {"
                + " \"entity\": [ { \"condition\": \"minecraft:entity_properties\", \"entity\": \"this\","
                + " \"predicate\": { \"minecraft:entity_type\": \"minecraft:skeleton\","
                + " \"minecraft:distance\": { \"horizontl\": { \"min\": 50 } } } } ] } }";
        List<String> problems = problems(commission("", misspelt));
        assertEquals(1, problems.size(), () -> String.join("\n", problems));
        assertTrue(problems.get(0).contains("'entity[0].predicate.minecraft:distance.horizontl'"), problems.get(0));
    }

    @Test
    void aMisspeltItemPredicateKeyIsAProblem() {
        String proof = "{ \"type\": \"item\", \"all_of\": [ { \"item\": { \"items\": \"minecraft:potion\","
                + " \"component\": { \"minecraft:potion_contents\": { \"potion\": \"minecraft:strong_turtle_master\" } } } } ] }";
        List<String> problems = problems(commission("", proof));
        assertEquals(1, problems.size(), () -> String.join("\n", problems));
        assertTrue(problems.get(0).startsWith("extension alina:guild, commission alina:c, proof, all_of entry 1: item key "
                + "'component' has no effect"), problems.get(0));
    }

    @Test
    void theCatalogConditionsParseStrictly() {
        // E1 from the catalog, with the projectile tag on the killing blow.
        assertEquals(List.of(), guild(EXAMPLE).problems());
    }

    @Test
    void aCountedStateTriggerStillReportsItsConditions() {
        String counted = "{ \"type\": \"criterion\", \"trigger\": \"minecraft:inventory_changed\", \"count\": 2,"
                + " \"conditions\": { \"itemz\": [] } }";
        List<String> problems = problems(commission("", counted));
        assertEquals(2, problems.size(), () -> String.join("\n", problems));
        assertTrue(problems.get(1).contains("'itemz' has no effect"), problems.get(1));
    }

    @Test
    void aProofWithoutATypeStillReportsUnknownKeys() {
        List<String> problems = problems(commission("", "{ \"trigge\": \"minecraft:tame_animal\" }"));
        assertEquals(List.of(
                "extension alina:guild, commission alina:c, proof: type is required",
                "extension alina:guild, commission alina:c, proof: unknown key 'trigge'"), problems);
    }

    @Test
    void anItemEntryWithACountInItsPredicateStillReportsItsOtherFields() {
        String proof = "{ \"type\": \"item\", \"any_of\": [ { \"item\": { \"items\": \"minecraft:heavy_core\", \"count\": 2 },"
                + " \"consume\": \"yes\" } ] }";
        assertEquals(List.of(
                "extension alina:guild, commission alina:c, proof, any_of entry 1: item must not have a count; give the "
                        + "entry's count instead",
                "extension alina:guild, commission alina:c, proof, any_of entry 1: consume must be true or false"),
                problems(commission("", proof)));
    }

    @Test
    void aStateTriggerCannotBeCounted() {
        String counted = "{ \"type\": \"criterion\", \"trigger\": \"minecraft:inventory_changed\", \"count\": 2 }";
        assertEquals(List.of("extension alina:guild, commission alina:c, proof: trigger minecraft:inventory_changed "
                + "fires repeatedly for one state, so it can only be used with count 1"), problems(commission("", counted)));
    }

    @Test
    void anEventTriggerCanBeCounted() {
        String counted = "{ \"type\": \"criterion\", \"trigger\": \"minecraft:cured_zombie_villager\", \"count\": 3 }";
        assertEquals(List.of(), problems(commission("", counted)));
    }

    @Test
    void anUnknownTriggerIsAProblem() {
        String unknown = "{ \"type\": \"criterion\", \"trigger\": \"minecraft:not_a_trigger\" }";
        assertEquals(List.of("extension alina:guild, commission alina:c, proof: trigger minecraft:not_a_trigger is not "
                + "a registered criterion trigger"), problems(commission("", unknown)));
    }

    @Test
    void badConditionsAreAProblem() {
        String bad = "{ \"type\": \"criterion\", \"trigger\": \"minecraft:player_killed_entity\","
                + " \"conditions\": { \"entity\": 7 } }";
        List<String> problems = problems(commission("", bad));
        assertEquals(1, problems.size(), () -> String.join("\n", problems));
        assertTrue(problems.get(0).startsWith("extension alina:guild, commission alina:c, proof: conditions "),
                problems.get(0));
    }

    @Test
    void aDescentNamesVolumesOfTheExtension() {
        String descent = "{ \"type\": \"descent\", \"start\": \"alina:v\", \"finish\": \"alina:nowhere\" }";
        assertEquals(List.of("extension alina:guild, commission alina:c, proof: finish 'alina:nowhere' is not a volume "
                + "in this extension"), problems(commission("", descent)));
    }

    @Test
    void anUnknownItemCheckIsAProblem() {
        String proof = "{ \"type\": \"item\", \"all_of\": [ { \"item\": { \"items\": \"minecraft:bee_nest\" },"
                + " \"checks\": [ { \"type\": \"alina:honey\" } ] } ] }";
        assertEquals(List.of("extension alina:guild, commission alina:c, proof, all_of entry 1, check 1: type "
                + "alina:honey is not an item check; use alina:bees, alina:stew_effect or alina:instrument_tag"),
                problems(commission("", proof)));
    }

    @Test
    void stewEffectAndBeeChecksParse() {
        String proof = "{ \"type\": \"item\", \"all_of\": [ { \"item\": { \"items\": \"minecraft:suspicious_stew\" },"
                + " \"checks\": [ { \"type\": \"alina:stew_effect\", \"effect\": \"minecraft:wither\" },"
                + " { \"type\": \"alina:bees\", \"count\": 3 } ] } ] }";
        assertEquals(List.of(), problems(commission("", proof)));
    }

    @Test
    void twoKeysForOneIdAreAProblem() {
        String body = "{ \"category\": \"F\", \"kind\": \"repeatable\", \"name\": \"n\", \"hint\": \"h\", \"proof\": "
                + ITEM_PROOF + " }";
        List<String> problems = problems("{ \"minecraft:c\": " + body + ", \"c\": " + body + " }");
        assertTrue(problems.contains("extension alina:guild: commissions keys 'minecraft:c' and 'c' both name minecraft:c"),
                () -> String.join("\n", problems));
    }

    @Test
    void everyCountedVanillaTriggerIsAWatchableRegisteredTrigger() {
        for (Identifier trigger : CriterionReader.COUNTED_VANILLA_TRIGGERS) {
            assertInstanceOf(SimpleCriterionTrigger.class, BuiltInRegistries.TRIGGER_TYPES.getValue(trigger),
                    trigger + " is not a registered simple criterion trigger");
        }
    }
}
