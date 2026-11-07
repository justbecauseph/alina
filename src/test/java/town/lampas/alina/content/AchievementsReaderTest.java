package town.lampas.alina.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static town.lampas.alina.content.ContentFixtures.achievements;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import town.lampas.alina.content.AchievementsContent.Achievement;

/**
 * The {@code alina:achievements} extension. Expected values come from ALINA-PLAN §6.2: field defaults, tiers on one
 * counted criterion, vanilla's requirements, and parents in the same tab.
 */
class AchievementsReaderTest {

    @BeforeAll
    static void boot() {
        ContentFixtures.boot();
    }

    /** The §6.2 example, with a vanilla trigger in place of Alina's own for the first achievement. */
    static final String EXAMPLE = """
            {
              "tabs": {
                "alina:guild": {
                  "title": "The Guild",
                  "icon": { "id": "minecraft:writable_book" },
                  "background": "minecraft:block/spruce_planks"
                }
              },
              "achievements": {
                "alina:guild/first_commission": {
                  "tab": "alina:guild",
                  "title": "Signed On",
                  "description": "Complete a Guild commission.",
                  "icon": { "id": "minecraft:paper" },
                  "frame": "task",
                  "criteria": { "deliver": { "trigger": "minecraft:villager_trade", "conditions": {} } },
                  "reward": { "loot_table": "alina:rewards/placeholder", "reputation": 1 }
                },
                "alina:guild/zombie_slayer": {
                  "tab": "alina:guild",
                  "parent": "alina:guild/first_commission",
                  "title": "Night Watch",
                  "description": "Kill zombies.",
                  "icon": { "id": "minecraft:zombie_head" },
                  "frame": "goal",
                  "criteria": {
                    "kills": {
                      "trigger": "minecraft:player_killed_entity",
                      "conditions": {
                        "entity": [
                          { "condition": "minecraft:entity_properties", "entity": "this",
                            "predicate": { "minecraft:entity_type": "minecraft:zombie" } }
                        ]
                      }
                    }
                  },
                  "tiers": [
                    { "count": 10, "title": "Night Watch I" },
                    { "count": 100, "title": "Night Watch II", "reward": { "loot_table": "alina:rewards/placeholder" } },
                    { "count": 1000, "title": "Night Watch III", "frame": "challenge" }
                  ],
                  "repeat": { "cooldown": "P7D" }
                }
              }
            }
            """;

    private static Identifier id(String text) {
        return Identifier.parse(text);
    }

    @Test
    void theExampleParses() {
        AchievementsContent content = achievements(EXAMPLE).orThrow();
        assertEquals("The Guild", content.tabs().get(id("alina:guild")).title().getString());

        Achievement first = content.achievements().get(id("alina:guild/first_commission"));
        assertEquals(Optional.empty(), first.parent());
        assertEquals(AdvancementType.TASK, first.frame());
        assertTrue(first.announce());
        assertTrue(first.toast());
        assertEquals(List.of(List.of("deliver")), first.requirements());
        assertEquals(1, first.criteria().get("deliver").count());
        assertEquals(1, first.reward().reputation());

        Achievement slayer = content.achievements().get(id("alina:guild/zombie_slayer"));
        assertEquals(Optional.of(id("alina:guild/first_commission")), slayer.parent());
        assertEquals(List.of(10, 100, 1000), slayer.tiers().stream().map(AchievementsContent.Tier::count).toList());
        assertEquals(Optional.of(AdvancementType.CHALLENGE), slayer.tiers().get(2).frame());
        assertEquals(Optional.of(Duration.ofDays(7)), slayer.repeat());
        assertEquals(Reward.NONE, slayer.reward());
    }

    private static final String TAB = "\"tabs\": { \"alina:t\": { \"title\": \"T\", \"icon\": { \"id\": \"minecraft:paper\" } },"
            + " \"alina:u\": { \"title\": \"U\", \"icon\": { \"id\": \"minecraft:paper\" } } }";

    private static String achievement(String id, String fields) {
        return "\"" + id + "\": { \"tab\": \"alina:t\", \"title\": \"A\", \"description\": \"D\","
                + " \"icon\": { \"id\": \"minecraft:paper\" }, " + fields + " }";
    }

