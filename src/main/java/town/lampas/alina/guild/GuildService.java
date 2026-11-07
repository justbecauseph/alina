package town.lampas.alina.guild;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import town.lampas.alina.Alina;
import town.lampas.alina.content.AlinaContent;
import town.lampas.alina.content.Board;
import town.lampas.alina.content.Commission;
import town.lampas.alina.content.Cooldown;
import town.lampas.alina.content.GuildContent;
import town.lampas.alina.content.GuildSettings;
import town.lampas.alina.content.Proof;
import town.lampas.alina.criteria.AlinaTriggers;
import town.lampas.alina.criteria.CriterionWatchers;
import town.lampas.alina.guild.board.BoardDay;
import town.lampas.alina.guild.board.BoardDraw;
import town.lampas.alina.guild.proof.ItemPlan;
import town.lampas.alina.guild.proof.ItemProofPlanner;
import town.lampas.alina.settlement.PendingOperation;
import town.lampas.alina.settlement.SettledOperation;
import town.lampas.alina.settlement.SettlementRequest;
import town.lampas.alina.settlement.SettlementService;
import town.lampas.alina.storage.AlinaStorage;
import town.lampas.alina.storage.CharacterState;
import town.lampas.alina.storage.CommissionState;
import town.lampas.alina.storage.CommissionStatus;
import town.lampas.alina.storage.Operation;
import town.lampas.alina.storage.OperationEffect;
import town.lampas.alina.storage.OperationKind;
import town.lampas.alina.storage.StoredBoard;
import town.lampas.haruhi.api.HaruhiServices;

/**
 * The Guild (ALINA-PLAN §8): each online character's Guild record, today's board, accept, abandon and turn-in, and the
 * tracking of criterion and descent proofs. Server thread only, except where a method says otherwise.
 *
 * <p><b>Records.</b> {@link #join} loads the character's state from storage; until it arrives, {@link #view} is empty
 * and every operation is refused with {@link Refusal.Reason#LOADING}. A failed load is retried every
 * {@link #RETRY_TICKS} while the player is online. {@link #leave} writes pending progress and drops the record.
 * Storage calls for one character run one after another, in the order they were made, loads included, so a load never
 * reads past a write made before it.
 *
 * <p><b>Accept</b> is write-through and optimistic: the commission is {@link Standing#ACTIVE} in the record at once and
 * its proof tracking starts, and the commission is {@link Refusal.Reason#BUSY} for turn-in and abandon until the write
 * finishes. When the write succeeds, {@code alina:commission_accepted} fires and the player gets
 * {@link GuildText#accepted}. When it fails, the commission is taken out of the record, its tracking stops and the
 * player gets {@link GuildText#acceptFailed}. A repeatable must be on today's board; a feat need not be.
 *
 * <p><b>Abandon</b> frees the slot with no cooldown and no reward (open question 4's proposal). It is optimistic in the
 * same way: the commission leaves the record at once, so its slot is free for an accept, and the commission is
 * {@link Refusal.Reason#BUSY} until the write finishes. When the write fails and the record is still current, the row is
 * put back, with its progress and tracking, and the player gets {@link GuildText#abandonFailed}, if the active content
 * still has the commission and the record is under {@code active_cap}. Otherwise the write is repeated once, after
 * anything submitted in the meantime, and the commission stays busy until the repeat finishes. A repeat that succeeds
 * gives the player {@link GuildText#abandoned}. A repeat that fails too is logged, the commission is no longer busy, and
 * the player gets {@link GuildText#abandonFailed}; the row stays in storage, and the next load of the character restores
 * or withdraws it. A write that fails after the player has left is not repeated: the player, if online, gets
 * {@link GuildText#abandonFailed}, and the row is the next load's.
 *
 * <p><b>Turn-in</b> checks the proof now, then asks {@link SettlementService#begin} to settle it: the item proof's plan
 * is the removal, the reward and reputation come from the commission, and the effect sets the commission to
 * {@code COOLDOWN} until now plus its cooldown (or the next board reset), or to {@code COMPLETED} for a feat. The
 * request names the commission's category and kind, so {@code alina:commission_completed} fires on delivery. While the
 * operation is in the player's escrow the commission is {@link Standing#SETTLING}. When settlement records the storage
 * result, the record takes the effect and the reputation from it; when the reward is delivered, the commission leaves
 * {@code SETTLING} and the player gets {@link GuildText#commissionComplete}. Settlement's listeners keep the record
 * right for operations begun in an earlier session too: the operations in the escrow when a record loads are
 * {@code SETTLING}, and a record whose load overlapped a storage result is loaded again.
 *
 * <p><b>Proofs.</b> A criterion proof counts matching events through {@code CriterionWatchers} while the commission is
 * active and its progress is below the count, so events before acceptance never count. A descent proof is met by the
 * descent tracker (§8.3). Progress is cached and written behind every {@link #FLUSH_TICKS}, on leave and on stop. When
 * a proof is met the player gets {@link GuildText#ready}.
 *
 * <p><b>Boards.</b> Each board of the content has its own state: the board day it holds, whether its load is in flight,
 * the tick of its next retry, and the draw it holds, stored or not. Each tick, once the content's startup activation is
 * complete, the service makes sure every board holds the board of the current board day: it loads the board's stored
 * draw, or draws one from the board's pool (its repeatables in rotation, that {@link GuildContent#boardOf} puts in the
 * board) with {@code BoardDraw}, seeded by the board's id, and stores it. A board with an empty pool is not stored. A
 * board the content no longer has is dropped. {@link #board(Identifier)} filters the stored ids to the repeatables that
 * the active content's {@link GuildContent#boardOf} puts in this board, so a stored draw keeps no commission its pool no
 * longer holds. A commission that an activation moves to another board leaves its old board's view and entries at once,
 * and joins the new board at that board's next draw: the next board day, or the activation itself when the new board's
 * pool was empty and is drawn again then. A repeatable is accepted only when it is on today's draw of its own board; a
 * feat need not be drawn.
 *
 * <p><b>Content activation.</b> {@link #contentActivated} withdraws, for every loaded record, each active commission the
 * new content lacks (it is abandoned in storage and the player gets {@link GuildText#withdrawn}), stops all tracking and
 * starts it again from the new content. A record that loads withdraws the same way once haruhi's startup activation has
 * finished; haruhi runs its activation listeners before it reports startup complete, so the startup activation itself
 * withdraws from the records loaded before it. Nothing is withdrawn while the content is {@link GuildContent#EMPTY},
 * which is what the content is before any bundle is active, after a refused startup load, and when the active bundle
 * has no {@code alina:guild} section. Then a row the content lacks stays in the record and in storage, untracked,
 * until an activation with a section withdraws it. A withdrawal whose storage write fails is written once more; if that
 * fails too, the next load withdraws the row. Each board whose draw came from an empty pool is drawn again at once when
 * the content changes, and each stored draw is kept, so a board the new content still has keeps its draw less the
 * commissions its pool no longer holds.
 */
