package town.lampas.alina.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every promise {@link AlinaStorage} makes, run against one implementation. A subclass supplies a fresh, empty storage
 * for each test, and this class closes it afterwards.
 */
abstract class AlinaStorageContractTest {
    private static final long TIMEOUT_SECONDS = 10;
    private static final UUID ALICE = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    private static final UUID BOB = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    private static final UUID OP1 = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OP2 = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID OP3 = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID OP4 = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-05T10:01:00Z");
    private static final Instant T2 = Instant.parse("2026-11-05T10:02:00Z");
    private static final Instant T3 = Instant.parse("2026-11-05T10:03:00Z");
    private static final Instant T4 = Instant.parse("2026-11-05T10:04:00Z");
    private static final Instant T5 = Instant.parse("2026-11-05T10:05:00Z");
    private static final Instant T6 = Instant.parse("2026-11-05T10:06:00Z");
    private static final LocalDate DAY = LocalDate.parse("2026-11-05");
    private static final LocalDate NEXT_DAY = LocalDate.parse("2026-11-06");
    private static final Identifier COURIER = Identifier.parse("alina:guild/courier");
    private static final Identifier SCRIBE = Identifier.parse("alina:guild/scribe");
    private static final Identifier PORTER = Identifier.parse("alina:guild/porter");
    private static final Identifier BOARD = Identifier.parse("alina:board");
    private static final Identifier HUNTERS = Identifier.parse("alina:guild/hunters");
    private static final Identifier UNLISTED = Identifier.parse("alina:guild/unlisted");
    private static final Identifier FIRST_STEPS = Identifier.parse("alina:achievement/first_steps");
    private static final Identifier MINER = Identifier.parse("alina:achievement/miner");
    private static final CriterionKey WALK = new CriterionKey(FIRST_STEPS, "walk");
    private static final CriterionKey JUMP = new CriterionKey(FIRST_STEPS, "jump");
    private static final CriterionKey DIG = new CriterionKey(MINER, "dig");
    private static final String WORLD = "minecraft:overworld";
    private static final String REWARD = "{\"coins\":5}";
    private static final long REVISION = 3;

    private final List<AlinaStorage> opened = new ArrayList<>();
    protected AlinaStorage storage;

    /** A new, empty storage for one test. The base class closes it. */
    protected abstract AlinaStorage createStorage() throws Exception;

    @BeforeEach
    void openStorage() throws Exception {
        storage = createStorage();
        opened.add(storage);
    }

    @AfterEach
    void closeStorage() {
        opened.forEach(AlinaStorage::close);
    }

    @Test
    void aCharacterWithNoRowsLoadsAsReputationZeroWithEmptyMaps() {
        assertEquals(new CharacterState(ALICE, 0, Map.of(), Map.of(), Map.of()), await(storage.loadCharacter(ALICE)));
    }

    @Test
    void setReputationIsReadBackAndMayBeNegative() {
        await(storage.setReputation(ALICE, 7, T0));
        assertEquals(7, await(storage.loadCharacter(ALICE)).reputation());

        await(storage.setReputation(ALICE, -4, T1));
        assertEquals(-4, await(storage.loadCharacter(ALICE)).reputation());
    }

    @Test
    void charactersAreKeptApart() {
        await(storage.setReputation(ALICE, 3, T0));
        await(storage.acceptCommission(ALICE, COURIER, 1, T0));
        await(storage.writeAchievement(ALICE, new AchievementState(FIRST_STEPS, 1, 1, Optional.of(T0), Optional.empty()), T0));
        await(storage.writeCriterionProgress(ALICE, Map.of(DIG, 2), T0));

        assertEquals(new CharacterState(BOB, 0, Map.of(), Map.of(), Map.of()), await(storage.loadCharacter(BOB)));
    }

    @Test
    void acceptingACommissionCreatesAnActiveRowWithNoProgress() {
        await(storage.acceptCommission(ALICE, COURIER, 4, T0));

        assertEquals(new CommissionState(COURIER, CommissionStatus.ACTIVE, 0, T0, Optional.empty(), Optional.empty(), 4),
                commission(ALICE, COURIER));
    }

