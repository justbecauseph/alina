package town.lampas.alina.guild;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import town.lampas.alina.criteria.CriterionWatchers;
import town.lampas.alina.storage.CommissionState;

/**
 * One online character's Guild state, held by {@link GuildService} on the server thread. A record is loading until its
 * first load completes, and {@code loaded} is false until then. Its rows and reputation are what storage held at the
 * last load, changed by the operations since.
 */
final class PlayerRecord {
    final UUID id;
    /** The generation of the load in flight. A completion of an older load is ignored. */
    long loadGeneration;
    boolean loaded;
    boolean loadInFlight;
    /** Set when a settlement result or a staff change lands while the record loads; the load is then repeated. */
    boolean reloadRequested;
    /** The server tick a failed load may be tried again at. */
    long retryAt;
    int reputation;
    /** The character's commission rows, by commission id. */
    final Map<Identifier, CommissionState> rows = new HashMap<>();
    /** The turn-ins in settlement, by commission id, with the time each began. */
    final Map<Identifier, Instant> settling = new HashMap<>();
    /** Commissions with an accept, abandon or reset still being written. */
    final Set<Identifier> busy = new HashSet<>();
    /** Abandons still being written. Their commissions have no row, but they count towards the cap until the write ends. */
    final Set<Identifier> abandoning = new HashSet<>();
    /** The open criterion watches, by commission id. */
    final Map<Identifier, CriterionWatchers.Watch> watches = new HashMap<>();
    /** Progress not yet written to storage, by commission id. */
    final Map<Identifier, Integer> dirty = new HashMap<>();
    /** The descent commissions whose dive is armed. */
    final Set<Identifier> armed = new HashSet<>();
    /** The watch on {@code minecraft:used_totem}, open while a dive is armed. */
    CriterionWatchers.Watch totem;
    /** The volumes that held the player at the last descent check. */
    Set<Identifier> volumesIn = Set.of();

    PlayerRecord(UUID id) {
        this.id = id;
    }
}
