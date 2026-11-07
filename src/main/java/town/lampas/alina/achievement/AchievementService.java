package town.lampas.alina.achievement;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRules;
import town.lampas.alina.Alina;
import town.lampas.alina.content.AchievementsContent;
import town.lampas.alina.content.AchievementsContent.Achievement;
import town.lampas.alina.content.AchievementsContent.Tier;
import town.lampas.alina.content.AlinaContent;
import town.lampas.alina.content.CriterionSpec;
import town.lampas.alina.content.Reward;
import town.lampas.alina.criteria.AlinaTriggers;
import town.lampas.alina.criteria.CriterionWatchers;
import town.lampas.alina.settlement.PendingOperation;
import town.lampas.alina.settlement.SettledOperation;
import town.lampas.alina.settlement.SettlementRequest;
import town.lampas.alina.settlement.SettlementService;
import town.lampas.alina.storage.AchievementState;
import town.lampas.alina.storage.AlinaStorage;
import town.lampas.alina.storage.CharacterState;
import town.lampas.alina.storage.CriterionKey;
import town.lampas.alina.storage.Operation;
import town.lampas.alina.storage.OperationEffect;
import town.lampas.alina.storage.OperationKind;
import town.lampas.haruhi.api.HaruhiServices;

/**
 * Achievements (ALINA-PLAN §9.2, §9.3): each online character's achievement record, criterion counting, tiers, repeats
 * and earns. Server thread only, except where a method says otherwise.
 *
 * <p><b>Records.</b> {@link #join} loads the character's {@code CharacterState} (its achievements and criterion counts)
 * from storage; until it arrives, {@link #view} is empty. A failed load is retried every {@link #RETRY_TICKS} while the
 * player is online. A loaded state whose player is not online yet is held, and applied on the first tick the player is
 * online; a player who leaves first takes it with the record. Storage calls for one character run one after another, in
 * order, loads included, and every storage completion runs on the server thread and is dropped once the server has
 * stopped running tasks, as in the Guild
 * service. The operations in the player's settlement escrow when the record loads are earns being settled, and a record
 * whose load overlapped a storage result of one of them is loaded again. A loaded record is never loaded again while
 * the player stays online: a storage result for an earn this service began in this run is already in the record. A result
 * for any other earn (one found in the escrow at load) is counted when storage applied it in that call; a result storage had
 * applied before the call is not counted, and the record is loaded again, which reads storage's own count.
 * Staff operations are refused while a record loads.
 *
 * <p><b>The current cycle.</b> An achievement without {@code repeat} has one cycle. A repeatable's cycle is complete when
 * its stored tier reaches its number of tiers, or 1 for an achievement without tiers: that is, when it is earned in the
 * current cycle. Its stored {@code availableAt} is then the end of the cooldown; without one, the cycle ends at its last
 * earn time plus the cooldown, which is how an achievement earned before the content gave it {@code repeat} is treated. Once
 * that time has passed (checked at load and on the tick), and no earn of the achievement is waiting to be written or is in
 * the escrow, a new cycle starts: the criterion counts are reset first, then the stored state is written with tier 0 and no
 * {@code availableAt}, keeping the earn count and last earn time, and the listeners get
 * {@link AchievementListener#cycleStarted}. A cycle start that does not finish both writes is tried again, so a crash
 * between them leaves counts at 0 and the old cooldown, never old counts in a new cycle. An achievement without tiers is
 * earned in its current cycle when its stored state exists and, for a repeatable, its stored tier is 1 or more or it has an
 * {@code availableAt}; a tiered achievement has earned the tiers up to its stored tier. The counts never decide whether a
 * complete cycle exists, so a cycle that content makes complete is not reset by counts that already met a lower requirement:
 * an activation that lowers a count earns the new cycle, as an event would.
 *
 * <p><b>Counting.</b> While a criterion's achievement is not complete in the current cycle and the criterion is below
 * its target, a {@code CriterionWatchers} watch counts its events: the target is the criterion's {@code count}, or for a
 * tiered achievement the last tier's count. Each matching event adds 1, up to the target. Requirements are vanilla's AND
 * of ORs: a criterion is met at its count. Counts are cached and written behind every {@link #FLUSH_TICKS}, on leave and
 * on stop; an earn writes its achievement's counts through first. A failed count write puts its counts back only when
 * the achievement's cycle has not been reset or revoked since. An achievement's own earns never count towards its own
 * criteria (an {@code alina:achievement_earned} criterion with no {@code achievement} condition).
 *
 * <p><b>Earns.</b> An achievement without tiers is earned when its requirements are met; a tiered one earns each tier
 * whose count its criterion reaches, in order. Met-but-not-earned is also found when a record loads (counts written
 * before a crash that lost the earn) and earned then. Each earn:
 * <ul>
 *   <li>with a reward (the tier's reward, else the achievement's; a loot table or reputation), goes through
 *       {@link SettlementService#begin} as an {@code ACHIEVEMENT_REWARD} with no removal and the effect
 *       {@code AchievementEarned(id, storage tier, availableAt)}, the storage tier being 1 for an achievement without
 *       tiers. A refused begin is tried again every {@link #RETRY_TICKS} while the player is online;</li>
 *   <li>without a reward, is written through with {@code writeAchievement}, retried the same way on failure;</li>
 *   <li>in both cases counts at once in the record, so it is not earned twice. Once the earn is durable (settlement
 *       began it, or the direct write succeeded) it fires {@code alina:achievement_earned} with the achievement and its
 *       1-based tier, announces in chat when the achievement's {@code announce} is set and the
 *       {@code show_advancement_messages} game rule is on (with vanilla's {@code AdvancementType.createAnnouncement}),
 *       and tells the listeners with {@link AchievementListener#earned}. An earn that is never made durable before the
 *       player leaves is found again at the next load and notified then, once.</li>
 * </ul>
 *
 * <p><b>Content activation.</b> {@link #contentActivated} stops all watches and starts them again from the new content,
 * tells the listeners with {@link AchievementListener#contentActivated}, and then earns what the new content finds met.
 * Stored state of an achievement the new content lacks is kept, untouched. Counts are kept by criterion name.
 *
 * <p><b>Storage order.</b> Storage calls that change one achievement's state run in an order that keeps the stored state
 * equal to the record's: a reward operation in the escrow adds its own effect when storage applies it, so a write of the
 * achievement's state waits until no reward of that achievement is unapplied, and while a load or a reload is in flight or
 * requested. A reward begins only when no write of that achievement is in flight. A later tier's earn begins only once every
 * earlier tier's earn of that achievement is durable: a reward waits for every earlier pending earn and for every earlier
 * reward to be applied, and a later tier is not earned while a direct write of an earlier tier is pending and its own tier
 * has a reward, so that write can never wait for that reward. A direct write of a later tier, with no reward, may be batched
 * into an earlier one's write. A load starts only when no earn write is in flight, so the load's result never includes a
 * write the record still counts as pending.
 */
public final class AchievementService {
    /** Server ticks between count write-behind flushes: 5 seconds. */
    public static final long FLUSH_TICKS = 100;
    /** Server ticks before a failed load, earn or begin is tried again: 5 seconds. */
    public static final long RETRY_TICKS = 100;
    /** Server ticks between cycle checks of loaded records. */
    private static final long CYCLE_CHECK_TICKS = 20;
    private static final Runnable NOTHING = () -> {};

