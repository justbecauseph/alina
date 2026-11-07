package town.lampas.alina.achievement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;

/**
 * What {@link AdvancementSync} knows of one online player: the Alina holders the client has, with the state last sent
 * for each, and the throttle and load state. Held on the server thread, and dropped when the player leaves.
 */
final class SyncState {
    /** An earn reported before the client had its holders: the achievement and the tier earned. */
    record Owed(Identifier achievement, int tier) {}

    /**
     * The Alina holders the client has, by id, with their state as the client has it. A holder sent without progress
     * has the state the client shows for it, not done.
     */
    final Map<Identifier, HolderProgress.State> sent = new HashMap<>();
    /** Achievements whose progress changed since the last progress packet. */
    final Set<Identifier> dirty = new HashSet<>();
    /** Earns reported while the client had no holders yet, sent on the first tick it has them, in the order reported. */
    final List<Owed> owed = new ArrayList<>();
    /** True once vanilla's reset packet of this join has been built, with or without Alina's holders. */
    boolean resetSent;
    /** True while the holders are owed: the record had not loaded when the reset was built, or the hold timed out. */
    boolean pendingLoad;
    /** The server tick of the first hold check that found the record empty, or -1. */
    long firstAskedAt = -1;
    /** The server tick of the last progress-only packet, valid once {@link #progressSent} is true. */
    long lastProgressTick;
    boolean progressSent;
}
