package town.lampas.alina.trade;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import town.lampas.alina.Alina;
import town.lampas.alina.content.GuildSettings;
import town.lampas.alina.content.TradeContent;
import town.lampas.alina.content.TradeContent.Denomination;
import town.lampas.alina.content.TradeContent.Limit;
import town.lampas.alina.content.TradeContent.Offer;
import town.lampas.alina.content.TradeContent.Requires;
import town.lampas.alina.content.TradeContent.Shop;
import town.lampas.alina.criteria.AlinaTriggers;
import town.lampas.alina.guild.board.BoardDay;
import town.lampas.alina.settlement.PendingOperation;
import town.lampas.alina.settlement.SettledOperation;
import town.lampas.alina.settlement.SettlementRequest;
import town.lampas.alina.settlement.SettlementService;
import town.lampas.alina.storage.AlinaStorage;
import town.lampas.alina.storage.MarketTick;
import town.lampas.alina.storage.MarketState;
import town.lampas.alina.storage.Operation;
import town.lampas.alina.storage.OperationEffect;
import town.lampas.alina.storage.OperationKind;
import town.lampas.alina.storage.TradeState;
import town.lampas.alina.trade.TradeRefusal.Reason;
import town.lampas.alina.trade.network.TradeClosePayload;
import town.lampas.alina.trade.network.TradeOpenPayload;
import town.lampas.alina.trade.network.TradeResultPayload;
import town.lampas.alina.trade.network.TradeView;
import town.lampas.alina.trade.network.TradeViewPayload;
import town.lampas.alina.trade.pricing.ExperiencePoints;
import town.lampas.alina.trade.pricing.ReserveCurve;
import town.lampas.alina.trade.pricing.ReserveCurve.Reserves;
import town.lampas.alina.trade.pricing.Take;
import town.lampas.alina.trade.pricing.Wallet;
import town.lampas.haruhi.dialogue.api.DialogueAnchor;
import town.lampas.haruhi.api.HaruhiServices;

/**
 * The NPC shop trade: each online character's trade record, the shop screen of each player, and the four requests (sell,
 * sell junk, buy, and experience). Server thread only.
 *
 * <p><b>Records.</b> {@link #join} makes the player's record; the record's rows are read from storage on a tick when the
 * player has no pending trade in the settlement escrow, so the rows are complete. Until the load finishes,
 * {@link #loaded} is false, every request is refused with {@link Reason#LOADING}, and no session opens. Every trade the
 * service begins is added to the record when {@link SettlementService#begin} accepts it, and it stays busy (every request
 * refused with {@link Reason#BUSY}) until settlement reports its operation settled. {@link #leave} drops the record and
 * its sessions. A storage completion is applied only to the entity that started it, and only while that entity is still
 * the player's online entity, compared with {@code ==}.
 *
 * <p><b>The trade day</b> is the Guild's board day, from the Guild's reset time and zone. Budgets and daily limits count
 * per trade day.
 *
 * <p><b>Sessions.</b> {@link #open} opens a shop for a player and closes the player's previous session. A session closes
 * when the player dies or leaves, when the shop is gone or the content revision changed, when its interlocutor dies,
 * leaves the player's level or stands more than {@link #INTERLOCUTOR_REACH} blocks away, or when its anchor no longer holds
 * the player. {@link #dismiss} ends the session the player closed, and reopens the conversation that sent them, if any.
 *
 * <p><b>Requests</b> are planned against the live inventory by {@link TradePlanner}, and settle through
 * {@link SettlementService#begin} as {@code TRADE} operations with a {@code TradeSettled} effect. The service never moves
 * items or experience itself: the removal of a request is the only change it makes, and settlement runs it inside
 * {@code begin}. Each accepted trade is answered with the shop's line, except an experience trade at a market shop that is
 * accepted at a moved price, which is answered with Alina's line that names the points traded and the points quoted. Every
 * request ends with a fresh view.
 *
 * <p><b>The experience market</b> is one pool that every fee shop trades against. The server holds its live reserves in
 * {@link ExperienceMarket}: loaded from storage on a tick, and restocked toward the content's seed on the first tick of each
 * later board day. Every change is a delta, made here and in storage alike: an accepted trade moves the reserves at once and
 * storage gets the same delta in its settlement, a restock and a staff set do the same, and no request is refused for a
 * write in flight. A trade that storage applies which this run did not begin moves the reserves when it is reported fresh.
 * When the reserves may differ from storage for a reason the deltas do not repair, the reconcile reads the row back once
 * nothing is in flight. An experience request at a market shop carries the points the client quoted: it goes through when
 * the market's quote tolerance allows the live points to differ from the quote, and it then settles at the live points, with
 * a line that says what was quoted. The service counts those requests, which staff read with {@link #market()}.
 */
public final class TradeService {
    /** Server ticks between two checks of an open session's view: half a second. */
    public static final long VIEW_TICKS = 10;
    /** Server ticks before a failed load is tried again: 5 seconds. */
    public static final long RETRY_TICKS = 100;
    /** How far, in blocks, a player may stand from the interlocutor before the session closes. */
    public static final double INTERLOCUTOR_REACH = 8.0;
    /** The period a per-character limit counts under, as {@code OperationEffect.TradeSettled.Purchase} names it. */
    static final String CHARACTER_PERIOD = "character";

    /** A player's reputation, empty while their Guild record loads. */
    @FunctionalInterface
    public interface ReputationLookup {
        OptionalInt reputationOf(ServerPlayer player);
    }

    /** Whether the player has earned the achievement, empty while their achievement record loads. */
    @FunctionalInterface
    public interface AchievementLookup {
        Optional<Boolean> earned(ServerPlayer player, Identifier achievement);
    }

    /**
     * A shop's daily budget as one player has used it today.
     *
     * @param paid the Aur the shop has paid the player today
     * @param daily the shop's daily budget
     */
    public record Budget(int paid, int daily) {
        /** The Aur the shop can still pay the player today. */
        public int left() {
            return Math.max(0, daily - paid);
        }
    }

