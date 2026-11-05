package town.lampas.alina.criteria;

import net.minecraft.advancements.triggers.CriterionTrigger;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import town.lampas.alina.Alina;

/**
 * Alina's six criterion triggers (ALINA-PLAN §6.3). {@link #init} registers them in
 * {@code BuiltInRegistries.TRIGGER_TYPES}, where datapack advancements and Alina's content find them by id.
 */
public final class AlinaTriggers {
    public static final CommissionAcceptedTrigger COMMISSION_ACCEPTED = new CommissionAcceptedTrigger();
    public static final CommissionCompletedTrigger COMMISSION_COMPLETED = new CommissionCompletedTrigger();
    public static final ReputationChangedTrigger REPUTATION_CHANGED = new ReputationChangedTrigger();
    public static final VolumeEnteredTrigger VOLUME_ENTERED = new VolumeEnteredTrigger();
    public static final AchievementEarnedTrigger ACHIEVEMENT_EARNED = new AchievementEarnedTrigger();
    public static final ManualTrigger MANUAL = new ManualTrigger();

    private static boolean registered;

    private AlinaTriggers() {}

    /**
     * Registers the triggers. Call it from {@code onInitialize}: Fabric freezes the built-in registries after the mod
     * initializers run. Unit tests call it through {@code TestBootstrap}. A second call does nothing.
     */
    public static synchronized void init() {
        if (registered) {
            return;
        }
        register("commission_accepted", COMMISSION_ACCEPTED);
        register("commission_completed", COMMISSION_COMPLETED);
        register("reputation_changed", REPUTATION_CHANGED);
        register("volume_entered", VOLUME_ENTERED);
        register("achievement_earned", ACHIEVEMENT_EARNED);
        register("manual", MANUAL);
        registered = true;
    }

    private static void register(String name, CriterionTrigger<?> trigger) {
        Registry.register(BuiltInRegistries.TRIGGER_TYPES, Alina.id(name), trigger);
    }
}
