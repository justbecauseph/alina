package town.lampas.alina.storage;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import town.lampas.alina.Alina;
import town.lampas.alina.api.AlinaServices;

/**
 * Owned by the storage component. Reads {@code config/alina.json}, builds the configured {@link AlinaStorage}
 * (in-memory or PostgreSQL), runs migrations, registers it with {@link AlinaServices#setStorage} and closes it when the
 * server stops.
 *
 * <p>{@link #init()} builds the storage and registers it, so later modules can read it. The migrations run on
 * {@code SERVER_STARTING}. This module registers that listener first, so they finish before the other modules read the
 * database. A singleplayer session can start the server again in the same JVM. The storage closes on
 * {@code SERVER_STOPPED}, and the next start builds a fresh one.
 */
public final class StorageModule {
    private static final String CONFIG_FILE = "alina.json";

    private static StorageConfig config;
    private static AlinaStorage current;

    private StorageModule() {}

    public static void init() {
        config = StorageConfig.loadOrCreate(FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE));
        open();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> start());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> stop());
    }

    private static void open() {
        current = build(config);
        AlinaServices.setStorage(current);
    }

    private static void start() {
        if (current == null) {
            open();
        }
        if (current instanceof PostgresStorage postgres) {
            postgres.migrate();
        }
    }

    private static void stop() {
        AlinaStorage stopping = current;
        current = null;
        if (stopping != null) {
            stopping.close();
        }
    }

    private static AlinaStorage build(StorageConfig settings) {
        return switch (settings.backend()) {
            case MEMORY -> {
                Alina.LOGGER.info("Alina storage: in-memory backend. Commissions, achievements and operations are lost "
                        + "when the server stops.");
                yield new InMemoryStorage();
            }
            case POSTGRES -> {
                Alina.LOGGER.info("Alina storage: PostgreSQL backend, schema {}", settings.schema());
                yield new PostgresStorage(settings.jdbcUrl(), settings.username(),
                        password(settings.passwordEnv()), settings.schema(), settings.poolSize());
            }
        };
    }

    private static String password(String variable) {
        String value = System.getenv(variable);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException(
                    "Alina storage is set to PostgreSQL, but the environment variable " + variable + " is not set");
        }
        return value;
    }
}