    /**
     * The experience market for staff: the live reserves, the seed the content gives, the board day of the last restock, the
     * quote tolerance, and the experience requests at the market since the server started.
     *
     * @param live the reserves now
     * @param seed the seed reserves of the content
     * @param restockedDay the board day the live reserves were last restocked on
     * @param quoteTolerancePercent how far, in percent of the quoted points, a trade may have moved against the player and still
     *     go through; 0 refuses any difference
     * @param requests the experience requests the planner has worked out at the market
     * @param movedAccepted the ones accepted at a moved price, that is at points other than the quote
     * @param staleRefused the ones refused as stale
     */
    public record MarketStatus(Reserves live, Reserves seed, LocalDate restockedDay, int quoteTolerancePercent,
            long requests, long movedAccepted, long staleRefused) {}

    private final MinecraftServer server;
    private final AlinaStorage storage;
    private final SettlementService settlement;
    private final Supplier<TradeContent> contentSource;
    private final Supplier<GuildSettings> settingsSource;
    private final LongSupplier revisionId;
    private final Clock clock;
    private final Supplier<? extends Collection<ServerPlayer>> online;
    private final TradeMessenger messenger;
    private final ReputationLookup reputations;
    private final AchievementLookup achievements;
    private final Map<UUID, TradeLedger> ledgers = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final List<DeferredOpen> deferred = new ArrayList<>();
    /** The experience market. Replaced, not cleared, when the service stops, so a load that finishes later is dropped. */
    private ExperienceMarket market = new ExperienceMarket();
    private int nextSessionId = 1;
    private long loadGenerations;
    private long ticks;

    /**
     * @param content the active {@code alina:trade} section, read each time it is needed
     * @param settings the Guild's settings, for the reset time and zone of the trade day
     * @param revisionId the active content revision, 0 when none is stored
     * @param clock real time, for the trade day
     * @param online the players this service serves; the entity it serves for a player is the one in this collection
     * @param messenger sends the trade's payloads
     */
    public TradeService(MinecraftServer server, AlinaStorage storage, SettlementService settlement,
            Supplier<TradeContent> content, Supplier<GuildSettings> settings, LongSupplier revisionId, Clock clock,
            Supplier<? extends Collection<ServerPlayer>> online, TradeMessenger messenger,
            ReputationLookup reputations, AchievementLookup achievements) {
        this.server = Objects.requireNonNull(server, "server");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.settlement = Objects.requireNonNull(settlement, "settlement");
        this.contentSource = Objects.requireNonNull(content, "content");
        this.settingsSource = Objects.requireNonNull(settings, "settings");
        this.revisionId = Objects.requireNonNull(revisionId, "revisionId");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.online = Objects.requireNonNull(online, "online");
        this.messenger = Objects.requireNonNull(messenger, "messenger");
        this.reputations = Objects.requireNonNull(reputations, "reputations");
        this.achievements = Objects.requireNonNull(achievements, "achievements");
        settlement.addSettledListener(this::settled);
        settlement.addAppliedListener(this::applied);
    }

    /** Makes the player's record. Its rows load on a later tick, once the player has no trade pending. */
    public void join(ServerPlayer player) {
        requireServerThread();
        UUID id = player.getUUID();
        ledgers.put(id, new TradeLedger(id));
        sessions.remove(id);
        deferred.removeIf(request -> request.player().getUUID().equals(id));
    }

    /** Drops the player's record and session. */
    public void leave(ServerPlayer player) {
        requireServerThread();
        UUID id = player.getUUID();
        ledgers.remove(id);
        sessions.remove(id);
        deferred.removeIf(request -> request.player().getUUID().equals(id));
    }

    /** Called every server tick: deferred opens, record loads, and the open sessions. */
    public void tick() {
        requireServerThread();
        ticks++;
        drainDeferred();
        TradeContent content = contentSource.get();
        content.market().ifPresent(this::tickMarket);
        for (ServerPlayer player : List.copyOf(online.get())) {
            TradeLedger ledger = ledgers.get(player.getUUID());
            if (ledger != null && !ledger.loaded && !ledger.loadInFlight && ticks >= ledger.retryAt) {
                startLoadWhenSettled(ledger, player);
            }
        }
        boolean viewDue = ticks % VIEW_TICKS == 0;
        for (Session session : List.copyOf(sessions.values())) {
            tickSession(session, content, viewDue);
        }
    }

    /** Closes every session: the active content changed. Records are kept. */
    public void contentActivated() {
        requireServerThread();
        for (Session session : List.copyOf(sessions.values())) {
            closeSession(session);
        }
    }

    /** Closes every session and drops every record, as the server stops. */
    public void stop() {
        requireServerThread();
        for (Session session : List.copyOf(sessions.values())) {
            closeSession(session);
        }
        sessions.clear();
        ledgers.clear();
        deferred.clear();
        market = new ExperienceMarket();
    }

    /**
     * Opens the shop for the player, closing the player's previous session. Refused when the player is dead or removed
     * (closed), the shop is not in the active content, the record has not loaded, a trade of the player is still settling,
     * or the client cannot show the screen.
     *
     * @param interlocutor the entity the player trades with, if any; its distance is checked each tick
     * @param anchor the place the trade is tied to, if any
     * @param thenGraph the conversation to reopen when the player dismisses the screen, if any
     */
    public Optional<TradeRefusal> open(ServerPlayer player, Identifier shopId, Optional<Entity> interlocutor,
            Optional<DialogueAnchor> anchor, Optional<Identifier> thenGraph) {
        requireServerThread();
        if (!alive(player)) {
            closePlayersSession(player);
            return Optional.of(refusalOf(Reason.CLOSED, null));
        }
        Shop shop = contentSource.get().shops().get(shopId);
        if (shop == null) {
            return Optional.of(refusalOf(Reason.UNKNOWN_SHOP, null));
        }
        TradeLedger ledger = ledgers.get(player.getUUID());
        if (ledger == null || !ledger.loaded) {
            return Optional.of(refusalOf(Reason.LOADING, null));
        }
        if (ledger.busy()) {
            return Optional.of(refusalOf(Reason.BUSY, shop));
        }
        if (!messenger.canOpen(player)) {
            return Optional.of(refusalOf(Reason.CANNOT_OPEN, null));
        }
        Session previous = sessions.remove(player.getUUID());
        if (previous != null) {
            messenger.close(previous.player, new TradeClosePayload(previous.id));
        }
        Session session = new Session(nextSessionId++, player, shopId, revisionId.getAsLong(), interlocutor, anchor,
                thenGraph);
        TradeView view = view(player, shop, ledger);
        session.lastView = view;
        sessions.put(player.getUUID(), session);
        messenger.open(player, new TradeOpenPayload(session.id, shopId, shop.name(), speaker(interlocutor),
                TradeText.line(shop, "greeting"), view));
        return Optional.empty();
    }

