package town.lampas.alina.gametest;

import com.mojang.serialization.DataResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import town.lampas.alina.Alina;
import town.lampas.alina.content.AlinaContent;
import town.lampas.alina.content.GuildSettings;
import town.lampas.alina.content.TradeContent;
import town.lampas.alina.guild.board.BoardDay;
import town.lampas.alina.settlement.PlayerDataSaves;
import town.lampas.alina.settlement.SettlementService;
import town.lampas.alina.storage.AlinaStorage;
import town.lampas.alina.storage.InMemoryStorage;
import town.lampas.alina.storage.MarketTick;
import town.lampas.alina.storage.MarketState;
import town.lampas.alina.storage.TradeState;
import town.lampas.alina.trade.TradeMessenger;
import town.lampas.alina.trade.TradeService;
import town.lampas.alina.trade.network.TradeClosePayload;
import town.lampas.alina.trade.network.TradeOpenPayload;
import town.lampas.alina.trade.network.TradeResultPayload;
import town.lampas.alina.trade.network.TradeView;
import town.lampas.alina.trade.network.TradeViewPayload;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;

/**
 * One trade GameTest's world: a {@link TradeService} and its {@link SettlementService} over one in-memory storage, a clock
 * the test moves, the {@code alina:trade} content the test parsed from a fixture, a messenger that records what the service
 * sends, and mock players that are not in the server's player list. The test calls {@link #tick} each game tick, which
 * ticks both services. The content revision is a number the test sets.
 */
final class TradeRig {
    /** 2026-11-05 10:00 in Asia/Manila: board day 2026-11-05, which the reset at 04:00 on 2026-11-06 ends. */
    static final Instant START = Instant.parse("2026-11-05T02:00:00Z");
    /** The content revision the service reports until a test changes it. */
    static final long REVISION = 3;
    static final Identifier MIRA = Alina.id("trade_test/mira");
    static final Identifier TIGHT = Alina.id("trade_test/tight");
    static final Identifier LUMBER = Alina.id("trade_test/lumber");
    /** The shops of {@code bundle_market.json}, which trade experience against the experience market. */
    static final Identifier BROKER = Alina.id("trade_test/broker");
    static final Identifier TOLL = Alina.id("trade_test/toll");
    static final Identifier ONEWAY = Alina.id("trade_test/oneway");
    private static final String FIXTURES = "/alina/fixtures/trade/";
    private static final GuildSettings SETTINGS = GuildSettings.DEFAULTS;

    private final GameTestHelper helper;
    private final MinecraftServer server;
    private final MarketFaults faults = new MarketFaults();
    private final AlinaStorage storage = faulty(new InMemoryStorage(), faults);
    private final GuildTestClock clock = new GuildTestClock(START);
    private final AtomicReference<TradeContent> content = new AtomicReference<>();
    private final AtomicLong revision = new AtomicLong(REVISION);
    private final List<ServerPlayer> online = new ArrayList<>();
    private final RecordingMessenger messenger = new RecordingMessenger();
    private final SettlementService settlement;
    private final TradeService service;

    private TradeRig(GameTestHelper helper, String fixture) {
        this.helper = helper;
        this.server = helper.getLevel().getServer();
        this.content.set(parse(readFixture(helper, fixture)));
        this.settlement = new SettlementService(server, storage, UUID.randomUUID(), () -> online,
                (player, expected) -> PlayerDataSaves.saveAndVerify(server, player, expected));
        this.service = new TradeService(server, storage, settlement, content::get, () -> SETTINGS, revision::get, clock,
                () -> online, messenger, player -> OptionalInt.of(0), (player, achievement) -> Optional.of(false));
    }

    /** A rig over the fixture, with no player yet. */
    static TradeRig create(GameTestHelper helper, String fixture) {
        return new TradeRig(helper, fixture);
    }

