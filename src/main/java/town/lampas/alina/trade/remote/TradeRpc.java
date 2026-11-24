package town.lampas.alina.trade.remote;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.jsonrpc.IncomingRpcMethod;
import net.minecraft.server.jsonrpc.api.Schema;
import net.minecraft.util.ExtraCodecs;
import org.jspecify.annotations.Nullable;
import town.lampas.alina.Alina;

/**
 * Registers the read-only {@code alina:trade/*} methods on the Minecraft management protocol (JSON-RPC). Every method
 * decodes its param as any JSON so malformed fields answer {@code invalid} without failing vanilla's decode, and runs
 * off the server thread ({@code notOnMainThread()}). Before the server has started they answer {@code unavailable}.
 */
public final class TradeRpc {
    public static final Identifier MARKET = Alina.id("trade/market");
    public static final Identifier SUMMARY = Alina.id("trade/summary");
    public static final Identifier OPERATIONS = Alina.id("trade/operations");
    public static final List<Identifier> ALL = List.of(MARKET, SUMMARY, OPERATIONS);

    private static final Codec<JsonElement> ANY = ExtraCodecs.JSON;
    private static final Schema<Long> LONG_SCHEMA = Schema.ofType("integer", Codec.LONG);
    private static final Schema<JsonElement> ANSWER = object()
            .withField("status", Schema.STRING_SCHEMA)
            .withField("message", Schema.STRING_SCHEMA);
    private static final Schema<JsonElement> MARKET_SCHEMA = object()
            .withField("live_points", LONG_SCHEMA)
            .withField("live_aur", LONG_SCHEMA)
            .withField("rate_hundredths", LONG_SCHEMA)
            .withField("seed_points", LONG_SCHEMA)
            .withField("seed_aur", LONG_SCHEMA)
            .withField("base_rate_hundredths", LONG_SCHEMA)
            .withField("restocked_day", Schema.STRING_SCHEMA)
            .withField("restock_percent", Schema.INT_SCHEMA)
            .withField("max_trade_percent", Schema.INT_SCHEMA)
            .withField("quote_tolerance_percent", Schema.INT_SCHEMA)
            .withField("requests", LONG_SCHEMA)
            .withField("moved_accepted", LONG_SCHEMA)
            .withField("stale_refused", LONG_SCHEMA);
    private static final Schema<JsonElement> TICK_SCHEMA = object()
            .withField("id", LONG_SCHEMA)
            .withField("points", LONG_SCHEMA)
            .withField("aur", LONG_SCHEMA)
            .withField("rate_hundredths", LONG_SCHEMA)
            .withField("at", Schema.STRING_SCHEMA);
    private static final Schema<JsonElement> DAILY_SCHEMA = object()
            .withField("trade_day", Schema.STRING_SCHEMA)
            .withField("trade_count", Schema.INT_SCHEMA)
            .withField("unique_traders", Schema.INT_SCHEMA)
            .withField("aur_minted_items", LONG_SCHEMA)
            .withField("aur_minted_xp", LONG_SCHEMA)
            .withField("aur_sunk_offers", LONG_SCHEMA)
            .withField("aur_sunk_xp", LONG_SCHEMA)
            .withField("xp_points_sold", LONG_SCHEMA)
            .withField("xp_points_bought", LONG_SCHEMA)
            .withField("revision_ids", LONG_SCHEMA.asArray());
    private static final Schema<JsonElement> BUCKET_SCHEMA = object()
            .withField("bucket_start", Schema.STRING_SCHEMA)
            .withField("trade_count", Schema.INT_SCHEMA)
            .withField("unique_traders", Schema.INT_SCHEMA)
            .withField("aur_minted_items", LONG_SCHEMA)
            .withField("aur_minted_xp", LONG_SCHEMA)
            .withField("aur_sunk_offers", LONG_SCHEMA)
            .withField("aur_sunk_xp", LONG_SCHEMA)
            .withField("xp_points_sold", LONG_SCHEMA)
            .withField("xp_points_bought", LONG_SCHEMA)
            .withField("revision_ids", LONG_SCHEMA.asArray());
    private static final Schema<JsonElement> SHOP_SCHEMA = object()
            .withField("shop_id", Schema.STRING_SCHEMA)
            .withField("daily_budget", Schema.INT_SCHEMA)
            .withField("trade_count", Schema.INT_SCHEMA)
            .withField("unique_traders", Schema.INT_SCHEMA)
            .withField("aur_minted", LONG_SCHEMA)
            .withField("aur_sunk", LONG_SCHEMA)
            .withField("xp_points_sold", LONG_SCHEMA)
            .withField("xp_points_bought", LONG_SCHEMA)
            .withField("player_days_active", Schema.INT_SCHEMA)
            .withField("player_days_near_cap", Schema.INT_SCHEMA)
            .withField("player_days_at_cap", Schema.INT_SCHEMA)
            .withField("avg_budget_used_bps", Schema.INT_SCHEMA)
            .withField("max_player_day_aur_paid", Schema.INT_SCHEMA);
    private static final Schema<JsonElement> ITEM_FLOW_SCHEMA = object()
            .withField("item", Schema.STRING_SCHEMA)
            .withField("count", LONG_SCHEMA)
            .withField("aur", LONG_SCHEMA)
            .withField("trade_count", Schema.INT_SCHEMA);
    private static final Schema<JsonElement> OFFER_FLOW_SCHEMA = object()
            .withField("shop_id", Schema.STRING_SCHEMA)
            .withField("offer_id", Schema.STRING_SCHEMA)
            .withField("item", Schema.STRING_SCHEMA)
            .withField("count", LONG_SCHEMA)
            .withField("aur", LONG_SCHEMA)
            .withField("trade_count", Schema.INT_SCHEMA);
    private static final Schema<JsonElement> CONCENTRATION_SCHEMA = object()
            .withField("total_aur_minted", LONG_SCHEMA)
            .withField("unique_earners", Schema.INT_SCHEMA)
            .withField("top_5_aur_minted", LONG_SCHEMA)
            .withField("top_10_pct_aur_minted", LONG_SCHEMA);
    private static final Schema<JsonElement> HEALTH_SCHEMA = object()
            .withField("recorded_count", Schema.INT_SCHEMA)
            .withField("reconcile_count", Schema.INT_SCHEMA);
    private static final Schema<JsonElement> PURCHASE_SCHEMA = object()
            .withField("offer_id", Schema.STRING_SCHEMA)
            .withField("period", Schema.STRING_SCHEMA)
            .withField("count", Schema.INT_SCHEMA);
    private static final Schema<JsonElement> OPERATION_SCHEMA = object()
            .withField("operation_id", Schema.UUID_SCHEMA)
            .withField("character_id", Schema.UUID_SCHEMA)
            .withField("shop_id", Schema.STRING_SCHEMA)
            .withField("revision_id", LONG_SCHEMA)
            .withField("world_id", Schema.STRING_SCHEMA)
            .withField("step", Schema.STRING_SCHEMA)
            .withField("created_at", Schema.STRING_SCHEMA)
            .withField("updated_at", Schema.STRING_SCHEMA)
            .withField("note", Schema.STRING_SCHEMA)
            .withField("trade_day", Schema.STRING_SCHEMA)
            .withField("aur_paid", Schema.INT_SCHEMA)
            .withField("market_points", LONG_SCHEMA)
            .withField("market_aur", LONG_SCHEMA)
            .withField("purchases", PURCHASE_SCHEMA.asArray())
            .withField("aur_spent", LONG_SCHEMA)
            .withField("xp_sold", LONG_SCHEMA)
            .withField("xp_bought", LONG_SCHEMA)
            .withField("sold", object().asArray())
            .withField("bought", object().asArray());