    /**
     * Asks for {@link #open} on the next server tick, for a dialogue action that ends its conversation first. A refusal is
     * sent to the player as a chat message.
     */
    public void openNextTick(ServerPlayer player, Identifier shop, Optional<Entity> interlocutor,
            Optional<DialogueAnchor> anchor, Optional<Identifier> thenGraph) {
        requireServerThread();
        deferred.add(new DeferredOpen(player, shop, interlocutor, anchor, thenGraph));
    }

    /**
     * The player closed the screen of the session. The session ends, and when it has a conversation to reopen, that
     * conversation opens again with the same interlocutor or anchor. A trade still settling goes on.
     */
    public void dismiss(ServerPlayer player, int sessionId) {
        requireServerThread();
        Session session = currentSession(player, sessionId);
        if (session == null) {
            return;
        }
        sessions.remove(player.getUUID());
        session.thenGraph.ifPresent(graph -> {
            if (session.anchor.isPresent()) {
                HaruhiServices.dialogue().openAt(player, graph, session.anchor.get());
            } else {
                HaruhiServices.dialogue().open(player, graph, session.interlocutor.orElse(null));
            }
        });
    }

    /** Sells {@code count} items of the main inventory stack in {@code slot} for {@code expectedAur}. */
    public TradeOutcome sell(ServerPlayer player, int sessionId, int slot, int count, int expectedAur) {
        requireServerThread();
        return request(player, sessionId, (shop, ledger) -> TradePlanner.sell(shop, currency(), player.getInventory(),
                budgetLeft(shop, ledger), slot, count, expectedAur), plan -> Optional.empty());
    }

    /** Sells what "Sell all junk" sells now, for {@code expectedAur}. */
    public TradeOutcome sellJunk(ServerPlayer player, int sessionId, int expectedAur) {
        requireServerThread();
        return request(player, sessionId, (shop, ledger) -> TradePlanner.junk(shop, currency(),
                player.getInventory(), budgetLeft(shop, ledger), expectedAur), plan -> Optional.empty());
    }

    /** Buys {@code count} purchases of the offer, for {@code expectedCost} Aur in all. */
    public TradeOutcome buy(ServerPlayer player, int sessionId, String offerId, int count, int expectedCost) {
        requireServerThread();
        return request(player, sessionId, (shop, ledger) -> {
            Optional<Offer> offer = shop.offers().stream().filter(candidate -> candidate.id().equals(offerId)).findFirst();
            if (offer.isEmpty()) {
                return TradePlan.refuse(Reason.INVALID);
            }
            OfferState state = offerState(player, shop, ledger, day(), offer.get());
            return TradePlanner.buy(shop, currency(), player.getInventory(), state, count, expectedCost);
        }, plan -> Optional.empty());
    }

    /**
     * Sells the player's experience for {@code aur} Aur when {@code selling}, or buys experience for {@code aur} Aur, for
     * {@code expectedPoints} points. At a shop that trades against the market, {@code expectedPoints} is the quote, and the
     * request counts toward the market's figures; an accepted one at points other than the quote says what was quoted.
     */
    public TradeOutcome experience(ServerPlayer player, int sessionId, boolean selling, int aur, int expectedPoints) {
        requireServerThread();
        AtomicReference<TradePlan> marketPlan = new AtomicReference<>();
        TradeOutcome outcome = request(player, sessionId, (shop, ledger) -> {
            Optional<ExperienceMarket.Snapshot> snapshot = marketSnapshot();
            TradePlan plan = TradePlanner.experience(shop, currency(), player.getInventory(), budgetLeft(shop, ledger),
                    ExperiencePoints.total(player), selling, aur, expectedPoints, snapshot);
            if (shop.experienceFees().isPresent() && snapshot.isPresent()) {
                marketPlan.set(plan);
            }
            return plan;
        }, plan -> movedLine(plan, selling, aur, expectedPoints));
        TradePlan plan = marketPlan.get();
        if (plan != null) {
            countMarketRequest(outcome, plan, selling, expectedPoints);
        }
        return outcome;
    }

    /**
     * The line of an accepted experience trade whose points moved from the quote: what the player gave or got, and the points
     * quoted. Empty when the points are the quoted ones, which every trade at a fixed rate is.
     */
    private static Optional<Component> movedLine(TradePlan plan, boolean selling, int aur, int expectedPoints) {
        int points = selling ? plan.pointsTaken() : plan.pointsGiven();
        if (points == expectedPoints) {
            return Optional.empty();
        }
        return Optional.of(selling ? TradeText.soldMoved(points, aur, expectedPoints)
                : TradeText.boughtMoved(points, aur, expectedPoints));
    }

    /**
     * Counts one experience request the planner worked out at the market: every one, the ones refused as stale, and the
     * accepted ones whose points moved from the quote.
     */
    private void countMarketRequest(TradeOutcome outcome, TradePlan plan, boolean selling, int expectedPoints) {
        market.requests++;
        if (outcome.refusal().filter(refusal -> refusal.reason() == Reason.STALE).isPresent()) {
            market.staleRefused++;
        }
        int points = selling ? plan.pointsTaken() : plan.pointsGiven();
        if (outcome.accepted() && points != expectedPoints) {
            market.movedAccepted++;
        }
    }

