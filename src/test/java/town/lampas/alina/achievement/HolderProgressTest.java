package town.lampas.alina.achievement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static town.lampas.alina.achievement.AchievementFixtures.Ach;
import static town.lampas.alina.achievement.AchievementFixtures.standing;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import town.lampas.alina.content.AchievementsContent.Achievement;

/**
 * Segments and progress as ALINA-PLAN §9.4 states them: one segment per counted event at most 1000, a target above that is
 * shown in 1000 steps, and a requirements group is one segment.
 */
class HolderProgressTest {

    @BeforeAll
    static void boot() {
        AchievementFixtures.boot();
    }

    @Test
    void aTargetHasOneSegmentPerEventUpToAThousand() {
        assertEquals(1, HolderProgress.segments(1));
        assertEquals(37, HolderProgress.segments(37));
        assertEquals(1000, HolderProgress.segments(1000));
        assertEquals(1000, HolderProgress.segments(2500));
    }

    @Test
    void aTargetAboveAThousandIsDoneInStepsOfItsThousandth() {
        // Segment i is done at ceil(i * 2500 / 1000) events: segment 1 at 3, segment 2 at 5.
        assertEquals(0, HolderProgress.doneSegments(2500, 0));
        assertEquals(0, HolderProgress.doneSegments(2500, 2));
        assertEquals(1, HolderProgress.doneSegments(2500, 3));
        assertEquals(2, HolderProgress.doneSegments(2500, 5));
        assertEquals(500, HolderProgress.doneSegments(2500, 1250));
        assertEquals(1000, HolderProgress.doneSegments(2500, 2500));
    }

    @Test
    void aCountedEventIsOneSegmentForTargetsUpToAThousand() {
        assertEquals(20, HolderProgress.doneSegments(37, 20));
        assertEquals(37, HolderProgress.doneSegments(37, 37));
        assertEquals(999, HolderProgress.doneSegments(1000, 999));
    }

    @Test
    void anUntieredCountedAchievementShowsItsCount() {
        Achievement hunt = Ach.of("hunt").criterion("kills", 37).build();
        HolderProgress.State state = HolderProgress.of(hunt, 1, standing(hunt.id(), 0, 0, Map.of("kills", 20)));
        assertEquals(new HolderProgress.State(false, 37, 20), state);
    }

    @Test
    void aCountAboveAThousandSegmentsIsShownInAThousandSteps() {
        Achievement hunt = Ach.of("hunt").criterion("kills", 2500).build();
        HolderProgress.State state = HolderProgress.of(hunt, 1, standing(hunt.id(), 0, 0, Map.of("kills", 1250)));
        assertEquals(new HolderProgress.State(false, 1000, 500), state);
    }

    @Test
    void anEarnedHolderHasEverySegmentDone() {
        Achievement hunt = Ach.of("hunt").criterion("kills", 37).build();
        assertEquals(new HolderProgress.State(true, 37, 37),
                HolderProgress.of(hunt, 1, standing(hunt.id(), 1, 1, Map.of("kills", 37))));
    }

    @Test
    void aMetCountThatIsNotYetEarnedStillShowsOneSegmentShort() {
        Achievement hunt = Ach.of("hunt").criterion("kills", 37).build();
        assertEquals(new HolderProgress.State(false, 37, 36),
                HolderProgress.of(hunt, 1, standing(hunt.id(), 0, 0, Map.of("kills", 37))));
    }

    @Test
    void aTierIsShownAgainstItsOwnCount() {
        Achievement bell = Ach.of("bell").criterion("rings", 1).tier(10).tier(100).build();
        Map<String, Integer> counts = Map.of("rings", 40);
        assertEquals(new HolderProgress.State(true, 10, 10),
                HolderProgress.of(bell, 1, standing(bell.id(), 1, 1, counts)));
        assertEquals(new HolderProgress.State(false, 100, 40),
                HolderProgress.of(bell, 2, standing(bell.id(), 1, 1, counts)));
    }

    @Test
    void eachRequirementsGroupIsOneSegmentDoneWhenItsGroupIsMet() {
        Achievement pair = Ach.of("pair").criterion("one", 1).criterion("two", 1).build();
        assertEquals(new HolderProgress.State(false, 2, 1),
                HolderProgress.of(pair, 1, standing(pair.id(), 0, 0, Map.of("two", 1))));
        assertEquals(new HolderProgress.State(true, 2, 2),
                HolderProgress.of(pair, 1, standing(pair.id(), 1, 1, Map.of("one", 1, "two", 1))));
    }

    @Test
    void aGroupWithSeveralCriteriaIsMetByAnyOfThem() {
        Achievement either = Ach.of("either").criterion("a", 1).criterion("b", 2).criterion("c", 1)
                .requirements(List.of(List.of("a", "b"), List.of("c"))).build();
        assertEquals(new HolderProgress.State(false, 2, 1),
                HolderProgress.of(either, 1, standing(either.id(), 0, 0, Map.of("b", 2))));
    }

    @Test
    void aStateNeedsASegmentAndDoneSegmentsThatAgreeWithDone() {
        assertThrows(IllegalArgumentException.class, () -> new HolderProgress.State(false, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new HolderProgress.State(false, 3, 3));
        assertThrows(IllegalArgumentException.class, () -> new HolderProgress.State(true, 3, 2));
    }
}
