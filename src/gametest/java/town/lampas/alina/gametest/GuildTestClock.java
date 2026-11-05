package town.lampas.alina.gametest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock the Guild GameTests move by hand. Its zone is UTC; board days use the settings' zone. */
final class GuildTestClock extends Clock {
    private Instant now;

    GuildTestClock(Instant start) {
        this.now = start;
    }

    void advance(Duration by) {
        now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
