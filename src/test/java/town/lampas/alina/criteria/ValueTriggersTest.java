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
 * The four triggers whose conditions are a bound or a single value (ALINA-PLAN §6.3): {@code reputation_changed},
 * {@code volume_entered}, {@code achievement_earned} and {@code manual}. Each condition is optional, so {@code {}}
 * matches every event. These tests check the codecs and the match rules; the fire methods run in the GameTests.
 */
class ValueTriggersTest {
    private static final Identifier FIRST = Identifier.parse("alina:guild/first_commission");
    private static final Identifier SLAYER = Identifier.parse("alina:guild/zombie_slayer");
    private static final Identifier HIGH_DIVE = Identifier.parse("alina:guild/high_dive");
    private static final Identifier DIVE_BASIN = Identifier.parse("alina:guild/dive_basin");

    @BeforeAll
    static void boot() {
        TestBootstrap.boot();
    }

    private static <T> T parse(Codec<T> codec, String json) {
        return codec.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    /** Encodes the value and parses the encoding again: the result must equal the value. */
    private static <T> void assertRoundTrip(Codec<T> codec, T value) {
        JsonElement encoded = codec.encodeStart(JsonOps.INSTANCE, value).getOrThrow();
        assertEquals(value, codec.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }

    @Test
    void emptyConditionsMatchEveryEvent() {
        assertTrue(parse(ReputationChangedTrigger.TriggerInstance.CODEC, "{}").matches(-5));
        assertTrue(parse(VolumeEnteredTrigger.TriggerInstance.CODEC, "{}").matches(HIGH_DIVE));
        assertTrue(parse(AchievementEarnedTrigger.TriggerInstance.CODEC, "{}").matches(SLAYER, 3));
        assertTrue(parse(ManualTrigger.TriggerInstance.CODEC, "{}").matches("anything"));
    }

    @Test
    void reputationBoundsIncludeTheirEnds() {
        ReputationChangedTrigger.TriggerInstance bounded = parse(ReputationChangedTrigger.TriggerInstance.CODEC,
                "{ \"reputation\": { \"min\": 10, \"max\": 20 } }");
        assertTrue(bounded.matches(10));
        assertTrue(bounded.matches(20));
        assertFalse(bounded.matches(9));
        assertFalse(bounded.matches(21));
    }

    @Test
    void achievementAndTierMustBothMatch() {
        AchievementEarnedTrigger.TriggerInstance instance = parse(AchievementEarnedTrigger.TriggerInstance.CODEC,
                "{ \"achievement\": \"alina:guild/first_commission\", \"tier\": { \"min\": 2 } }");
        assertTrue(instance.matches(FIRST, 2));
        assertTrue(instance.matches(FIRST, 3));
        assertFalse(instance.matches(FIRST, 1), "a tier below the bound");
        assertFalse(instance.matches(SLAYER, 2), "another achievement");
    }

    @Test
    void volumeAndKeyMatchExactly() {
        VolumeEnteredTrigger.TriggerInstance volume = parse(VolumeEnteredTrigger.TriggerInstance.CODEC,
                "{ \"volume\": \"alina:guild/high_dive\" }");
        assertTrue(volume.matches(HIGH_DIVE));
        assertFalse(volume.matches(DIVE_BASIN));

        ManualTrigger.TriggerInstance key =
                parse(ManualTrigger.TriggerInstance.CODEC, "{ \"key\": \"met_receptionist\" }");
        assertTrue(key.matches("met_receptionist"));
        assertFalse(key.matches("met_receptionist_later"));
        assertFalse(key.matches(""));
    }

    @Test
    void theConditionsRoundTrip() {
        assertRoundTrip(ReputationChangedTrigger.TriggerInstance.CODEC,
                parse(ReputationChangedTrigger.TriggerInstance.CODEC, "{ \"reputation\": { \"min\": 10 } }"));
        assertRoundTrip(VolumeEnteredTrigger.TriggerInstance.CODEC,
                parse(VolumeEnteredTrigger.TriggerInstance.CODEC, "{ \"volume\": \"alina:guild/high_dive\" }"));
        assertRoundTrip(AchievementEarnedTrigger.TriggerInstance.CODEC,
                parse(AchievementEarnedTrigger.TriggerInstance.CODEC,
                        "{ \"achievement\": \"alina:guild/first_commission\", \"tier\": { \"min\": 2 } }"));
        assertRoundTrip(ManualTrigger.TriggerInstance.CODEC,
                parse(ManualTrigger.TriggerInstance.CODEC, "{ \"key\": \"met_receptionist\" }"));
    }

    @Test
    void conditionsAreReadAsWritten() {
        Optional<Identifier> volume = parse(VolumeEnteredTrigger.TriggerInstance.CODEC,
                "{ \"volume\": \"alina:guild/high_dive\" }").volume();
        assertEquals(Optional.of(HIGH_DIVE), volume);
        assertEquals(Optional.of("met_receptionist"), parse(ManualTrigger.TriggerInstance.CODEC,
                "{ \"key\": \"met_receptionist\" }").key());
    }
}