    private static final String KILLS =
            "\"criteria\": { \"kills\": { \"trigger\": \"minecraft:player_killed_entity\" } }";

    private static List<String> problems(String... achievements) {
        return achievements("{ " + TAB + ", \"achievements\": { " + String.join(", ", achievements) + " } }").problems();
    }

    @Test
    void tiersNeedExactlyOneCriterion() {
        List<String> problems = problems(achievement("alina:a", "\"criteria\": {"
                + " \"kills\": { \"trigger\": \"minecraft:player_killed_entity\" },"
                + " \"cures\": { \"trigger\": \"minecraft:cured_zombie_villager\" } }, \"tiers\": [ { \"count\": 5 } ]"));
        assertEquals(List.of("extension alina:achievements, achievement alina:a: tiers need exactly one criterion; this "
                + "achievement has 2"), problems);
    }

    @Test
    void tierCountsMustRise() {
        List<String> problems = problems(achievement("alina:a", KILLS + ", \"tiers\": [ { \"count\": 5 }, { \"count\": 5 } ]"));
        assertEquals(List.of("extension alina:achievements, achievement alina:a, tier 2: count must be above the "
                + "previous tier's count"), problems);
    }

    @Test
    void aStateTriggerCannotBeTiered() {
        List<String> problems = problems(achievement("alina:a",
                "\"criteria\": { \"has\": { \"trigger\": \"minecraft:inventory_changed\" } }, \"tiers\": [ { \"count\": 5 } ]"));
        assertEquals(List.of("extension alina:achievements, achievement alina:a: trigger minecraft:inventory_changed "
                + "fires repeatedly for one state, so it cannot be counted by tiers"), problems);
    }

    @Test
    void aTieredCriterionHasNoCountOfItsOwn() {
        List<String> problems = problems(achievement("alina:a",
                "\"criteria\": { \"kills\": { \"trigger\": \"minecraft:player_killed_entity\", \"count\": 3 } },"
                        + " \"tiers\": [ { \"count\": 5 } ]"));
        assertEquals(List.of("extension alina:achievements, achievement alina:a, criterion kills: count is set by the "
                + "tiers of this achievement, not here"), problems);
    }

    @Test
    void requirementsNameCriteriaAndCoverThemAll() {
        List<String> problems = problems(achievement("alina:a", "\"criteria\": {"
                + " \"kills\": { \"trigger\": \"minecraft:player_killed_entity\" },"
                + " \"cures\": { \"trigger\": \"minecraft:cured_zombie_villager\" } },"
                + " \"requirements\": [ [ \"kills\", \"trades\" ] ]"));
        assertEquals(List.of(
                "extension alina:achievements, achievement alina:a: requirements group 1 names 'trades', which is not a "
                        + "criterion of this achievement",
                "extension alina:achievements, achievement alina:a: criterion 'cures' is not in any requirements group"),
                problems);
    }

    @Test
    void theTabMustExist() {
        List<String> problems = problems(achievement("alina:a", KILLS).replace("\"alina:t\"", "\"alina:missing\""));
        assertEquals(List.of("extension alina:achievements, achievement alina:a: tab 'alina:missing' is not a tab in "
                + "this extension"), problems);
    }

    @Test
    void aParentIsInTheSameTab() {
        List<String> problems = problems(
                achievement("alina:a", KILLS),
                achievement("alina:b", KILLS + ", \"parent\": \"alina:a\"").replace("\"alina:t\"", "\"alina:u\""));
        assertEquals(List.of("extension alina:achievements, achievement alina:b: parent 'alina:a' is in tab alina:t, "
                + "not alina:u"), problems);
    }

    @Test
    void aHiddenAchievementCannotBeAParent() {
        List<String> problems = problems(
                achievement("alina:a", KILLS + ", \"hidden\": true"),
                achievement("alina:b", KILLS + ", \"parent\": \"alina:a\""));
        assertEquals(List.of("extension alina:achievements, achievement alina:b: parent 'alina:a' is hidden, and a hidden "
                + "achievement cannot be a parent"), problems);
    }