    @Test
    void acceptingAnActiveCommissionAgainResetsItsProgressAndRevision() {
        await(storage.acceptCommission(ALICE, COURIER, 4, T0));
        await(storage.writeCommissionProgress(ALICE, Map.of(COURIER, 3)));

        await(storage.acceptCommission(ALICE, COURIER, 5, T1));

        assertEquals(new CommissionState(COURIER, CommissionStatus.ACTIVE, 0, T1, Optional.empty(), Optional.empty(), 5),
                commission(ALICE, COURIER));
    }

    @Test
    void acceptingACommissionAfterATurnInClearsItsCompletionAndCooldown() {
        await(storage.applyOperation(turnIn(OP1, ALICE, COURIER, 1, T1, CommissionStatus.COOLDOWN, Optional.of(T4))));

        await(storage.acceptCommission(ALICE, COURIER, 5, T3));

        assertEquals(new CommissionState(COURIER, CommissionStatus.ACTIVE, 0, T3, Optional.empty(), Optional.empty(), 5),
                commission(ALICE, COURIER));
    }

    @Test
    void abandoningDeletesAnActiveCommissionOnly() {
        await(storage.acceptCommission(ALICE, COURIER, 1, T0));
        await(storage.applyOperation(turnIn(OP1, ALICE, SCRIBE, 0, T1, CommissionStatus.COOLDOWN, Optional.of(T4))));

        assertTrue(await(storage.abandonCommission(ALICE, COURIER)));
        assertFalse(await(storage.abandonCommission(ALICE, COURIER)));
        assertFalse(await(storage.abandonCommission(ALICE, SCRIBE)));

        assertEquals(Map.of(SCRIBE, new CommissionState(SCRIBE, CommissionStatus.COOLDOWN, 0, T1, Optional.of(T1),
                Optional.of(T4), REVISION)), commissions(ALICE));
    }

    @Test
    void progressWritesChangeOnlyActiveCommissions() {
        await(storage.acceptCommission(ALICE, COURIER, 1, T0));
        await(storage.acceptCommission(ALICE, SCRIBE, 1, T0));
        await(storage.applyOperation(turnIn(OP1, ALICE, PORTER, 0, T1, CommissionStatus.COOLDOWN, Optional.of(T4))));

        await(storage.writeCommissionProgress(ALICE, Map.of(COURIER, 3, PORTER, 9, UNLISTED, 1)));

        assertEquals(Map.of(
                COURIER, new CommissionState(COURIER, CommissionStatus.ACTIVE, 3, T0, Optional.empty(), Optional.empty(), 1),
                SCRIBE, new CommissionState(SCRIBE, CommissionStatus.ACTIVE, 0, T0, Optional.empty(), Optional.empty(), 1),
                PORTER, new CommissionState(PORTER, CommissionStatus.COOLDOWN, 0, T1, Optional.of(T1), Optional.of(T4), 3)),
                commissions(ALICE));
    }

    @Test
    void aNegativeProgressRefusesTheWholeWrite() {
        // Twenty valid entries and one negative one. A partial write would show in nearly every run, since the entries
        // are applied in the order a hash map gives, which differs between JVM runs.
        Map<Identifier, Integer> progress = new HashMap<>();
        for (int index = 0; index < 20; index++) {
            Identifier commission = Identifier.parse("alina:guild/bulk_" + index);
            await(storage.acceptCommission(ALICE, commission, 1, T0));
            progress.put(commission, index + 1);
        }
        await(storage.acceptCommission(ALICE, SCRIBE, 1, T0));
        progress.put(SCRIBE, -1);
        Map<Identifier, CommissionState> before = commissions(ALICE);

        Throwable failure = failure(storage.writeCommissionProgress(ALICE, progress));

        assertInstanceOf(IllegalArgumentException.class, failure);
        assertEquals(before, commissions(ALICE));
    }

    @Test
    void clearingACommissionDeletesItWhateverItsStatus() {
        await(storage.acceptCommission(ALICE, COURIER, 1, T0));
        await(storage.applyOperation(turnIn(OP1, ALICE, SCRIBE, 0, T1, CommissionStatus.COOLDOWN, Optional.of(T4))));

        await(storage.clearCommission(ALICE, COURIER));
        await(storage.clearCommission(ALICE, SCRIBE));
        await(storage.clearCommission(ALICE, UNLISTED));

        assertEquals(Map.of(), commissions(ALICE));
    }

