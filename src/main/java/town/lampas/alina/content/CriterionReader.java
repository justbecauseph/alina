package town.lampas.alina.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.advancements.triggers.CriterionTrigger;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/**
 * Reads a criterion as content writes it: {@code trigger}, {@code conditions} (vanilla's condition JSON for that
 * trigger, default none) and {@code count}. The conditions parse with the trigger's own codec, so they are exactly
 * vanilla's, except that keys the codec would ignore are refused, and on a running server they are validated as
 * vanilla validates an advancement's criteria.
 */
final class CriterionReader {
    /**
     * Vanilla triggers that fire once per real event, so a count of them means something (ALINA-PLAN §6.3). State
     * triggers such as {@code inventory_changed} and {@code location} fire again and again for one state, and are
     * allowed only with count 1. Every trigger in the {@code alina} namespace is allowed too.
     */
    static final Set<Identifier> COUNTED_VANILLA_TRIGGERS = Stream.of(
                    "player_killed_entity", "entity_killed_player", "cured_zombie_villager", "fishing_rod_hooked",
                    "bred_animals", "brewed_potion", "consume_item", "enchanted_item", "filled_bucket",
                    "recipe_crafted", "summoned_entity", "tame_animal", "villager_trade", "used_totem",
                    "channeled_lightning", "lightning_strike")
            .map(Identifier::withDefaultNamespace)
            .collect(Collectors.toUnmodifiableSet());

    private CriterionReader() {}

    static boolean countable(Identifier trigger) {
        return trigger.getNamespace().equals("alina") || COUNTED_VANILLA_TRIGGERS.contains(trigger);
    }

    /**
     * The criterion in {@code section}, or empty after recording its problems. Every problem in the criterion is
     * recorded, not only the first. {@code countKey} is null where the count is set elsewhere (tiers).
     */
    static Optional<CriterionSpec> read(Section section, String countKey) {
        Optional<Identifier> triggerId = section.required("trigger", Json::identifier);
        Optional<Integer> parsedCount = countKey == null || !section.has(countKey)
                ? Optional.of(1)
                : section.optional(countKey, Json.integerAtLeast(1));
        boolean countValid = parsedCount.isPresent();
        int count = parsedCount.orElse(1);
        if (triggerId.isEmpty()) {
            return Optional.empty();
        }
        Optional<CriterionTrigger<?>> trigger = BuiltInRegistries.TRIGGER_TYPES.getOptional(triggerId.get());
        if (trigger.isEmpty()) {
            section.add("trigger " + triggerId.get() + " is not a registered criterion trigger");
            return Optional.empty();
        }
        if (!(trigger.get() instanceof SimpleCriterionTrigger<?>)) {
            section.add("trigger " + triggerId.get() + " cannot be watched: it never fires for a player");
            return Optional.empty();
        }
        boolean countAllowed = count <= 1 || countable(triggerId.get());
        if (!countAllowed) {
            section.add("trigger " + triggerId.get() + " fires repeatedly for one state, so it can only be used with "
                    + countKey + " 1");
        }
        Optional<CriterionTriggerInstance> instance = conditions(section, trigger.get());
        return countValid && countAllowed
                ? instance.map(parsed -> new CriterionSpec(triggerId.get(), parsed, count))
                : Optional.empty();
    }

    /** Parses {@code conditions} (none means {@code {}}) strictly, then validates it on a running server. */
    private static Optional<CriterionTriggerInstance> conditions(Section section, CriterionTrigger<?> trigger) {
        JsonElement conditions = section.has("conditions") ? section.raw("conditions") : new JsonObject();
        @SuppressWarnings("unchecked")
        Codec<CriterionTriggerInstance> codec = (Codec<CriterionTriggerInstance>) (Codec<?>) trigger.codec();
        DataResult<CriterionTriggerInstance> parsed;
        try {
            parsed = StrictCodec.parse(codec, section.context().ops(), conditions);
        } catch (RuntimeException e) {
            section.add("conditions could not be parsed: " + e.getMessage());
            return Optional.empty();
        }
        parsed.error().ifPresent(error -> section.add("conditions " + error.message()));
        if (parsed.result().isEmpty()) {
            return Optional.empty();
        }
        var problems = ServerLookups.validate(parsed.result().get());
        problems.forEach(problem -> section.add("conditions: " + problem));
        return problems.isEmpty() ? parsed.result() : Optional.empty();
    }
}