public final class GuildService {
    /** Server ticks between progress write-behind flushes: 5 seconds. */
    public static final long FLUSH_TICKS = 100;
    /** Server ticks before a failed load or board load is tried again: 5 seconds. */
    public static final long RETRY_TICKS = 100;
    /** Server ticks between descent tracker checks of players with no armed dive. */
    public static final long DESCENT_CHECK_TICKS = 5;
    private static final Runnable NOTHING = () -> {};

    private final MinecraftServer server;
    private final AlinaStorage storage;
    private final SettlementService settlement;
    private final Supplier<GuildContent> contentSource;
    private final LongSupplier revisionId;
    private final Clock clock;
    private final Supplier<? extends Collection<ServerPlayer>> online;
    private final BooleanSupplier contentReady;
    private final PlayerWrites writes = new PlayerWrites();
    private final DescentTracker descent;
    private final Map<UUID, PlayerRecord> records = new HashMap<>();
    private long loadGenerations;
    private long ticks;
    /** The content as the last tick saw it, so names of commissions a new content removes can still be shown. */
    private GuildContent known = GuildContent.EMPTY;
    /** The state of each board of the content, by board id. */
    private final Map<Identifier, BoardState> boards = new HashMap<>();

    /**
     * The service of the running server: the server's player list, the system clock, the active {@code alina:guild}
     * content and revision, and haruhi's startup activation.
     */
    public GuildService(MinecraftServer server, AlinaStorage storage, SettlementService settlement) {
        this(server, storage, settlement, AlinaContent::guild,
                () -> HaruhiServices.content().activeRevisionId().orElse(0L), Clock.systemUTC(),
                () -> server.getPlayerList().getPlayers(), () -> HaruhiServices.content().isStartupComplete());
    }

    /**
     * @param content the active {@code alina:guild} content, read each time it is needed
     * @param revisionId the active content revision, 0 when none is stored
     * @param clock real time, for the board day, cooldowns and timestamps
     * @param online the players {@link #tick} serves: progress flushes, descent tracking and load retries
     * @param contentReady true once the content's startup activation has finished; no board is drawn, and no commission
     *     is withdrawn, before
     */
    public GuildService(MinecraftServer server, AlinaStorage storage, SettlementService settlement,
            Supplier<GuildContent> content, LongSupplier revisionId, Clock clock,
            Supplier<? extends Collection<ServerPlayer>> online, BooleanSupplier contentReady) {
        this.server = Objects.requireNonNull(server, "server");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.settlement = Objects.requireNonNull(settlement, "settlement");
        this.contentSource = Objects.requireNonNull(content, "content");
        this.revisionId = Objects.requireNonNull(revisionId, "revisionId");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.online = Objects.requireNonNull(online, "online");
        this.contentReady = Objects.requireNonNull(contentReady, "contentReady");
        this.descent = new DescentTracker(server);
        settlement.addAppliedListener(this::applied);
        settlement.addSettledListener(this::settled);
    }

    /** Starts loading the player's record. A record already loaded or loading is loaded again. */
    public void join(ServerPlayer player) {
        requireServerThread();
        PlayerRecord previous = records.remove(player.getUUID());
        if (previous != null) {
            retire(previous);
        }
        PlayerRecord record = new PlayerRecord(player.getUUID());
        // The volumes the player stands in now are the baseline: a player who joins inside a volume has not entered it.
        record.volumesIn = descent.volumesHolding(player, contentSource.get());
        records.put(record.id, record);
        startLoad(record);
    }

    /** Writes the player's pending progress, stops their tracking and drops their record. */
    public void leave(ServerPlayer player) {
        requireServerThread();
        PlayerRecord record = records.remove(player.getUUID());
        if (record != null) {
            retire(record);
        }
    }

    /** Called every server tick: board day, load retries, progress flushes and the descent tracker. */
    public void tick() {
        requireServerThread();
        ticks++;
        GuildContent content = contentSource.get();
        known = content;
        if (contentReady.getAsBoolean()) {
            advanceBoards(content);
        }
        boolean descentDue = ticks % DESCENT_CHECK_TICKS == 0;
        for (ServerPlayer player : List.copyOf(online.get())) {
            PlayerRecord record = records.get(player.getUUID());
            if (record == null) {
                continue;
            }
            if (!record.loaded) {
                if (!record.loadInFlight && ticks >= record.retryAt) {
                    startLoad(record);
                }
                continue;
            }
            if (descentDue || !record.armed.isEmpty()) {
                for (Identifier commission : descent.check(player, record, content)) {
                    meet(record, commission);
                }
            }
        }
        if (ticks % FLUSH_TICKS == 0) {
            for (PlayerRecord record : List.copyOf(records.values())) {
                if (record.loaded) {
                    flush(record);
                }
            }
        }
        writes.prune();
    }

    /** Called when haruhi activates a bundle. See the class comment. */
    public void contentActivated() {
        requireServerThread();
        GuildContent content = contentSource.get();
        // An activation is real content even while haruhi's startup is unfinished, which is when its startup
        // activation runs this listener.
        boolean withdrawing = content != GuildContent.EMPTY;
        for (PlayerRecord record : List.copyOf(records.values())) {
            // Standing in a volume the new content defines is not entering it, so the baseline is taken now, also for a
            // record still loading: its baseline came from the content at join.
            ServerPlayer entity = online(record.id);
            if (entity != null) {
                record.volumesIn = descent.volumesHolding(entity, content);
            }
            if (!record.loaded) {
                continue;
            }
            closeTracking(record);
            if (withdrawing) {
                for (CommissionState row : List.copyOf(record.rows.values())) {
                    Identifier id = row.commissionId();
                    if (isActive(record, row) && !content.commissions().containsKey(id)) {
                        withdraw(record, id);
                    }
                }
            }
            for (CommissionState row : List.copyOf(record.rows.values())) {
                if (isActive(record, row)) {
                    startTracking(record, row.commissionId());
                }
            }
        }
        known = content;
        boards.keySet().removeIf(id -> !content.boards().containsKey(id));
        for (Identifier id : List.copyOf(boards.keySet())) {
            BoardState state = boards.get(id);
            if (state.held != null && !state.held.stored()) {
                redrawUnstored(id, state, content);
            }
        }
    }

    /**
     * Writes every record's pending progress and stops all tracking, as the server stops. The future completes when the
     * writes have finished or failed; it never fails.
     */
    public CompletableFuture<Void> stop() {
        requireServerThread();
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (PlayerRecord record : records.values()) {
            closeTracking(record);
            if (record.loaded) {
                pending.add(flush(record));
            }
        }
        records.clear();
        return CompletableFuture.allOf(pending.toArray(new CompletableFuture<?>[0]))
                .handle((ignored, failure) -> null);
    }

