package town.lampas.alina.criteria;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.Optional;
import net.minecraft.advancements.predicates.MinMaxBounds;

/**
 * The condition codecs and match rules that Alina's triggers share (ALINA-PLAN §6.3). Every condition is optional,
 * so a condition that is absent matches every event.
 */
final class TriggerConditions {
    /** A commission's category: one letter from A to F. */
    static final Codec<String> CATEGORY = Codec.STRING.validate(category -> category.matches("[A-F]")
            ? DataResult.success(category)
            : DataResult.error(() -> "category must be one of A to F, not '" + category + "'"));

    /** A commission's kind: {@code repeatable} or {@code feat}. */
    static final Codec<String> KIND = Codec.STRING.validate(kind -> kind.equals("repeatable") || kind.equals("feat")
            ? DataResult.success(kind)
            : DataResult.error(() -> "kind must be repeatable or feat, not '" + kind + "'"));

    private TriggerConditions() {}

    /** True when the condition is absent or equal to the event's value. */
    static <T> boolean matches(Optional<T> condition, T value) {
        return condition.isEmpty() || condition.get().equals(value);
    }

    /** True when the bounds are absent or contain the event's value. */
    static boolean inBounds(Optional<MinMaxBounds.Ints> bounds, int value) {
        return bounds.isEmpty() || bounds.get().matches(value);
    }
}