    private final MinecraftServer server;
    private final AlinaStorage storage;
    private final SettlementService settlement;
    private final Supplier<AchievementsContent> contentSource;
    private final LongSupplier revisionId;
    private final Clock clock;
    private final Supplier<? extends Collection<ServerPlayer>> online;
    private final BooleanSupplier contentReady;
    private final AchievementWrites writes = new AchievementWrites();
    private final List<AchievementListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<UUID, AchievementRecord> records = new HashMap<>();
    private long loadGenerations;
    private long ticks;
    /** The achievement whose {@code alina:achievement_earned} event is being dispatched, or null. Its own watches ignore it. */
    private Identifier firingEarn;

    /** The service of the running server, over the active {@code alina:achievements} content. */
    public AchievementService(MinecraftServer server, AlinaStorage storage, SettlementService settlement) {
        this(server, storage, settlement, AlinaContent::achievements,
                () -> HaruhiServices.content().activeRevisionId().orElse(0L), Clock.systemUTC(),
                () -> server.getPlayerList().getPlayers(), () -> HaruhiServices.content().isStartupComplete());
    }

    /**
     * @param content the active {@code alina:achievements} content, read each time it is needed
     * @param revisionId the active content revision, 0 when none is stored
     * @param clock real time, for cooldowns and earn times
     * @param online the players {@link #tick} serves
     * @param contentReady true once haruhi's startup activation has finished; records load their earns only after
     */
    public AchievementService(MinecraftServer server, AlinaStorage storage, SettlementService settlement,
            Supplier<AchievementsContent> content, LongSupplier revisionId, Clock clock,
            Supplier<? extends Collection<ServerPlayer>> online, BooleanSupplier contentReady) {
        this.server = Objects.requireNonNull(server, "server");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.settlement = Objects.requireNonNull(settlement, "settlement");
        this.contentSource = Objects.requireNonNull(content, "content");
        this.revisionId = Objects.requireNonNull(revisionId, "revisionId");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.online = Objects.requireNonNull(online, "online");
        this.contentReady = Objects.requireNonNull(contentReady, "contentReady");
        settlement.addAppliedListener(this::applied);
        settlement.addSettledListener(this::settled);
    }