    /**
     * A hidden parent with its own problem is still a hidden parent: the child's problem is reported in the same pass,
     * not on a second validation after the parent's problem is fixed.
     */
    @Test
    void aHiddenParentThatFailsToParseIsStillReportedForItsChild() {
        List<String> problems = problems(
                "\"alina:a\": { \"tab\": \"alina:t\", \"title\": \"A\", \"description\": \"D\","
                        + " \"icon\": { \"id\": \"minecraft:no_such_item\" }, \"hidden\": true, " + KILLS + " }",
                achievement("alina:b", KILLS + ", \"parent\": \"alina:a\""));
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).startsWith("extension alina:achievements, achievement alina:a"), problems.toString());
        assertEquals("extension alina:achievements, achievement alina:b: parent 'alina:a' is hidden, and a hidden "
                + "achievement cannot be a parent", problems.get(1));
    }

    @Test
    void aHiddenAchievementMayBeAChild() {
        assertEquals(List.of(), problems(
                achievement("alina:a", KILLS),
                achievement("alina:b", KILLS + ", \"parent\": \"alina:a\", \"hidden\": true")));
    }

    @Test
    void parentsDoNotLoop() {
        List<String> problems = problems(
                achievement("alina:a", KILLS + ", \"parent\": \"alina:b\""),
                achievement("alina:b", KILLS + ", \"parent\": \"alina:a\""));
        assertEquals(List.of(
                "extension alina:achievements, achievement alina:a: parent chain loops back to itself",
                "extension alina:achievements, achievement alina:b: parent chain loops back to itself"), problems);
    }

    @Test
    void anAchievementNeedsACriterion() {
        assertEquals(List.of("extension alina:achievements, achievement alina:a: criteria must name at least one "
                + "criterion"), problems(achievement("alina:a", "\"frame\": \"goal\"")));
    }

    @Test
    void aTieredCriterionWithACountStillReportsItsTrigger() {
        List<String> problems = problems(achievement("alina:a",
                "\"criteria\": { \"kills\": { \"trigger\": \"minecraft:no_such_trigger\", \"count\": 3 } },"
                        + " \"tiers\": [ { \"count\": 5 } ]"));
        assertEquals(List.of(
                "extension alina:achievements, achievement alina:a, criterion kills: count is set by the tiers of this "
                        + "achievement, not here",
                "extension alina:achievements, achievement alina:a, criterion kills: trigger minecraft:no_such_trigger is "
                        + "not a registered criterion trigger"), problems);
    }

    @Test
    void aBackgroundWrittenAsAFullTexturePathIsAProblem() {
        List<String> problems = achievements("{ \"tabs\": { \"alina:t\": { \"title\": \"T\", \"icon\": \"minecraft:paper\","
                + " \"background\": \"minecraft:textures/block/spruce_planks.png\" } } }").problems();
        assertEquals(List.of("extension alina:achievements, tab alina:t: background is written without textures/ and "
                + ".png, as in vanilla: for minecraft:textures/block/spruce_planks.png write minecraft:block/spruce_planks"),
                problems);
    }

    @Test
    void anIconCanBeAnItemId() {
        AchievementsContent content = achievements("{ \"tabs\": { \"alina:t\": { \"title\": \"T\", \"icon\": "
                + "\"minecraft:paper\" } } }").orThrow();
        assertEquals("minecraft:paper",
                content.tabs().get(id("alina:t")).icon().item().unwrapKey().orElseThrow().identifier().toString());
    }

    @Test
    void anAchievementEarnedCriterionNamesAnAchievementOfTheExtension() {
        String earned = "\"criteria\": { \"earned\": { \"trigger\": \"alina:achievement_earned\","
                + " \"conditions\": { \"achievement\": \"alina:%s\" } } }";
        assertEquals(List.of(), problems(achievement("alina:a", KILLS),
                achievement("alina:b", String.format(earned, "a"))));
        assertEquals(List.of("extension alina:achievements, achievement alina:b, criterion earned: conditions name "
                + "achievement 'alina:z', which is not an achievement in this extension"),
                problems(achievement("alina:a", KILLS), achievement("alina:b", String.format(earned, "z"))));
    }

    @Test
    void unknownKeysAreProblems() {
        assertEquals(List.of("extension alina:achievements, achievement alina:a: unknown key 'parnet'"),
                problems(achievement("alina:a", KILLS + ", \"parnet\": \"alina:b\"")));
    }
}
