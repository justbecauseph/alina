package town.lampas.alina.api;

import java.util.Objects;
import town.lampas.alina.storage.AlinaStorage;

/**
 * The cross-module service seams. Each module sets its own service during {@code init()} and reads the others' through
 * the getters. A getter throws if the owning module has not initialised yet, which means an init-order bug.
 */
public final class AlinaServices {
    private static volatile AlinaStorage storage;

    private AlinaServices() {}

    public static AlinaStorage storage() {
        return require(storage, "storage");
    }

    public static void setStorage(AlinaStorage value) {
        storage = Objects.requireNonNull(value);
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalStateException("Alina " + name + " service is not initialised");
        }
        return value;
    }
}
