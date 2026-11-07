package town.lampas.alina.achievement;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Told by {@link AchievementService} what changed in an online player's achievements, on the server thread, after the
 * service's own state has changed. Tiers here are 1-based advancement positions: an achievement without tiers has one,
 * tier 1.
 */
public interface AchievementListener {

    /** The player's record has loaded. */
    default void loaded(ServerPlayer player) {}

    /** A count changed and nothing was earned. */
    default void progressed(ServerPlayer player, Identifier achievement) {}

    /** The tier was earned in this cycle, for the first time in it, and the earn is now durable. */
    default void earned(ServerPlayer player, Identifier achievement, int tier) {}

    /** A repeatable's cooldown ended: its counts are 0 and nothing of it is earned in the new cycle. */
    default void cycleStarted(ServerPlayer player, Identifier achievement) {}

    /** Staff revoked the achievement or some of its tiers; called after the writes succeeded. */
    default void revoked(ServerPlayer player, Identifier achievement) {}

    /**
     * A content bundle was activated. Called after the service has closed its watches, and before it starts cycles, reopens
     * its watches or earns what the new content finds met, so a listener sees the new content before any earn under it.
     */
    default void contentActivated() {}
}
