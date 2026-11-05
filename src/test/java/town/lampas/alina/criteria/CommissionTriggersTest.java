package town.lampas.alina.criteria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import town.lampas.alina.TestBootstrap;

/**
 * The two commission triggers (ALINA-PLAN §6.3). Their conditions are {@code commission} (an id), {@code category}
 * (A to F) and {@code kind} ({@code repeatable} or {@code feat}), each optional. These tests check the codecs and the
 * match rules; the fire methods run in the GameTests.
 */
class CommissionTriggersTest {
    private static final Identifier HORN = Identifier.parse("alina:guild/b3_screaming_horn");
    private static final Identifier LONGSHOT = Identifier.parse("alina:guild/e1_longshot");
    private static final String FULL = """
            { "commission": "alina:guild/b3_screaming_horn", "category": "B", "kind": "repeatable" }
            """;

    @BeforeAll
    static void boot() {
        TestBootstrap.boot();
    }

    private static <T> T parse(Codec<T> codec, String json) {
        return codec.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    private static boolean refused(Codec<?> codec, String json) {
        return codec.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).result().isEmpty();
    }

    /** Encodes the value and parses the encoding again: the result must equal the value. */
    private static <T> void assertRoundTrip(Codec<T> codec, T value) {
        JsonElement encoded = codec.encodeStart(JsonOps.INSTANCE, value).getOrThrow();
        assertEquals(value, codec.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }

    @Test
    void emptyConditionsMatchEveryAcceptance() {
        CommissionAcceptedTrigger.TriggerInstance any =
                parse(CommissionAcceptedTrigger.TriggerInstance.CODEC, "{}");
        assertTrue(any.matches(HORN, 'B', "repeatable"));
        assertTrue(any.matches(LONGSHOT, 'E', "feat"));
    }

    @Test
    void emptyConditionsMatchEveryTurnIn() {
        CommissionCompletedTrigger.TriggerInstance any =
                parse(CommissionCompletedTrigger.TriggerInstance.CODEC, "{}");
        assertTrue(any.matches(HORN, 'B', "repeatable"));
        assertTrue(any.matches(LONGSHOT, 'E', "feat"));
    }

    @Test
    void theAcceptedConditionsAreReadAsWritten() {
        CommissionAcceptedTrigger.TriggerInstance instance =
                parse(CommissionAcceptedTrigger.TriggerInstance.CODEC, FULL);
        assertEquals(Optional.of(HORN), instance.commission());
        assertEquals(Optional.of("B"), instance.category());
        assertEquals(Optional.of("repeatable"), instance.kind());
        assertEquals(Optional.empty(), instance.player());
    }

    @Test
    void theCompletedConditionsAreReadAsWritten() {
        CommissionCompletedTrigger.TriggerInstance instance =
                parse(CommissionCompletedTrigger.TriggerInstance.CODEC, FULL);
        assertEquals(Optional.of(HORN), instance.commission());
        assertEquals(Optional.of("B"), instance.category());
        assertEquals(Optional.of("repeatable"), instance.kind());
    }

    @Test
    void theConditionsRoundTrip() {
        assertRoundTrip(CommissionAcceptedTrigger.TriggerInstance.CODEC,
                parse(CommissionAcceptedTrigger.TriggerInstance.CODEC, FULL));
        assertRoundTrip(CommissionCompletedTrigger.TriggerInstance.CODEC,
                parse(CommissionCompletedTrigger.TriggerInstance.CODEC, FULL));
    }

    @Test
    void everyConditionMustMatchTheEvent() {
        CommissionAcceptedTrigger.TriggerInstance accepted =
                parse(CommissionAcceptedTrigger.TriggerInstance.CODEC, FULL);
        assertTrue(accepted.matches(HORN, 'B', "repeatable"));
        assertFalse(accepted.matches(LONGSHOT, 'B', "repeatable"), "another commission");
        assertFalse(accepted.matches(HORN, 'C', "repeatable"), "another category");
        assertFalse(accepted.matches(HORN, 'B', "feat"), "another kind");

        CommissionCompletedTrigger.TriggerInstance completed =
                parse(CommissionCompletedTrigger.TriggerInstance.CODEC, FULL);
        assertTrue(completed.matches(HORN, 'B', "repeatable"));
        assertFalse(completed.matches(LONGSHOT, 'B', "repeatable"), "another commission");
        assertFalse(completed.matches(HORN, 'C', "repeatable"), "another category");
        assertFalse(completed.matches(HORN, 'B', "feat"), "another kind");
    }

    /** An object whose only member is {@code key} with the string {@code value}. */
    private static String member(String key, String value) {
        return "{ \"" + key + "\": \"" + value + "\" }";
    }

    @Test
    void categoryIsOneLetterFromAToF() {
        for (String category : new String[] {"A", "F"}) {
            String json = member("category", category);
            assertFalse(refused(CommissionAcceptedTrigger.TriggerInstance.CODEC, json), "accepted: " + category);
            assertFalse(refused(CommissionCompletedTrigger.TriggerInstance.CODEC, json), "completed: " + category);
        }
        for (String category : new String[] {"G", "AB", ""}) {
            String json = member("category", category);
            assertTrue(refused(CommissionAcceptedTrigger.TriggerInstance.CODEC, json), "accepted: '" + category + "'");
            assertTrue(refused(CommissionCompletedTrigger.TriggerInstance.CODEC, json),
                    "completed: '" + category + "'");
        }
    }

    @Test
    void kindIsRepeatableOrFeat() {
        for (String kind : new String[] {"repeatable", "feat"}) {
            String json = member("kind", kind);
            assertFalse(refused(CommissionAcceptedTrigger.TriggerInstance.CODEC, json), "accepted: " + kind);
            assertFalse(refused(CommissionCompletedTrigger.TriggerInstance.CODEC, json), "completed: " + kind);
        }
        for (String kind : new String[] {"quest", "Feat", ""}) {
            String json = member("kind", kind);
            assertTrue(refused(CommissionAcceptedTrigger.TriggerInstance.CODEC, json), "accepted: '" + kind + "'");
            assertTrue(refused(CommissionCompletedTrigger.TriggerInstance.CODEC, json), "completed: '" + kind + "'");
        }
    }
}
