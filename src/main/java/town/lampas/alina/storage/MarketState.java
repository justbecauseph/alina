package town.lampas.alina.storage;

import java.time.LocalDate;
import java.util.Objects;

/**
 * The shared experience market every experience broker trades against: its two reserves, experience points and Aur, and
 * the board day it last restocked. Storage keeps each reserve as the exact sum of the seed and every delta applied to it,
 * so a reserve may be below 1 or above the server's cap; the server clamps what it reads into its own pool.
 */
public record MarketState(long points, long aur, LocalDate restockedDay) {

    public MarketState {
        Objects.requireNonNull(restockedDay, "restockedDay");
    }
}
