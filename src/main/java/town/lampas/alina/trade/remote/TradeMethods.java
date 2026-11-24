package town.lampas.alina.trade.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;
import town.lampas.alina.content.GuildSettings;
import town.lampas.alina.content.TradeContent;
import town.lampas.alina.guild.board.BoardDay;
import town.lampas.alina.storage.AlinaStorage;
import town.lampas.alina.storage.IndexedMarketTick;
import town.lampas.alina.storage.Operation;
import town.lampas.alina.storage.OperationCursor;
import town.lampas.alina.storage.OperationEffect;
import town.lampas.alina.storage.OperationStep;
import town.lampas.alina.storage.StoredOperation;
import town.lampas.alina.storage.TradeSummary;
import town.lampas.alina.storage.TradeSummary.BucketTotals;
import town.lampas.alina.storage.TradeSummary.Concentration;
import town.lampas.alina.storage.TradeSummary.DailyTotals;
import town.lampas.alina.storage.TradeSummary.ItemFlow;
import town.lampas.alina.storage.TradeSummary.OfferFlow;
import town.lampas.alina.storage.TradeSummary.OperationHealth;
import town.lampas.alina.storage.TradeSummary.ShopTotals;
import town.lampas.alina.trade.TradeService;
import town.lampas.alina.trade.pricing.ReserveCurve;

/**
 * Handlers behind the read-only {@code alina:trade/*} management methods for one running server. Every method runs off
 * the server thread, hops to the server thread only for quick in-memory snapshot reads (bounded by
 * {@link #READ_TIMEOUT}), and awaits storage reads off-thread.
 */
public final class TradeMethods {
    public static final int MARKET_TICKS_DEFAULT = 64;
    public static final int MARKET_TICKS_MAX = 64;
    public static final int SUMMARY_DEFAULT_OFFSET_DAYS = 13;
    public static final long SUMMARY_MAX_SPAN_DAYS = 89;
    public static final Duration SUMMARY_INSTANT_MAX_SPAN = Duration.ofDays(7);
    public static final int SUMMARY_BUCKET_MIN_MINUTES = 5;
    public static final int SUMMARY_BUCKET_MAX_MINUTES = 1440;
    public static final int SUMMARY_BUCKET_DEFAULT_MINUTES = 60;
    public static final int SUMMARY_MAX_BUCKETS = 360;
    public static final int SUMMARY_TOP_DEFAULT = 20;
    public static final int SUMMARY_TOP_MAX = 50;
    public static final int OPERATIONS_DEFAULT = 30;
    public static final int OPERATIONS_MAX = 100;
    static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    @FunctionalInterface
    interface ServerRunner {
        void run(Runnable task) throws Unavailable;
    }

    private final ServerRunner serverThread;
    private final Supplier<Optional<TradeService.MarketStatus>> marketStatus;
    private final AlinaStorage storage;
    private final Supplier<TradeContent> content;
    private final Supplier<GuildSettings> settings;
    private final Clock clock;

    public TradeMethods(
            MinecraftServer server,
            TradeService service,
            AlinaStorage storage,
            Supplier<TradeContent> content,
            Supplier<GuildSettings> settings,
            Clock clock) {
        this(task -> onServerThread(Objects.requireNonNull(server, "server"), task),
                Objects.requireNonNull(service, "service")::market,
                storage, content, settings, clock);
    }