    /** The active {@code alina:guild} content. */
    public GuildContent content() {
        return contentSource.get();
    }

    /** The service clock's current instant. */
    public Instant now() {
        return clock.instant();
    }

    /** The player's record, or empty while it loads. */
    public Optional<GuildView> view(ServerPlayer player) {
        requireServerThread();
        PlayerRecord record = records.get(player.getUUID());
        if (record == null || !record.loaded) {
            return Optional.empty();
        }
        return Optional.of(viewOf(record));
    }

    /**
     * Whether the record is loaded and the player is under the active cap, counted as {@link #checkAccept} counts it. False
     * while the record loads.
     */
    public boolean underActiveCap(ServerPlayer player) {
        requireServerThread();
        PlayerRecord record = records.get(player.getUUID());
        return record != null && record.loaded && activeCount(record) < contentSource.get().settings().activeCap();
    }

    /**
     * Today's draw of the board, or empty while it loads, when the content has no such board, or while the draw is held
     * for a day the service has not reached. Its commissions are the stored ids that are still repeatables of this board's
     * pool in the active content.
     */
    public Optional<BoardView> board(Identifier board) {
        BoardState state = boards.get(board);
        GuildContent content = contentSource.get();
        if (state == null || state.held == null || !content.boards().containsKey(board)) {
            return Optional.empty();
        }
        HeldBoard held = state.held;
        List<Identifier> ids = held.ids().stream()
                .filter(id -> isRepeatableOf(content, board, id))
                .toList();
        return Optional.of(new BoardView(board, held.date(), held.revisionId(), ids, held.stored()));
    }

