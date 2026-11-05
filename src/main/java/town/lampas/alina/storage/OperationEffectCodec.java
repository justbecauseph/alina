package town.lampas.alina.storage;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/** The JSON an operation's effect is stored as in the {@code effect} column. Only storage reads it back. */
final class OperationEffectCodec {
    private static final Gson GSON = new Gson();
    private static final String COMMISSION_SETTLED = "commission_settled";
    private static final String ACHIEVEMENT_EARNED = "achievement_earned";

    private OperationEffectCodec() {}

    static String toJson(OperationEffect effect) {
        JsonObject json = new JsonObject();
        switch (effect) {
            case OperationEffect.CommissionSettled settled -> {
                json.addProperty("type", COMMISSION_SETTLED);
                json.addProperty("commission_id", settled.commissionId().toString());
                json.addProperty("status", StorageSupport.stored(settled.status()));
                settled.cooldownUntil().ifPresent(until -> json.addProperty("cooldown_until", until.toString()));
            }
            case OperationEffect.AchievementEarned earned -> {
                json.addProperty("type", ACHIEVEMENT_EARNED);
                json.addProperty("achievement_id", earned.achievementId().toString());
                json.addProperty("tier", earned.tier());
                earned.availableAt().ifPresent(until -> json.addProperty("available_at", until.toString()));
            }
        }
        return GSON.toJson(json);
    }

    static OperationEffect fromJson(String text) {
        JsonObject json = JsonParser.parseString(text).getAsJsonObject();
        String type = json.get("type").getAsString();
        return switch (type) {
            case COMMISSION_SETTLED -> new OperationEffect.CommissionSettled(
                    Identifier.parse(json.get("commission_id").getAsString()),
                    StorageSupport.fromStored(CommissionStatus.class, json.get("status").getAsString()),
                    optionalInstant(json, "cooldown_until"));
            case ACHIEVEMENT_EARNED -> new OperationEffect.AchievementEarned(
                    Identifier.parse(json.get("achievement_id").getAsString()),
                    json.get("tier").getAsInt(),
                    optionalInstant(json, "available_at"));
            default -> throw new IllegalStateException("unknown operation effect type " + type);
        };
    }

    private static Optional<Instant> optionalInstant(JsonObject json, String key) {
        return json.has(key) ? Optional.of(Instant.parse(json.get(key).getAsString())) : Optional.empty();
    }
}
