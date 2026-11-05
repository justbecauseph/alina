package town.lampas.alina.settlement;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import town.lampas.alina.storage.ApplyResult;
import town.lampas.alina.storage.CommissionStatus;
import town.lampas.alina.storage.OperationEffect;
import town.lampas.alina.storage.OperationKind;

/**
 * The text the escrow attachment holds (ALINA-PLAN §7): a JSON array of pending operations. Each stack is written with
 * {@link ItemStack#CODEC} through the server's registries, so it reads back with its components. Only settlement reads
 * this text.
 */
final class EscrowJson {
    private static final Gson GSON = new Gson();

    private EscrowJson() {}

    static String encode(List<PendingOperation> operations, HolderLookup.Provider registries) {
        JsonArray array = new JsonArray();
        for (PendingOperation operation : operations) {
            array.add(operationJson(operation, registries));
        }
        return GSON.toJson(array);
    }

    static List<PendingOperation> decode(String text, HolderLookup.Provider registries) {
        List<PendingOperation> operations = new ArrayList<>();
        for (JsonElement element : JsonParser.parseString(text).getAsJsonArray()) {
            operations.add(operationFrom(element.getAsJsonObject(), registries));
        }
        return List.copyOf(operations);
    }

    /** The reward as the storage record keeps it: a JSON array of the stacks. */
    static String rewardJson(List<ItemStack> stacks, HolderLookup.Provider registries) {
        return GSON.toJson(stacksJson(stacks, registries));
    }

    private static JsonObject operationJson(PendingOperation operation, HolderLookup.Provider registries) {
        JsonObject json = new JsonObject();
        json.addProperty("operation_id", operation.operationId().toString());
        json.addProperty("kind", operation.kind().name().toLowerCase(Locale.ROOT));
        json.addProperty("subject", operation.subjectId().toString());
        json.addProperty("tier", operation.tier());
        json.addProperty("revision_id", operation.revisionId());
        json.addProperty("world_id", operation.worldId().toString());
        json.addProperty("reputation", operation.reputationDelta());
        json.add("effect", effectJson(operation.effect()));
        json.addProperty("created_at", operation.createdAt().toString());
        json.add("rolled", stacksJson(operation.rolled(), registries));
        json.add("reward", stacksJson(operation.reward(), registries));
        json.add("removed", stacksJson(operation.removed(), registries));
        operation.applied().ifPresent(result -> {
            JsonObject applied = new JsonObject();
            applied.addProperty("fresh", result.fresh());
            applied.addProperty("reputation", result.reputation());
            json.add("applied", applied);
        });
        return json;
    }

    private static PendingOperation operationFrom(JsonObject json, HolderLookup.Provider registries) {
        Optional<ApplyResult> applied = json.has("applied")
                ? Optional.of(resultFrom(json.get("applied").getAsJsonObject()))
                : Optional.empty();
        return new PendingOperation(
                UUID.fromString(text(json, "operation_id")),
                OperationKind.valueOf(text(json, "kind").toUpperCase(Locale.ROOT)),
                Identifier.parse(text(json, "subject")),
                json.get("tier").getAsInt(),
                json.get("revision_id").getAsLong(),
                UUID.fromString(text(json, "world_id")),
                json.get("reputation").getAsInt(),
                effectFrom(json.get("effect").getAsJsonObject()),
                Instant.parse(text(json, "created_at")),
                stacksFrom(json.get("rolled").getAsJsonArray(), registries),
                stacksFrom(json.get("reward").getAsJsonArray(), registries),
                stacksFrom(json.get("removed").getAsJsonArray(), registries),
                applied);
    }

    private static JsonObject effectJson(OperationEffect effect) {
        JsonObject json = new JsonObject();
        switch (effect) {
            case OperationEffect.CommissionSettled settled -> {
                json.addProperty("type", "commission_settled");
                json.addProperty("commission_id", settled.commissionId().toString());
                json.addProperty("status", settled.status().name().toLowerCase(Locale.ROOT));
                settled.cooldownUntil().ifPresent(until -> json.addProperty("cooldown_until", until.toString()));
            }
            case OperationEffect.AchievementEarned earned -> {
                json.addProperty("type", "achievement_earned");
                json.addProperty("achievement_id", earned.achievementId().toString());
                json.addProperty("tier", earned.tier());
                earned.availableAt().ifPresent(at -> json.addProperty("available_at", at.toString()));
            }
        }
        return json;
    }

    private static OperationEffect effectFrom(JsonObject json) {
        String type = text(json, "type");
        return switch (type) {
            case "commission_settled" -> new OperationEffect.CommissionSettled(
                    Identifier.parse(text(json, "commission_id")),
                    CommissionStatus.valueOf(text(json, "status").toUpperCase(Locale.ROOT)),
                    optionalInstant(json, "cooldown_until"));
            case "achievement_earned" -> new OperationEffect.AchievementEarned(
                    Identifier.parse(text(json, "achievement_id")),
                    json.get("tier").getAsInt(),
                    optionalInstant(json, "available_at"));
            default -> throw new IllegalStateException("unknown effect type " + type + " in the escrow");
        };
    }

    private static ApplyResult resultFrom(JsonObject json) {
        return new ApplyResult(json.get("fresh").getAsBoolean(), json.get("reputation").getAsInt());
    }

    private static JsonArray stacksJson(List<ItemStack> stacks, HolderLookup.Provider registries) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        JsonArray array = new JsonArray();
        for (ItemStack stack : stacks) {
            array.add(ItemStack.CODEC.encodeStart(ops, stack)
                    .getOrThrow(message -> new IllegalStateException("could not write a stack: " + message)));
        }
        return array;
    }

    private static List<ItemStack> stacksFrom(JsonArray array, HolderLookup.Provider registries) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        List<ItemStack> stacks = new ArrayList<>();
        for (JsonElement element : array) {
            stacks.add(ItemStack.CODEC.parse(ops, element)
                    .getOrThrow(message -> new IllegalStateException("could not read a stack: " + message)));
        }
        return stacks;
    }

    private static String text(JsonObject json, String key) {
        return json.get(key).getAsString();
    }

    private static Optional<Instant> optionalInstant(JsonObject json, String key) {
        return json.has(key) ? Optional.of(Instant.parse(text(json, key))) : Optional.empty();
    }
}
