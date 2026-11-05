package town.lampas.alina;

import java.lang.reflect.Field;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import town.lampas.alina.criteria.AlinaTriggers;

/**
 * Boots Minecraft for the unit tests, with Alina's criterion triggers in {@code BuiltInRegistries.TRIGGER_TYPES}.
 *
 * <p>In a game, Fabric's {@code BootstrapMixin} defers the freeze of the built-in registries until the mod initializers
 * have run, so Alina registers its triggers in {@code onInitialize}. A unit test has no Fabric, and
 * {@code Bootstrap.bootStrap()} creates and freezes the registries in one call. So {@link #boot} sets Bootstrap's
 * private {@code isBootstrapped} flag only while {@link AlinaTriggers#init} runs (registration into the built-in
 * registries requires the flag), clears it, and then bootstraps for real.
 */
public final class TestBootstrap {
    private static boolean booted;

    private TestBootstrap() {}

    /** Boots once; later calls do nothing. */
    public static synchronized void boot() {
        if (booted) {
            return;
        }
        SharedConstants.tryDetectVersion();
        setBootstrapped(true);
        try {
            AlinaTriggers.init();
        } finally {
            setBootstrapped(false);
        }
        Bootstrap.bootStrap();
        booted = true;
    }

    private static void setBootstrapped(boolean value) {
        try {
            Field flag = Bootstrap.class.getDeclaredField("isBootstrapped");
            flag.setAccessible(true);
            flag.setBoolean(null, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Bootstrap.isBootstrapped is not as TestBootstrap expects", e);
        }
    }
}
