package town.lampas.alina.achievement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import town.lampas.alina.criteria.CriterionWatchers;
import town.lampas.alina.storage.AchievementState;
import town.lampas.alina.storage.CriterionKey;

/**
 * One online character's achievement state, held by {@link AchievementService} on the server thread. {@code states} and
 * {@code counts} mirror what storage holds after the load, with this record's earns applied: the earns that are still in
 * {@code pending}, and the reward operations in the escrow ({@code escrow}), which storage may not have applied yet.
 */
final class AchievementRecord {
    /** A reward operation in the escrow: its achievement, its storage tier, and whether storage has applied it. */
    record Escrowed(Identifier achievement, int tier, boolean applied) {}

    final UUID id;
    /** The generation of the load in flight. A completion of an older load is ignored. */
    long loadGeneration;
    boolean loaded;
    boolean loadInFlight;
    /** Set when a settlement result lands while the record loads or after it loaded; the next load runs after it. */
    boolean reloadRequested;
    /** Set when the met-but-not-earned check is due, and cleared once it has run with haruhi's startup finished. */
    boolean metCheckDue;
    /** The server tick a failed load may be tried again at. */
    long retryAt;
    /** The stored state of each achievement, with this record's earns applied. */
    final Map<Identifier, AchievementState> states = new HashMap<>();
    /** Each criterion's count in the current cycle. */
    final Map<CriterionKey, Integer> counts = new HashMap<>();
    /** Counts not yet written to storage, with their current values. */
    final Map<CriterionKey, Integer> dirty = new HashMap<>();
    /** The open criterion watches. */
    final Map<CriterionKey, CriterionWatchers.Watch> watches = new HashMap<>();
    /** Earns and cycle resets whose storage step has not succeeded yet, oldest first. */
    final List<AchievementEarn> pending = new ArrayList<>();
    /** The reward operations of this player in the escrow, by operation id. */
    final Map<UUID, Escrowed> escrow = new HashMap<>();

    AchievementRecord(UUID id) {
        this.id = id;
    }
}