    private TradeRpc() {}

    /**
     * Registers all three read-only trade management methods. Call during mod initialisation. {@code methods} provides
     * the running server's handlers, or null before the server starts / after it stops.
     */
    public static void register(Supplier<@Nullable TradeMethods> methods) {
        register(MARKET, "Alina: live experience market reserves, configuration and ticks since after_id",
                methods, TradeMethods::market,
                object().withField("after_id", LONG_SCHEMA).withField("limit", Schema.INT_SCHEMA),
                ANSWER.withField("board_day", Schema.STRING_SCHEMA)
                        .withField("configured", Schema.BOOL_SCHEMA)
                        .withField("loaded", Schema.BOOL_SCHEMA)
                        .withField("market", MARKET_SCHEMA)
                        .withField("ticks", TICK_SCHEMA.asArray()));
        register(SUMMARY, "Alina: read-only trade economy summary over a window of board days or instants",
                methods, TradeMethods::summary,
                object().withField("from_day", Schema.STRING_SCHEMA)
                        .withField("to_day", Schema.STRING_SCHEMA)
                        .withField("since", Schema.STRING_SCHEMA)
                        .withField("until", Schema.STRING_SCHEMA)
                        .withField("bucket_minutes", Schema.INT_SCHEMA)
                        .withField("top_limit", Schema.INT_SCHEMA),
                ANSWER.withField("board_day", Schema.STRING_SCHEMA)
                        .withField("from_day", Schema.STRING_SCHEMA)
                        .withField("to_day", Schema.STRING_SCHEMA)
                        .withField("since", Schema.STRING_SCHEMA)
                        .withField("until", Schema.STRING_SCHEMA)
                        .withField("bucket_minutes", Schema.INT_SCHEMA)
                        .withField("daily", DAILY_SCHEMA.asArray())
                        .withField("buckets", BUCKET_SCHEMA.asArray())
                        .withField("shops", SHOP_SCHEMA.asArray())
                        .withField("top_sold_items", ITEM_FLOW_SCHEMA.asArray())
                        .withField("top_bought_offers", OFFER_FLOW_SCHEMA.asArray())
                        .withField("concentration", CONCENTRATION_SCHEMA)
                        .withField("health", HEALTH_SCHEMA));
        register(OPERATIONS, "Alina: recent trade operations, newest first, with keyset cursor pagination",
                methods, TradeMethods::operations,
                object().withField("shop", Schema.STRING_SCHEMA)
                        .withField("player", Schema.UUID_SCHEMA)
                        .withField("steps", Schema.STRING_SCHEMA.asArray())
                        .withField("before", Schema.STRING_SCHEMA)
                        .withField("limit", Schema.INT_SCHEMA),
                ANSWER.withField("operations", OPERATION_SCHEMA.asArray())
                        .withField("next", Schema.STRING_SCHEMA));
    }

    private static void register(
            Identifier id,
            String description,
            Supplier<@Nullable TradeMethods> methods,
            BiFunction<TradeMethods, @Nullable JsonElement, JsonObject> handler,
            Schema<JsonElement> params,
            Schema<JsonElement> answer) {
        IncomingRpcMethod<JsonElement, JsonElement> built = IncomingRpcMethod
                .<JsonElement, JsonElement>method((api, param, client) -> answer(methods.get(), handler, param))
                .description(description)
                .param("params", params)
                .response("result", answer)
                .notOnMainThread()
                .build();
        Registry.register(BuiltInRegistries.INCOMING_RPC_METHOD, id, built);
    }

    private static JsonElement answer(
            @Nullable TradeMethods methods,
            BiFunction<TradeMethods, @Nullable JsonElement, JsonObject> handler,
            @Nullable JsonElement param) {
        if (methods == null) {
            JsonObject answer = new JsonObject();
            answer.addProperty("status", "unavailable");
            answer.addProperty("message", "The server has not started");
            return answer;
        }
        return handler.apply(methods, param);
    }

    private static Schema<JsonElement> object() {
        return Schema.record(ANY);
    }
}