    /** Adds a listener; see {@link AchievementListener}. */
    public void addListener(AchievementListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Starts loading the player's record. A record already loaded or loading is loaded again. */
    public void join(ServerPlayer player) {
        requireServerThread();
        AchievementRecord previous = records.remove(player.getUUID());
        if (previous != null) {
            retire(previous);
        }
        AchievementRecord record = new AchievementRecord(player.getUUID());
        records.put(record.id, record);
        startLoad(record);
    }

    /** Writes the player's pending counts, stops their watches and drops their record. */
    public void leave(ServerPlayer player) {
        requireServerThread();
        AchievementRecord record = records.remove(player.getUUID());
        if (record != null) {
            retire(record);
        }
    }

    /** Called every server tick: load and earn retries, cycle starts, count flushes. */
    public void tick() {
        requireServerThread();
        ticks++;
        for (AchievementRecord record : List.copyOf(records.values())) {
            ServerPlayer entity = online(record.id);
            if (entity == null) {
                continue;
            }
            if (record.held != null) {
                // A load that arrived while the player was offline: applied on the first tick they are online.
                applyHeld(record, entity);
                continue;
            }
            if (!record.loaded) {
                if (!record.loadInFlight && ticks >= record.retryAt) {
                    startLoad(record);
                }
                continue;
            }
            if (record.reloadRequested && !record.loadInFlight && ticks >= record.retryAt && !earnInFlight(record)) {
                startLoad(record);
            }
            retryEarns(record);
            if (record.metCheckDue) {
                meetIfReady(record);
            }
            if (ticks % CYCLE_CHECK_TICKS == 0 && !record.loadInFlight) {
                cycleStarts(record, entity);
            }
        }
        if (ticks % FLUSH_TICKS == 0) {
            for (AchievementRecord record : List.copyOf(records.values())) {
                if (record.loaded) {
                    flush(record);
                }
            }
        }
        writes.prune();
    }

    /**
     * Called when haruhi activates a bundle. Stops the watches, tells the listeners, then starts each loaded record's cycles
     * and earns from the new content.
     */
    public void contentActivated() {
        requireServerThread();
        for (AchievementRecord record : List.copyOf(records.values())) {
            closeWatches(record);
        }
        notifyListeners(AchievementListener::contentActivated);
        for (AchievementRecord record : List.copyOf(records.values())) {
            if (!record.loaded) {
                continue;
            }
            record.metCheckDue = true;
            ServerPlayer entity = online(record.id);
            if (entity != null) {
                cycleStarts(record, entity);
            }
            refreshWatches(record);
            if (entity != null) {
                meetIfReady(record);
            }
        }
    }

    /** Writes every record's pending counts and stops all watches. The future never fails. */
    public CompletableFuture<Void> stop() {
        requireServerThread();
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (AchievementRecord record : records.values()) {
            closeWatches(record);
            if (record.loaded) {
                pending.add(flush(record));
            }
        }
        records.clear();
        return CompletableFuture.allOf(pending.toArray(new CompletableFuture<?>[0]))
                .handle((ignored, failure) -> null);
    }

    /** The active {@code alina:achievements} content. */
    public AchievementsContent content() {
        return contentSource.get();
    }

    /** The service clock's current instant. */
    public Instant now() {
        return clock.instant();
    }

    /** The player's achievements, or empty while their record loads. */
    public Optional<AchievementView> view(ServerPlayer player) {
        requireServerThread();
        AchievementRecord record = records.get(player.getUUID());
        if (record == null || !record.loaded) {
            return Optional.empty();
        }
        Map<Identifier, AchievementStanding> standings = new HashMap<>();
        for (Achievement achievement : achievementsSorted()) {
            standings.put(achievement.id(), standingOf(record, achievement));
        }
        return Optional.of(new AchievementView(record.id, standings));
    }

    /**
     * For staff: earns the achievement now, through the normal earn path (reward included). For a tiered achievement,
     * earns each tier up to {@code tier} (default the last) not yet earned in the current cycle, and raises the count to
     * that tier's count. Completes with a refusal text (unknown achievement, a tier out of range, a tier given for an
     * achievement without tiers, already earned, record loading), or empty when it was earned.
     */
    public CompletableFuture<Optional<String>> grant(ServerPlayer player, Identifier achievement, OptionalInt tier) {
        CompletableFuture<Optional<String>> done = new CompletableFuture<>();
        runOnServer(() -> complete(done, () -> grantNow(player, achievement, tier)));
        return done;
    }

    /**
     * For staff: without a tier, deletes the achievement's stored state and counts; with tier k, keeps tiers below k: the
     * stored tier becomes k - 1, the earn count drops by the tiers removed (the current cycle's tiers from k up, one earn
     * each), and the count is at most tier k's count minus 1. Grants no reward back and takes none away. Refused while the
     * record loads and while an earn of the achievement is waiting to be written or is in the escrow. The record changes
     * only after the writes succeed; the future completes then, with empty, and the listeners get
     * {@link AchievementListener#revoked}. A failed write completes with the failure's text and changes nothing in the record,
     * except that a tiered revoke whose count write succeeded and whose state write failed leaves storage's counts lowered, and
     * the record takes those counts too; the text says so.
     */
    public CompletableFuture<Optional<String>> revoke(ServerPlayer player, Identifier achievement, OptionalInt tier) {
        CompletableFuture<Optional<String>> done = new CompletableFuture<>();
        runOnServer(() -> revokeNow(player, achievement, tier, done));
        return done;
    }

    // Records and storage.

    private void startLoad(AchievementRecord record) {
        long generation = ++loadGenerations;
        record.loadGeneration = generation;
        record.loadInFlight = true;
        record.reloadRequested = false;
        UUID id = record.id;
        if (record.loaded) {
            flush(record);
        }
        writes.submit(id, () -> storage.loadCharacter(id))
                .whenComplete((state, failure) -> onServer("load of " + id,
                        () -> loaded(record, generation, state, failure), NOTHING));
    }

    private void loaded(AchievementRecord record, long generation, CharacterState state, Throwable failure) {
        if (!isCurrent(record) || record.loadGeneration != generation) {
            return;
        }
        record.loadInFlight = false;
        if (failure != null) {
            retryLoad(record, "Alina could not load the achievements of " + record.id, failure);
            return;
        }
        if (record.reloadRequested) {
            // A settlement result landed during the load, so the load may predate it. The tick loads again.
            return;
        }
        // A result for a player who is not online waits for them: the tick applies it on the first tick they are online.
        record.held = state;
        ServerPlayer entity = online(record.id);
        if (entity != null) {
            applyHeld(record, entity);
        }
    }

    /**
     * Applies the held load result to the record, with its player online. The escrow is read from the player's entity
     * now. A failed escrow read drops the result and tries the load again.
     */
    private void applyHeld(AchievementRecord record, ServerPlayer entity) {
        CharacterState state = record.held;
        record.held = null;
        List<PendingOperation> escrow;
        try {
            escrow = settlement.pendingFor(entity);
        } catch (RuntimeException failed) {
            retryLoad(record, "Alina could not read the settlement escrow of " + record.id, failed);
            return;
        }
        applyLoad(record, state, escrow);
        boolean first = !record.loaded;
        record.loaded = true;
        if (first) {
            notifyListeners(listener -> listener.loaded(entity));
        }
        cycleStarts(record, entity);
        refreshWatches(record);
        record.metCheckDue = true;
        meetIfReady(record);
    }

    private void retryLoad(AchievementRecord record, String what, Throwable failure) {
        record.retryAt = ticks + RETRY_TICKS;
        record.reloadRequested = record.loaded;
        Alina.LOGGER.warn("{}; it is tried again in 5 seconds", what, failure);
    }

    /**
     * Replaces the record's states and counts with storage's, keeping the counts not yet written. The escrow's reward
     * operations are read in. A reward this service began and storage has not applied is counted again here, because the
     * load may predate its apply. A reward found in the escrow at load is left to its result, which counts it. Then the
     * pending earns are applied.
     */
    private void applyLoad(AchievementRecord record, CharacterState state, List<PendingOperation> escrow) {
        record.states.clear();
        record.states.putAll(state.achievements());
        record.counts.clear();
        record.counts.putAll(state.criterionProgress());
        record.counts.putAll(record.dirty);
        record.escrow.clear();
        for (PendingOperation operation : escrow) {
            if (operation.kind() != OperationKind.ACHIEVEMENT_REWARD
                    || !operation.worldId().equals(settlement.worldId())) {
                // An operation of another world is never applied here, so it counts nowhere in this world.
                continue;
            }
            boolean applied = operation.applied().isPresent();
            record.escrow.put(operation.operationId(), new AchievementRecord.Escrowed(operation.subjectId(),
                    operation.tier(), applied));
            if (!applied && record.begun.contains(operation.operationId())
                    && operation.effect() instanceof OperationEffect.AchievementEarned earned) {
                counted(record, earned, operation.createdAt());
            }
        }
        for (AchievementEarn job : record.pending) {
            overlay(record, job);
        }
    }

    /** Applies a pending step to the stored state the record was just given, as storage will once the step lands. */
    private static void overlay(AchievementRecord record, AchievementEarn job) {
        AchievementState stored = record.states.get(job.achievement);
        if (job.kind == AchievementEarn.Kind.RESET) {
            if (stored != null) {
                record.states.put(job.achievement,
                        new AchievementState(job.achievement, 0, stored.earnCount(), stored.lastEarnedAt(), Optional.empty()));
            }
            record.counts.keySet().removeIf(key -> key.achievementId().equals(job.achievement)
                    && !record.dirty.containsKey(key));
            return;
        }
        record.states.put(job.achievement,
                earnedState(job.achievement, stored, job.storageTier, job.at, job.availableAt));
    }

    /** Applies an earn's effect to the record's state, as storage applies it when the operation is recorded. */
    private static void counted(AchievementRecord record, OperationEffect.AchievementEarned earned, Instant at) {
        AchievementState stored = record.states.get(earned.achievementId());
        record.states.put(earned.achievementId(),
                earnedState(earned.achievementId(), stored, earned.tier(), at, earned.availableAt()));
    }

    /** Stops the record's watches and writes its pending counts; the record is dropped by the caller. */
    private void retire(AchievementRecord record) {
        closeWatches(record);
        if (record.loaded) {
            flush(record);
        }
    }

    /**
     * Writes the record's dirty counts, except those of an achievement whose cycle is being reset or whose revoke is being
     * written: their counts are settled by that step.
     */
    private CompletableFuture<Void> flush(AchievementRecord record) {
        Map<CriterionKey, Integer> taken = new HashMap<>();
        for (Map.Entry<CriterionKey, Integer> entry : record.dirty.entrySet()) {
            Identifier achievement = entry.getKey().achievementId();
            if (!resetting(record, achievement) && !revoking(record, achievement)) {
                taken.put(entry.getKey(), entry.getValue());
            }
        }
        if (taken.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        taken.keySet().forEach(record.dirty::remove);
        Map<CriterionKey, Integer> snapshot = Map.copyOf(taken);
        Map<Identifier, Long> epochs = new HashMap<>();
        for (CriterionKey key : snapshot.keySet()) {
            epochs.put(key.achievementId(), epochOf(record, key.achievementId()));
        }
        UUID id = record.id;
        Instant at = stamp();
        CompletableFuture<Void> write = writes.submit(id, () -> storage.writeCriterionProgress(id, snapshot, at));
        write.whenComplete((ignored, failure) -> onServer("count write of " + id,
                () -> flushed(record, snapshot, epochs, failure), () -> {
                    if (failure != null) {
                        Alina.LOGGER.warn("Alina could not write the counts of {}, and the server had stopped; they are "
                                + "lost", id, failure);
                    }
                }));
        return write;
    }

    /**
     * A failed count write puts its counts back into dirty, so the next flush writes them again. Counts of an achievement
     * whose cycle was reset or revoked since the write was sent are not put back: the step that reset or revoked it has
     * settled them.
     */
    private void flushed(AchievementRecord record, Map<CriterionKey, Integer> taken, Map<Identifier, Long> epochs,
            Throwable failure) {
        if (failure == null) {
            return;
        }
        if (!isCurrent(record)) {
            Alina.LOGGER.warn("Alina could not write the counts of {}; the counts of {} criteria are lost", record.id,
                    taken.size(), failure);
            return;
        }
        Alina.LOGGER.warn("Alina could not write the counts of {}; they are written again on the next flush", record.id,
                failure);
        for (CriterionKey key : taken.keySet()) {
            Identifier achievement = key.achievementId();
            if (epochOf(record, achievement) != epochs.get(achievement).longValue()) {
                continue;
            }
            Integer now = record.counts.get(key);
            record.dirty.putIfAbsent(key, now != null ? now : taken.get(key));
        }
    }

    // Earns.

    /** Retries the pending steps that are due. */
    private void retryEarns(AchievementRecord record) {
        for (AchievementEarn job : List.copyOf(record.pending)) {
            if (!job.inFlight && ticks >= job.retryAt) {
                advanceEarn(record, job);
            }
        }
    }

    private static boolean earnInFlight(AchievementRecord record) {
        return record.pending.stream().anyMatch(job -> job.inFlight);
    }

    /**
     * Earns the tier now: the record's state takes it at once, and its storage step is queued. The event, the announcement
     * and the listeners follow once the earn is durable. {@code tier} is 1-based; an achievement without tiers has tier 1.
     */
    private void earnTier(AchievementRecord record, Achievement achievement, int tier) {
        boolean tiered = !achievement.tiers().isEmpty();
        int storageTier = tiered ? tier : 1;
        boolean complete = !tiered || tier == achievement.tiers().size();
        Instant now = stamp();
        Optional<Instant> availableAt = complete && achievement.repeat().isPresent()
                ? Optional.of(now.plus(achievement.repeat().get()))
                : Optional.empty();
        record.states.put(achievement.id(),
                earnedState(achievement.id(), record.states.get(achievement.id()), storageTier, now, availableAt));
        Reward reward = rewardOf(achievement, tier);
        AchievementEarn job = reward.isEmpty()
                ? AchievementEarn.direct(achievement.id(), tier, storageTier, now, availableAt)
                : AchievementEarn.reward(achievement.id(), tier, storageTier, now, availableAt, reward);
        record.pending.add(job);
        advanceEarn(record, job);
        refreshWatches(record);
    }

    /** The reward of the tier, 1-based: the tier's own, else the achievement's. Empty when neither has one. */
    private static Reward rewardOf(Achievement achievement, int tier) {
        return !achievement.tiers().isEmpty() && achievement.tiers().get(tier - 1).reward().isPresent()
                ? achievement.tiers().get(tier - 1).reward().get()
                : achievement.reward();
    }

    /**
     * Whether the earn of {@code tier} has to wait for a direct write of the achievement that is pending: the earn has a reward,
     * and so its reward could not be begun while that write waits for it. A direct write of the next tier with no reward is
     * batched into the pending one instead, so that is not held.
     */
    private static boolean waitsForDirectWrite(AchievementRecord record, Achievement achievement, int tier) {
        return !rewardOf(achievement, tier).isEmpty() && record.pending.stream().anyMatch(job ->
                job.kind == AchievementEarn.Kind.DIRECT && job.achievement.equals(achievement.id()));
    }

    /**
     * Earns every tier, or the one achievement, whose requirements the counts meet and which is not earned in the current
     * cycle. Returns true when something was earned.
     */
    private boolean checkEarns(AchievementRecord record, Achievement achievement) {
        Identifier id = achievement.id();
        if (resetting(record, id) || revoking(record, id)) {
            return false;
        }
        if (achievement.tiers().isEmpty()) {
            if (earnedInCycle(record, achievement) || !requirementsMet(record, achievement)) {
                return false;
            }
            earnTier(record, achievement, 1);
            return true;
        }
        boolean any = false;
        String criterion = onlyCriterion(achievement);
        // Read again each time: an earn can change the record.
        while (true) {
            int highest = highestInCycle(record, achievement);
            if (highest >= achievement.tiers().size()) {
                break;
            }
            Tier next = achievement.tiers().get(highest);
            if (counts(record, new CriterionKey(id, criterion)) < next.count()
                    || waitsForDirectWrite(record, achievement, highest + 1)) {
                break;
            }
            earnTier(record, achievement, highest + 1);
            any = true;
        }
        return any;
    }

    private void meetIfReady(AchievementRecord record) {
        if (!record.metCheckDue || !record.loaded || !contentReady.getAsBoolean()) {
            return;
        }
        record.metCheckDue = false;
        for (Achievement achievement : achievementsSorted()) {
            checkEarns(record, achievement);
        }
    }

    /**
     * Starts the cycle of each repeatable whose cooldown has passed. A cycle waits while an earn of the achievement is
     * pending or in the escrow, so the new cycle never sees an earn of the old one.
     */
    private void cycleStarts(AchievementRecord record, ServerPlayer entity) {
        Instant now = stamp();
        for (Achievement achievement : achievementsSorted()) {
            AchievementState state = record.states.get(achievement.id());
            if (state == null) {
                continue;
            }
            Optional<Instant> end = cycleEnd(record, state, achievement);
            if (end.isEmpty() || now.isBefore(end.get())) {
                continue;
            }
            Identifier id = achievement.id();
            if (resetting(record, id) || busy(record, id)) {
                continue;
            }
            record.states.put(id, new AchievementState(id, 0, state.earnCount(), state.lastEarnedAt(), Optional.empty()));
            record.counts.keySet().removeIf(key -> key.achievementId().equals(id));
            record.dirty.keySet().removeIf(key -> key.achievementId().equals(id));
            bumpEpoch(record, id);
            AchievementEarn job = AchievementEarn.reset(id, now);
            record.pending.add(job);
            advanceEarn(record, job);
            notifyListeners(listener -> listener.cycleStarted(entity, id));
        }
        refreshWatches(record);
    }

    /** Makes the next storage call of a pending step, when the step may take one now. */
    private void advanceEarn(AchievementRecord record, AchievementEarn job) {
        if (job.inFlight || !isCurrent(record) || !record.pending.contains(job)) {
            return;
        }
        switch (job.kind) {
            case REWARD -> advanceReward(record, job);
            case DIRECT, RESET -> advanceWrite(record, job);
        }
    }

    private void advanceReward(AchievementRecord record, AchievementEarn job) {
        if (directInFlight(record, job.achievement) || revoking(record, job.achievement)
                || earlierUnsettled(record, job)) {
            job.retryAt = ticks + 1;
            return;
        }
        if (!job.countsWritten) {
            Map<CriterionKey, Integer> snapshot = countsOf(record, job.achievement);
            if (!snapshot.isEmpty()) {
                writeCounts(record, job, snapshot);
                return;
            }
            job.countsWritten = true;
        }
        ServerPlayer entity = online(record.id);
        if (entity == null) {
            job.retryAt = ticks + RETRY_TICKS;
            return;
        }
        commitReward(record, job, entity);
    }

    private void writeCounts(AchievementRecord record, AchievementEarn job, Map<CriterionKey, Integer> snapshot) {
        UUID id = record.id;
        Instant at = stamp();
        job.inFlight = true;
        writes.submit(id, () -> storage.writeCriterionProgress(id, snapshot, at))
                .whenComplete((ignored, failure) -> onServer("counts of " + job.achievement + " for " + id, () -> {
                    job.inFlight = false;
                    if (failure != null) {
                        job.retryAt = ticks + RETRY_TICKS;
                        Alina.LOGGER.warn("Alina could not write the counts of {} for {}; the reward is tried again in 5 "
                                + "seconds", job.achievement, id, failure);
                        return;
                    }
                    if (isCurrent(record)) {
                        job.countsWritten = true;
                        advanceEarn(record, job);
                    }
                }, NOTHING));
    }

    /**
     * Begins the reward in the escrow. A refusal leaves the step pending, to be begun again. Once the operation is in the
     * escrow the earn is durable, and its event, announcement and listeners follow.
     */
    private void commitReward(AchievementRecord record, AchievementEarn job, ServerPlayer entity) {
        Reward reward = job.reward;
        SettlementRequest request = new SettlementRequest(
                OperationKind.ACHIEVEMENT_REWARD,
                job.achievement,
                job.storageTier,
                revisionId.getAsLong(),
                reward.lootTable(),
                reward.reputation(),
                new OperationEffect.AchievementEarned(job.achievement, job.storageTier, job.availableAt),
                player -> List.of(),
                Optional.empty(),
                Optional.empty());
        Optional<UUID> operation;
        try {
            operation = settlement.begin(entity, request);
        } catch (RuntimeException failure) {
            job.retryAt = ticks + RETRY_TICKS;
            Alina.LOGGER.warn("Alina could not begin the reward of {} for {}; it is tried again in 5 seconds",
                    job.achievement, record.id, failure);
            return;
        }
        if (operation.isEmpty()) {
            job.retryAt = ticks + RETRY_TICKS;
            Alina.LOGGER.warn("Alina settlement refused the reward of {} for {}; it is tried again in 5 seconds",
                    job.achievement, record.id);
            return;
        }
        record.escrow.put(operation.get(), new AchievementRecord.Escrowed(job.achievement, job.storageTier, false));
        record.begun.add(operation.get());
        record.pending.remove(job);
        durable(record, job);
    }

    /**
     * Writes the state of a direct earn or a cycle reset. A direct earn writes its counts with its state. A reset deletes
     * the counts first and then writes its state, so a failure between the two leaves the old cooldown and no counts. Waits
     * while a reward of the achievement is unapplied, the achievement is being revoked, or the record is loaded again: a load
     * that predates its result would write a state missing the effect of an earn the record counts.
     */
    private void advanceWrite(AchievementRecord record, AchievementEarn job) {
        if (unappliedRewards(record, job.achievement) || revoking(record, job.achievement)
                || record.reloadRequested || record.loadInFlight) {
            job.retryAt = ticks + 1;
            return;
        }
        UUID id = record.id;
        Identifier achievement = job.achievement;
        AchievementState state = record.states.get(achievement);
        Instant at = stamp();
        // The state written now holds the effect of every direct earn and reset pending for the achievement, so each of
        // them is durable once this write succeeds.
        List<AchievementEarn> covered = record.pending.stream()
                .filter(other -> other.kind != AchievementEarn.Kind.REWARD && other.achievement.equals(achievement))
                .toList();
        job.inFlight = true;
        CompletableFuture<Void> write;
        if (job.kind == AchievementEarn.Kind.DIRECT) {
            Map<CriterionKey, Integer> snapshot = countsOf(record, achievement);
            write = writes.submit(id, () -> (snapshot.isEmpty()
                    ? CompletableFuture.<Void>completedFuture(null)
                    : storage.writeCriterionProgress(id, snapshot, at))
                    .thenCompose(ignored -> storage.writeAchievement(id, state, at)));
        } else {
            write = writes.submit(id, () -> storage.resetCriterionProgress(id, achievement)
                    .thenCompose(ignored -> state == null
                            ? CompletableFuture.<Void>completedFuture(null)
                            : storage.writeAchievement(id, state, at)));
        }
        write.whenComplete((ignored, failure) -> onServer("state write of " + achievement + " for " + id, () -> {
            job.inFlight = false;
            if (failure != null) {
                job.retryAt = ticks + RETRY_TICKS;
                Alina.LOGGER.warn("Alina could not write the achievement {} for {}; it is written again in 5 seconds",
                        achievement, id, failure);
                return;
            }
            if (!isCurrent(record)) {
                return;
            }
            List<AchievementEarn> done = covered.stream().filter(record.pending::contains).toList();
            record.pending.removeAll(done);
            for (AchievementEarn earn : done) {
                if (earn.kind == AchievementEarn.Kind.DIRECT) {
                    durable(record, earn);
                }
            }
            if (job.kind == AchievementEarn.Kind.RESET || done.stream().anyMatch(earn -> earn.kind == AchievementEarn.Kind.DIRECT)) {
                // Earns of the achievement were held while its reset or an earlier direct earn was pending, so they are checked now.
                recheck(record, achievement);
            }
        }, NOTHING));
    }

    /** Checks the earns of one achievement again, for one whose checks were held by another of its pending steps. */
    private void recheck(AchievementRecord record, Identifier achievement) {
        Achievement found = contentSource.get().achievements().get(achievement);
        if (found != null) {
            checkEarns(record, found);
        }
    }

    /**
     * Tells the player and the listeners that an earn is durable: the event, the announcement and the listeners, once. An
     * earn whose step completes after the player has left is not announced; it is stored, so the next load finds it earned.
     */
    private void durable(AchievementRecord record, AchievementEarn job) {
        ServerPlayer entity = online(record.id);
        if (entity == null || !isCurrent(record)) {
            return;
        }
        Achievement achievement = contentSource.get().achievements().get(job.achievement);
        Identifier outer = firingEarn;
        firingEarn = job.achievement;
        try {
            AlinaTriggers.ACHIEVEMENT_EARNED.trigger(entity, job.achievement, job.tier);
        } finally {
            firingEarn = outer;
        }
        if (achievement != null) {
            announce(entity, achievement, job.tier);
        }
        notifyListeners(listener -> listener.earned(entity, job.achievement, job.tier));
    }

    /** Whether a direct earn or a reset of the achievement is being written. */
    private static boolean directInFlight(AchievementRecord record, Identifier achievement) {
        return record.pending.stream().anyMatch(job -> job.inFlight && job.kind != AchievementEarn.Kind.REWARD
                && job.achievement.equals(achievement));
    }

    /** Whether a reward of the achievement is unapplied, in the escrow or still to be begun. */
    private static boolean unappliedRewards(AchievementRecord record, Identifier achievement) {
        return record.escrow.values().stream().anyMatch(escrowed -> escrowed.achievement().equals(achievement)
                && !escrowed.applied())
                || record.pending.stream().anyMatch(job -> job.kind == AchievementEarn.Kind.REWARD
                && job.achievement.equals(achievement));
    }

    /**
     * Whether an earlier step of the achievement than this job's is not durable yet: a reward of it unapplied in the escrow, or
     * an earlier earn or reset still pending, a reward or a direct write. A later tier's reward waits for it, so storage applies
     * the rewards in tier order, and a tier is announced only once every earlier tier is.
     */
    private static boolean earlierUnsettled(AchievementRecord record, AchievementEarn job) {
        if (record.escrow.values().stream().anyMatch(escrowed -> escrowed.achievement().equals(job.achievement)
                && !escrowed.applied())) {
            return true;
        }
        for (AchievementEarn other : record.pending) {
            if (other == job) {
                return false;
            }
            if (other.achievement.equals(job.achievement)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a cycle reset of the achievement is still pending. */
    private static boolean resetting(AchievementRecord record, Identifier achievement) {
        return record.pending.stream().anyMatch(job -> job.kind == AchievementEarn.Kind.RESET
                && job.achievement.equals(achievement));
    }

    /** Whether a staff revoke of the achievement is being written. */
    private static boolean revoking(AchievementRecord record, Identifier achievement) {
        return record.revoking.contains(achievement);
    }

    /**
     * Whether any step of the achievement is pending, a reward of it is in the escrow, or a revoke of it is being written.
     */
    private static boolean busy(AchievementRecord record, Identifier achievement) {
        return record.revoking.contains(achievement)
                || record.pending.stream().anyMatch(job -> job.achievement.equals(achievement))
                || record.escrow.values().stream().anyMatch(escrowed -> escrowed.achievement().equals(achievement));
    }

    private static long epochOf(AchievementRecord record, Identifier achievement) {
        return record.epochs.getOrDefault(achievement, 0L);
    }

    private static void bumpEpoch(AchievementRecord record, Identifier achievement) {
        record.epochs.merge(achievement, 1L, Long::sum);
    }

    // Counting and watches.

    /** One matching event for a criterion: counts it, closes the watch at the target, and checks the earns. */
    private void matched(AchievementRecord record, CriterionKey key) {
        if (!isCurrent(record) || !record.loaded) {
            return;
        }
        Identifier id = key.achievementId();
        if (id.equals(firingEarn) || revoking(record, id)) {
            // An achievement's own earns never count towards its own criteria.
            return;
        }
        ServerPlayer entity = online(record.id);
        Achievement achievement = contentSource.get().achievements().get(id);
        CriterionSpec spec = achievement == null ? null : achievement.criteria().get(key.criterion());
        if (entity == null || achievement == null || spec == null || completeInCycle(record, achievement)) {
            refreshWatches(record);
            return;
        }
        int target = targetOf(achievement, spec);
        int current = counts(record, key);
        if (current >= target) {
            refreshWatches(record);
            return;
        }
        record.counts.put(key, current + 1);
        record.dirty.put(key, current + 1);
        refreshWatches(record);
        if (!checkEarns(record, achievement)) {
            notifyListeners(listener -> listener.progressed(entity, id));
        }
    }

    /**
     * Opens the watches the record needs and closes the ones it does not, from the content and the counts. An achievement
     * that is complete in the current cycle, or being revoked, has none.
     */
    private void refreshWatches(AchievementRecord record) {
        Set<CriterionKey> wanted = new HashSet<>();
        if (record.loaded) {
            for (Achievement achievement : achievementsSorted()) {
                if (completeInCycle(record, achievement) || revoking(record, achievement.id())) {
                    continue;
                }
                for (Map.Entry<String, CriterionSpec> criterion : achievement.criteria().entrySet()) {
                    CriterionKey key = new CriterionKey(achievement.id(), criterion.getKey());
                    if (counts(record, key) >= targetOf(achievement, criterion.getValue())) {
                        continue;
                    }
                    wanted.add(key);
                    if (!record.watches.containsKey(key)) {
                        openWatch(record, key, criterion.getValue());
                    }
                }
            }
        }
        for (CriterionKey key : List.copyOf(record.watches.keySet())) {
            if (!wanted.contains(key)) {
                record.watches.remove(key).close();
            }
        }
    }

    private void openWatch(AchievementRecord record, CriterionKey key, CriterionSpec spec) {
        try {
            record.watches.put(key, CriterionWatchers.watch(record.id, spec, player -> matched(record, key)));
        } catch (RuntimeException failure) {
            Alina.LOGGER.error("Alina could not watch the criterion {} of {} for {}", key.criterion(),
                    key.achievementId(), record.id, failure);
        }
    }

    private static void closeWatches(AchievementRecord record) {
        for (CriterionWatchers.Watch watch : record.watches.values()) {
            watch.close();
        }
        record.watches.clear();
    }

    private static int targetOf(Achievement achievement, CriterionSpec spec) {
        return achievement.tiers().isEmpty() ? spec.count() : achievement.tiers().get(achievement.tiers().size() - 1).count();
    }

    // Achievement state in the current cycle.

    /**
     * Whether an achievement without tiers is earned in the current cycle: its stored state exists and, for a repeatable, has
     * a stored tier of 1 or more (an earn in this cycle; a cycle start stores 0) or an {@code availableAt}; or an earn of it is
     * in the escrow.
     */
    private static boolean earnedInCycle(AchievementRecord record, Achievement achievement) {
        AchievementState state = record.states.get(achievement.id());
        boolean stored = state != null && (achievement.repeat().isEmpty() || state.tier() >= 1
                || state.availableAt().isPresent());
        return stored || record.escrow.values().stream()
                .anyMatch(escrowed -> escrowed.achievement().equals(achievement.id()));
    }

    /**
     * The highest tier earned in the current cycle, stored or in the escrow; 0 for an achievement without tiers, whose
     * earn is {@link #earnedInCycle}.
     */
    private static int highestInCycle(AchievementRecord record, Achievement achievement) {
        if (achievement.tiers().isEmpty()) {
            return 0;
        }
        int highest = 0;
        AchievementState state = record.states.get(achievement.id());
        if (state != null) {
            highest = state.tier();
        }
        for (AchievementRecord.Escrowed escrowed : record.escrow.values()) {
            if (escrowed.achievement().equals(achievement.id())) {
                highest = Math.max(highest, escrowed.tier());
            }
        }
        return highest;
    }

    private static boolean completeInCycle(AchievementRecord record, Achievement achievement) {
        return achievement.tiers().isEmpty()
                ? earnedInCycle(record, achievement)
                : highestInCycle(record, achievement) >= achievement.tiers().size();
    }

    /**
     * When a repeatable's complete cycle becomes available again, or empty when the stored state does not show a complete
     * cycle. The stored {@code availableAt} is the end. A complete cycle without an {@code availableAt} ends at its last earn
     * time plus the cooldown: a stored tier that is the last tier, or 1 for an achievement without tiers.
     */
    private static Optional<Instant> cycleEnd(AchievementRecord record, AchievementState state,
            Achievement achievement) {
        Optional<Duration> cooldown = achievement.repeat();
        if (cooldown.isEmpty()) {
            return Optional.empty();
        }
        if (state.availableAt().isPresent()) {
            return state.availableAt();
        }
        boolean complete = state.tier() >= Math.max(1, achievement.tiers().size());
        if (!complete) {
            return Optional.empty();
        }
        return state.lastEarnedAt().map(at -> at.plus(cooldown.get()));
    }

    private static boolean requirementsMet(AchievementRecord record, Achievement achievement) {
        for (List<String> group : achievement.requirements()) {
            boolean met = false;
            for (String name : group) {
                CriterionSpec spec = achievement.criteria().get(name);
                if (spec != null && counts(record, new CriterionKey(achievement.id(), name)) >= spec.count()) {
                    met = true;
                    break;
                }
            }
            if (!met) {
                return false;
            }
        }
        return true;
    }

    private static String onlyCriterion(Achievement achievement) {
        return achievement.criteria().keySet().iterator().next();
    }

    private static int counts(AchievementRecord record, CriterionKey key) {
        return record.counts.getOrDefault(key, 0);
    }

    /** The achievement's counts that this record holds, as a copy. */
    private static Map<CriterionKey, Integer> countsOf(AchievementRecord record, Identifier achievement) {
        Map<CriterionKey, Integer> result = new HashMap<>();
        for (Map.Entry<CriterionKey, Integer> entry : record.counts.entrySet()) {
            if (entry.getKey().achievementId().equals(achievement)) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /**
     * The stored state after an earn, as storage applies it: the higher tier, one more earn, the earn time, and the earn's
     * availability, unless the stored tier is above the earn's tier. Then the stored availability stays.
     */
    private static AchievementState earnedState(Identifier achievement, AchievementState old, int tier, Instant at,
            Optional<Instant> availableAt) {
        int highest = old == null ? tier : Math.max(old.tier(), tier);
        int earnCount = old == null ? 1 : old.earnCount() + 1;
        Optional<Instant> available = old != null && old.tier() > tier ? old.availableAt() : availableAt;
        return new AchievementState(achievement, highest, earnCount, Optional.of(at), available);
    }

    private AchievementStanding standingOf(AchievementRecord record, Achievement achievement) {
        AchievementState state = record.states.get(achievement.id());
        int highest = highestInCycle(record, achievement);
        boolean complete = completeInCycle(record, achievement);
        int earnedTiers = achievement.tiers().isEmpty() ? (earnedInCycle(record, achievement) ? 1 : 0) : highest;
        Map<String, Integer> counts = new HashMap<>();
        for (String name : achievement.criteria().keySet()) {
            Integer value = record.counts.get(new CriterionKey(achievement.id(), name));
            if (value != null) {
                counts.put(name, value);
            }
        }
        boolean settling = record.escrow.values().stream()
                .anyMatch(escrowed -> escrowed.achievement().equals(achievement.id()));
        Optional<Instant> availableAt = Optional.empty();
        if (state != null) {
            availableAt = achievement.repeat().isPresent() && complete
                    ? cycleEnd(record, state, achievement)
                    : state.availableAt();
        }
        return new AchievementStanding(
                achievement.id(),
                earnedTiers,
                highest,
                state == null ? 0 : state.earnCount(),
                state == null ? Optional.empty() : state.lastEarnedAt(),
                availableAt,
                counts,
                settling);
    }

    // Staff.

    private String stillLoading(ServerPlayer player) {
        return "The achievements of " + player.getName().getString() + " are still loading.";
    }

    private String reloading(ServerPlayer player) {
        return "The achievements of " + player.getName().getString() + " are being reloaded; try again.";
    }

    private Optional<String> grantNow(ServerPlayer player, Identifier id, OptionalInt tier) {
        AchievementRecord record = records.get(player.getUUID());
        if (record == null || !record.loaded) {
            return Optional.of(stillLoading(player));
        }
        if (record.loadInFlight || record.reloadRequested) {
            return Optional.of(reloading(player));
        }
        Achievement achievement = contentSource.get().achievements().get(id);
        if (achievement == null) {
            return Optional.of("Unknown achievement " + id + ".");
        }
        if (achievement.tiers().isEmpty()) {
            if (tier.isPresent()) {
                return Optional.of(id + " has no tiers, so no tier can be granted.");
            }
            if (earnedInCycle(record, achievement)) {
                return Optional.of(id + " is already earned.");
            }
            if (busy(record, id)) {
                return Optional.of(id + " is being recorded or delivered; try again.");
            }
            earnTier(record, achievement, 1);
            return Optional.empty();
        }
        int size = achievement.tiers().size();
        int target = tier.orElse(size);
        if (target < 1 || target > size) {
            return Optional.of("Tier " + target + " is out of range; " + id + " has tiers 1 to " + size + ".");
        }
        if (highestInCycle(record, achievement) >= target) {
            return Optional.of(id + " tier " + target + " is already earned.");
        }
        if (busy(record, id)) {
            return Optional.of(id + " is being recorded or delivered; try again.");
        }
        CriterionKey key = new CriterionKey(id, onlyCriterion(achievement));
        int needed = achievement.tiers().get(target - 1).count();
        if (counts(record, key) < needed) {
            record.counts.put(key, needed);
            record.dirty.put(key, needed);
        }
        // A tier that waits for a pending direct write is earned when that write is durable: recheck does it.
        while (highestInCycle(record, achievement) < target
                && !waitsForDirectWrite(record, achievement, highestInCycle(record, achievement) + 1)) {
            earnTier(record, achievement, highestInCycle(record, achievement) + 1);
        }
        refreshWatches(record);
        return Optional.empty();
    }

    /**
     * Refuses, or starts the revoke and completes {@code done} once its storage write has finished. The record changes in
     * the write's completion, and only then: a failed write changes nothing and completes with its text.
     */
    private void revokeNow(ServerPlayer player, Identifier id, OptionalInt tier, CompletableFuture<Optional<String>> done) {
        AchievementRecord record = records.get(player.getUUID());
        if (record == null || !record.loaded) {
            done.complete(Optional.of(stillLoading(player)));
            return;
        }
        if (record.loadInFlight || record.reloadRequested) {
            done.complete(Optional.of(reloading(player)));
            return;
        }
        Achievement achievement = contentSource.get().achievements().get(id);
        if (achievement == null) {
            done.complete(Optional.of("Unknown achievement " + id + "."));
            return;
        }
        if (achievement.tiers().isEmpty() && tier.isPresent()) {
            done.complete(Optional.of(id + " has no tiers, so no tier can be revoked."));
            return;
        }
        if (tier.isPresent() && (tier.getAsInt() < 1 || tier.getAsInt() > achievement.tiers().size())) {
            done.complete(Optional.of("Tier " + tier.getAsInt() + " is out of range; " + id + " has tiers 1 to "
                    + achievement.tiers().size() + "."));
            return;
        }
        if (busy(record, id)) {
            done.complete(Optional.of(id + " is being recorded or delivered; try again."));
            return;
        }
        if (tier.isPresent() && highestInCycle(record, achievement) < tier.getAsInt()) {
            done.complete(Optional.of(id + " tier " + tier.getAsInt() + " is not earned."));
            return;
        }
        UUID character = record.id;
        Instant at = stamp();
        record.revoking.add(id);
        if (tier.isEmpty()) {
            CompletableFuture<Void> write = writes.submit(character, () -> storage.deleteAchievement(character, id));
            settleRevoke(player, record, id, write, done, null, Map.of(), new AtomicBoolean());
            return;
        }
        int kept = tier.getAsInt() - 1;
        AchievementState old = record.states.get(id);
        if (old == null) {
            record.revoking.remove(id);
            done.complete(Optional.of(id + " tier " + tier.getAsInt() + " is not earned."));
            return;
        }
        // One earn is removed for each tier from k up to the highest earned in this cycle. The floor at 0 is there because
        // AchievementState refuses a negative count, so a state whose count is below its tier cannot fail the revoke.
        int removed = highestInCycle(record, achievement) - kept;
        int earns = Math.max(0, old.earnCount() - removed);
        AchievementState next = new AchievementState(id, kept, earns, old.lastEarnedAt(), Optional.empty());
        CriterionKey key = new CriterionKey(id, onlyCriterion(achievement));
        int cap = achievement.tiers().get(tier.getAsInt() - 1).count() - 1;
        Map<CriterionKey, Integer> capped = new HashMap<>();
        if (counts(record, key) > cap) {
            capped.put(key, cap);
        }
        Map<CriterionKey, Integer> snapshot = Map.copyOf(capped);
        AtomicBoolean lowered = new AtomicBoolean();
        CompletableFuture<Void> write = writes.submit(character, () -> (snapshot.isEmpty()
                ? CompletableFuture.<Void>completedFuture(null)
                : storage.writeCriterionProgress(character, snapshot, at).thenRun(() -> lowered.set(true)))
                .thenCompose(ignored -> storage.writeAchievement(character, next, at)));
        settleRevoke(player, record, id, write, done, next, snapshot, lowered);
    }

    /**
     * Applies a revoke's result when its writes finish: the record takes the new state (or loses the achievement) and the
     * capped counts, then the listeners are told. A failed write changes nothing in the record, with one exception: when the
     * counts were lowered in storage and the state write failed, the record takes the lowered counts, so it matches storage,
     * and the completion says the counts were lowered and the tiers were not.
     */
    private void settleRevoke(ServerPlayer player, AchievementRecord record, Identifier id,
            CompletableFuture<Void> write, CompletableFuture<Optional<String>> done, AchievementState next,
            Map<CriterionKey, Integer> capped, AtomicBoolean lowered) {
        write.whenComplete((ignored, failure) -> onServer("revoke of " + id + " for " + record.id, () -> {
            record.revoking.remove(id);
            if (failure != null) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                        ? failure.getCause()
                        : failure;
                if (lowered.get()) {
                    if (isCurrent(record)) {
                        for (Map.Entry<CriterionKey, Integer> entry : capped.entrySet()) {
                            record.counts.put(entry.getKey(), entry.getValue());
                            record.dirty.remove(entry.getKey());
                        }
                        refreshWatches(record);
                        notifyListeners(listener -> listener.progressed(player, id));
                    }
                    Alina.LOGGER.warn("Alina lowered the counts of {} for {}, but could not revoke its tiers", id, record.id,
                            cause);
                    done.complete(Optional.of("Could not revoke " + id + ": the counts were lowered, but the tiers were not "
                            + "revoked: " + cause.getMessage()));
                    return;
                }
                Alina.LOGGER.warn("Alina could not revoke {} of {}; the record is unchanged", id, record.id, cause);
                done.complete(Optional.of("Could not revoke " + id + ": " + cause.getMessage()));
                return;
            }
            if (isCurrent(record)) {
                if (next == null) {
                    record.states.remove(id);
                    record.counts.keySet().removeIf(key -> key.achievementId().equals(id));
                    record.dirty.keySet().removeIf(key -> key.achievementId().equals(id));
                } else {
                    record.states.put(id, next);
                    for (Map.Entry<CriterionKey, Integer> entry : capped.entrySet()) {
                        record.counts.put(entry.getKey(), entry.getValue());
                        record.dirty.remove(entry.getKey());
                    }
                }
                bumpEpoch(record, id);
                refreshWatches(record);
                notifyListeners(listener -> listener.revoked(player, id));
            }
            done.complete(Optional.empty());
        }, () -> done.completeExceptionally(new IllegalStateException("the server stopped before the revoke finished"))));
    }

    // Settlement listeners.

    /**
     * Storage recorded a settlement result. A result that arrives while the record's load is in flight, or its state is
     * held, drops that load, which may predate it. A reward this service began is already counted in the record. Any other
     * reward in the escrow is counted now, the way storage counts it.
     */
    private void applied(SettledOperation settled) {
        if (!listenerOnServer("storage result of an operation", settled.player().getUUID())) {
            return;
        }
        Operation operation = settled.operation();
        if (operation.kind() != OperationKind.ACHIEVEMENT_REWARD) {
            return;
        }
        AchievementRecord record = records.get(settled.player().getUUID());
        if (record == null) {
            return;
        }
        if (record.loadInFlight || record.held != null) {
            // The load may have read storage before this result, so its result is dropped and the tick loads again.
            record.held = null;
            record.reloadRequested = true;
        }
        if (!settled.result().fresh()) {
            // Storage applied the operation in an earlier call, whose answer was lost, so this record may not count its effect
            // and may hold a count of it already. Loading again reads storage's count, so nothing is counted from here.
            record.reloadRequested = true;
        }
        AchievementRecord.Escrowed escrowed = record.escrow.get(operation.operationId());
        if (escrowed == null) {
            return;
        }
        record.escrow.put(operation.operationId(),
                new AchievementRecord.Escrowed(escrowed.achievement(), escrowed.tier(), true));
        if (settled.result().fresh() && !record.begun.contains(operation.operationId())
                && operation.effect() instanceof OperationEffect.AchievementEarned earned) {
            counted(record, earned, operation.createdAt());
        }
    }

    /**
     * The reward reached the player: its escrow mark goes, and its effect is already in the record. While the record is loaded
     * again, the mark stays, marked applied, until the load has read storage: the load may predate the operation's effect, and
     * the earn must still count as made until then.
     */
    private void settled(SettledOperation settled) {
        if (!listenerOnServer("settlement of an operation", settled.player().getUUID())) {
            return;
        }
        Operation operation = settled.operation();
        if (operation.kind() != OperationKind.ACHIEVEMENT_REWARD) {
            return;
        }
        AchievementRecord record = records.get(settled.player().getUUID());
        if (record != null) {
            record.begun.remove(operation.operationId());
            if (!record.reloadRequested && !record.loadInFlight) {
                record.escrow.remove(operation.operationId());
            }
        }
    }

    // Announcements.

    /** Announces the earn in chat, as vanilla announces an advancement, when the achievement and the game rule allow. */
    private void announce(ServerPlayer entity, Achievement achievement, int tier) {
        if (!achievement.announce() || !entity.level().getGameRules().get(GameRules.SHOW_ADVANCEMENT_MESSAGES)) {
            return;
        }
        Component title = tier == 1 && achievement.tiers().isEmpty()
                ? achievement.title()
                : tierOf(achievement, tier).title().orElse(achievement.title());
        AdvancementType frame = achievement.tiers().isEmpty()
                ? achievement.frame()
                : tierOf(achievement, tier).frame().orElse(achievement.frame());
        DisplayInfo display = new DisplayInfo(achievement.icon(), title, achievement.description(), Optional.empty(),
                frame, achievement.toast(), true, false);
        AdvancementHolder holder = new AdvancementHolder(holderId(achievement.id(), tier), new Advancement(
                Optional.empty(), Optional.of(display), AdvancementRewards.EMPTY, Map.of(),
                AdvancementRequirements.EMPTY, false, Optional.of(title)));
        Component message = frame.createAnnouncement(holder, entity);
        // Vanilla's broadcast: the console, then each player of the server's list. Here the recipients are the players this
        // service serves, which are the server's players in production and the rig's players in GameTests.
        server.sendSystemMessage(message);
        for (ServerPlayer recipient : List.copyOf(online.get())) {
            recipient.sendSystemMessage(message, false);
        }
    }

    private static Tier tierOf(Achievement achievement, int tier) {
        return achievement.tiers().get(tier - 1);
    }

    /**
     * The holder id of an achievement's tier, as {@code AdvancementSync} names it: the achievement's first tier has the
     * plain id, a later tier a {@code /tier_<k>} suffix.
     */
    static Identifier holderId(Identifier achievement, int tier) {
        String base = "achievement/" + achievement.getNamespace() + "/" + achievement.getPath();
        return Alina.id(tier <= 1 ? base : base + "/tier_" + tier);
    }

    // Helpers.

    private List<Achievement> achievementsSorted() {
        return contentSource.get().achievements().values().stream()
                .sorted(Comparator.comparing((Achievement achievement) -> achievement.id().toString()))
                .toList();
    }

    private void notifyListeners(Consumer<AchievementListener> call) {
        for (AchievementListener listener : listeners) {
            try {
                call.accept(listener);
            } catch (RuntimeException failure) {
                Alina.LOGGER.error("An achievement listener failed", failure);
            }
        }
    }

    private static <T> void complete(CompletableFuture<T> done, Supplier<T> work) {
        try {
            done.complete(work.get());
        } catch (RuntimeException failure) {
            done.completeExceptionally(failure);
        }
    }

    private void runOnServer(Runnable task) {
        if (server.isSameThread()) {
            task.run();
        } else {
            server.execute(task);
        }
    }

    /**
     * Runs a storage completion on the server thread. Storage completes on its own threads. Once the server has stopped,
     * {@code server.execute} runs its task inline on the storage thread, so the task checks the thread: a completion that
     * is not on the server thread is logged and {@code dropped} runs instead.
     */
    private void onServer(String what, Runnable task, Runnable dropped) {
        server.execute(() -> {
            if (server.isSameThread()) {
                task.run();
            } else {
                Alina.LOGGER.info("Alina achievements dropped the {} completion: it finished after the server stopped", what);
                dropped.run();
            }
        });
    }

    /** Settlement calls its listeners on the server thread. A call from another thread is logged and changes nothing. */
    private boolean listenerOnServer(String listener, UUID character) {
        if (server.isSameThread()) {
            return true;
        }
        Alina.LOGGER.warn("Alina achievements ignored a {} for {}: settlement called it off the server thread", listener,
                character);
        return false;
    }

    private boolean isCurrent(AchievementRecord record) {
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

    /** The clock's instant at the precision storage keeps, for values written to storage and kept in records. */
    private Instant stamp() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Alina achievements run on the server thread");
        }
    }
}
