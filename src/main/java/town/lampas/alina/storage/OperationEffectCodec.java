package town.lampas.alina.storage;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/** The JSON an operation's effect is stored as in the {@code effect} column. Only storage reads it back. */
final class OperationEffectCodec {
    private static final Gson GSON = new Gson();
    private static final String COMMISSION_SETTLED = "commission_settled";
    private static final String ACHIEVEMENT_EARNED = "achievement_earned";
    private static final String TRADE_SETTLED = "trade_settled";

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
            case OperationEffect.TradeSettled trade -> {
                json.addProperty("type", TRADE_SETTLED);
                json.addProperty("shop_id", trade.shopId().toString());
                json.addProperty("trade_day", trade.tradeDay().toString());
                json.addProperty("aur_paid", trade.aurPaid());
                JsonArray purchases = new JsonArray();
                for (OperationEffect.TradeSettled.Purchase purchase : trade.purchases()) {
                    JsonObject bought = new JsonObject();
                    bought.addProperty("offer_id", purchase.offerId());
                    bought.addProperty("period", purchase.period());
                    bought.addProperty("count", purchase.count());
                    purchases.add(bought);
                }
                json.add("purchases", purchases);
                json.add("detail", JsonParser.parseString(trade.detailJson()));
                if (trade.marketPoints() != 0) {
                    json.addProperty("market_points", trade.marketPoints());
                }
                if (trade.marketAur() != 0) {
                    json.addProperty("market_aur", trade.marketAur());
                }
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
            case TRADE_SETTLED -> new OperationEffect.TradeSettled(
                    Identifier.parse(json.get("shop_id").getAsString()),
                    LocalDate.parse(json.get("trade_day").getAsString()),
                    json.get("aur_paid").getAsInt(),
                    purchases(json.getAsJsonArray("purchases")),
                    // Canonical, as normalised() makes it on the way in, so both backends read the same text.
                    CanonicalJson.of(GSON.toJson(json.get("detail"))),
                    optionalLong(json, "market_points"),
                    optionalLong(json, "market_aur"));
            default -> throw new IllegalStateException("unknown operation effect type " + type);
        };
    }

    private static List<OperationEffect.TradeSettled.Purchase> purchases(JsonArray array) {
        List<OperationEffect.TradeSettled.Purchase> purchases = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject bought = element.getAsJsonObject();
            purchases.add(new OperationEffect.TradeSettled.Purchase(
                    bought.get("offer_id").getAsString(),
                    bought.get("period").getAsString(),
                    bought.get("count").getAsInt()));
        }
        return purchases;
    }

    private static Optional<Instant> optionalInstant(JsonObject json, String key) {
        return json.has(key) ? Optional.of(Instant.parse(json.get(key).getAsString())) : Optional.empty();
    }

    /** The number under the key, or 0 when the key is absent. */
    private static long optionalLong(JsonObject json, String key) {
        return json.has(key) ? json.get(key).getAsLong() : 0;
    }
}