    TradeMethods(
            ServerRunner serverThread,
            Supplier<Optional<TradeService.MarketStatus>> marketStatus,
            AlinaStorage storage,
            Supplier<TradeContent> content,
            Supplier<GuildSettings> settings,
            Clock clock) {
        this.serverThread = Objects.requireNonNull(serverThread, "serverThread");
        this.marketStatus = Objects.requireNonNull(marketStatus, "marketStatus");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.content = Objects.requireNonNull(content, "content");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** {@code alina:trade/market}: live experience market state and ticks with id &gt; {@code after_id}. */
    public JsonObject market(@Nullable JsonElement param) {
        long afterId;
        int limit;
        try {
            TradeParams params = TradeParams.of(param);
            afterId = params.optionalWhole("after_id", 0).orElse(0);
            limit = (int) Math.min(MARKET_TICKS_MAX, params.optionalWhole("limit", 1).orElse(MARKET_TICKS_DEFAULT));
        } catch (TradeParams.Invalid e) {
            return invalid(e);
        }

        MarketSnapshot snapshot;
        List<IndexedMarketTick> ticks;
        try {
            snapshot = snapshot(() -> {
                GuildSettings guildSettings = settings.get();
                LocalDate boardDay = BoardDay.dateOf(clock.instant(), guildSettings.resetTime(), guildSettings.zone());
                Optional<TradeContent.ExperienceMarket> terms = content.get().market();
                Optional<TradeService.MarketStatus> status = marketStatus.get();
                return new MarketSnapshot(boardDay, terms, status);
            });
            ticks = read(storage.loadMarketTicksSince(afterId, limit));
        } catch (Unavailable e) {
            return unavailable(e.getMessage());
        }

        JsonObject answer = ok();
        answer.addProperty("board_day", snapshot.boardDay().toString());
        answer.addProperty("configured", snapshot.terms().isPresent());
        answer.addProperty("loaded", snapshot.status().isPresent());
        if (snapshot.status().isPresent() && snapshot.terms().isPresent()) {
            answer.add("market", marketObject(snapshot.status().get(), snapshot.terms().get()));
        }
        JsonArray tickArray = new JsonArray();
        for (IndexedMarketTick tick : ticks) {
            JsonObject tickJson = new JsonObject();
            tickJson.addProperty("id", tick.id());
            tickJson.addProperty("points", tick.points());
            tickJson.addProperty("aur", tick.aur());
            if (tick.points() >= 1 && tick.points() <= ReserveCurve.MAX_RESERVE
                    && tick.aur() >= 1 && tick.aur() <= ReserveCurve.MAX_RESERVE) {
                tickJson.addProperty("rate_hundredths",
                        ReserveCurve.rateHundredths(new ReserveCurve.Reserves(tick.points(), tick.aur())));
            }
            tickJson.addProperty("at", tick.at().toString());
            tickArray.add(tickJson);
        }
        answer.add("ticks", tickArray);
        return answer;
    }

    /** {@code alina:trade/summary}: read-only trade economy summary over a window of board days or instants. */
    public JsonObject summary(@Nullable JsonElement param) {
        Optional<LocalDate> fromParam;
        Optional<LocalDate> toParam;
        Optional<Instant> sinceParam;
        Optional<Instant> untilParam;
        OptionalLong bucketParam;
        int topLimit;
        try {
            TradeParams params = TradeParams.of(param);
            fromParam = params.optionalDate("from_day");
            toParam = params.optionalDate("to_day");
            sinceParam = params.optionalInstant("since");
            untilParam = params.optionalInstant("until");
            bucketParam = params.optionalWhole("bucket_minutes", 1);
            topLimit = (int) Math.min(SUMMARY_TOP_MAX, params.optionalWhole("top_limit", 1).orElse(SUMMARY_TOP_DEFAULT));
        } catch (TradeParams.Invalid e) {
            return invalid(e);
        }

        if ((fromParam.isPresent() || toParam.isPresent())
                && (sinceParam.isPresent() || untilParam.isPresent() || bucketParam.isPresent())) {
            String field = sinceParam.isPresent() ? "since" : untilParam.isPresent() ? "until" : "bucket_minutes";
            return invalid(field, "cannot mix since/until/bucket_minutes with from_day/to_day");
        }
        if (sinceParam.isEmpty() && (untilParam.isPresent() || bucketParam.isPresent())) {
            String field = untilParam.isPresent() ? "until" : "bucket_minutes";
            return invalid(field, "since is required when until or bucket_minutes is set");
        }
        int bucketMinutes = SUMMARY_BUCKET_DEFAULT_MINUTES;
        if (bucketParam.isPresent()) {
            long raw = bucketParam.getAsLong();
            if (raw < SUMMARY_BUCKET_MIN_MINUTES || raw > SUMMARY_BUCKET_MAX_MINUTES) {
                return invalid("bucket_minutes", "bucket_minutes must be between 5 and 1440");
            }
            bucketMinutes = (int) raw;
        }

        Instant untilInstant = untilParam.orElseGet(clock::instant);
        if (sinceParam.isPresent()) {
            Instant since = sinceParam.get();
            if (since.isAfter(untilInstant)) {
                return invalid("since", "since must not be after until");
            }
            if (Duration.between(since, untilInstant).compareTo(SUMMARY_INSTANT_MAX_SPAN) > 0) {
                return invalid("since", "instant range must be at most 7 days");
            }
            long bucketSeconds = bucketMinutes * 60L;
            long bucketCount = Math.floorDiv(untilInstant.getEpochSecond(), bucketSeconds)
                    - Math.floorDiv(since.getEpochSecond(), bucketSeconds) + 1;
            if (bucketCount > SUMMARY_MAX_BUCKETS) {
                return invalid("bucket_minutes", "bucket_minutes produces too many buckets (max 360)");
            }
        }

        SummarySnapshot snapshot;
        try {
            snapshot = snapshot(() -> {
                GuildSettings guildSettings = settings.get();
                LocalDate boardDay = BoardDay.dateOf(clock.instant(), guildSettings.resetTime(), guildSettings.zone());
                Map<Identifier, Integer> shopBudgets = new HashMap<>();
                for (TradeContent.Shop shop : content.get().shops().values()) {
                    shopBudgets.put(shop.id(), shop.selling().map(TradeContent.Selling::dailyBudget).orElse(0));
                }
                LocalDate sinceBoardDay = sinceParam.map(s -> BoardDay.dateOf(s, guildSettings.resetTime(), guildSettings.zone()))
                        .orElse(null);
                LocalDate untilBoardDay = sinceParam.isPresent()
                        ? BoardDay.dateOf(untilInstant, guildSettings.resetTime(), guildSettings.zone())
                        : null;
                return new SummarySnapshot(boardDay, sinceBoardDay, untilBoardDay, Map.copyOf(shopBudgets));
            });
        } catch (Unavailable e) {
            return unavailable(e.getMessage());
        }

        LocalDate fromDay;
        LocalDate toDay;
        TradeSummary summary;
        if (sinceParam.isPresent()) {
            fromDay = Objects.requireNonNull(snapshot.sinceBoardDay());
            toDay = Objects.requireNonNull(snapshot.untilBoardDay());
            try {
                summary = read(storage.loadTradeSummaryBetween(
                        sinceParam.get(), untilInstant, bucketMinutes, fromDay, toDay, snapshot.shopBudgets(), topLimit));
            } catch (Unavailable e) {
                return unavailable(e.getMessage());
            }
        } else {
            toDay = toParam.orElse(snapshot.boardDay());
            fromDay = fromParam.orElseGet(() -> toDay.minusDays(SUMMARY_DEFAULT_OFFSET_DAYS));
            if (fromDay.isAfter(toDay)) {
                return invalid("from_day", "from_day must not be after to_day");
            }
            if (ChronoUnit.DAYS.between(fromDay, toDay) > SUMMARY_MAX_SPAN_DAYS) {
                return invalid("from_day", "date range must be at most 90 days");
            }
            try {
                summary = read(storage.loadTradeSummary(fromDay, toDay, snapshot.shopBudgets(), topLimit));
            } catch (Unavailable e) {
                return unavailable(e.getMessage());
            }
        }

        JsonObject answer = ok();
        answer.addProperty("board_day", snapshot.boardDay().toString());
        answer.addProperty("from_day", fromDay.toString());
        answer.addProperty("to_day", toDay.toString());
        if (sinceParam.isPresent()) {
            answer.addProperty("since", sinceParam.get().toString());
            answer.addProperty("until", untilInstant.toString());
            answer.addProperty("bucket_minutes", bucketMinutes);
            answer.add("buckets", bucketsArray(summary.buckets()));
        }
        answer.add("daily", dailyArray(summary.daily()));
        answer.add("shops", shopsArray(summary.shops()));
        answer.add("top_sold_items", soldArray(summary.topSoldItems()));
        answer.add("top_bought_offers", boughtArray(summary.topBoughtOffers()));
        answer.add("concentration", concentrationObject(summary.concentration()));
        answer.add("health", healthObject(summary.health()));
        return answer;
    }

    /** {@code alina:trade/operations}: recent trade operations, newest first, with keyset cursor pagination. */
    public JsonObject operations(@Nullable JsonElement param) {
        Optional<Identifier> shop;
        Optional<UUID> player;
        Set<OperationStep> steps;
        Optional<OperationCursor> before;
        int limit;
        try {
            TradeParams params = TradeParams.of(param);
            shop = params.optionalIdentifier("shop");
            player = params.optionalUuid("player");
            steps = parseSteps(params.optionalStringList("steps"));
            before = params.optionalCursor("before");
            limit = (int) Math.min(OPERATIONS_MAX, params.optionalWhole("limit", 1).orElse(OPERATIONS_DEFAULT));
        } catch (TradeParams.Invalid e) {
            return invalid(e);
        }

        List<StoredOperation> page;
        try {
            page = read(storage.listRecentTradeOperations(shop, player, steps, before, limit + 1));
        } catch (Unavailable e) {
            return unavailable(e.getMessage());
        }

        JsonObject answer = ok();
        JsonArray operations = new JsonArray();
        int count = Math.min(limit, page.size());
        for (int index = 0; index < count; index++) {
            operations.add(operationObject(page.get(index)));
        }
        answer.add("operations", operations);
        if (page.size() > limit) {
            Operation last = page.get(limit - 1).operation();
            answer.addProperty("next", last.createdAt().toString() + "|" + last.operationId().toString());
        }
        return answer;
    }

    private static Set<OperationStep> parseSteps(Optional<List<String>> raw) throws TradeParams.Invalid {
        if (raw.isEmpty()) {
            return EnumSet.allOf(OperationStep.class);
        }
        List<String> list = raw.get();
        if (list.isEmpty()) {
            throw new TradeParams.Invalid("steps", "steps must not be empty");
        }
        EnumSet<OperationStep> parsed = EnumSet.noneOf(OperationStep.class);
        for (String name : list) {
            switch (name) {
                case "recorded" -> parsed.add(OperationStep.RECORDED);
                case "settled" -> parsed.add(OperationStep.SETTLED);
                case "reconcile" -> parsed.add(OperationStep.RECONCILE);
                default -> throw new TradeParams.Invalid(
                        "steps", "steps entries must be recorded, settled or reconcile");
            }
        }
        return Set.copyOf(parsed);
    }

    private static JsonObject marketObject(TradeService.MarketStatus status, TradeContent.ExperienceMarket terms) {
        JsonObject json = new JsonObject();
        json.addProperty("live_points", status.live().points());
        json.addProperty("live_aur", status.live().aur());
        json.addProperty("rate_hundredths", ReserveCurve.rateHundredths(status.live()));
        json.addProperty("seed_points", status.seed().points());
        json.addProperty("seed_aur", status.seed().aur());
        json.addProperty("base_rate_hundredths", ReserveCurve.rateHundredths(status.seed()));
        json.addProperty("restocked_day", status.restockedDay().toString());
        json.addProperty("restock_percent", terms.restockPercent());
        json.addProperty("max_trade_percent", terms.maxTradePercent());
        json.addProperty("quote_tolerance_percent", status.quoteTolerancePercent());
        json.addProperty("requests", status.requests());
        json.addProperty("moved_accepted", status.movedAccepted());
        json.addProperty("stale_refused", status.staleRefused());
        return json;
    }

    private static JsonArray dailyArray(List<DailyTotals> daily) {
        JsonArray array = new JsonArray();
        for (DailyTotals row : daily) {
            JsonObject json = periodTotalsObject(
                    row.tradeCount(),
                    row.uniqueTraders(),
                    row.aurMintedItems(),
                    row.aurMintedXp(),
                    row.aurSunkOffers(),
                    row.aurSunkXp(),
                    row.xpPointsSold(),
                    row.xpPointsBought(),
                    row.revisionIds());
            json.addProperty("trade_day", row.tradeDay().toString());
            array.add(json);
        }
        return array;
    }

    private static JsonArray bucketsArray(List<BucketTotals> buckets) {
        JsonArray array = new JsonArray();
        for (BucketTotals row : buckets) {
            JsonObject json = periodTotalsObject(
                    row.tradeCount(),
                    row.uniqueTraders(),
                    row.aurMintedItems(),
                    row.aurMintedXp(),
                    row.aurSunkOffers(),
                    row.aurSunkXp(),
                    row.xpPointsSold(),
                    row.xpPointsBought(),
                    row.revisionIds());
            json.addProperty("bucket_start", row.bucketStart().toString());
            array.add(json);
        }
        return array;
    }

    private static JsonObject periodTotalsObject(
            int tradeCount,
            int uniqueTraders,
            long aurMintedItems,
            long aurMintedXp,
            long aurSunkOffers,
            long aurSunkXp,
            long xpPointsSold,
            long xpPointsBought,
            List<Long> revisionIds) {
        JsonObject json = new JsonObject();
        json.addProperty("trade_count", tradeCount);
        json.addProperty("unique_traders", uniqueTraders);
        json.addProperty("aur_minted_items", aurMintedItems);
        json.addProperty("aur_minted_xp", aurMintedXp);
        json.addProperty("aur_sunk_offers", aurSunkOffers);
        json.addProperty("aur_sunk_xp", aurSunkXp);
        json.addProperty("xp_points_sold", xpPointsSold);
        json.addProperty("xp_points_bought", xpPointsBought);
        JsonArray revisions = new JsonArray();
        for (long revisionId : revisionIds) {
            revisions.add(revisionId);
        }
        json.add("revision_ids", revisions);
        return json;
    }

    private static JsonArray shopsArray(List<ShopTotals> shops) {
        JsonArray array = new JsonArray();
        for (ShopTotals row : shops) {
            JsonObject json = new JsonObject();
            json.addProperty("shop_id", row.shopId().toString());
            json.addProperty("daily_budget", row.dailyBudget());
            json.addProperty("trade_count", row.tradeCount());
            json.addProperty("unique_traders", row.uniqueTraders());
            json.addProperty("aur_minted", row.aurMinted());
            json.addProperty("aur_sunk", row.aurSunk());
            json.addProperty("xp_points_sold", row.xpPointsSold());
            json.addProperty("xp_points_bought", row.xpPointsBought());
            json.addProperty("player_days_active", row.playerDaysActive());
            json.addProperty("player_days_near_cap", row.playerDaysNearCap());
            json.addProperty("player_days_at_cap", row.playerDaysAtCap());
            json.addProperty("avg_budget_used_bps", row.avgBudgetUsedBps());
            json.addProperty("max_player_day_aur_paid", row.maxPlayerDayAurPaid());
            array.add(json);
        }
        return array;
    }

    private static JsonArray soldArray(List<ItemFlow> items) {
        JsonArray array = new JsonArray();
        for (ItemFlow row : items) {
            JsonObject json = new JsonObject();
            json.addProperty("item", row.item());
            json.addProperty("count", row.count());
            json.addProperty("aur", row.aur());
            json.addProperty("trade_count", row.tradeCount());
            array.add(json);
        }
        return array;
    }

    private static JsonArray boughtArray(List<OfferFlow> offers) {
        JsonArray array = new JsonArray();
        for (OfferFlow row : offers) {
            JsonObject json = new JsonObject();
            json.addProperty("shop_id", row.shopId().toString());
            row.offerId().ifPresent(offerId -> json.addProperty("offer_id", offerId));
            json.addProperty("item", row.item());
            json.addProperty("count", row.count());
            json.addProperty("aur", row.aur());
            json.addProperty("trade_count", row.tradeCount());
            array.add(json);
        }
        return array;
    }

    private static JsonObject concentrationObject(Concentration concentration) {
        JsonObject json = new JsonObject();
        json.addProperty("total_aur_minted", concentration.totalAurMinted());
        json.addProperty("unique_earners", concentration.uniqueEarners());
        json.addProperty("top_5_aur_minted", concentration.top5AurMinted());
        json.addProperty("top_10_pct_aur_minted", concentration.top10PctAurMinted());
        return json;
    }

    private static JsonObject healthObject(OperationHealth health) {
        JsonObject json = new JsonObject();
        json.addProperty("recorded_count", health.recordedCount());
        json.addProperty("reconcile_count", health.reconcileCount());
        return json;
    }

    private static JsonObject operationObject(StoredOperation stored) {
        Operation op = stored.operation();
        JsonObject json = new JsonObject();
        json.addProperty("operation_id", op.operationId().toString());
        json.addProperty("character_id", op.characterId().toString());
        json.addProperty("shop_id", op.subjectId().toString());
        json.addProperty("revision_id", op.revisionId());
        json.addProperty("world_id", op.worldId());
        json.addProperty("step", stored.step().name().toLowerCase(Locale.ROOT));
        json.addProperty("created_at", op.createdAt().toString());
        json.addProperty("updated_at", stored.updatedAt().toString());
        stored.note().ifPresent(note -> json.addProperty("note", note));
        if (op.effect() instanceof OperationEffect.TradeSettled trade) {
            json.addProperty("trade_day", trade.tradeDay().toString());
            json.addProperty("aur_paid", trade.aurPaid());
            json.addProperty("market_points", trade.marketPoints());
            json.addProperty("market_aur", trade.marketAur());
            JsonArray purchases = new JsonArray();
            for (OperationEffect.TradeSettled.Purchase purchase : trade.purchases()) {
                JsonObject bought = new JsonObject();
                bought.addProperty("offer_id", purchase.offerId());
                bought.addProperty("period", purchase.period());
                bought.addProperty("count", purchase.count());
                purchases.add(bought);
            }
            json.add("purchases", purchases);
            JsonObject detail = JsonParser.parseString(trade.detailJson()).getAsJsonObject();
            json.addProperty("aur_spent", longField(detail, "aur_spent"));
            json.addProperty("xp_sold", longField(detail, "experience_sold"));
            json.addProperty("xp_bought", longField(detail, "experience_bought"));
            json.add("sold", arrayField(detail, "sold"));
            json.add("bought", arrayField(detail, "bought"));
        }
        return json;
    }

    private static long longField(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return 0;
        }
        return element.getAsLong();
    }

