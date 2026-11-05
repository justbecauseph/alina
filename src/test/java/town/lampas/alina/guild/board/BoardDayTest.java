package town.lampas.alina.guild.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * Board days (ALINA-PLAN §8.2). The expected instants are written out from the offsets in force on each date, so they do
 * not come from BoardDay.
 */
class BoardDayTest {

    private static final ZoneId MANILA = ZoneId.of("Asia/Manila");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalTime FOUR = LocalTime.of(4, 0);

    @Test
    void theResetInManilaSeparatesTheBoardDays() {
        // 03:59:59 and 04:00 on 2026-11-05 in Manila (UTC+8, no daylight saving).
        Instant before = Instant.parse("2026-11-04T19:59:59Z");
        Instant atReset = Instant.parse("2026-11-04T20:00:00Z");
        assertEquals(LocalDate.of(2026, 11, 4), BoardDay.dateOf(before, FOUR, MANILA));
        assertEquals(LocalDate.of(2026, 11, 5), BoardDay.dateOf(atReset, FOUR, MANILA));
        assertEquals(atReset, BoardDay.resetOf(LocalDate.of(2026, 11, 5), FOUR, MANILA));
        assertEquals(atReset, BoardDay.nextReset(before, FOUR, MANILA));
        assertEquals(Instant.parse("2026-11-05T20:00:00Z"), BoardDay.nextReset(atReset, FOUR, MANILA));
    }

    @Test
    void aResetInTheSpringGapIsMovedForwardAndTheDayBeforeIsUnchanged() {
        // 2026-03-08 02:00 to 03:00 does not exist in New York. 02:30 that day is 03:30 EDT, which is 07:30Z.
        LocalTime gapReset = LocalTime.of(2, 30);
        LocalDate gapDay = LocalDate.of(2026, 3, 8);
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), BoardDay.resetOf(gapDay, gapReset, NEW_YORK));
        assertEquals(Instant.parse("2026-03-07T07:30:00Z"), BoardDay.resetOf(gapDay.minusDays(1), gapReset, NEW_YORK));

        // 06:59:59Z is 01:59:59 EST, and 07:00Z is 03:00 EDT: both are before the reset, so they belong to 03-07.
        assertEquals(LocalDate.of(2026, 3, 7), BoardDay.dateOf(Instant.parse("2026-03-08T06:59:59Z"), gapReset, NEW_YORK));
        assertEquals(LocalDate.of(2026, 3, 7), BoardDay.dateOf(Instant.parse("2026-03-08T07:00:00Z"), gapReset, NEW_YORK));
        assertEquals(LocalDate.of(2026, 3, 7), BoardDay.dateOf(Instant.parse("2026-03-08T07:29:59Z"), gapReset, NEW_YORK));
        assertEquals(LocalDate.of(2026, 3, 8), BoardDay.dateOf(Instant.parse("2026-03-08T07:30:00Z"), gapReset, NEW_YORK));

        assertEquals(Instant.parse("2026-03-08T07:30:00Z"),
                BoardDay.nextReset(Instant.parse("2026-03-07T12:00:00Z"), gapReset, NEW_YORK));
        assertEquals(Instant.parse("2026-03-09T06:30:00Z"),
                BoardDay.nextReset(Instant.parse("2026-03-08T07:30:00Z"), gapReset, NEW_YORK));
    }

    @Test
    void aResetInTheAutumnOverlapTakesTheEarlierOffsetAndTheRepeatDoesNotStartADay() {
        // 2026-11-01 01:00 to 02:00 happens twice in New York. 01:30 is 01:30 EDT (05:30Z) and again 01:30 EST (06:30Z).
        LocalTime overlapReset = LocalTime.of(1, 30);
        LocalDate overlapDay = LocalDate.of(2026, 11, 1);
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), BoardDay.resetOf(overlapDay, overlapReset, NEW_YORK));

        assertEquals(LocalDate.of(2026, 10, 31), BoardDay.dateOf(Instant.parse("2026-11-01T05:29:59Z"), overlapReset, NEW_YORK));
        assertEquals(LocalDate.of(2026, 11, 1), BoardDay.dateOf(Instant.parse("2026-11-01T05:30:00Z"), overlapReset, NEW_YORK));
        // The second 01:30 is still the 11-01 board day, which began at the first one.
        assertEquals(LocalDate.of(2026, 11, 1), BoardDay.dateOf(Instant.parse("2026-11-01T06:30:00Z"), overlapReset, NEW_YORK));

        assertEquals(Instant.parse("2026-11-02T06:30:00Z"),
                BoardDay.nextReset(Instant.parse("2026-11-01T05:30:00Z"), overlapReset, NEW_YORK));
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"),
                BoardDay.nextReset(Instant.parse("2026-11-01T06:45:00Z"), overlapReset, NEW_YORK));
    }

    @Test
    void theInvariantsHoldAcrossTheDaylightSavingChangesForSeveralResets() {
        LocalTime[] resets = {LocalTime.of(2, 30), LocalTime.of(1, 30), LocalTime.MIDNIGHT, LocalTime.of(23, 59, 59)};
        Instant[] starts = {Instant.parse("2026-03-06T00:00:00Z"), Instant.parse("2026-10-30T00:00:00Z")};
        for (LocalTime reset : resets) {
            for (Instant start : starts) {
                for (Instant now = start; now.isBefore(start.plus(Duration.ofDays(4))); now = now.plusSeconds(17 * 60)) {
                    assertInvariants(now, reset, NEW_YORK);
                }
            }
        }
        for (Instant now = Instant.parse("2026-11-04T00:00:00Z"); now.isBefore(Instant.parse("2026-11-06T00:00:00Z"));
                now = now.plusSeconds(17 * 60)) {
            assertInvariants(now, FOUR, MANILA);
        }
    }

    private static void assertInvariants(Instant now, LocalTime reset, ZoneId zone) {
        LocalDate date = BoardDay.dateOf(now, reset, zone);
        assertTrue(!BoardDay.resetOf(date, reset, zone).isAfter(now), "the reset of " + date + " is after " + now);
        Instant next = BoardDay.nextReset(now, reset, zone);
        assertTrue(next.isAfter(now), "nextReset " + next + " is not after " + now);
        assertEquals(BoardDay.resetOf(date.plusDays(1), reset, zone), next, "nextReset of " + now);
        assertEquals(date.plusDays(1), BoardDay.dateOf(next, reset, zone), "the board day that starts at nextReset of " + now);
        assertEquals(date, BoardDay.dateOf(next.minusNanos(1), reset, zone), "the last instant before nextReset of " + now);
    }
}
