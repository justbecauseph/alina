package town.lampas.alina.achievement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.resources.Identifier;
import town.lampas.alina.content.AchievementsContent;
import town.lampas.alina.content.AchievementsContent.Achievement;
import town.lampas.alina.content.AlinaContent;
import town.lampas.haruhi.dialogue.api.DialogueCondition;
import town.lampas.haruhi.dialogue.api.DialogueContext;
import town.lampas.haruhi.dialogue.api.ParseContext;

/**
 * The {@code alina:achievement} dialogue condition: whether the player has earned the achievement, or a tier of it, in any
 * cycle. Its arguments are {@code achievement} (an id of the bundle's {@code alina:achievements} section) and an optional
 * {@code tier}. Without a tier it is true once the achievement has been earned, in any cycle, or an earn of it is being
 * settled. With a tier k it is true when tier k has been earned in any cycle: either the current cycle's earned tiers reach
 * k, or a cycle has been completed (the earn count is at least the number of tiers, since a complete cycle earns every
 * tier). While the player's record loads, the condition is false.
 */
final class AchievementConditions {
    private static final String TYPE = "alina:achievement";
    private static final Set<String> KEYS = Set.of("achievement", "tier");

    private AchievementConditions() {}

    /** Thrown by the parsing helpers; its message is the argument problem, without the context prefix. */
    private static final class Invalid extends RuntimeException {
        private Invalid(String message) {
            super(message, null, false, false);
        }
    }

    static DataResult<DialogueCondition> achievement(JsonObject json, ParseContext context) {
        try {
            for (String key : json.keySet()) {
                if (!KEYS.contains(key)) {
                    throw new Invalid("no argument named '" + key + "'");
                }
            }
            Identifier id = achievementId(json);
            AchievementsContent content = context.extension(AlinaContent.ACHIEVEMENTS).orElseThrow(() -> new Invalid(
                    "the bundle has no alina:achievements section, so 'achievement' cannot name an achievement"));
            Achievement achievement = content.achievements().get(id);
            if (achievement == null) {
                throw new Invalid("'achievement' names '" + id + "', which is not an achievement of alina:achievements in "
                        + "this bundle");
            }
            OptionalInt tier = tierOf(json, achievement);
            int tiers = achievement.tiers().size();
            return DataResult.<DialogueCondition>success(ctx -> earned(ctx, id, tier, tiers));
        } catch (Invalid e) {
            String message = context.where() + TYPE + ": " + e.getMessage();
            return DataResult.error(() -> message);
        }
    }

    private static Identifier achievementId(JsonObject json) {
        if (!json.has("achievement") || json.get("achievement").isJsonNull()) {
            throw new Invalid("'achievement' is required");
        }
        JsonElement element = json.get("achievement");
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new Invalid("'achievement' must be an id");
        }
        Identifier id = Identifier.tryParse(element.getAsString());
        if (id == null) {
            throw new Invalid("'achievement' is not an id: " + element.getAsString());
        }
        return id;
    }

    /** The {@code tier} argument, which only a tiered achievement takes and which must be one of its tiers. */
    private static OptionalInt tierOf(JsonObject json, Achievement achievement) {
        if (!json.has("tier") || json.get("tier").isJsonNull()) {
            return OptionalInt.empty();
        }
        JsonElement element = json.get("tier");
        if (achievement.tiers().isEmpty()) {
            throw new Invalid("achievement " + achievement.id() + " has no tiers, so 'tier' cannot be given");
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new Invalid("'tier' must be an integer");
        }
        int tier;
        try {
            tier = element.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException e) {
            throw new Invalid("'tier' must be a whole number that fits in 32 bits");
        }
        int size = achievement.tiers().size();
        if (tier < 1 || tier > size) {
            throw new Invalid("'tier' must be from 1 to " + size + " for " + achievement.id() + ", not " + tier);
        }
        return OptionalInt.of(tier);
    }

    /**
     * With a tier, the current cycle reaches it or a cycle has been completed, which a complete cycle shows as an earn count of
     * at least the number of tiers. Without a tier, the achievement has been earned in any cycle.
     */
    private static boolean earned(DialogueContext context, Identifier id, OptionalInt tier, int tiers) {
        return AchievementModule.conditionView(context.player())
                .map(view -> view.achievements().get(id))
                .map(standing -> tier.isPresent()
                        ? standing.earnedTiers() >= tier.getAsInt() || standing.earnCount() >= tiers
                        : standing.everEarned())
                .orElse(false);
    }
}
