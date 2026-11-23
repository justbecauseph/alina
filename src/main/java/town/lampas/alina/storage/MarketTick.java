package town.lampas.alina.storage;

import java.time.Instant;
import java.util.Objects;

/**
 * The reserves of the experience market after one change of the pool, and the time of the change: the points of the rate
 * chart. The values are the stored row's, so any long, as {@link MarketState} holds them.
 */
public record MarketTick(long points, long aur, Instant at) {

    public MarketTick {
        Objects.requireNonNull(at, "at");
    }
}