    @Test
    void aTurnInSetsTheCooldownAndAddsItsReputationDelta() {
        Operation turnIn = turnIn(OP1, ALICE, COURIER, 10, T1, CommissionStatus.COOLDOWN, Optional.of(T4));

        assertEquals(new ApplyResult(true, 10), await(storage.applyOperation(turnIn)));

        CharacterState state = await(storage.loadCharacter(ALICE));
        assertEquals(10, state.reputation());
        assertEquals(Map.of(COURIER, new CommissionState(COURIER, CommissionStatus.COOLDOWN, 0, T1, Optional.of(T1),
                Optional.of(T4), REVISION)), state.commissions());
    }

    @Test
    void aTurnInToCompletedHasNoCooldown() {
        await(storage.applyOperation(turnIn(OP1, ALICE, COURIER, 0, T1, CommissionStatus.COMPLETED, Optional.empty())));

        assertEquals(new CommissionState(COURIER, CommissionStatus.COMPLETED, 0, T1, Optional.of(T1), Optional.empty(),
                REVISION), commission(ALICE, COURIER));
    }

    @Test
    void aTurnInKeepsTheAcceptedTimeAndRevisionOfAnAcceptedRow() {
        await(storage.acceptCommission(ALICE, COURIER, 2, T0));
        await(storage.writeCommissionProgress(ALICE, Map.of(COURIER, 4)));

        assertEquals(new ApplyResult(true, 5),
                await(storage.applyOperation(turnIn(OP1, ALICE, COURIER, 5, T2, CommissionStatus.COMPLETED, Optional.empty()))));

        assertEquals(new CommissionState(COURIER, CommissionStatus.COMPLETED, 0, T0, Optional.of(T2), Optional.empty(), 2),
                commission(ALICE, COURIER));
    }

    @Test
    void anAchievementEffectIncrementsTheEarnCountAndKeepsTheHighestTier() {
        // Tiers 2, 3, 1 in that order, so the highest tier is not the last one applied. The tier-1 earn is below the
        // stored tier 3, so the availability of the tier-3 earn stays.
        await(storage.applyOperation(earned(OP1, FIRST_STEPS, 2, T6, T1)));
        await(storage.applyOperation(earned(OP2, FIRST_STEPS, 3, T5, T2)));
        assertEquals(new ApplyResult(true, 0), await(storage.applyOperation(earned(OP3, FIRST_STEPS, 1, T4, T3))));

        assertEquals(new AchievementState(FIRST_STEPS, 3, 3, Optional.of(T3), Optional.of(T5)),
                achievement(ALICE, FIRST_STEPS));
    }

    @Test
    void aLowerTierAppliedAfterTheHighestKeepsTheHighestTiersAvailability() {
        // The completing tier-3 earn is applied first; the tier-2 earn of the same achievement is applied after it and
        // carries no availability.
        await(storage.applyOperation(earned(OP1, FIRST_STEPS, 3, T5, T1)));
        await(storage.applyOperation(earnedWithoutAvailability(OP2, FIRST_STEPS, 2, T2)));

        assertEquals(new AchievementState(FIRST_STEPS, 3, 2, Optional.of(T2), Optional.of(T5)),
                achievement(ALICE, FIRST_STEPS));
    }

    @Test
    void aHigherTierTakesItsAvailabilityWhenTheStoredTierIsLower() {
        await(storage.applyOperation(earnedWithoutAvailability(OP1, FIRST_STEPS, 2, T1)));
        await(storage.applyOperation(earned(OP2, FIRST_STEPS, 3, T5, T2)));

        assertEquals(new AchievementState(FIRST_STEPS, 3, 2, Optional.of(T2), Optional.of(T5)),
                achievement(ALICE, FIRST_STEPS));
    }

    @Test
    void anEarnOfTheSameTierTakesItsOwnAvailability() {
        await(storage.applyOperation(earned(OP1, FIRST_STEPS, 2, T6, T1)));
        await(storage.applyOperation(earnedWithoutAvailability(OP2, FIRST_STEPS, 2, T2)));

        assertEquals(new AchievementState(FIRST_STEPS, 2, 2, Optional.of(T2), Optional.empty()),
                achievement(ALICE, FIRST_STEPS));
    }

