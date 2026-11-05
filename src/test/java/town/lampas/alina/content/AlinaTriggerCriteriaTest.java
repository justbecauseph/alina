package town.lampas.alina.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static town.lampas.alina.content.ContentFixtures.achievements;

import java.util.List;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import town.lampas.alina.criteria.ReputationChangedTrigger;

/**
 * Alina's own triggers as achievement criteria (ALINA-PLAN §6.2, §6.3): they parse with their conditions, an event
 * trigger may be counted, and a misspelt condition key is refused by the strict parse.
 */
class AlinaTriggerCriteriaTest {
    private static final Identifier ACHIEVEMENT = Identifier.parse("alina:a");

    @BeforeAll
    static void boot() {
        ContentFixtures.boot();
    }

    /** One achievement on the tab {@code alina:t}, with the given criteria object written as JSON members. */
    private static String achievement(String criteria) {
        return "{ \"tabs\": { \"alina:t\": { \"title\": \"T\", \"icon\": { \"id\": \"minecraft:paper\" } } },"
                + " \"achievements\": { \"alina:a\": { \"tab\": \"alina:t\", \"title\": \"A\", \"description\": \"D\","
                + " \"icon\": { \"id\": \"minecraft:paper\" }, \"criteria\": { " + criteria + " } } } }";
    }

    @Test
    void aCountedAlinaEventTriggerIsAccepted() {
        AchievementsContent content = achievements(achievement("\"deliveries\": { \"trigger\": "
                + "\"alina:commission_completed\", \"conditions\": {}, \"count\": 5 }")).orThrow();
        assertEquals(5, content.achievements().get(ACHIEVEMENT).criteria().get("deliveries").count());
    }

    @Test
    void reputationChangedTakesItsBounds() {
        AchievementsContent content = achievements(achievement("\"trusted\": { \"trigger\": "
                + "\"alina:reputation_changed\", \"conditions\": { \"reputation\": { \"min\": 10 } } }")).orThrow();
        CriterionSpec spec = content.achievements().get(ACHIEVEMENT).criteria().get("trusted");
        assertEquals(Identifier.parse("alina:reputation_changed"), spec.trigger());
        CriterionTriggerInstance instance = spec.instance();
        ReputationChangedTrigger.TriggerInstance reputation = (ReputationChangedTrigger.TriggerInstance) instance;
        assertTrue(reputation.reputation().orElseThrow().matches(10));
        assertFalse(reputation.reputation().orElseThrow().matches(9));
    }

    @Test
    void aMisspeltConditionKeyOnAnAlinaTriggerIsRefused() {
        List<String> problems = achievements(achievement("\"deliveries\": { \"trigger\": "
                + "\"alina:commission_completed\", \"conditions\": { \"comission\": \"alina:guild/horn\" } }"))
                .problems();
        assertEquals(List.of("extension alina:achievements, achievement alina:a, criterion deliveries: conditions key "
                + "'comission' has no effect: it is not a key at that place, or only repeats its default; remove it"),
                problems);
    }

    @Test
    void aCategoryOutsideAToFIsRefused() {
        List<String> problems = achievements(achievement("\"deliveries\": { \"trigger\": "
                + "\"alina:commission_completed\", \"conditions\": { \"category\": \"G\" } }")).problems();
        assertEquals(List.of("extension alina:achievements, achievement alina:a, criterion deliveries: conditions "
                + "category must be one of A to F, not 'G'"), problems);
    }
}