    /** The shop's budget for the player today: empty while the record loads, or for a shop that does not buy. */
    public Optional<Budget> budget(ServerPlayer player, Identifier shopId) {
        requireServerThread();
        TradeLedger ledger = ledgers.get(player.getUUID());
        Shop shop = contentSource.get().shops().get(shopId);
        if (ledger == null || !ledger.loaded || shop == null || shop.selling().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Budget(ledger.aurPaid(shopId, day()), shop.selling().get().dailyBudget()));
    }

    /** Whether the player's record has loaded. */
    public boolean loaded(ServerPlayer player) {
        requireServerThread();
        TradeLedger ledger = ledgers.get(player.getUUID());
        return ledger != null && ledger.loaded;
    }

    /**
     * For staff: deletes the player's budget rows for today, of one shop or of every shop, in storage, and then forgets
     * them in the record. Completes with a refusal, and changes nothing, when the record has not loaded, or when a trade of
     * the player is still settling: the trade's Aur is written to storage after the delete, and the record would forget it.
     * Fails when storage does. A trade begun while the delete runs is not refused, and the record forgets its Aur when the
     * delete completes; only a reset issued while no trade settles is covered.
     */
    public CompletableFuture<Optional<TradeRefusal>> resetBudget(ServerPlayer player, Optional<Identifier> shop) {
        requireServerThread();
        TradeLedger ledger = ledgers.get(player.getUUID());
        if (ledger == null || !ledger.loaded) {
            return CompletableFuture.completedFuture(Optional.of(new TradeRefusal(Reason.LOADING, TradeText.loading())));
        }
        if (ledger.busy()) {
            return CompletableFuture.completedFuture(Optional.of(new TradeRefusal(Reason.BUSY, TradeText.resetBusy())));
        }
        UUID id = player.getUUID();
        LocalDate day = day();
        CompletableFuture<Optional<TradeRefusal>> done = new CompletableFuture<>();
        storage.resetTradeBudget(id, shop, day).whenComplete((ignored, failure) -> server.execute(() -> {
            if (!server.isSameThread()) {
                done.completeExceptionally(new IllegalStateException("the server has stopped"));
            } else if (failure != null) {
                done.completeExceptionally(failure);
            } else {
                if (ledgers.get(id) == ledger) {
                    ledger.clearBudget(shop, day);
                }
                done.complete(Optional.empty());
            }
        }));
        return done;
    }

    /**
     * For staff: the experience market's live reserves, seed, last restock day, quote tolerance and request counts. Empty when
     * the content has no market, or its reserves have not loaded.
     */
    public Optional<MarketStatus> market() {
        requireServerThread();
        Optional<TradeContent.ExperienceMarket> terms = contentSource.get().market();
        if (terms.isEmpty() || market.live.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MarketStatus(market.live.get(), seed(terms.get()), market.restockedDay,
                terms.get().quoteTolerancePercent(), market.requests, market.movedAccepted, market.staleRefused));
    }