    @Test
    void anOperationWhoseEarnCountWouldOverflowIsRefusedAndRecordsNothing() {
        await(storage.setReputation(ALICE, 5, T0));
        await(storage.writeAchievement(ALICE,
                new AchievementState(FIRST_STEPS, 1, Integer.MAX_VALUE, Optional.of(T0), Optional.empty()), T0));
        Operation reward = new Operation(OP1, ALICE, OperationKind.ACHIEVEMENT_REWARD, FIRST_STEPS, 1, REVISION, WORLD,
                REWARD, 3, new OperationEffect.AchievementEarned(FIRST_STEPS, 1, Optional.of(T5)), T1);

        assertNotNull(failure(storage.applyOperation(reward)));
        // A retry fails the same way. It must not report the operation as already applied.
        assertNotNull(failure(storage.applyOperation(reward)));

        assertEquals(Optional.empty(), await(storage.loadOperation(OP1)));
        assertEquals(5, await(storage.loadCharacter(ALICE)).reputation());
        assertEquals(Integer.MAX_VALUE, achievement(ALICE, FIRST_STEPS).earnCount());
    }

    @Test
    void aRepeatedOperationIsAppliedOnce() {
        Operation turnIn = turnIn(OP1, ALICE, COURIER, 7, T1, CommissionStatus.COOLDOWN, Optional.of(T4));

        assertEquals(new ApplyResult(true, 7), await(storage.applyOperation(turnIn)));
        assertEquals(new ApplyResult(false, 7), await(storage.applyOperation(turnIn)));

        CharacterState state = await(storage.loadCharacter(ALICE));
        assertEquals(7, state.reputation());
        assertEquals(CommissionStatus.COOLDOWN, state.commissions().get(COURIER).status());
    }

    @Test
    void aRepeatedAchievementRewardIsCountedOnce() {
        Operation reward = earned(OP1, FIRST_STEPS, 1, T5, T1);
        await(storage.applyOperation(reward));

        assertEquals(new ApplyResult(false, 0), await(storage.applyOperation(reward)));

        assertEquals(1, achievement(ALICE, FIRST_STEPS).earnCount());
    }

    @Test
    void aRepeatedIdWithDifferentContentChangesNothing() {
        await(storage.applyOperation(turnIn(OP1, ALICE, COURIER, 7, T1, CommissionStatus.COOLDOWN, Optional.of(T4))));

        ApplyResult again = await(storage.applyOperation(
                turnIn(OP1, ALICE, SCRIBE, 100, T2, CommissionStatus.COMPLETED, Optional.empty())));

        assertEquals(new ApplyResult(false, 7), again);
        CharacterState state = await(storage.loadCharacter(ALICE));
        assertEquals(7, state.reputation());
        assertEquals(Map.of(COURIER, commission(ALICE, COURIER)), state.commissions());
    }

    @Test
    void concurrentCopiesOfOneOperationAreAppliedOnce() {
        Operation turnIn = turnIn(OP1, ALICE, COURIER, 4, T1, CommissionStatus.COOLDOWN, Optional.of(T4));
        List<CompletableFuture<ApplyResult>> pending = IntStream.range(0, 20)
                .mapToObj(index -> storage.applyOperation(turnIn))
                .toList();

        List<ApplyResult> results = pending.stream().map(AlinaStorageContractTest::await).toList();

        assertEquals(1, results.stream().filter(ApplyResult::fresh).count());
        assertTrue(results.stream().allMatch(result -> result.reputation() == 4), "results were " + results);
        assertEquals(4, await(storage.loadCharacter(ALICE)).reputation());
    }

    @Test
    void reputationDeltasAddAndMayBeNegative() {
        assertEquals(new ApplyResult(true, 3),
                await(storage.applyOperation(turnIn(OP1, ALICE, COURIER, 3, T1, CommissionStatus.COOLDOWN, Optional.of(T4)))));
        assertEquals(new ApplyResult(true, 2),
                await(storage.applyOperation(turnIn(OP2, ALICE, SCRIBE, -1, T2, CommissionStatus.COOLDOWN, Optional.of(T5)))));

        assertEquals(2, await(storage.loadCharacter(ALICE)).reputation());
    }

