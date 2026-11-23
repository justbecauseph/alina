package town.lampas.alina.trade.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The points formula and the points a player holds. The per-level values come from vanilla's
 * {@code Player.getXpNeededForNextLevel}: 7 + 2L below level 15, 37 + 5(L - 15) below 30, and 112 + 9(L - 30) from 30 up.
 */
class ExperiencePointsTest {

    @Test
    void theNeededPointsFollowVanillaAtTheSegmentEnds() {
        assertEquals(7, ExperiencePoints.neededForLevel(0));
        assertEquals(37, ExperiencePoints.neededForLevel(15));
        assertEquals(42, ExperiencePoints.neededForLevel(16));
        assertEquals(112, ExperiencePoints.neededForLevel(30));
        assertEquals(121, ExperiencePoints.neededForLevel(31));
    }

    @Test
    void aLevelThirtyWithNoProgressHoldsTheSumOfTheLowerLevels() {
        // Levels 0 to 14 need 7 + 2L: 315. Levels 15 to 29 need 37 + 5k: 1080. Together 1395.
        assertEquals(1395, ExperiencePoints.total(30, 0f));
    }

    @Test
    void theTotalAddsEachLevelAboveThirty() {
        assertEquals(352, ExperiencePoints.total(16, 0f), "315 for levels 0 to 14, and 37 for level 15");
        assertEquals(1507, ExperiencePoints.total(31, 0f), "1395 for levels 0 to 29, and 112 for level 30");
    }

    @Test
    void progressCountsItsShareOfTheLevelRoundedDown() {
        // Level 1 holds the 7 points of level 0 and half of level 1's 9: 4.5, which rounds down to 4.
        assertEquals(11, ExperiencePoints.total(1, 0.5f));
        // Half of level 0's 7 points is 3.5. Vanilla takes 4 points from a player with 3.5 and stops at level 0 progress 0,
        // so 4 would be more than the player holds.
        assertEquals(3, ExperiencePoints.total(0, 0.5f));
    }

    @Test
    void aTotalPastTheIntegerLimitSaturates() {
        assertEquals(Integer.MAX_VALUE, ExperiencePoints.total(30_000, 0f));
        assertEquals(Integer.MAX_VALUE, ExperiencePoints.total(Integer.MAX_VALUE, 0.5f));
    }

    @Test
    void aNegativeLevelOrAProgressOutsideZeroToOneIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ExperiencePoints.neededForLevel(-1));
        assertThrows(IllegalArgumentException.class, () -> ExperiencePoints.total(-1, 0f));
        assertThrows(IllegalArgumentException.class, () -> ExperiencePoints.total(1, 1f));
        assertThrows(IllegalArgumentException.class, () -> ExperiencePoints.total(1, -0.1f));
        assertThrows(IllegalArgumentException.class, () -> ExperiencePoints.total(1, Float.NaN));
    }

    @Test
    void zeroPointsIsLevelZeroWithNothingInto() {
        assertEquals(new ExperiencePoints.Standing(0, 0, 7), ExperiencePoints.standing(0));
    }

    @Test
    void sixPointsIsStillLevelZeroAndSevenIsLevelOne() {
        assertEquals(new ExperiencePoints.Standing(0, 6, 7), ExperiencePoints.standing(6));
        assertEquals(new ExperiencePoints.Standing(1, 0, 9), ExperiencePoints.standing(7));
    }

    @Test
    void theTotalAtLevelSixteenIsExactlyLevelSixteen() {
        assertEquals(new ExperiencePoints.Standing(16, 0, 42), ExperiencePoints.standing(352));
        assertEquals(new ExperiencePoints.Standing(15, 36, 37), ExperiencePoints.standing(351));
    }

    @Test
    void theTotalAtLevelThirtyIsExactlyLevelThirty() {
        assertEquals(new ExperiencePoints.Standing(30, 0, 112), ExperiencePoints.standing(1395));
        assertEquals(new ExperiencePoints.Standing(29, 106, 107), ExperiencePoints.standing(1394));
    }

    @Test
    void theTotalAtLevelThirtyOneIsLevelThirtyOneWithNothingInto() {
        assertEquals(new ExperiencePoints.Standing(31, 0, 121), ExperiencePoints.standing(1507));
    }

    @Test
    void theLargestTotalStandsBelowTheSaturatedLevel() {
        ExperiencePoints.Standing standing = ExperiencePoints.standing(Integer.MAX_VALUE);

        assertTrue(standing.level() < 30_000, "level " + standing.level());
        assertTrue(standing.into() < standing.needed(), "into " + standing.into());
    }

    @Test
    void standingReturnsTheLevelWhoseTotalIsTheStartOfThatLevel() {
        for (int level : new int[] {0, 1, 14, 15, 16, 29, 30, 31, 100, 1000}) {
            ExperiencePoints.Standing standing = ExperiencePoints.standing(ExperiencePoints.total(level, 0f));

            assertEquals(level, standing.level());
            assertEquals(0, standing.into());
        }
    }

    @Test
    void aNegativeTotalIsRefusedByStanding() {
        assertThrows(IllegalArgumentException.class, () -> ExperiencePoints.standing(-1));
    }
}
