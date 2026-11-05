package town.lampas.alina.guild.board;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Board days (ALINA-PLAN §8.2). A board day starts at {@code reset} in {@code zone} and lasts until the next one. The
 * reset instant of a date is {@code ZonedDateTime.of(date, reset, zone)}, which moves a time in a daylight-saving gap
 * forward and takes the earlier offset in an overlap.
 */
public final class BoardDay {

    private BoardDay() {}

    /** The board day {@code now} falls in: the latest date whose reset instant is at or before {@code now}. */
    public static LocalDate dateOf(Instant now, LocalTime reset, ZoneId zone) {
        throw new UnsupportedOperationException("not built yet");
    }

    /** The reset instant that starts the date's board day. */
    public static Instant resetOf(LocalDate date, LocalTime reset, ZoneId zone) {
        throw new UnsupportedOperationException("not built yet");
    }

    /** The first reset instant after {@code now}: the start of the next board day. Always after {@code now}. */
    public static Instant nextReset(Instant now, LocalTime reset, ZoneId zone) {
        throw new UnsupportedOperationException("not built yet");
    }
}