    @Test
    void anOperationWhoseRewardIsNotStrictJsonOrHoldsANulIsRefusedAndRecordsNothing() {
        OperationEffect effect = new OperationEffect.CommissionSettled(COURIER, CommissionStatus.COOLDOWN, Optional.of(T4));
        Operation notJson = new Operation(OP1, ALICE, OperationKind.TURN_IN, COURIER, 0, REVISION, WORLD, "{\"coins\":",
                5, effect, T1);
        Operation withNul = new Operation(OP2, ALICE, OperationKind.TURN_IN, COURIER, 0, REVISION, WORLD,
                "{\"name\":\"\\u0000\"}", 5, effect, T1);
        Operation lenientOnly = new Operation(OP3, ALICE, OperationKind.TURN_IN, COURIER, 0, REVISION, WORLD,
                "{\"coins\":+1}", 5, effect, T1);

        assertInstanceOf(IllegalArgumentException.class, failure(storage.applyOperation(notJson)));
        assertInstanceOf(IllegalArgumentException.class, failure(storage.applyOperation(withNul)));
        assertInstanceOf(IllegalArgumentException.class, failure(storage.applyOperation(lenientOnly)));

        assertEquals(Optional.empty(), await(storage.loadOperation(OP1)));
        assertEquals(Optional.empty(), await(storage.loadOperation(OP2)));
        assertEquals(Optional.empty(), await(storage.loadOperation(OP3)));
        assertEquals(new CharacterState(ALICE, 0, Map.of(), Map.of(), Map.of()), await(storage.loadCharacter(ALICE)));
    }

    @Test
    void rewardJsonIsReadBackInCanonicalForm() {
        String written = "{ \"b\": 1, \"a\": { \"y\": 2.50, \"x\": [1, 1e2] } }";
        await(storage.applyOperation(new Operation(OP1, ALICE, OperationKind.ACHIEVEMENT_REWARD, FIRST_STEPS, 0,
                REVISION, WORLD, written, 0, new OperationEffect.AchievementEarned(FIRST_STEPS, 0, Optional.empty()), T1)));

        StoredOperation stored = await(storage.loadOperation(OP1)).orElseThrow();

        assertEquals("{\"a\":{\"x\":[1,100],\"y\":2.50},\"b\":1}", stored.operation().rewardJson());
    }

    @Test
    void aRecordedOperationKeepsItsStepUntilItIsMarked() {
        Operation turnIn = turnIn(OP1, ALICE, COURIER, 2, T1, CommissionStatus.COOLDOWN, Optional.of(T4));
        await(storage.applyOperation(turnIn));
        assertEquals(Optional.of(new StoredOperation(turnIn, OperationStep.RECORDED, T1, Optional.empty())),
                await(storage.loadOperation(OP1)));

        await(storage.markOperation(OP1, OperationStep.SETTLED, Optional.empty(), T2));
        assertEquals(Optional.of(new StoredOperation(turnIn, OperationStep.SETTLED, T2, Optional.empty())),
                await(storage.loadOperation(OP1)));

        await(storage.markOperation(OP1, OperationStep.RECONCILE, Optional.of("world changed"), T3));
        assertEquals(Optional.of(new StoredOperation(turnIn, OperationStep.RECONCILE, T3, Optional.of("world changed"))),
                await(storage.loadOperation(OP1)));

        await(storage.markOperation(OP1, OperationStep.SETTLED, Optional.empty(), T4));
        assertEquals(Optional.of(new StoredOperation(turnIn, OperationStep.SETTLED, T4, Optional.empty())),
                await(storage.loadOperation(OP1)));
    }

    @Test
    void markingAnUnknownOperationFailsAndLoadingItIsEmpty() {
        assertInstanceOf(IllegalArgumentException.class,
                failure(storage.markOperation(OP1, OperationStep.SETTLED, Optional.empty(), T1)));
        assertEquals(Optional.empty(), await(storage.loadOperation(OP1)));
    }