    /**
     * For staff: sets both reserves of the experience market to the given values. The write is the difference from the live
     * reserves, added to storage, so the trades and restocks in flight keep their place: no request is refused for it, and
     * once the in-flight writes settle, storage equals the live reserves. Completes with a refusal, and changes nothing, when
     * the market has not loaded. A failed write fails the future and makes the reconcile due.
     *
     * @throws IllegalArgumentException when either value is outside 1 to {@link ReserveCurve#MAX_RESERVE}
     */
    public CompletableFuture<Optional<TradeRefusal>> setMarket(long points, long aur) {
        requireServerThread();
        Reserves target = new Reserves(points, aur);
        if (market.live.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.of(new TradeRefusal(Reason.LOADING, TradeText.marketLoading())));
        }
        ExperienceMarket owner = market;
        Reserves live = owner.live.get();
        owner.live = Optional.of(target);
        owner.history = ExperienceMarket.withRate(owner.history, ExperienceMarket.rateOf(target));
        owner.epoch++;
        owner.adjustsInFlight++;
        CompletableFuture<Optional<TradeRefusal>> done = new CompletableFuture<>();
        storage.adjustMarket(target.points() - live.points(), target.aur() - live.aur(), clock.instant())
                .whenComplete((applied, failure) -> server.execute(() -> adjustCompleted(owner, applied, failure, done)));
        return done;
    }

    private static void adjustCompleted(ExperienceMarket owner, @Nullable Boolean applied, @Nullable Throwable failure,
            CompletableFuture<Optional<TradeRefusal>> done) {
        owner.adjustsInFlight--;
        if (failure != null) {
            owner.reconcileDue = true;
            done.completeExceptionally(failure);
        } else if (!Boolean.TRUE.equals(applied)) {
            owner.reconcileDue = true;
            done.completeExceptionally(new IllegalStateException("the experience market has no row to set"));
        } else {
            done.complete(Optional.empty());
        }
    }

    // Requests.

    /** Plans one request for the shop and the record. */
    @FunctionalInterface
    private interface Planner {
        TradePlan plan(Shop shop, TradeLedger ledger);
    }

    /**
     * The checks every request makes, in order: the session is the player's current one, the shop and the revision still
     * hold, no trade is settling, and the planner accepts the request. Every outcome sends the result and a fresh view.
     * {@code lineOf} gives the shop's line for an accepted plan in place of the shop's own line, when it gives one.
     */
    private TradeOutcome request(ServerPlayer player, int sessionId, Planner planner,
            Function<TradePlan, Optional<Component>> lineOf) {
        if (!alive(player)) {
            // A lethal hit can land between two ticks, so a request may arrive before the tick that closes the session.
            closePlayersSession(player);
            return reply(player, sessionId, TradeOutcome.refused(refusalOf(Reason.CLOSED, null)));
        }
        Session session = currentSession(player, sessionId);
        if (session == null) {
            return reply(player, sessionId, TradeOutcome.refused(refusalOf(Reason.NO_SESSION, null)));
        }
        TradeLedger ledger = ledgers.get(player.getUUID());
        if (ledger == null || !ledger.loaded) {
            return reply(player, sessionId, TradeOutcome.refused(refusalOf(Reason.LOADING, null)));
        }
        Shop shop = contentSource.get().shops().get(session.shop);
        if (shop == null || revisionId.getAsLong() != session.revision) {
            closeSession(session);
            return reply(player, sessionId, TradeOutcome.refused(refusalOf(Reason.CLOSED, null)));
        }
        if (ledger.busy()) {
            return reply(player, sessionId, TradeOutcome.refused(refusalOf(Reason.BUSY, shop)));
        }
        TradePlan plan = planner.plan(shop, ledger);
        if (plan.refusal().isPresent()) {
            return reply(player, sessionId, TradeOutcome.refused(refusalOf(plan.refusal().get(), shop)));
        }
        return reply(player, sessionId, submit(player, ledger, shop, plan, lineOf));
    }

    /**
     * Hands the plan to settlement. A refused begin changes nothing and is reported as busy. An accepted one is added to
     * the record at once, and its line answers it: the one {@code lineOf} gives, else the shop's line.
     */
    private TradeOutcome submit(ServerPlayer player, TradeLedger ledger, Shop shop, TradePlan plan,
            Function<TradePlan, Optional<Component>> lineOf) {
        LocalDate day = day();
        OperationEffect.TradeSettled effect = new OperationEffect.TradeSettled(shop.id(), day, plan.aurPaid(),
                plan.purchase().stream().toList(), plan.detail(), plan.marketPoints(), plan.marketAur());
        SettlementRequest request = new SettlementRequest(OperationKind.TRADE, shop.id(), 0, revisionId.getAsLong(),
                Optional.empty(), 0, effect, removal(plan), Optional.empty(), Optional.empty(), plan.grant(),
                plan.pointsGiven());
        Optional<UUID> operation;
        try {
            operation = settlement.begin(player, request);
        } catch (IllegalArgumentException failure) {
            Alina.LOGGER.warn("Alina could not begin a trade with {} for {}", shop.id(), player.getUUID(), failure);
            operation = Optional.empty();
        }
        if (operation.isEmpty()) {
            return TradeOutcome.refused(refusalOf(Reason.BUSY, shop));
        }
        ledger.inFlight.add(operation.get());
        if (plan.aurPaid() > 0) {
            ledger.addAurPaid(shop.id(), day, plan.aurPaid());
        }
        plan.purchase().ifPresent(purchase -> ledger.addPurchase(shop.id(), purchase.offerId(), purchase.period(),
                purchase.count()));
        if (plan.marketPoints() != 0 || plan.marketAur() != 0) {
            market.begun.put(operation.get(), new ExperienceMarket.Move(plan.marketPoints(), plan.marketAur()));
            moveMarket(plan.marketPoints(), plan.marketAur());
        }
        return TradeOutcome.ok(lineOf.apply(plan).orElseGet(() -> TradeText.line(shop, plan.line())));
    }

    /** The removal of a plan: its takes from the inventory, and the experience points it gives up. */
    private static SettlementRequest.Removal removal(TradePlan plan) {
        return player -> {
            List<ItemStack> removed = Take.apply(player.getInventory(), plan.takes());
            if (plan.pointsTaken() > 0) {
                player.giveExperiencePoints(-plan.pointsTaken());
            }
            return removed;
        };
    }

    /** Sends the result of a request and a fresh view when the session is still the player's current one. */
    private TradeOutcome reply(ServerPlayer player, int sessionId, TradeOutcome outcome) {
        messenger.result(player, new TradeResultPayload(sessionId, outcome.accepted(), outcome.line()));
        Session session = sessions.get(player.getUUID());
        if (session != null && session.id == sessionId && session.player == player) {
            sendView(session, true);
        }
        return outcome;
    }

    // Settlement.

    /** Runs on the server thread when settlement reports an operation settled: the trade is no longer in flight. */
    private void settled(SettledOperation settled) {
        if (!server.isSameThread()) {
            Alina.LOGGER.warn("Alina trade ignored a settled operation for {}: settlement called it off the server thread",
                    settled.player().getUUID());
            return;
        }
        Operation operation = settled.operation();
        if (operation.kind() != OperationKind.TRADE) {
            return;
        }
        ServerPlayer player = settled.player();
        TradeLedger ledger = ledgers.get(player.getUUID());
        if (ledger != null) {
            ledger.inFlight.remove(operation.operationId());
        }
        if (operation.effect() instanceof OperationEffect.TradeSettled effect) {
            AlinaTriggers.TRADE_COMPLETED.trigger(player, effect.shopId(), effect.aurPaid(), spentIn(effect));
        }
        Session session = sessions.get(player.getUUID());
        if (session != null && session.player == player) {
            sendView(session, true);
        }
    }

    private static int spentIn(OperationEffect.TradeSettled effect) {
        try {
            return TradePlanner.aurSpentIn(effect.detailJson());
        } catch (RuntimeException failure) {
            Alina.LOGGER.error("Alina trade log detail of {} has no aur_spent", effect.shopId(), failure);
            return 0;
        }
    }

    // Experience market.

    /**
     * Loads the market while it has not loaded. Once it has, restocks it when a board day has passed, and reconciles it with
     * storage when a reconcile is due.
     */
    private void tickMarket(TradeContent.ExperienceMarket terms) {
        if (market.live.isEmpty()) {
            if (!market.loadInFlight && ticks >= market.retryAt) {
                startMarketLoad(terms);
            }
            return;
        }
        if (!market.restockInFlight && day().isAfter(market.restockedDay)) {
            restock(terms);
        }
        if (market.reconcileDue) {
            reconcile();
        }
    }

    /** Reads the market row, creating it from the seed when there is none, and then the ticks of the rate history. */
    private void startMarketLoad(TradeContent.ExperienceMarket terms) {
        ExperienceMarket owner = market;
        owner.loadInFlight = true;
        storage.loadOrCreateMarket(terms.seedPoints(), terms.seedAur(), day(), clock.instant())
                .thenCompose(state -> storage.loadMarketTicks(ExperienceMarket.HISTORY_RATES)
                        .thenApply(ticks -> new MarketRead(Optional.of(state), ticks)))
                .whenComplete((loaded, failure) -> server.execute(() -> {
                    if (server.isSameThread()) {
                        marketLoadCompleted(owner, loaded, failure);
                    } else {
                        Alina.LOGGER.info("Alina dropped the experience market load: it finished after the server stopped");
                    }
                }));
    }

    private void marketLoadCompleted(ExperienceMarket owner, @Nullable MarketRead loaded, @Nullable Throwable failure) {
        if (market != owner) {
            return;
        }
        owner.loadInFlight = false;
        if (failure != null || loaded == null || loaded.row().isEmpty()) {
            owner.retryAt = ticks + RETRY_TICKS;
            Alina.LOGGER.warn("Alina could not load the experience market; it is tried again in 5 seconds", failure);
            return;
        }
        if (!installRow(owner, loaded)) {
            owner.retryAt = ticks + RETRY_TICKS;
        }
    }

    /**
     * Installs a read of the row and its ticks into memory, clamping each reserve into range. A clamp is logged once. Returns
     * false when the install failed, so the caller sets its retry tick instead of retrying on every tick.
     */
    private boolean installRow(ExperienceMarket owner, MarketRead read) {
        try {
            MarketState state = read.row().orElseThrow();
            if (owner.install(state.points(), state.aur(), state.restockedDay(), read.ticks())) {
                Alina.LOGGER.warn("Alina clamped the experience market row, {} points and {} Aur, into 1 to {}",
                        state.points(), state.aur(), ReserveCurve.MAX_RESERVE);
            }
            return true;
        } catch (RuntimeException failure) {
            Alina.LOGGER.warn("Alina could not install the experience market row; it is tried again in 5 seconds", failure);
            return false;
        }
    }

    /**
     * Restocks the live reserves toward the seed by the board days since the last restock, at most
     * {@link ExperienceMarket#MAX_RESTOCK_DAYS}, and sends the change to storage as a delta. The restock is one change of the
     * pool, so its rate is appended to the history once. A restock write that fails or is not applied makes the reconcile due.
     */
    private void restock(TradeContent.ExperienceMarket terms) {
        ExperienceMarket owner = market;
        LocalDate today = day();
        LocalDate closedDay = owner.restockedDay;
        Reserves close = owner.live.orElseThrow();
        Reserves restocked = ExperienceMarket.restocked(close, seed(terms), terms.restockPercent(),
                ChronoUnit.DAYS.between(closedDay, today));
        owner.live = Optional.of(restocked);
        owner.restockedDay = today;
        owner.history = ExperienceMarket.withRate(owner.history, ExperienceMarket.rateOf(restocked));
        owner.epoch++;
        owner.restockInFlight = true;
        storage.restockMarket(today, restocked.points() - close.points(), restocked.aur() - close.aur(), clock.instant())
                .whenComplete((applied, failure) -> server.execute(() -> restockCompleted(owner, today, applied, failure)));
    }

    private void restockCompleted(ExperienceMarket owner, LocalDate day, @Nullable Boolean applied,
            @Nullable Throwable failure) {
        owner.restockInFlight = false;
        if (failure != null) {
            owner.reconcileDue = true;
            Alina.LOGGER.warn("Alina could not restock the experience market for {}; it is reconciled with storage", day,
                    failure);
        } else if (!Boolean.TRUE.equals(applied)) {
            owner.reconcileDue = true;
            Alina.LOGGER.warn("Alina did not restock the experience market for {}: storage did not apply it; it is "
                    + "reconciled with storage", day);
        }
    }

    /**
     * Reads the row and its ticks, and installs them when nothing began, arrived or was written while the read ran. Runs
     * only when no trade is begun and unreported, and no load, restock or adjustment is in flight: then the row is the whole
     * truth. Otherwise the reconcile stays due and a later tick tries again.
     */
    private void reconcile() {
        ExperienceMarket owner = market;
        if (owner.reconcileInFlight || ticks < owner.reconcileRetryAt || !owner.begun.isEmpty() || owner.loadInFlight
                || owner.restockInFlight || owner.adjustsInFlight > 0) {
            return;
        }
        owner.reconcileInFlight = true;
        long epoch = owner.epoch;
        storage.loadMarket()
                .thenCompose(row -> storage.loadMarketTicks(ExperienceMarket.HISTORY_RATES)
                        .thenApply(ticks -> new MarketRead(row, ticks)))
                .whenComplete((read, failure) -> server.execute(() -> reconcileCompleted(owner, epoch, read, failure)));
    }

    private void reconcileCompleted(ExperienceMarket owner, long epoch, @Nullable MarketRead read,
            @Nullable Throwable failure) {
        owner.reconcileInFlight = false;
        if (market != owner) {
            return;
        }
        if (failure != null || read == null || read.row().isEmpty()) {
            owner.reconcileRetryAt = ticks + RETRY_TICKS;
            Alina.LOGGER.warn("Alina could not reconcile the experience market with storage; it is tried again in 5 seconds",
                    failure);
            return;
        }
        if (owner.epoch != epoch || !owner.begun.isEmpty() || owner.restockInFlight || owner.adjustsInFlight > 0) {
            return;
        }
        if (!installRow(owner, read)) {
            owner.reconcileRetryAt = ticks + RETRY_TICKS;
            return;
        }
        owner.reconcileDue = false;
    }

    /**
     * Moves the live reserves by a change, and counts it as a change for the reconcile. A reserve that the move takes out of
     * range is clamped, logged once, and makes the reconcile due. Without loaded reserves there is nothing to move.
     */
    private void moveMarket(long points, long aur) {
        ExperienceMarket owner = market;
        owner.epoch++;
        if (owner.live.isEmpty()) {
            return;
        }
        Reserves live = owner.live.get();
        if (ExperienceMarket.clamps(live, points, aur)) {
            owner.reconcileDue = true;
            Alina.LOGGER.warn("Alina clamped the experience market: a change of {} points and {} Aur took the live {} points "
                    + "and {} Aur out of range", points, aur, live.points(), live.aur());
        }
        Reserves moved = ExperienceMarket.moved(live, points, aur);
        owner.live = Optional.of(moved);
        owner.history = ExperienceMarket.withRate(owner.history, ExperienceMarket.rateOf(moved));
    }

    /**
     * Runs on the server thread once per operation in this run, when storage has recorded its result. A market trade this run
     * began has its move in the live reserves already, so it only leaves {@link ExperienceMarket#begun}. A market trade this run
     * did not begin, applied fresh, is a change storage has and memory lacks: it is added to the live reserves, and the
     * reconcile is due, since a read of the row may or may not have included it. Without loaded reserves, the load reads the
     * change, and the reconcile is still due.
     */
    private void applied(SettledOperation settled) {
        if (!server.isSameThread()) {
            Alina.LOGGER.warn("Alina trade ignored an applied operation for {}: settlement called it off the server thread",
                    settled.player().getUUID());
            return;
        }
        Operation operation = settled.operation();
        if (operation.kind() != OperationKind.TRADE
                || !(operation.effect() instanceof OperationEffect.TradeSettled effect)
                || (effect.marketPoints() == 0 && effect.marketAur() == 0)) {
            return;
        }
        // A trade this run began is in the live reserves already, so only its entry goes.
        if (market.begun.remove(operation.operationId()) != null || !settled.result().fresh()) {
            return;
        }
        market.reconcileDue = true;
        moveMarket(effect.marketPoints(), effect.marketAur());
    }

    /** The market a trade or a view reads: empty when the content has no market, or the live reserves have not loaded. */
    private Optional<ExperienceMarket.Snapshot> marketSnapshot() {
        Optional<TradeContent.ExperienceMarket> terms = contentSource.get().market();
        if (terms.isEmpty() || market.live.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ExperienceMarket.Snapshot(market.live.get(), seed(terms.get()),
                terms.get().maxTradePercent(), terms.get().quoteTolerancePercent(), List.copyOf(market.history)));
    }

    private static Reserves seed(TradeContent.ExperienceMarket terms) {
        return new Reserves(terms.seedPoints(), terms.seedAur());
    }

    // Records.

    private void startLoadWhenSettled(TradeLedger ledger, ServerPlayer player) {
        boolean pending;
        try {
            pending = pendingTrade(player);
        } catch (RuntimeException failure) {
            ledger.retryAt = ticks + RETRY_TICKS;
            Alina.LOGGER.warn("Alina could not read the settlement escrow of {}; the trade record is tried again in 5 seconds",
                    ledger.id, failure);
            return;
        }
        if (!pending) {
            startLoad(ledger, player);
        }
    }

    /** Whether the player's escrow holds a trade of this world. */
    private boolean pendingTrade(ServerPlayer player) {
        for (PendingOperation operation : settlement.pendingFor(player)) {
            if (operation.kind() == OperationKind.TRADE && operation.worldId().equals(settlement.worldId())) {
                return true;
            }
        }
        return false;
    }

    private void startLoad(TradeLedger ledger, ServerPlayer player) {
        long generation = ++loadGenerations;
        ledger.loadGeneration = generation;
        ledger.loadInFlight = true;
        UUID id = ledger.id;
        storage.loadTradeState(id, day()).whenComplete((state, failure) -> server.execute(() -> {
            if (server.isSameThread()) {
                loadCompleted(ledger, generation, player, state, failure);
            } else {
                Alina.LOGGER.info("Alina dropped the trade record load of {}: it finished after the server stopped", id);
            }
        }));
    }

    private void loadCompleted(TradeLedger ledger, long generation, ServerPlayer player, TradeState state,
            Throwable failure) {
        if (ledgers.get(ledger.id) != ledger || ledger.loadGeneration != generation) {
            return;
        }
        ledger.loadInFlight = false;
        if (failure != null) {
            ledger.retryAt = ticks + RETRY_TICKS;
            Alina.LOGGER.warn("Alina could not load the trade record of {}; it is tried again in 5 seconds", ledger.id,
                    failure);
            return;
        }
        if (liveEntity(ledger.id) != player) {
            // The entity that asked has left or been replaced. The next tick loads the live entity's record.
            return;
        }
        ledger.aurPaid.clear();
        ledger.aurPaid.putAll(state.aurPaid());
        ledger.purchases.clear();
        ledger.purchases.putAll(state.purchases());
        ledger.loaded = true;
    }

    // Sessions.

    private void drainDeferred() {
        if (deferred.isEmpty()) {
            return;
        }
        List<DeferredOpen> due = List.copyOf(deferred);
        deferred.clear();
        for (DeferredOpen request : due) {
            if (liveEntity(request.player().getUUID()) != request.player()) {
                continue;
            }
            open(request.player(), request.shop(), request.interlocutor(), request.anchor(), request.thenGraph())
                    .ifPresent(refusal -> request.player().sendSystemMessage(refusal.message()));
        }
    }

    private void tickSession(Session session, TradeContent content, boolean viewDue) {
        ServerPlayer player = session.player;
        if (liveEntity(player.getUUID()) != player || !player.isAlive() || player.isRemoved()) {
            closeSession(session);
            return;
        }
        if (!content.shops().containsKey(session.shop) || revisionId.getAsLong() != session.revision) {
            closeSession(session);
            return;
        }
        if (session.interlocutor.isPresent() && !reachable(player, session.interlocutor.get())) {
            closeSession(session);
            return;
        }
        if (session.anchor.isPresent() && !session.anchor.get().holds(player)) {
            closeSession(session);
            return;
        }
        if (viewDue) {
            sendView(session, false);
        }
    }

    private void closeSession(Session session) {
        sessions.remove(session.player.getUUID(), session);
        messenger.close(session.player, new TradeClosePayload(session.id));
    }

    /** Closes the player's session, if it has one. */
    private void closePlayersSession(ServerPlayer player) {
        Session session = sessions.get(player.getUUID());
        if (session != null) {
            closeSession(session);
        }
    }

    /** Whether the player may trade: a dead or removed player is not served. */
    private static boolean alive(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved();
    }

    /** Sends the view when it differs from the last one sent, or always when {@code force}. */
    private void sendView(Session session, boolean force) {
        Shop shop = contentSource.get().shops().get(session.shop);
        TradeLedger ledger = ledgers.get(session.player.getUUID());
        if (shop == null || ledger == null || !ledger.loaded) {
            return;
        }
        TradeView view = view(session.player, shop, ledger);
        if (!force && session.lastView != null && TradeViews.same(session.lastView, view)) {
            return;
        }
        session.lastView = view;
        messenger.view(session.player, new TradeViewPayload(session.id, view));
    }

    private boolean reachable(ServerPlayer player, Entity interlocutor) {
        if (interlocutor.isRemoved() || (interlocutor instanceof LivingEntity living && !living.isAlive())) {
            return false;
        }
        return interlocutor.level() == player.level()
                && player.distanceToSqr(interlocutor) <= INTERLOCUTOR_REACH * INTERLOCUTOR_REACH;
    }

    private Session currentSession(ServerPlayer player, int sessionId) {
        Session session = sessions.get(player.getUUID());
        return session != null && session.id == sessionId && session.player == player ? session : null;
    }

    private static OptionalInt speaker(Optional<Entity> interlocutor) {
        return interlocutor.filter(LivingEntity.class::isInstance)
                .map(entity -> OptionalInt.of(entity.getId()))
                .orElse(OptionalInt.empty());
    }

    // Views and offers.

    private TradeView view(ServerPlayer player, Shop shop, TradeLedger ledger) {
        TradeContent content = contentSource.get();
        LocalDate day = day();
        Inventory inventory = player.getInventory();
        List<OfferState> offers = new ArrayList<>();
        for (Offer offer : shop.offers()) {
            offers.add(offerState(player, shop, ledger, day, offer));
        }
        return TradeViews.build(shop, content.currency(), inventory, Wallet.total(inventory, content.currency()),
                budgetLeft(shop, ledger), ExperiencePoints.total(player), player.experienceLevel, offers, ledger.busy(),
                marketSnapshot());
    }

    private OfferState offerState(ServerPlayer player, Shop shop, TradeLedger ledger, LocalDate day, Offer offer) {
        Optional<String> period = offer.limit().map(limit -> periodOf(limit, day));
        int limitLeft = -1;
        if (offer.limit().isPresent()) {
            int bought = ledger.purchased(shop.id(), offer.id(), period.orElseThrow());
            limitLeft = Math.max(0, offer.limit().get().count() - bought);
        }
        return new OfferState(offer, period, limitLeft, lockedBy(offer.requires(), player));
    }

    private static String periodOf(Limit limit, LocalDate day) {
        return limit.per() == Limit.Period.DAY ? day.toString() : CHARACTER_PERIOD;
    }

    /** What the player lacks to buy the offer, or empty when they meet its requirements. A lookup still loading locks it. */
    private Optional<Component> lockedBy(Optional<Requires> requires, ServerPlayer player) {
        if (requires.isEmpty()) {
            return Optional.empty();
        }
        Requires needed = requires.get();
        if (needed.reputation().isPresent()) {
            OptionalInt reputation = reputations.reputationOf(player);
            if (reputation.isEmpty()) {
                return Optional.of(TradeText.loading());
            }
            int required = needed.reputation().getAsInt();
            if (reputation.getAsInt() < required) {
                return Optional.of(TradeText.needsReputation(required));
            }
        }
        if (needed.achievement().isPresent()) {
            Identifier achievement = needed.achievement().get();
            Optional<Boolean> earned = achievements.earned(player, achievement);
            if (earned.isEmpty()) {
                return Optional.of(TradeText.loading());
            }
            if (!earned.get()) {
                return Optional.of(TradeText.needsAchievement(achievement));
            }
        }
        return Optional.empty();
    }

    /** The Aur the shop can still pay the player today; 0 for a shop that does not buy. */
    private int budgetLeft(Shop shop, TradeLedger ledger) {
        return shop.selling()
                .map(selling -> Math.max(0, selling.dailyBudget() - ledger.aurPaid(shop.id(), day())))
                .orElse(0);
    }

    private List<Denomination> currency() {
        return contentSource.get().currency();
    }

    private LocalDate day() {
        GuildSettings settings = settingsSource.get();
        return BoardDay.dateOf(clock.instant(), settings.resetTime(), settings.zone());
    }

    private ServerPlayer liveEntity(UUID id) {
        for (ServerPlayer candidate : online.get()) {
            if (candidate.getUUID().equals(id)) {
                return candidate;
            }
        }
        return null;
    }

    private static TradeRefusal refusalOf(Reason reason, @Nullable Shop shop) {
        return new TradeRefusal(reason, TradeText.refusal(reason, shop));
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Alina trade runs on the server thread");
        }
    }

    /** A session: the shop a player is trading at, and what keeps it open. */
    private static final class Session {
        final int id;
        final ServerPlayer player;
        final Identifier shop;
        /** The content revision the session was opened under. */
        final long revision;
        final Optional<Entity> interlocutor;
        final Optional<DialogueAnchor> anchor;
        final Optional<Identifier> thenGraph;
        /** The view last sent, which the poll compares with the current one. */
        TradeView lastView;

        Session(int id, ServerPlayer player, Identifier shop, long revision, Optional<Entity> interlocutor,
                Optional<DialogueAnchor> anchor, Optional<Identifier> thenGraph) {
            this.id = id;
            this.player = player;
            this.shop = shop;
            this.revision = revision;
            this.interlocutor = interlocutor;
            this.anchor = anchor;
            this.thenGraph = thenGraph;
        }
    }

    /** A read of the market row, and the closed days, newest first. The row is empty when storage has none. */
    private record MarketRead(Optional<MarketState> row, List<MarketTick> ticks) {}

    /** An open asked for by a dialogue action, carried out on the next tick. */
    private record DeferredOpen(ServerPlayer player, Identifier shop, Optional<Entity> interlocutor,
            Optional<DialogueAnchor> anchor, Optional<Identifier> thenGraph) {}
}
