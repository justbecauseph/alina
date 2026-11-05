package town.lampas.alina.settlement;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import town.lampas.alina.storage.ApplyResult;
import town.lampas.alina.storage.CommissionStatus;
import town.lampas.alina.storage.OperationEffect;
import town.lampas.alina.storage.OperationKind;

/**
 * The text the escrow attachment holds (ALINA-PLAN section 7): a JSON array of pending operations. Each stack is written
 * as {@link ItemStack#CODEC} writes it through the server's registries, as NBT, and the NBT is kept as its SNBT text.
 * SNBT keeps each number's type (a long stays a long, a double stays a double), so a stack reads back as it was written.
 * The rolled stacks are stored once. The part of each still to deliver is a count per rolled stack. Only settlement
 * reads this text.
 */
final class EscrowJson {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private EscrowJson() {}

    static String encode(List<PendingOperation> operations, HolderLookup.Provider registries) {
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        JsonArray array = new JsonArray();
        for (PendingOperation operation : operations) {
            array.add(operationJson(operation, ops));
        }
        return GSON.toJson(array);
    }

    static List<PendingOperation> decode(String text, HolderLookup.Provider registries) {
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        List<PendingOperation> operations = new ArrayList<>();
        for (JsonElement element : JsonParser.parseString(text).getAsJsonArray()) {
            operations.add(operationFrom(element.getAsJsonObject(), ops));
        }
        return List.copyOf(operations);
    }

    /** The rolled reward as the storage record keeps it: a JSON array of the stacks, each as SNBT text. */
    static String rewardJson(List<ItemStack> rolled, HolderLookup.Provider registries) {
        return GSON.toJson(stackTexts(rolled, RegistryOps.create(NbtOps.INSTANCE, registries)));
    }

    private static JsonObject operationJson(PendingOperation operation, RegistryOps<Tag> ops) {
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
        json.add("rolled", stackTexts(operation.rolled(), ops));
        json.add("remaining", remainingCounts(operation.reward()));
        json.add("removed", stackTexts(operation.removed(), ops));
        operation.commissionCategory().ifPresent(category -> json.addProperty("commission_category",
                String.valueOf(category)));
        operation.commissionKind().ifPresent(kind -> json.addProperty("commission_kind", kind));
        operation.applied().ifPresent(result -> {
            JsonObject applied = new JsonObject();
            applied.addProperty("fresh", result.fresh());
            applied.addProperty("reputation", result.reputation());
            json.add("applied", applied);
        });
        return json;
    }

    private static PendingOperation operationFrom(JsonObject json, RegistryOps<Tag> ops) {
        Optional<ApplyResult> applied = json.has("applied")
                ? Optional.of(resultFrom(json.get("applied").getAsJsonObject()))
                : Optional.empty();
        List<ItemStack> rolled = stacksFrom(json.getAsJsonArray("rolled"), ops);
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
                rolled,
                rewardFrom(rolled, json.getAsJsonArray("remaining")),
                stacksFrom(json.getAsJsonArray("removed"), ops),
                applied,
                json.has("commission_category")
                        ? Optional.of(text(json, "commission_category").charAt(0))
                        : Optional.empty(),
                json.has("commission_kind") ? Optional.of(text(json, "commission_kind")) : Optional.empty());
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

    private static JsonArray stackTexts(List<ItemStack> stacks, RegistryOps<Tag> ops) {
        JsonArray array = new JsonArray();
        for (ItemStack stack : stacks) {
            Tag tag = ItemStack.CODEC.encodeStart(ops, stack)
                    .getOrThrow(message -> new IllegalStateException("could not write a stack: " + message));
            array.add(tag.toString());
        }
        return array;
    }

    private static List<ItemStack> stacksFrom(JsonArray array, RegistryOps<Tag> ops) {
        List<ItemStack> stacks = new ArrayList<>();
        for (JsonElement element : array) {
            CompoundTag tag;
            try {
                tag = TagParser.parseCompoundFully(element.getAsString());
            } catch (CommandSyntaxException failure) {
                throw new IllegalStateException("could not read a stack's NBT text", failure);
            }
            stacks.add(ItemStack.CODEC.parse(ops, tag)
                    .getOrThrow(message -> new IllegalStateException("could not read a stack: " + message)));
        }
        return stacks;
    }

    private static JsonArray remainingCounts(List<ItemStack> reward) {
        JsonArray array = new JsonArray();
        for (ItemStack stack : reward) {
            array.add(stack.getCount());
        }
        return array;
    }

    private static List<ItemStack> rewardFrom(List<ItemStack> rolled, JsonArray remaining) {
        if (remaining.size() != rolled.size()) {
            throw new IllegalStateException("the escrow has " + remaining.size() + " remaining counts for "
                    + rolled.size() + " rolled stacks");
        }
        List<ItemStack> reward = new ArrayList<>(rolled.size());
        for (int i = 0; i < rolled.size(); i++) {
            int count = remaining.get(i).getAsInt();
            if (count == 0) {
                reward.add(ItemStack.EMPTY);
            } else if (count < 0 || count > rolled.get(i).getCount()) {
                throw new IllegalStateException("the escrow's remaining count " + count + " is not part of stack " + i);
            } else {
                reward.add(rolled.get(i).copyWithCount(count));
            }
        }
        return reward;
    }

    private static String text(JsonObject json, String key) {
        return json.get(key).getAsString();
    }

    private static Optional<Instant> optionalInstant(JsonObject json, String key) {
        return json.has(key) ? Optional.of(Instant.parse(text(json, key))) : Optional.empty();
    }
}