    @Test
    void listOperationsFiltersBySteps() {
        // createdAt runs T1 (OP2), T2 (OP3 and OP4, equal), T3 (OP1), so the listings are not in id order: OP1 has the
        // smallest id and a later createdAt. OP4 is applied first, so insertion order does not give the tie-break either.
        Operation fourth = earned(OP4, FIRST_STEPS, 1, T6, T2);
        Operation first = turnIn(OP1, ALICE, COURIER, 0, T3, CommissionStatus.COOLDOWN, Optional.of(T6));
        Operation second = turnIn(OP2, ALICE, SCRIBE, 0, T1, CommissionStatus.COOLDOWN, Optional.of(T6));
        Operation third = turnIn(OP3, ALICE, PORTER, 0, T2, CommissionStatus.COOLDOWN, Optional.of(T6));
        for (Operation operation : List.of(fourth, first, second, third)) {
            await(storage.applyOperation(operation));
        }
        await(storage.markOperation(OP4, OperationStep.SETTLED, Optional.empty(), T5));
        await(storage.markOperation(OP3, OperationStep.RECONCILE, Optional.of("check"), T5));

        assertEquals(List.of(OP2, OP1), ids(await(storage.listOperations(Set.of(OperationStep.RECORDED), 10))));
        assertEquals(List.of(OP2, OP3, OP1),
                ids(await(storage.listOperations(Set.of(OperationStep.RECORDED, OperationStep.RECONCILE), 10))));
        assertEquals(List.of(OP4), ids(await(storage.listOperations(Set.of(OperationStep.SETTLED), 10))));
        assertEquals(List.of(OP3, OP4),
                ids(await(storage.listOperations(Set.of(OperationStep.RECONCILE, OperationStep.SETTLED), 10))));
        assertEquals(List.of(OP2, OP3, OP4, OP1), ids(await(storage.listOperations(
                Set.of(OperationStep.RECORDED, OperationStep.RECONCILE, OperationStep.SETTLED), 10))));
        assertEquals(List.of(OP2, OP3, OP4), ids(await(storage.listOperations(
                Set.of(OperationStep.RECORDED, OperationStep.RECONCILE, OperationStep.SETTLED), 3))));
        assertEquals(List.of(OP2), ids(await(storage.listOperations(Set.of(OperationStep.RECORDED), 1))));
        assertEquals(List.of(), await(storage.listOperations(Set.of(), 10)));
    }

    @Test
    void listingWithANonPositiveLimitFails() {
        assertInstanceOf(IllegalArgumentException.class,
                failure(storage.listOperations(Set.of(OperationStep.RECORDED), 0)));
    }

    @Test
    void aBoardIsStoredOnceAndTheFirstStoredBoardWins() {
        StoredBoard first = new StoredBoard(DAY, BOARD, 1, 10, List.of(COURIER, SCRIBE));
        StoredBoard second = new StoredBoard(DAY, BOARD, 2, 20, List.of(PORTER));

        assertEquals(first, await(storage.storeBoard(first)));
        assertEquals(first, await(storage.storeBoard(second)));

        assertEquals(Optional.of(first), await(storage.loadBoard(DAY, BOARD)));
    }

    @Test
    void loadingAnUnknownBoardIsEmpty() {
        assertEquals(Optional.empty(), await(storage.loadBoard(DAY, BOARD)));
    }

    @Test
    void boardsAreKeptPerDate() {
        StoredBoard today = new StoredBoard(DAY, BOARD, 1, 10, List.of(COURIER));
        StoredBoard tomorrow = new StoredBoard(NEXT_DAY, BOARD, 1, 11, List.of(PORTER, SCRIBE));
        await(storage.storeBoard(today));
        await(storage.storeBoard(tomorrow));

        assertEquals(Optional.of(today), await(storage.loadBoard(DAY, BOARD)));
        assertEquals(Optional.of(tomorrow), await(storage.loadBoard(NEXT_DAY, BOARD)));
    }

    @Test
    void twoBoardsOnOneDateAreIndependent() {
        StoredBoard implicit = new StoredBoard(DAY, BOARD, 1, 10, List.of(COURIER));
        StoredBoard hunters = new StoredBoard(DAY, HUNTERS, 1, 11, List.of(PORTER, SCRIBE));

        assertEquals(implicit, await(storage.storeBoard(implicit)));
        assertEquals(hunters, await(storage.storeBoard(hunters)));

        assertEquals(Optional.of(implicit), await(storage.loadBoard(DAY, BOARD)));
        assertEquals(Optional.of(hunters), await(storage.loadBoard(DAY, HUNTERS)));
    }