    /** Today's draw of every board of the content that has loaded, in board id order. */
    public List<BoardView> boards() {
        return contentSource.get().boards().keySet().stream()
                .map(this::board)
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * What the board shows this player, in order: the board's draw, then the feats of the board's pool that the player has
     * not completed, by id text. Empty while the record or the board's draw loads, or when the content has no such board.
     */
    public Optional<List<Identifier>> boardEntries(ServerPlayer player, Identifier board) {
        requireServerThread();
        PlayerRecord record = records.get(player.getUUID());
        if (record == null || !record.loaded) {
            return Optional.empty();
        }
        Optional<BoardView> today = board(board);
        if (today.isEmpty()) {
            return Optional.empty();
        }
        GuildContent content = contentSource.get();
        Board pool = content.boards().get(board);
        List<Identifier> entries = new ArrayList<>(today.get().commissions());
        content.commissions().values().stream()
                .filter(commission -> commission.kind() == Commission.Kind.FEAT && pool.holds(commission))
                .map(Commission::id)
                .sorted(Comparator.comparing(Identifier::toString))
                .filter(id -> !completed(record, id))
                .forEach(entries::add);
        return Optional.of(List.copyOf(entries));
    }

    /**
     * Why the player cannot accept the commission now, or empty when they can. Checked in this order: loading, unknown,
     * board loading and not on the board (repeatables), active, settling, busy, cooldown, completed, cap reached.
     */
    public Optional<Refusal> checkAccept(ServerPlayer player, Identifier commission) {
        requireServerThread();
        PlayerRecord record = records.get(player.getUUID());
        if (record == null || !record.loaded) {
            return refuse(Refusal.Reason.LOADING, GuildText.loading());
        }
        GuildContent content = contentSource.get();
        Commission found = content.commissions().get(commission);
        if (found == null) {
            return refuse(Refusal.Reason.UNKNOWN, GuildText.unknown(commission));
        }
        if (found.kind() == Commission.Kind.REPEATABLE) {
            // A repeatable is on the draw of the one board whose pool holds it.
            Optional<Board> own = content.boardOf(commission);
            if (own.isEmpty()) {
                return refuse(Refusal.Reason.NOT_ON_BOARD, GuildText.notOnBoard(found.name()));
            }
            Optional<BoardView> today = board(own.get().id());
            if (today.isEmpty()) {
                return refuse(Refusal.Reason.BOARD_LOADING, GuildText.boardLoading());
            }
            if (!today.get().commissions().contains(commission)) {
                return refuse(Refusal.Reason.NOT_ON_BOARD, GuildText.notOnBoard(found.name()));
            }
        }
        CommissionState row = record.rows.get(commission);
        boolean settling = record.settling.containsKey(commission);
        if (row != null && row.status() == CommissionStatus.ACTIVE && !settling) {
            return refuse(Refusal.Reason.ACTIVE, GuildText.active(found.name()));
        }
        if (settling) {
            return refuse(Refusal.Reason.SETTLING, GuildText.settling(found.name()));
        }
        if (record.busy.contains(commission)) {
            return refuse(Refusal.Reason.BUSY, GuildText.busy(found.name()));
        }
        Instant now = stamp();
        if (cooldownEnd(row).filter(end -> end.isAfter(now)).isPresent()) {
            Duration left = Duration.between(now, cooldownEnd(row).get());
            return refuse(Refusal.Reason.COOLDOWN, GuildText.cooldown(found.name(), left));
        }
        if (row != null && row.status() == CommissionStatus.COMPLETED) {
            return refuse(Refusal.Reason.COMPLETED, GuildText.completed(found.name()));
        }
        int cap = content.settings().activeCap();
        if (activeCount(record) >= cap) {
            return refuse(Refusal.Reason.CAP_REACHED, GuildText.capReached(cap));
        }
        return Optional.empty();
    }

    /**
     * Why the player cannot turn the commission in now, or empty when they can. Checked in this order: loading,
     * unknown, settling, not active, busy, proof not met. The proof-not-met message is the commission's hint.
     */
    public Optional<Refusal> checkTurnIn(ServerPlayer player, Identifier commission) {
        requireServerThread();
        PlayerRecord record = records.get(player.getUUID());
        if (record == null || !record.loaded) {
            return refuse(Refusal.Reason.LOADING, GuildText.loading());
        }
        Commission found = contentSource.get().commissions().get(commission);
        if (found == null) {
            return refuse(Refusal.Reason.UNKNOWN, GuildText.unknown(commission));
        }
        if (record.settling.containsKey(commission)) {
            return refuse(Refusal.Reason.SETTLING, GuildText.settling(found.name()));
        }
        CommissionState row = record.rows.get(commission);
        if (row == null || row.status() != CommissionStatus.ACTIVE) {
            return refuse(Refusal.Reason.NOT_ACTIVE, GuildText.notActive(found.name()));
        }
        if (record.busy.contains(commission)) {
            return refuse(Refusal.Reason.BUSY, GuildText.busy(found.name()));
        }
        if (!proofMet(player, found, row)) {
            return refuse(Refusal.Reason.PROOF_NOT_MET, GuildText.proofNotMet(found.name(), found.hint()));
        }
        return Optional.empty();
    }

    /** Why the player cannot abandon the commission now, or empty when they can: loading, unknown, settling, not active, busy. */
    public Optional<Refusal> checkAbandon(ServerPlayer player, Identifier commission) {
        requireServerThread();
        PlayerRecord record = records.get(player.getUUID());
        if (record == null || !record.loaded) {
            return refuse(Refusal.Reason.LOADING, GuildText.loading());
        }
        Commission found = contentSource.get().commissions().get(commission);
        if (found == null) {
            return refuse(Refusal.Reason.UNKNOWN, GuildText.unknown(commission));
        }
        if (record.settling.containsKey(commission)) {
            return refuse(Refusal.Reason.SETTLING, GuildText.settling(found.name()));
        }
        CommissionState row = record.rows.get(commission);
        if (row == null || row.status() != CommissionStatus.ACTIVE) {
            return refuse(Refusal.Reason.NOT_ACTIVE, GuildText.notActive(found.name()));
        }
        if (record.busy.contains(commission)) {
            return refuse(Refusal.Reason.BUSY, GuildText.busy(found.name()));
        }
        return Optional.empty();
    }

    /** Accepts the commission, or returns why not; checks again first. See the class comment. */
    public Optional<Refusal> accept(ServerPlayer player, Identifier commission) {
        requireServerThread();
        Optional<Refusal> refused = checkAccept(player, commission);
        if (refused.isPresent()) {
            return refused;
        }
        UUID id = player.getUUID();
        PlayerRecord record = records.get(id);
        Commission accepted = contentSource.get().commissions().get(commission);
        long revision = revisionId.getAsLong();
        Instant at = stamp();
        record.rows.put(commission, new CommissionState(
                commission, CommissionStatus.ACTIVE, 0, at, Optional.empty(), Optional.empty(), revision));
        record.busy.add(commission);
        // A new acceptance starts at progress 0, so unwritten progress left for the commission is stale.
        record.dirty.remove(commission);
        startTracking(record, commission);
        writes.submit(id, () -> storage.acceptCommission(id, commission, revision, at))
                .whenComplete((ignored, failure) -> onServer("acceptance of " + commission,
                        () -> acceptWritten(record, accepted, at, failure), NOTHING));
        return Optional.empty();
    }

    /**
     * Begins the turn-in, or returns why not; checks again first. {@link Refusal.Reason#SETTLEMENT_REFUSED} when
     * settlement refused to begin, and then nothing changed. On success the player gets {@link GuildText#turnedIn}.
     */
    public Optional<Refusal> turnIn(ServerPlayer player, Identifier commission) {
        requireServerThread();
        Optional<Refusal> refused = checkTurnIn(player, commission);
        if (refused.isPresent()) {
            return refused;
        }
        PlayerRecord record = records.get(player.getUUID());
        GuildContent content = contentSource.get();
        Commission turned = content.commissions().get(commission);
        Instant now = stamp();
        SettlementRequest request = new SettlementRequest(
                OperationKind.TURN_IN,
                commission,
                0,
                revisionId.getAsLong(),
                turned.reward().lootTable(),
                turned.reward().reputation(),
                effect(turned, content.settings(), now),
                removal(turned.proof()),
                Optional.of(turned.category()),
                Optional.of(kindName(turned)));
        Optional<UUID> operation;
        try {
            operation = settlement.begin(player, request);
        } catch (IllegalArgumentException failure) {
            Alina.LOGGER.warn("Alina could not begin the turn-in of {} for {}; settlement refused it",
                    commission, player.getUUID(), failure);
            return refuse(Refusal.Reason.SETTLEMENT_REFUSED, GuildText.settlementRefused(turned.name()));
        }
        if (operation.isEmpty()) {
            return refuse(Refusal.Reason.SETTLEMENT_REFUSED, GuildText.settlementRefused(turned.name()));
        }
        record.settling.put(commission, now);
        stopTracking(record, commission);
        player.sendSystemMessage(GuildText.turnedIn(turned.name()));
        return Optional.empty();
    }

    /** Abandons the commission, or returns why not; checks again first. See the class comment. */
    public Optional<Refusal> abandon(ServerPlayer player, Identifier commission) {
        requireServerThread();
        Optional<Refusal> refused = checkAbandon(player, commission);
        if (refused.isPresent()) {
            return refused;
        }
        PlayerRecord record = records.get(player.getUUID());
        Commission abandoned = contentSource.get().commissions().get(commission);
        CommissionState row = record.rows.remove(commission);
        stopTracking(record, commission);
        record.busy.add(commission);
        writeAbandon(record, abandoned, row, false);
        return Optional.empty();
    }

    /**
     * For staff: sets the character's reputation in storage, then in the record if it is loaded. Any thread may wait
     * on the future; it completes after the record is updated on the server thread.
     */
    public CompletableFuture<Void> setReputation(ServerPlayer player, int reputation) {
        UUID id = player.getUUID();
        CompletableFuture<Void> done = new CompletableFuture<>();
        Runnable start = () -> writes.submit(id, () -> storage.setReputation(id, reputation, stamp()))
                .whenComplete((ignored, failure) -> onServer("reputation write of " + id, () -> {
                    if (failure != null) {
                        done.completeExceptionally(failure);
                        return;
                    }
                    PlayerRecord record = records.get(id);
                    if (record != null && record.loaded) {
                        record.reputation = reputation;
                    } else if (record != null) {
                        // The load in flight may have read the old reputation, so it is repeated.
                        record.reloadRequested = true;
                    }
                    done.complete(null);
                }, () -> done.completeExceptionally(new IllegalStateException("the server has stopped"))));
        if (server.isSameThread()) {
            start.run();
        } else {
            server.execute(start);
        }
        return done;
    }

    /**
     * For staff: deletes the commission's state whatever its status (it becomes available), in storage and then in the
     * record. Refused with {@link Refusal.Reason#SETTLING} while it is being settled. Its tracking keeps running while the
     * delete is written, and stops when the delete succeeds, which also drops its unwritten progress. A failed delete
     * changes nothing except the busy mark, so the commission keeps counting.
     */
    public CompletableFuture<Optional<Refusal>> reset(ServerPlayer player, Identifier commission) {
        UUID id = player.getUUID();
        CompletableFuture<Optional<Refusal>> done = new CompletableFuture<>();
        Runnable start = () -> {
            PlayerRecord record = records.get(id);
            if (record == null || !record.loaded) {
                done.complete(refuse(Refusal.Reason.LOADING, GuildText.loading()));
                return;
            }
            Optional<Commission> found = Optional.ofNullable(contentSource.get().commissions().get(commission));
            Component name = found.map(Commission::name).orElseGet(() -> Component.literal(commission.toString()));
            if (record.settling.containsKey(commission)) {
                done.complete(refuse(Refusal.Reason.SETTLING, GuildText.settling(name)));
                return;
            }
            if (record.busy.contains(commission)) {
                done.complete(refuse(Refusal.Reason.BUSY, GuildText.busy(name)));
                return;
            }
            record.busy.add(commission);
            writes.submit(id, () -> storage.clearCommission(id, commission))
                    .whenComplete((ignored, failure) -> onServer("reset of " + commission, () -> {
                        if (isCurrent(record)) {
                            record.busy.remove(commission);
                            if (failure == null) {
                                record.rows.remove(commission);
                                stopTracking(record, commission);
                            }
                        }
                        if (failure != null) {
                            done.completeExceptionally(failure);
                        } else {
                            done.complete(Optional.empty());
                        }
                    }, () -> done.completeExceptionally(new IllegalStateException("the server has stopped"))));
        };
        if (server.isSameThread()) {
            start.run();
        } else {
            server.execute(start);
        }
        return done;
    }

    // Records and storage.

    private void startLoad(PlayerRecord record) {
        long generation = ++loadGenerations;
        record.loadGeneration = generation;
        record.loadInFlight = true;
        UUID id = record.id;
        writes.submit(id, () -> storage.loadCharacter(id))
                .whenComplete((state, failure) -> onServer("load of " + id,
                        () -> loaded(record, generation, state, failure), NOTHING));
    }

    private void loaded(PlayerRecord record, long generation, CharacterState state, Throwable failure) {
        if (!isCurrent(record) || record.loadGeneration != generation) {
            return;
        }
        record.loadInFlight = false;
        if (failure != null) {
            record.retryAt = ticks + RETRY_TICKS;
            Alina.LOGGER.warn("Alina could not load the Guild record of {}; it is tried again in 5 seconds",
                    record.id, failure);
            return;
        }
        ServerPlayer entity = online(record.id);
        if (entity == null) {
            return;
        }
        List<PendingOperation> pending;
        try {
            pending = settlement.pendingFor(entity);
        } catch (RuntimeException failed) {
            record.retryAt = ticks + RETRY_TICKS;
            Alina.LOGGER.warn("Alina could not read the settlement escrow of {}; the Guild record is tried again in "
                    + "5 seconds", record.id, failed);
            return;
        }
        record.reputation = state.reputation();
        record.rows.clear();
        record.rows.putAll(state.commissions());
        record.settling.clear();
        for (PendingOperation operation : pending) {
            if (operation.kind() == OperationKind.TURN_IN) {
                record.settling.put(operation.subjectId(), operation.createdAt());
            }
        }
        if (record.reloadRequested) {
            record.reloadRequested = false;
            startLoad(record);
            return;
        }
        record.loaded = true;
        GuildContent content = contentSource.get();
        if (withdrawalAllowed(content)) {
            for (CommissionState row : List.copyOf(record.rows.values())) {
                Identifier id = row.commissionId();
                if (isActive(record, row) && !content.commissions().containsKey(id)) {
                    withdraw(record, id);
                }
            }
        }
        for (CommissionState row : List.copyOf(record.rows.values())) {
            if (isActive(record, row)) {
                startTracking(record, row.commissionId());
            }
        }
    }

    private void withdraw(PlayerRecord record, Identifier commission) {
        withdraw(record, commission, knownName(commission));
    }

    /** Removes the commission from the record and writes its storage row away. {@code name} is the name the player is told. */
    private void withdraw(PlayerRecord record, Identifier commission, Component name) {
        record.rows.remove(commission);
        stopTracking(record, commission);
        writeWithdrawal(record, commission, name, false);
    }

    /** Writes the storage delete of a withdrawal. {@code retried} is true for the second write. */
    private void writeWithdrawal(PlayerRecord record, Identifier commission, Component name, boolean retried) {
        writes.submit(record.id, () -> storage.abandonCommission(record.id, commission))
                .whenComplete((removed, failure) -> onServer("withdrawal of " + commission,
                        () -> withdrawn(record, commission, name, failure, retried), NOTHING));
    }

    private void withdrawn(PlayerRecord record, Identifier commission, Component name, Throwable failure,
            boolean retried) {
        if (failure != null) {
            if (!retried) {
                Alina.LOGGER.warn("Alina could not withdraw {} for {}; it is written again", commission, record.id,
                        failure);
                writeWithdrawal(record, commission, name, true);
                return;
            }
            Alina.LOGGER.warn("Alina could not abandon a withdrawn commission for {}; the next load tries again",
                    record.id, failure);
            return;
        }
        ServerPlayer entity = online(record.id);
        if (entity != null) {
            entity.sendSystemMessage(GuildText.withdrawn(name));
        }
    }

    private void retire(PlayerRecord record) {
        closeTracking(record);
        if (record.loaded) {
            flush(record);
        }
    }

    /** Writes the record's dirty progress. The returned future is the storage call, and never fails. */
    private CompletableFuture<Void> flush(PlayerRecord record) {
        if (record.dirty.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        Map<Identifier, Integer> taken = Map.copyOf(record.dirty);
        record.dirty.clear();
        UUID id = record.id;
        CompletableFuture<Void> write = writes.submit(id, () -> storage.writeCommissionProgress(id, taken));
        write.whenComplete((ignored, failure) -> onServer("progress write of " + id,
                () -> flushed(record, taken, failure), () -> {
                    if (failure != null) {
                        Alina.LOGGER.warn("Alina could not write the progress of {}, and the server had stopped; it is lost",
                                id, failure);
                    }
                }));
        return write;
    }

    private void flushed(PlayerRecord record, Map<Identifier, Integer> taken, Throwable failure) {
        if (failure == null) {
            return;
        }
        if (!isCurrent(record)) {
            Alina.LOGGER.warn("Alina could not write the progress of {}; the progress of {} commissions is lost",
                    record.id, taken.size(), failure);
            return;
        }
        Alina.LOGGER.warn("Alina could not write the progress of {}; it is tried again on the next flush",
                record.id, failure);
        for (Identifier commission : taken.keySet()) {
            CommissionState row = record.rows.get(commission);
            if (row != null && row.status() == CommissionStatus.ACTIVE && !record.dirty.containsKey(commission)) {
                record.dirty.put(commission, row.progress());
            }
        }
    }

    // Accept, abandon and turn-in completions.

    private void acceptWritten(PlayerRecord record, Commission accepted, Instant at, Throwable failure) {
        boolean current = isCurrent(record);
        if (current) {
            record.busy.remove(accepted.id());
        }
        ServerPlayer entity = online(record.id);
        if (failure == null) {
            if (entity != null) {
                AlinaTriggers.COMMISSION_ACCEPTED.trigger(entity, accepted.id(), accepted.category(),
                        kindName(accepted));
                entity.sendSystemMessage(GuildText.accepted(accepted.name()));
            }
            return;
        }
        Alina.LOGGER.warn("Alina could not record the acceptance of {} for {}; it is available again",
                accepted.id(), record.id, failure);
        if (current) {
            CommissionState row = record.rows.get(accepted.id());
            if (row != null && row.status() == CommissionStatus.ACTIVE && at.equals(row.acceptedAt())) {
                record.rows.remove(accepted.id());
                stopTracking(record, accepted.id());
            }
        }
        if (entity != null) {
            entity.sendSystemMessage(GuildText.acceptFailed(accepted.name()));
        }
    }

    /** Writes the abandon of a commission the record no longer holds. {@code retried} is true for the second write. */
    private void writeAbandon(PlayerRecord record, Commission abandoned, CommissionState row, boolean retried) {
        Identifier commission = abandoned.id();
        writes.submit(record.id, () -> storage.abandonCommission(record.id, commission))
                .whenComplete((removed, failure) -> onServer("abandon of " + commission,
                        () -> abandonWritten(record, abandoned, row, failure, retried), NOTHING));
    }

    /**
     * An abandon's write has answered. On a failure for a current record, the row goes back when the content still has the
     * commission and a slot is free. Otherwise the write is repeated once; a second failure leaves the row to the next load.
     */
    private void abandonWritten(PlayerRecord record, Commission abandoned, CommissionState row, Throwable failure,
            boolean retried) {
        Identifier commission = abandoned.id();
        boolean current = isCurrent(record);
        ServerPlayer entity = online(record.id);
        if (failure == null) {
            if (current) {
                record.busy.remove(commission);
            }
            if (entity != null) {
                entity.sendSystemMessage(GuildText.abandoned(abandoned.name()));
            }
            return;
        }
        Alina.LOGGER.warn("Alina could not abandon {} for {}", commission, record.id, failure);
        if (current && !retried) {
            GuildContent content = contentSource.get();
            if (content.commissions().containsKey(commission) && activeCount(record) < content.settings().activeCap()) {
                record.busy.remove(commission);
                record.rows.put(commission, row);
                if (row.progress() > 0) {
                    record.dirty.put(commission, row.progress());
                }
                startTracking(record, commission);
            } else {
                // The slot was taken while the abandon was written, or the content no longer has the commission. The
                // commission stays busy, and the write is repeated after anything submitted since.
                writeAbandon(record, abandoned, row, true);
                return;
            }
        } else if (current) {
            // The repeat failed too. Storage still holds the row, and the record does not: the next load restores or
            // withdraws it.
            record.busy.remove(commission);
            Alina.LOGGER.warn("Alina could not abandon {} for {} a second time; storage still holds its row, and the next "
                    + "load of the character restores or withdraws it", commission, record.id);
        }
        if (entity != null) {
            entity.sendSystemMessage(GuildText.abandonFailed(abandoned.name()));
        }
    }

    private void settled(SettledOperation settled) {
        if (!listenerOnServer("settlement of an operation", settled.player().getUUID())) {
            return;
        }
        Operation operation = settled.operation();
        if (operation.kind() != OperationKind.TURN_IN) {
            return;
        }
        Identifier commission = operation.subjectId();
        PlayerRecord record = records.get(settled.player().getUUID());
        if (record != null) {
            record.settling.remove(commission);
            if (record.loaded && operation.effect() instanceof OperationEffect.CommissionSettled effect) {
                // Normally the applied listener has set the row already. A row still active here came from a load
                // that read storage before the result was recorded, so the effect is set now.
                CommissionState row = record.rows.get(commission);
                if (row != null && row.status() == CommissionStatus.ACTIVE) {
                    commissionSettled(record, operation, effect);
                }
            }
        }
        ServerPlayer entity = online(settled.player().getUUID());
        if (entity != null) {
            entity.sendSystemMessage(GuildText.commissionComplete(nameOf(commission)));
        }
    }

    /** Storage recorded a settlement result: the record takes the effect and the reputation. */
    private void applied(SettledOperation settled) {
        if (!listenerOnServer("storage result of an operation", settled.player().getUUID())) {
            return;
        }
        PlayerRecord record = records.get(settled.player().getUUID());
        if (record == null) {
            return;
        }
        if (!record.loaded) {
            record.reloadRequested = true;
            return;
        }
        Operation operation = settled.operation();
        record.reputation = settled.result().reputation();
        if (operation.kind() == OperationKind.TURN_IN && operation.effect() instanceof OperationEffect.CommissionSettled effect) {
            commissionSettled(record, operation, effect);
        }
    }

    private void commissionSettled(PlayerRecord record, Operation operation, OperationEffect.CommissionSettled effect) {
        Identifier commission = effect.commissionId();
        CommissionState row = record.rows.get(commission);
        Instant acceptedAt = row != null ? row.acceptedAt() : operation.createdAt().truncatedTo(ChronoUnit.MICROS);
        long revision = row != null ? row.revisionId() : operation.revisionId();
        Optional<Instant> cooldownUntil = effect.cooldownUntil().map(end -> end.truncatedTo(ChronoUnit.MICROS));
        record.rows.put(commission, new CommissionState(commission, effect.status(), 0, acceptedAt,
                Optional.of(operation.createdAt().truncatedTo(ChronoUnit.MICROS)), cooldownUntil, revision));
        stopTracking(record, commission);
    }

    // Tracking.

    private void startTracking(PlayerRecord record, Identifier commission) {
        CommissionState row = record.rows.get(commission);
        Commission found = contentSource.get().commissions().get(commission);
        if (row == null || row.status() != CommissionStatus.ACTIVE || record.settling.containsKey(commission)
                || found == null) {
            return;
        }
        if (!(found.proof() instanceof Proof.Criterion criterion) || row.progress() >= criterion.criterion().count()) {
            return;
        }
        CriterionWatchers.Watch previous = record.watches.remove(commission);
        if (previous != null) {
            previous.close();
        }
        try {
            record.watches.put(commission,
                    CriterionWatchers.watch(record.id, criterion.criterion(), player -> progressed(record, commission)));
        } catch (RuntimeException failure) {
            Alina.LOGGER.error("Alina could not watch the criterion of {} for {}", commission, record.id, failure);
        }
    }

    /** One matching event for a criterion proof: counts it, and closes the watch when the count is reached. */
    private void progressed(PlayerRecord record, Identifier commission) {
        if (!isCurrent(record) || !record.loaded) {
            return;
        }
        CommissionState row = record.rows.get(commission);
        Commission found = contentSource.get().commissions().get(commission);
        if (row == null || row.status() != CommissionStatus.ACTIVE || record.settling.containsKey(commission)
                || found == null || !(found.proof() instanceof Proof.Criterion criterion)) {
            stopTracking(record, commission);
            return;
        }
        int count = criterion.criterion().count();
        int progress = Math.min(count, row.progress() + 1);
        record.rows.put(commission, withProgress(row, progress));
        record.dirty.put(commission, progress);
        if (progress >= count) {
            CriterionWatchers.Watch watch = record.watches.remove(commission);
            if (watch != null) {
                watch.close();
            }
            ServerPlayer entity = online(record.id);
            if (entity != null) {
                entity.sendSystemMessage(GuildText.ready(found.name()));
            }
        }
    }

    /** A descent proof is met: its progress is 1. */
    private void meet(PlayerRecord record, Identifier commission) {
        CommissionState row = record.rows.get(commission);
        if (row == null || row.status() != CommissionStatus.ACTIVE || row.progress() != 0) {
            return;
        }
        record.rows.put(commission, withProgress(row, 1));
        record.dirty.put(commission, 1);
        ServerPlayer entity = online(record.id);
        Commission found = contentSource.get().commissions().get(commission);
        if (entity != null && found != null) {
            entity.sendSystemMessage(GuildText.ready(found.name()));
        }
    }

    /** Stops one commission's tracking: its watch, its dive and its unwritten progress. */
    private void stopTracking(PlayerRecord record, Identifier commission) {
        CriterionWatchers.Watch watch = record.watches.remove(commission);
        if (watch != null) {
            watch.close();
        }
        record.dirty.remove(commission);
        record.armed.remove(commission);
        descent.sync(record);
    }

    /** Stops every watch and dive of the record. Its unwritten progress stays in {@code dirty}. */
    private void closeTracking(PlayerRecord record) {
        record.watches.values().forEach(CriterionWatchers.Watch::close);
        record.watches.clear();
        descent.disarmAll(record);
    }

    // Board.

    /** Brings every board of the content to the current board day. Boards the content no longer has are dropped first. */
    private void advanceBoards(GuildContent content) {
        boards.keySet().removeIf(id -> !content.boards().containsKey(id));
        LocalDate today = BoardDay.dateOf(clock.instant(), content.settings().resetTime(), content.settings().zone());
        for (Board board : content.boards().values()) {
            BoardState state = boards.computeIfAbsent(board.id(), id -> new BoardState());
            if (state.loading || ticks < state.retryAt || today.equals(state.date)) {
                continue;
            }
            requestBoard(state, board.id(), today);
        }
    }

    /** Starts loading the board's draw for {@code date}, and holds nothing of it until the load answers. */
    private void requestBoard(BoardState state, Identifier board, LocalDate date) {
        state.loading = true;
        state.date = date;
        state.held = null;
        storage.loadBoard(date, board)
                .whenComplete((stored, failure) -> onServer("board load of " + board + " for " + date,
                        () -> boardLoaded(state, board, date, stored, failure), NOTHING));
    }

    /** Whether the state is still the one the content's board holds, so a completion for a dropped board is ignored. */
    private boolean isHeld(BoardState state, Identifier board) {
        return boards.get(board) == state;
    }

    private void boardLoaded(BoardState state, Identifier board, LocalDate date, Optional<StoredBoard> stored,
            Throwable failure) {
        if (!isHeld(state, board)) {
            return;
        }
        if (failure != null) {
            boardFailed(state, board, date, failure);
            return;
        }
        if (stored.isPresent()) {
            adoptBoard(state, stored.get());
            return;
        }
        GuildContent content = contentSource.get();
        Board found = content.boards().get(board);
        if (found == null) {
            boards.remove(board);
            return;
        }
        long revision = revisionId.getAsLong();
        long seed = BoardDraw.seed(content.settings().salt(), date, revision, board);
        List<Identifier> ids = BoardDraw.draw(boardPool(content, found), found.size(), seed);
        if (ids.isEmpty()) {
            state.held = new HeldBoard(date, revision, List.of(), false);
            state.loading = false;
            return;
        }
        storage.storeBoard(new StoredBoard(date, board, revision, seed, ids))
                .whenComplete((result, storeFailure) -> onServer("board store of " + board + " for " + date,
                        () -> boardStored(state, board, date, result, storeFailure), NOTHING));
    }

    private void boardStored(BoardState state, Identifier board, LocalDate date, StoredBoard result, Throwable failure) {
        if (!isHeld(state, board)) {
            return;
        }
        if (failure != null) {
            boardFailed(state, board, date, failure);
            return;
        }
        adoptBoard(state, result);
    }

    private void adoptBoard(BoardState state, StoredBoard stored) {
        state.held = new HeldBoard(stored.boardDate(), stored.revisionId(), stored.commissionIds(), true);
        state.loading = false;
    }

    private void boardFailed(BoardState state, Identifier board, LocalDate date, Throwable failure) {
        Alina.LOGGER.warn("Alina could not load or store the Guild board {} for {}; it is tried again in 5 seconds",
                board, date, failure);
        state.loading = false;
        state.date = null;
        state.retryAt = ticks + RETRY_TICKS;
    }

    /**
     * Draws again a board that was drawn from an empty pool, now that the content has changed. An empty pool gives an
     * empty unstored board at once, so {@link #board} has no gap. A pool with repeatables is loaded, drawn and stored as
     * at the start of a day. Before the startup activation has finished, the board is only cleared, and
     * {@link #advanceBoards} draws it once the content is ready.
     */
    private void redrawUnstored(Identifier id, BoardState state, GuildContent content) {
        state.held = null;
        state.date = null;
        state.retryAt = 0;
        if (!contentReady.getAsBoolean()) {
            return;
        }
        Board board = content.boards().get(id);
        GuildSettings settings = content.settings();
        LocalDate today = BoardDay.dateOf(clock.instant(), settings.resetTime(), settings.zone());
        if (boardPool(content, board).isEmpty()) {
            state.held = new HeldBoard(today, revisionId.getAsLong(), List.of(), false);
            state.date = today;
        } else {
            requestBoard(state, id, today);
        }
    }

    /** One board's state: the board day it holds, whether its load is in flight, the tick of its next retry, and its draw. */
    private static final class BoardState {
        /** The board day held or being loaded. Null before the first request and after a failed one. */
        LocalDate date;
        boolean loading;
        long retryAt;
        /** The draw held, or null while it loads or after a failure. */
        HeldBoard held;
    }

    /** A board's draw as storage holds it: the ids are not filtered to the active content. */
    private record HeldBoard(LocalDate date, long revisionId, List<Identifier> ids, boolean stored) {}

    // Helpers.

    private GuildView viewOf(PlayerRecord record) {
        Instant now = stamp();
        Map<Identifier, CommissionView> views = new HashMap<>();
        for (CommissionState row : record.rows.values()) {
            Identifier id = row.commissionId();
            boolean settling = record.settling.containsKey(id);
            switch (row.status()) {
                case ACTIVE -> views.put(id, new CommissionView(id,
                        settling ? Standing.SETTLING : Standing.ACTIVE,
                        row.progress(), row.acceptedAt(), Optional.empty(), Optional.empty()));
                case COOLDOWN -> {
                    if (cooldownEnd(row).filter(end -> end.isAfter(now)).isPresent()) {
                        views.put(id, new CommissionView(id, Standing.COOLDOWN, row.progress(), row.acceptedAt(),
                                row.cooldownUntil(), row.completedAt()));
                    }
                }
                case COMPLETED -> views.put(id, new CommissionView(id, Standing.COMPLETED, row.progress(),
                        row.acceptedAt(), Optional.empty(), row.completedAt()));
            }
        }
        for (Map.Entry<Identifier, Instant> entry : record.settling.entrySet()) {
            if (!record.rows.containsKey(entry.getKey())) {
                views.put(entry.getKey(), new CommissionView(entry.getKey(), Standing.SETTLING, 0, entry.getValue(),
                        Optional.empty(), Optional.empty()));
            }
        }
        return new GuildView(record.id, record.reputation, views);
    }

    private static boolean proofMet(ServerPlayer player, Commission commission, CommissionState row) {
        return switch (commission.proof()) {
            case Proof.Items items -> ItemProofPlanner.plan(player.getInventory(), items).isPresent();
            case Proof.Criterion criterion -> row.progress() >= criterion.criterion().count();
            case Proof.Descent descent -> row.progress() >= 1;
        };
    }

    /** The removal of an item proof, planned now and applied by settlement inside its begin. */
    private static SettlementRequest.Removal removal(Proof proof) {
        if (proof instanceof Proof.Items items) {
            return player -> {
                ItemPlan plan = ItemProofPlanner.plan(player.getInventory(), items)
                        .orElseThrow(() -> new IllegalStateException("the item proof is no longer met"));
                return plan.apply(player.getInventory());
            };
        }
        return player -> List.of();
    }

    private static OperationEffect effect(Commission commission, GuildSettings settings, Instant now) {
        if (commission.kind() == Commission.Kind.FEAT) {
            return new OperationEffect.CommissionSettled(commission.id(), CommissionStatus.COMPLETED, Optional.empty());
        }
        Cooldown cooldown = commission.cooldown().orElse(settings.defaultCooldown());
        Instant until = switch (cooldown) {
            case Cooldown.Fixed fixed -> now.plus(fixed.duration());
            case Cooldown.UntilBoardReset reset -> BoardDay.nextReset(now, settings.resetTime(), settings.zone());
        };
        return new OperationEffect.CommissionSettled(commission.id(), CommissionStatus.COOLDOWN, Optional.of(until));
    }

    private static String kindName(Commission commission) {
        return commission.kind() == Commission.Kind.REPEATABLE ? "repeatable" : "feat";
    }

    private static Optional<Instant> cooldownEnd(CommissionState row) {
        return row == null || row.status() != CommissionStatus.COOLDOWN ? Optional.empty() : row.cooldownUntil();
    }

    private static CommissionState withProgress(CommissionState row, int progress) {
        return new CommissionState(row.commissionId(), row.status(), progress, row.acceptedAt(), row.completedAt(),
                row.cooldownUntil(), row.revisionId());
    }

    private static boolean isRepeatable(GuildContent content, Identifier id) {
        Commission found = content.commissions().get(id);
        return found != null && found.kind() == Commission.Kind.REPEATABLE;
    }

    /** Whether the id is a repeatable that the active content puts in {@code board}'s pool. */
    private static boolean isRepeatableOf(GuildContent content, Identifier board, Identifier id) {
        return isRepeatable(content, id) && content.boardOf(id).map(Board::id).filter(board::equals).isPresent();
    }

    private static Optional<Refusal> refuse(Refusal.Reason reason, Component message) {
        return Optional.of(new Refusal(reason, message));
    }

    private boolean isActive(PlayerRecord record, CommissionState row) {
        return row.status() == CommissionStatus.ACTIVE && !record.settling.containsKey(row.commissionId());
    }

    private static boolean completed(PlayerRecord record, Identifier commission) {
        CommissionState row = record.rows.get(commission);
        return row != null && row.status() == CommissionStatus.COMPLETED;
    }

    /** The commissions the cap counts: the active rows that are not being settled. */
    private static int activeCount(PlayerRecord record) {
        return (int) record.rows.values().stream()
                .filter(row -> row.status() == CommissionStatus.ACTIVE && !record.settling.containsKey(row.commissionId()))
                .count();
    }

    /** The pool a board draws from: the repeatables in rotation that the board holds. */
    private static List<Commission> boardPool(GuildContent content, Board board) {
        return content.commissions().values().stream()
                .filter(commission -> commission.kind() == Commission.Kind.REPEATABLE && commission.inRotation()
                        && board.holds(commission))
                .toList();
    }

    /**
     * Whether a record that loads may withdraw a commission the content lacks. Not before haruhi's startup activation has
     * finished, because the content may not be active yet, and not
     * for {@link GuildContent#EMPTY}: the content is EMPTY before any bundle is active, and when the active bundle has no
     * {@code alina:guild} section, so an empty commission list there says nothing about what a revision removed.
     */
    private boolean withdrawalAllowed(GuildContent content) {
        return contentReady.getAsBoolean() && content != GuildContent.EMPTY;
    }

    /**
     * Runs a storage completion on the server thread. Storage completes on its own threads. Once the server has stopped,
     * {@code server.execute} runs its task inline on the storage thread, so the task checks the thread: a completion that
     * is not on the server thread is logged and {@code dropped} runs instead, and the state it would record stays as it is.
     */
    private void onServer(String what, Runnable task, Runnable dropped) {
        server.execute(() -> {
            if (server.isSameThread()) {
                task.run();
            } else {
                Alina.LOGGER.info("Alina Guild dropped the {} completion: it finished after the server stopped", what);
                dropped.run();
            }
        });
    }

    /** Settlement calls its listeners on the server thread. A call from another thread is logged and changes nothing. */
    private boolean listenerOnServer(String listener, UUID character) {
        if (server.isSameThread()) {
            return true;
        }
        Alina.LOGGER.warn("Alina Guild ignored a {} for {}: settlement called it off the server thread", listener,
                character);
        return false;
    }

    private boolean isCurrent(PlayerRecord record) {
        return records.get(record.id) == record;
    }

    private ServerPlayer online(UUID id) {
        for (ServerPlayer candidate : online.get()) {
            if (candidate.getUUID().equals(id)) {
                return candidate;
            }
        }
        return null;
    }

    private Component nameOf(Identifier commission) {
        Commission found = contentSource.get().commissions().get(commission);
        return found != null ? found.name() : Component.literal(commission.toString());
    }

    /** The name of a commission as the content before the last activation wrote it, or its id when it has no name. */
    private Component knownName(Identifier commission) {
        Commission found = known.commissions().get(commission);
        return found != null ? found.name() : Component.literal(commission.toString());
    }

    /** The clock's instant at the precision storage keeps, for values written to storage and kept in records. */
    private Instant stamp() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Alina Guild runs on the server thread");
        }
    }
}
