package town.lampas.alina.achievement;

import org.jspecify.annotations.Nullable;

/**
 * Owned by the achievement component (ALINA-PLAN §9). Registers {@code /alina achievement} and the
 * {@code alina:achievement} dialogue condition during {@code onInitialize}, makes one {@link AchievementService} and one
 * {@link AdvancementSync} when the server starts, and drives them from server, player and content events.
 */
public final class AchievementModule {

    private AchievementModule() {}

    public static void init() {
        // The registrations and lifecycle wiring are added by the service's implementer.
    }

    /** The running server's achievement service. Throws before the server has started. */
    public static AchievementService service() {
        throw new IllegalStateException("Alina achievements are not running; the server has not started");
    }

    /** The running server's advancement sync, or null when no server is running. For the mixin. */
    public static @Nullable AdvancementSync sync() {
        return null;
    }
}