    @Test
    void aStoredBoardIsNotReturnedForAnotherDateOrBoard() {
        await(storage.storeBoard(new StoredBoard(DAY, HUNTERS, 1, 10, List.of(COURIER))));

        assertEquals(Optional.empty(), await(storage.loadBoard(DAY, BOARD)));
        assertEquals(Optional.empty(), await(storage.loadBoard(NEXT_DAY, HUNTERS)));
    }

    @Test
    void concurrentStoresOfOneBoardAllReturnThatBoardsWinner() {
        List<CompletableFuture<StoredBoard>> racingForBoard = new ArrayList<>();
        List<CompletableFuture<StoredBoard>> racingForHunters = new ArrayList<>();
        for (int seed = 0; seed < 10; seed++) {
            racingForBoard.add(storage.storeBoard(new StoredBoard(DAY, BOARD, seed, seed, List.of(COURIER))));
            racingForHunters.add(storage.storeBoard(new StoredBoard(DAY, HUNTERS, seed, seed, List.of(PORTER))));
        }

        List<StoredBoard> returnedForBoard = racingForBoard.stream().map(AlinaStorageContractTest::await).toList();
        List<StoredBoard> returnedForHunters = racingForHunters.stream().map(AlinaStorageContractTest::await).toList();
        StoredBoard winner = await(storage.loadBoard(DAY, BOARD)).orElseThrow();
        StoredBoard huntersWinner = await(storage.loadBoard(DAY, HUNTERS)).orElseThrow();

        assertTrue(returnedForBoard.stream().allMatch(winner::equals), "returned " + returnedForBoard);
        assertTrue(returnedForHunters.stream().allMatch(huntersWinner::equals), "returned " + returnedForHunters);
    }

    @Test
    void writingAnAchievementReplacesItsState() {
        await(storage.writeAchievement(ALICE, new AchievementState(FIRST_STEPS, 1, 2, Optional.of(T1), Optional.of(T2)), T1));

        await(storage.writeAchievement(ALICE, new AchievementState(FIRST_STEPS, 2, 5, Optional.of(T3), Optional.empty()), T3));

        assertEquals(new AchievementState(FIRST_STEPS, 2, 5, Optional.of(T3), Optional.empty()), achievement(ALICE, FIRST_STEPS));
    }

    @Test
    void deletingAnAchievementRemovesItsCriterionCountsAndNoOthers() {
        await(storage.writeAchievement(ALICE, new AchievementState(FIRST_STEPS, 0, 0, Optional.empty(), Optional.empty()), T0));
        await(storage.writeAchievement(ALICE, new AchievementState(MINER, 0, 1, Optional.of(T1), Optional.empty()), T1));
        await(storage.writeCriterionProgress(ALICE, Map.of(WALK, 3, JUMP, 1, DIG, 7), T1));

        await(storage.deleteAchievement(ALICE, FIRST_STEPS));

        CharacterState state = await(storage.loadCharacter(ALICE));
        assertEquals(Map.of(MINER, new AchievementState(MINER, 0, 1, Optional.of(T1), Optional.empty())), state.achievements());
        assertEquals(Map.of(DIG, 7), state.criterionProgress());
    }

    @Test
    void resettingAnAchievementsCriteriaKeepsTheAchievement() {
        await(storage.writeAchievement(ALICE, new AchievementState(FIRST_STEPS, 0, 2, Optional.of(T1), Optional.empty()), T1));
        await(storage.writeCriterionProgress(ALICE, Map.of(WALK, 3, DIG, 7), T1));

        await(storage.resetCriterionProgress(ALICE, FIRST_STEPS));

        CharacterState state = await(storage.loadCharacter(ALICE));
        assertEquals(new AchievementState(FIRST_STEPS, 0, 2, Optional.of(T1), Optional.empty()), state.achievements().get(FIRST_STEPS));
        assertEquals(Map.of(DIG, 7), state.criterionProgress());
    }

    @Test
    void criterionCountsUpsertAndACountOfZeroDeletesTheRow() {
        await(storage.writeCriterionProgress(ALICE, Map.of(WALK, 3, JUMP, 1), T1));

        await(storage.writeCriterionProgress(ALICE, Map.of(WALK, 0, JUMP, 5, DIG, 2), T2));

        assertEquals(Map.of(JUMP, 5, DIG, 2), await(storage.loadCharacter(ALICE)).criterionProgress());
    }