    /** The text of a fixture under {@code alina/fixtures/trade/}. */
    static String readFixture(GameTestHelper helper, String name) {
        try (InputStream in = TradeRig.class.getResourceAsStream(FIXTURES + name)) {
            if (in == null) {
                throw helper.assertionException(Component.literal("missing fixture " + name));
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    TradeService service() {
        return service;
    }

    /** The storage the rig's services write to, for a test that makes a write of its own. */
    AlinaStorage storage() {
        return storage;
    }

    /**
     * Makes the next call of {@code method} on the storage fail, or be refused, once. The market writes a test can make fail are
     * {@code restockMarket} and {@code adjustMarket}.
     */
    void failNext(String method, Refusal how) {
        faults.failNext(method, how);
    }

    SettlementService settlement() {
        return settlement;
    }

    RecordingMessenger messenger() {
        return messenger;
    }

    GuildTestClock clock() {
        return clock;
    }

    /** Sets the active content revision the service reads. A change closes the sessions opened under the old one. */
    void setRevision(long value) {
        revision.set(value);
    }

    /** Replaces the active content with the fixture's {@code alina:trade} section. */
    void useContent(String fixture) {
        content.set(parse(readFixture(helper, fixture)));
    }

    /** A mock player that is not in the server's player list. It is online for this rig's services. */
    ServerPlayer newPlayer() {
        ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);
        online.add(player);
        return player;
    }

    /** A new player, joined to this rig's service. */
    ServerPlayer joined() {
        ServerPlayer player = newPlayer();
        service.join(player);
        return player;
    }

    /** Ticks the trade service and the settlement service, as the server does once per game tick. */
    void tick() {
        service.tick();
        settlement.tick();
    }

    /** The board day the service is on now, with the Guild's default reset. */
    LocalDate day() {
        return BoardDay.dateOf(clock.instant(), SETTINGS.resetTime(), SETTINGS.zone());
    }

    boolean loaded(ServerPlayer player) {
        return service.loaded(player);
    }

    /** The session id of the last session opened for the player. Fails the test when none was opened. */
    int sessionId(ServerPlayer player) {
        return messenger.last(player, TradeOpenPayload.class)
                .orElseThrow(() -> helper.assertionException(Component.literal("no shop was opened for the player")))
                .sessionId();
    }

    /** Whether the player's last view says no trade is settling. False when no view was sent. */
    boolean idle(ServerPlayer player) {
        return messenger.lastView(player).map(view -> !view.busy()).orElse(false);
    }

    /**
     * The Aur storage holds for the player at the shop on the day, or -1 while the storage read has not completed. The read
     * is a plain call; the result is read on the server thread once the storage completes.
     */
    int storedAur(ServerPlayer player, Identifier shop, LocalDate on) {
        return stored(player, on).map(state -> state.aurPaid().getOrDefault(
                new town.lampas.alina.storage.TradeBudgetKey(shop, on), 0)).orElse(-1);
    }

    /** The purchases storage holds for the player at the offer and period, or -1 while the storage read is not complete. */
    int storedPurchases(ServerPlayer player, Identifier shop, String offer, String period, LocalDate on) {
        return stored(player, on).map(state -> state.purchases().getOrDefault(
                new town.lampas.alina.storage.TradePurchaseKey(shop, offer, period), 0)).orElse(-1);
    }

    /** The experience market row storage holds, or empty when there is none. The read completes on the calling thread. */
    Optional<MarketState> storedMarket() {
        return storage.loadMarket().join();
    }

    /** The newest ticks storage holds for the market, newest first: the reserves after each change of the pool. */
    List<MarketTick> storedMarketTicks() {
        return storage.loadMarketTicks(64).join();
    }

    /** The rows storage holds for the player. The read is in memory, and it does not go through the server thread. */
    private Optional<TradeState> stored(ServerPlayer player, LocalDate on) {
        return Optional.of(storage.loadTradeState(player.getUUID(), on).join());
    }

    private TradeContent parse(String json) {
        DataResult<ContentBundle> result = HaruhiServices.content().parseAndValidate(json);
        ContentBundle bundle = result.result().orElseThrow(() -> helper.assertionException(Component.literal(
                "the fixture was refused:\n" + result.error().map(error -> error.message()).orElse(""))));
        return bundle.extension(AlinaContent.TRADE).orElseThrow(() -> helper.assertionException(
                Component.literal("the fixture has no alina:trade section")));
    }

    /** How a storage call the test arranged to fail is answered: the future fails, or it completes with false. */
    enum Refusal {
        FAILURE,
        NOT_APPLIED
    }

    /** The one-shot storage refusals a test has arranged, by method name. */
    static final class MarketFaults {
        private final java.util.Map<String, Refusal> next = new java.util.HashMap<>();

        synchronized void failNext(String method, Refusal how) {
            next.put(method, how);
        }

        synchronized Optional<Refusal> take(String method) {
            return Optional.ofNullable(next.remove(method));
        }
    }

    /**
     * The storage with the arranged refusals: a call of a method with one pending is answered by the refusal, and every other
     * call goes to {@code delegate}.
     */
    private static AlinaStorage faulty(AlinaStorage delegate, MarketFaults faults) {
        InvocationHandler handler = (proxy, method, args) -> {
            Optional<Refusal> refusal = faults.take(method.getName());
            if (refusal.isPresent()) {
                return refusal.get() == Refusal.FAILURE
                        ? CompletableFuture.failedFuture(new IllegalStateException("the test failed " + method.getName()))
                        : CompletableFuture.completedFuture(false);
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        };
        return (AlinaStorage) Proxy.newProxyInstance(AlinaStorage.class.getClassLoader(),
                new Class<?>[] {AlinaStorage.class}, handler);
    }

    /**
     * Records every payload the service sends, per player, and answers which view and result came last. Sending to a player
     * the test never made is recorded too.
     */
    static final class RecordingMessenger implements TradeMessenger {
        private final java.util.Map<UUID, List<Object>> sent = new java.util.HashMap<>();
        private boolean canOpen = true;

        /** Whether {@link #canOpen} answers yes. A test sets it no to see a client without the mod refused. */
        void setCanOpen(boolean value) {
            canOpen = value;
        }

        @Override
        public boolean canOpen(ServerPlayer player) {
            return canOpen;
        }

        @Override
        public void open(ServerPlayer player, TradeOpenPayload payload) {
            record(player, payload);
        }

        @Override
        public void view(ServerPlayer player, TradeViewPayload payload) {
            record(player, payload);
        }

        @Override
        public void result(ServerPlayer player, TradeResultPayload payload) {
            record(player, payload);
        }

        @Override
        public void close(ServerPlayer player, TradeClosePayload payload) {
            record(player, payload);
        }

        /** Every payload of the type sent to the player, oldest first. */
        <T> List<T> all(ServerPlayer player, Class<T> type) {
            return sent.getOrDefault(player.getUUID(), List.of()).stream().filter(type::isInstance).map(type::cast).toList();
        }

        <T> Optional<T> last(ServerPlayer player, Class<T> type) {
            List<T> all = all(player, type);
            return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
        }

        /** The view of the latest open or view payload sent to the player. */
        Optional<TradeView> lastView(ServerPlayer player) {
            List<Object> events = sent.getOrDefault(player.getUUID(), List.of());
            for (int i = events.size() - 1; i >= 0; i--) {
                if (events.get(i) instanceof TradeOpenPayload open) {
                    return Optional.of(open.view());
                }
                if (events.get(i) instanceof TradeViewPayload view) {
                    return Optional.of(view.view());
                }
            }
            return Optional.empty();
        }

        private void record(ServerPlayer player, Object payload) {
            sent.computeIfAbsent(player.getUUID(), id -> new ArrayList<>()).add(payload);
        }
    }
}
