package town.lampas.alina.storage;

import java.time.Instant;
import java.util.Objects;

/**
 * One experience market tick together with its monotonically increasing storage id, so callers can poll for ticks
 * recorded after a known id.
 */
public record IndexedMarketTick(long id, long points, long aur, Instant at) {

    public IndexedMarketTick {
        Objects.requireNonNull(at, "at");
    }
}