    private static JsonArray arrayField(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private <T> T snapshot(Supplier<T> supplier) throws Unavailable {
        AtomicReference<T> result = new AtomicReference<>();
        serverThread.run(() -> result.set(supplier.get()));
        return result.get();
    }

    private static void onServerThread(MinecraftServer server, Runnable task) throws Unavailable {
        if (server.isSameThread()) {
            task.run();
            return;
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        server.execute(() -> {
            if (!server.isSameThread()) {
                future.completeExceptionally(new IllegalStateException("The server has stopped"));
                return;
            }
            try {
                task.run();
                future.complete(null);
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        read(future);
    }

    private static <T> T read(CompletableFuture<T> future) throws Unavailable {
        try {
            return future.get(READ_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new Unavailable("Trade read did not answer within " + READ_TIMEOUT.toSeconds() + " seconds");
        } catch (ExecutionException e) {
            throw new Unavailable("Trade read failed: " + describe(unwrap(e.getCause())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Interrupted while reading trade state");
        }
    }

    private record MarketSnapshot(
            LocalDate boardDay,
            Optional<TradeContent.ExperienceMarket> terms,
            Optional<TradeService.MarketStatus> status) {}

    private record SummarySnapshot(
            LocalDate boardDay,
            @Nullable LocalDate sinceBoardDay,
            @Nullable LocalDate untilBoardDay,
            Map<Identifier, Integer> shopBudgets) {}

    static final class Unavailable extends Exception {
        Unavailable(String message) {
            super(message, null, false, false);
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String describe(Throwable error) {
        return error.getMessage() == null ? error.getClass().getName() : error.getMessage();
    }

    private static JsonObject ok() {
        return status("ok");
    }

    private static JsonObject unavailable(String message) {
        JsonObject answer = status("unavailable");
        answer.addProperty("message", message);
        return answer;
    }

    private static JsonObject invalid(TradeParams.Invalid invalid) {
        return invalid(invalid.field(), invalid.getMessage());
    }

    private static JsonObject invalid(String field, String message) {
        JsonObject answer = status("invalid");
        answer.addProperty("field", field);
        answer.addProperty("message", message);
        return answer;
    }

    private static JsonObject status(String status) {
        JsonObject answer = new JsonObject();
        answer.addProperty("status", status);
        return answer;
    }
}