    @Test
    void aNegativeCriterionCountRefusesTheWholeWrite() {
        await(storage.writeCriterionProgress(ALICE, Map.of(WALK, 3), T1));
        // Twenty valid counts, a change to WALK, and one negative count, so a partial write shows in nearly every run.
        Map<CriterionKey, Integer> counts = new HashMap<>();
        for (int index = 0; index < 20; index++) {
            counts.put(new CriterionKey(FIRST_STEPS, "bulk_" + index), index + 1);
        }
        counts.put(WALK, 9);
        counts.put(JUMP, -1);

        Throwable failure = failure(storage.writeCriterionProgress(ALICE, counts, T2));

        assertInstanceOf(IllegalArgumentException.class, failure);
        assertEquals(Map.of(WALK, 3), await(storage.loadCharacter(ALICE)).criterionProgress());
    }

    @Test
    void aLoadedCharacterIsASnapshotThatLaterWritesDoNotChange() {
        await(storage.acceptCommission(ALICE, COURIER, 1, T0));
        CharacterState before = await(storage.loadCharacter(ALICE));

        await(storage.acceptCommission(ALICE, SCRIBE, 1, T1));

        assertEquals(Map.of(COURIER, commission(ALICE, COURIER)), before.commissions());
        assertThrows(UnsupportedOperationException.class, () -> before.commissions().clear());
    }

    @Test
    void aClosedStorageFailsItsFuturesWithoutThrowing() throws Exception {
        AlinaStorage closed = createStorage();
        closed.close();

        CompletableFuture<CharacterState> loaded = assertDoesNotThrow(() -> closed.loadCharacter(ALICE));
        assertNotNull(failure(loaded));
    }

    private static Operation turnIn(UUID operationId, UUID character, Identifier commission, int delta, Instant at,
            CommissionStatus status, Optional<Instant> cooldownUntil) {
        return new Operation(operationId, character, OperationKind.TURN_IN, commission, 0, REVISION, WORLD, REWARD,
                delta, new OperationEffect.CommissionSettled(commission, status, cooldownUntil), at);
    }

    private static Operation earned(UUID operationId, Identifier achievement, int tier, Instant availableAt, Instant at) {
        return new Operation(operationId, ALICE, OperationKind.ACHIEVEMENT_REWARD, achievement, tier, REVISION, WORLD,
                REWARD, 0, new OperationEffect.AchievementEarned(achievement, tier, Optional.of(availableAt)), at);
    }

    /** An earn that makes no availability, as a tier short of the last one does. */
    private static Operation earnedWithoutAvailability(UUID operationId, Identifier achievement, int tier, Instant at) {
        return new Operation(operationId, ALICE, OperationKind.ACHIEVEMENT_REWARD, achievement, tier, REVISION, WORLD,
                REWARD, 0, new OperationEffect.AchievementEarned(achievement, tier, Optional.empty()), at);
    }

    private CommissionState commission(UUID character, Identifier commission) {
        return await(storage.loadCharacter(character)).commissions().get(commission);
    }

    private Map<Identifier, CommissionState> commissions(UUID character) {
        return await(storage.loadCharacter(character)).commissions();
    }

    private AchievementState achievement(UUID character, Identifier achievement) {
        return await(storage.loadCharacter(character)).achievements().get(achievement);
    }

    private static List<UUID> ids(List<StoredOperation> listing) {
        return listing.stream().map(stored -> stored.operation().operationId()).toList();
    }

    /** The result of a future, waiting at most {@link #TIMEOUT_SECONDS}. */
    protected static <T> T await(CompletableFuture<T> future) {
        return future.orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
    }

    /**
     * The cause of a future that must fail, waiting at most {@link #TIMEOUT_SECONDS}. A future still pending then
     * fails the test: {@code get} reports the timeout as a {@code TimeoutException}, not as an
     * {@code ExecutionException}.
     */
    private static Throwable failure(CompletableFuture<?> future) {
        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "the future must fail within " + TIMEOUT_SECONDS + " seconds");
        assertNotNull(thrown.getCause());
        return thrown.getCause();
    }
}
