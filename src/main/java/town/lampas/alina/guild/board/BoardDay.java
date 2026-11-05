package town.lampas.alina.guild.board;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Board days (ALINA-PLAN §8.2). A board day starts at {@code reset} in {@code zone} and lasts until the next one. The
 * reset instant of a date is {@code ZonedDateTime.of(date, reset, zone)}, which moves a time in a daylight-saving gap
 * forward and takes the earlier offset in an overlap.
 */
public final class BoardDay {

    private BoardDay() {}

    /** The board day {@code now} falls in: the latest date whose reset instant is at or before {@code now}. */
    public static LocalDate dateOf(Instant now, LocalTime reset, ZoneId zone) {
        LocalDate date = ZonedDateTime.ofInstant(now, zone).toLocalDate();
        // Reset instants rise with the date, so the latest started date is within a day of now's local date.
        while (resetOf(date, reset, zone).isAfter(now)) {
            date = date.minusDays(1);
        }
        while (!resetOf(date.plusDays(1), reset, zone).isAfter(now)) {
            date = date.plusDays(1);
        }
        return date;
    }

    /** The reset instant that starts the date's board day. */
    public static Instant resetOf(LocalDate date, LocalTime reset, ZoneId zone) {
        return ZonedDateTime.of(date, reset, zone).toInstant();
    }

    /** The first reset instant after {@code now}: the start of the next board day. Always after {@code now}. */
    public static Instant nextReset(Instant now, LocalTime reset, ZoneId zone) {
        return resetOf(dateOf(now, reset, zone).plusDays(1), reset, zone);
    }
}
