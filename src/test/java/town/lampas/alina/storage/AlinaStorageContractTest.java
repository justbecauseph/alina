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
    private static final LocalDate THIRD_DAY = LocalDate.parse("2026-11-07");
    private static final LocalDate FOURTH_DAY = LocalDate.parse("2026-11-08");
    private static final Identifier COURIER = Identifier.parse("alina:guild/courier");
    private static final Identifier SCRIBE = Identifier.parse("alina:guild/scribe");
    private static final Identifier PORTER = Identifier.parse("alina:guild/porter");
    private static final Identifier BOARD = Identifier.parse("alina:board");
    private static final Identifier HUNTERS = Identifier.parse("alina:guild/hunters");
    private static final Identifier UNLISTED = Identifier.parse("alina:guild/unlisted");
    private static final Identifier FIRST_STEPS = Identifier.parse("alina:achievement/first_steps");
    private static final Identifier MINER = Identifier.parse("alina:achievement/miner");
    private static final Identifier STALL = Identifier.parse("alina:shop/stall");
    private static final Identifier BAZAAR = Identifier.parse("alina:shop/bazaar");
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

    @Test
    void aTradeAppliedOnceAddsItsBudgetAndItsPurchases() {
        Operation trade = trade(OP1, ALICE, STALL, DAY, 30,
                List.of(purchase("scroll", DAY.toString(), 2), purchase("charm", "character", 1)), T1);

        assertEquals(new ApplyResult(true, 0), await(storage.applyOperation(trade)));

        assertEquals(new TradeState(ALICE,
                Map.of(new TradeBudgetKey(STALL, DAY), 30),
                Map.of(new TradePurchaseKey(STALL, "scroll", DAY.toString()), 2,
                        new TradePurchaseKey(STALL, "charm", "character"), 1)),
                await(storage.loadTradeState(ALICE, DAY)));
    }

    @Test
    void aRepeatedTradeChangesNothing() {
        Operation trade = trade(OP1, ALICE, STALL, DAY, 30, List.of(purchase("scroll", DAY.toString(), 2)), T1);
        await(storage.applyOperation(trade));

        assertEquals(new ApplyResult(false, 0), await(storage.applyOperation(trade)));

        TradeState state = await(storage.loadTradeState(ALICE, DAY));
        assertEquals(Map.of(new TradeBudgetKey(STALL, DAY), 30), state.aurPaid());
        assertEquals(Map.of(new TradePurchaseKey(STALL, "scroll", DAY.toString()), 2), state.purchases());
    }

    @Test
    void tradesOnOneDayAddUpPerShopAndPerOffer() {
        await(storage.applyOperation(trade(OP1, ALICE, STALL, DAY, 30, List.of(purchase("scroll", DAY.toString(), 2)), T1)));
        await(storage.applyOperation(trade(OP2, ALICE, STALL, DAY, 12, List.of(purchase("scroll", DAY.toString(), 1)), T2)));
        await(storage.applyOperation(trade(OP3, ALICE, BAZAAR, DAY, 5, List.of(), T3)));

        TradeState state = await(storage.loadTradeState(ALICE, DAY));
        assertEquals(Map.of(new TradeBudgetKey(STALL, DAY), 42, new TradeBudgetKey(BAZAAR, DAY), 5), state.aurPaid());
        assertEquals(Map.of(new TradePurchaseKey(STALL, "scroll", DAY.toString()), 3), state.purchases());
    }

    @Test
    void aTradeThatPaidNoAurAddsNoBudgetRow() {
        await(storage.applyOperation(trade(OP1, ALICE, STALL, DAY, 0, List.of(purchase("charm", "character", 1)), T1)));

        TradeState state = await(storage.loadTradeState(ALICE, DAY));
        assertEquals(Map.of(), state.aurPaid());
        assertEquals(Map.of(new TradePurchaseKey(STALL, "charm", "character"), 1), state.purchases());
    }

    @Test
    void loadingTradesFromADayLeavesOutEarlierBudgetsAndDayPurchasesButKeepsCharacterPurchases() {
        await(storage.applyOperation(trade(OP1, ALICE, STALL, DAY, 30,
                List.of(purchase("scroll", DAY.toString(), 2), purchase("charm", "character", 1)), T1)));
        await(storage.applyOperation(trade(OP2, ALICE, STALL, NEXT_DAY, 8,
                List.of(purchase("scroll", NEXT_DAY.toString(), 1)), T2)));

        assertEquals(new TradeState(ALICE,
                Map.of(new TradeBudgetKey(STALL, NEXT_DAY), 8),
                Map.of(new TradePurchaseKey(STALL, "scroll", NEXT_DAY.toString()), 1,
                        new TradePurchaseKey(STALL, "charm", "character"), 1)),
                await(storage.loadTradeState(ALICE, NEXT_DAY)));
        assertEquals(new TradeState(ALICE,
                Map.of(new TradeBudgetKey(STALL, DAY), 30, new TradeBudgetKey(STALL, NEXT_DAY), 8),
                Map.of(new TradePurchaseKey(STALL, "scroll", DAY.toString()), 2,
                        new TradePurchaseKey(STALL, "scroll", NEXT_DAY.toString()), 1,
                        new TradePurchaseKey(STALL, "charm", "character"), 1)),
                await(storage.loadTradeState(ALICE, DAY)));
    }

    @Test
    void aCharacterWithNoTradesLoadsEmptyMapsAndSeesNoOthersTrades() {
        await(storage.applyOperation(trade(OP1, BOB, STALL, DAY, 30, List.of(purchase("charm", "character", 1)), T1)));

        assertEquals(new TradeState(ALICE, Map.of(), Map.of()), await(storage.loadTradeState(ALICE, DAY)));
        assertEquals(new TradeState(BOB, Map.of(new TradeBudgetKey(STALL, DAY), 30),
                Map.of(new TradePurchaseKey(STALL, "charm", "character"), 1)), await(storage.loadTradeState(BOB, DAY)));
    }

    @Test
    void resettingADaysBudgetClearsOneShopOrAllShopsAndKeepsThePurchases() {
        await(storage.applyOperation(trade(OP1, ALICE, STALL, DAY, 30, List.of(purchase("scroll", DAY.toString(), 2)), T1)));
        await(storage.applyOperation(trade(OP2, ALICE, BAZAAR, DAY, 5, List.of(), T2)));
        await(storage.applyOperation(trade(OP3, ALICE, STALL, NEXT_DAY, 8, List.of(), T3)));
        Map<TradePurchaseKey, Integer> purchases = Map.of(new TradePurchaseKey(STALL, "scroll", DAY.toString()), 2);

        await(storage.resetTradeBudget(ALICE, Optional.of(STALL), DAY));

        assertEquals(Map.of(new TradeBudgetKey(BAZAAR, DAY), 5, new TradeBudgetKey(STALL, NEXT_DAY), 8),
                await(storage.loadTradeState(ALICE, DAY)).aurPaid());
        assertEquals(purchases, await(storage.loadTradeState(ALICE, DAY)).purchases());

        await(storage.resetTradeBudget(ALICE, Optional.empty(), DAY));

        assertEquals(Map.of(new TradeBudgetKey(STALL, NEXT_DAY), 8), await(storage.loadTradeState(ALICE, DAY)).aurPaid());
        assertEquals(purchases, await(storage.loadTradeState(ALICE, DAY)).purchases());
    }

    @Test
    void aTradeThatOverflowsABudgetIsRefusedAndRecordsNothing() {
        await(storage.applyOperation(trade(OP1, ALICE, STALL, DAY, Integer.MAX_VALUE, List.of(), T1)));

        assertNotNull(failure(storage.applyOperation(
                trade(OP2, ALICE, STALL, DAY, 1, List.of(purchase("charm", "character", 1)), T2))));

        assertEquals(Optional.empty(), await(storage.loadOperation(OP2)));
        TradeState state = await(storage.loadTradeState(ALICE, DAY));
        assertEquals(Map.of(new TradeBudgetKey(STALL, DAY), Integer.MAX_VALUE), state.aurPaid());
        assertEquals(Map.of(), state.purchases());
    }

    @Test
    void loadingTheMarketCreatesItFromTheSeedOnce() {
        assertEquals(new MarketState(500000, 10000, DAY), await(storage.loadOrCreateMarket(500000, 10000, DAY, T0)));

        assertEquals(new MarketState(500000, 10000, DAY), await(storage.loadOrCreateMarket(1, 2, NEXT_DAY, T1)));
    }

    @Test
    void theMarketIsEmptyUntilItIsCreated() {
        assertEquals(Optional.empty(), await(storage.loadMarket()));

        await(storage.loadOrCreateMarket(500000, 10000, DAY, T0));

        assertEquals(Optional.of(new MarketState(500000, 10000, DAY)), await(storage.loadMarket()));
        // Creating the row is not a change of the pool, so it records no tick.
        assertEquals(List.of(), await(storage.loadMarketTicks(10)));
    }

    @Test
    void aTradesMarketDeltasAddToTheReservesAndAReplayAddsThemOnce() {
        await(storage.loadOrCreateMarket(500000, 10000, DAY, T0));
        Operation trade = marketTrade(OP1, ALICE, 0, List.of(), 5051, -100, T1);

        assertEquals(new ApplyResult(true, 0), await(storage.applyOperation(trade)));
        assertEquals(new MarketState(505051, 9900, DAY), await(storage.loadMarket()).orElseThrow());
        assertEquals(List.of(new MarketTick(505051, 9900, T1)), await(storage.loadMarketTicks(10)));

        assertEquals(new ApplyResult(false, 0), await(storage.applyOperation(trade)));
        assertEquals(new MarketState(505051, 9900, DAY), await(storage.loadMarket()).orElseThrow());
        assertEquals(List.of(new MarketTick(505051, 9900, T1)), await(storage.loadMarketTicks(10)));
    }

    @Test
    void aTradeWithNoMarketDeltaRecordsNoTick() {
        await(storage.loadOrCreateMarket(500000, 10000, DAY, T0));

        assertEquals(new ApplyResult(true, 0), await(storage.applyOperation(marketTrade(OP1, ALICE, 30, List.of(), 0, 0, T1))));

        assertEquals(List.of(), await(storage.loadMarketTicks(10)));
    }

    @Test
    void twoTradesMarketDeltasReachTheSameReservesInEitherOrder() throws Exception {
        Operation first = marketTrade(OP1, ALICE, 0, List.of(), 5051, -100, T1);
        Operation second = marketTrade(OP2, BOB, 0, List.of(), -2000, 300, T2);
        await(storage.loadOrCreateMarket(500000, 10000, DAY, T0));
        await(storage.applyOperation(first));
        await(storage.applyOperation(second));

        try (AlinaStorage reversed = createStorage()) {
            await(reversed.loadOrCreateMarket(500000, 10000, DAY, T0));
            await(reversed.applyOperation(second));
            await(reversed.applyOperation(first));

            assertEquals(new MarketState(503051, 10200, DAY), await(storage.loadMarket()).orElseThrow());
            assertEquals(await(storage.loadMarket()), await(reversed.loadMarket()));
        }
    }

    @Test
    void theExactSumIsKeptWhenAnOrderPassesThroughReservesBelowOne() throws Exception {
        // P1 buys 10000 Aur, P2 sells 10000 Aur and P3 sells 5000 Aur. Applied as P2, P3, P1, the Aur passes below 1 (to 0,
        // then -5000) before P1 gives it back. The sum is the same in either order.
        Operation buy = marketTrade(OP1, ALICE, 0, List.of(), -60000, 10000, T1);
        Operation firstSale = marketTrade(OP2, BOB, 0, List.of(), 40000, -10000, T2);
        Operation secondSale = marketTrade(OP3, ALICE, 0, List.of(), 20000, -5000, T3);
        await(storage.loadOrCreateMarket(500000, 10000, DAY, T0));

        await(storage.applyOperation(firstSale));
        assertEquals(new MarketState(540000, 0, DAY), await(storage.loadMarket()).orElseThrow());
        await(storage.applyOperation(secondSale));
        assertEquals(new MarketState(560000, -5000, DAY), await(storage.loadMarket()).orElseThrow());
        await(storage.applyOperation(buy));
        assertEquals(new MarketState(500000, 5000, DAY), await(storage.loadMarket()).orElseThrow());

        try (AlinaStorage reversed = createStorage()) {
            await(reversed.loadOrCreateMarket(500000, 10000, DAY, T0));
            await(reversed.applyOperation(buy));
            await(reversed.applyOperation(firstSale));
            await(reversed.applyOperation(secondSale));

            assertEquals(new MarketState(500000, 5000, DAY), await(reversed.loadMarket()).orElseThrow());
            assertEquals(await(storage.loadMarket()), await(reversed.loadMarket()));
        }
    }

    @Test
    void theSeedOfANewMarketIsValidatedWhenItIsCreated() {
        assertInstanceOf(IllegalArgumentException.class, failure(storage.loadOrCreateMarket(0, 10000, DAY, T0)));
        assertInstanceOf(IllegalArgumentException.class, failure(storage.loadOrCreateMarket(500000, -1, DAY, T0)));

        assertEquals(Optional.empty(), await(storage.loadMarket()));
    }

    @Test
    void aTradeWithNoMarketAppliesItsOtherEffectsAndCreatesNoMarket() {
        Operation trade = marketTrade(OP1, ALICE, 30, List.of(purchase("charm", "character", 1)), 5051, -100, T1);

        assertEquals(new ApplyResult(true, 0), await(storage.applyOperation(trade)));

        assertEquals(Map.of(new TradeBudgetKey(STALL, DAY), 30), await(storage.loadTradeState(ALICE, DAY)).aurPaid());
        assertEquals(Optional.empty(), await(storage.loadMarket()));
        assertEquals(List.of(), await(storage.loadMarketTicks(10)));
        // The deltas were dropped, so a market created later keeps its seed.
        assertEquals(new MarketState(500000, 10000, DAY), await(storage.loadOrCreateMarket(500000, 10000, DAY, T2)));
    }

    @Test
    void aRestockAppliesOncePerDayAndRecordsTheReservesAfterIt() {
        await(storage.loadOrCreateMarket(500000, 10000, DAY, T0));

        assertTrue(await(storage.restockMarket(NEXT_DAY, 20000, 500, T1)));
        assertEquals(new MarketState(520000, 10500, NEXT_DAY), await(storage.loadMarket()).orElseThrow());
        assertEquals(List.of(new MarketTick(520000, 10500, T1)), await(storage.loadMarketTicks(10)));

        assertFalse(await(storage.restockMarket(NEXT_DAY, 20000, 500, T2)));
        assertEquals(new MarketState(520000, 10500, NEXT_DAY), await(storage.loadMarket()).orElseThrow());
        assertEquals(List.of(new MarketTick(520000, 10500, T1)), await(storage.loadMarketTicks(10)));
    }

    @Test
    void aRestockWithNoMarketReturnsFalseAndRecordsNothing() {
        assertFalse(await(storage.restockMarket(NEXT_DAY, 20000, 500, T1)));

        assertEquals(Optional.empty(), await(storage.loadMarket()));
        assertEquals(List.of(), await(storage.loadMarketTicks(10)));
    }

    @Test
    void loadingMarketTicksReturnsTheNewestFirstUpToTheLimit() {
        await(storage.loadOrCreateMarket(500000, 10000, DAY, T0));
        assertTrue(await(storage.restockMarket(NEXT_DAY, 1, 0, T1)));
        assertTrue(await(storage.restockMarket(THIRD_DAY, 1, 0, T2)));
        assertTrue(await(storage.restockMarket(FOURTH_DAY, 1, 0, T3)));

        assertEquals(List.of(new MarketTick(500003, 10000, T3), new MarketTick(500002, 10000, T2),
                new MarketTick(500001, 10000, T1)), await(storage.loadMarketTicks(10)));
        assertEquals(List.of(new MarketTick(500003, 10000, T3), new MarketTick(500002, 10000, T2)),
                await(storage.loadMarketTicks(2)));
        assertInstanceOf(IllegalArgumentException.class, failure(storage.loadMarketTicks(0)));
    }

    @Test
    void theTicksKeepTheNewest64ChangesNewestFirst() {
        await(storage.loadOrCreateMarket(500000, 10000, DAY, T0));
        for (int change = 1; change <= 70; change++) {
            assertTrue(await(storage.adjustMarket(1, 0, T0.plusSeconds(change))));
        }

        List<MarketTick> ticks = await(storage.loadMarketTicks(100));
        assertEquals(64, ticks.size());
        // The 70th change is the newest; the 64 kept are changes 7 to 70, so the oldest kept holds 500000 + 7.
        assertEquals(new MarketTick(500070, 10000, T0.plusSeconds(70)), ticks.get(0));
        assertEquals(new MarketTick(500007, 10000, T0.plusSeconds(7)), ticks.get(63));
        assertEquals(64, await(storage.loadMarketTicks(64)).size());
    }

    @Test
    void adjustingTheMarketAddsToBothReservesKeepsTheRestockedDayAndRecordsATick() {
        await(storage.loadOrCreateMarket(500000, 10000, DAY, T0));

        assertTrue(await(storage.adjustMarket(100000, -2500, T1)));

        assertEquals(Optional.of(new MarketState(600000, 7500, DAY)), await(storage.loadMarket()));
        assertEquals(List.of(new MarketTick(600000, 7500, T1)), await(storage.loadMarketTicks(10)));
    }

    @Test
    void adjustingAMarketWithNoRowReturnsFalseAndRecordsNothing() {
        assertFalse(await(storage.adjustMarket(100000, -2500, T1)));

        assertEquals(Optional.empty(), await(storage.loadMarket()));
        assertEquals(List.of(), await(storage.loadMarketTicks(10)));
    }

    @Test
    void loadMarketTicksSinceReturnsTicksAfterIdAscendingAndRespectsLimit() {
        await(storage.loadOrCreateMarket(500000, 10000, DAY, T0));
        await(storage.adjustMarket(1000, 0, T1));
        await(storage.adjustMarket(2000, 0, T2));
        await(storage.adjustMarket(3000, 0, T3));

        List<IndexedMarketTick> all = await(storage.loadMarketTicksSince(0, 10));
        assertEquals(3, all.size());
        assertTrue(all.get(0).id() < all.get(1).id() && all.get(1).id() < all.get(2).id());
        assertEquals(501000L, all.get(0).points());
        assertEquals(503000L, all.get(1).points());
        assertEquals(506000L, all.get(2).points());
        assertEquals(T1, all.get(0).at());

        List<IndexedMarketTick> firstTwo = await(storage.loadMarketTicksSince(0, 2));
        assertEquals(all.subList(0, 2), firstTwo);

        List<IndexedMarketTick> tail = await(storage.loadMarketTicksSince(all.get(0).id(), 10));
        assertEquals(all.subList(1, 3), tail);

        assertEquals(List.of(), await(storage.loadMarketTicksSince(all.get(2).id(), 10)));
        assertInstanceOf(IllegalArgumentException.class, failure(storage.loadMarketTicksSince(-1, 10)));
        assertInstanceOf(IllegalArgumentException.class, failure(storage.loadMarketTicksSince(0, 0)));
    }

    @Test
    void loadTradeSummaryComputesDailyShopItemOfferConcentrationAndGlobalHealth() {
        UUID charlie = UUID.fromString("55555555-5555-4555-8555-555555555555");
        UUID op5 = UUID.fromString("55555555-0000-4555-8555-555555555555");
        UUID op6 = UUID.fromString("66666666-0000-4666-8666-666666666666");
        UUID op7 = UUID.fromString("77777777-0000-4777-8777-777777777777");
        Identifier thirdShop = Identifier.parse("alina:shop/idle");

        // Non-trade ops are ignored by summary and trade health.
        await(storage.applyOperation(turnIn(op7, ALICE, COURIER, 2, T0, CommissionStatus.COOLDOWN, Optional.of(T6))));

        // Outside window [NEXT_DAY, THIRD_DAY]: a RECONCILE trade on DAY still counts in global health.reconcileCount,
        // and is excluded from daily/shops/concentration.
        await(storage.applyOperation(tradeWithDetail(OP1, ALICE, STALL, DAY, 999,
                "{\"sold\":[{\"item\":\"minecraft:dirt\",\"count\":10,\"aur\":999}],\"bought\":[],\"experience_sold\":0,\"experience_bought\":0,\"aur_spent\":0}",
                T0, 1L)));
        await(storage.markOperation(OP1, OperationStep.RECONCILE, Optional.of("mismatch"), T1));

        // NEXT_DAY (rev 2):
        // OP2: ALICE sells junk (two stacks of rotten_flesh + legacy entry without aur) at STALL for 90 Aur (budget 100 -> 90% near cap).
        await(storage.applyOperation(tradeWithDetail(OP2, ALICE, STALL, NEXT_DAY, 90,
                "{\"sold\":[{\"item\":\"minecraft:rotten_flesh\",\"count\":4,\"aur\":40},{\"item\":\"minecraft:rotten_flesh\",\"count\":5,\"aur\":50},{\"item\":\"minecraft:stick\",\"count\":1}],\"bought\":[],\"experience_sold\":0,\"experience_bought\":0,\"aur_spent\":0}",
                T1, 2L)));
        await(storage.markOperation(OP2, OperationStep.SETTLED, Optional.empty(), T2));

        // OP3: BOB sells XP at STALL for 100 Aur (budget 100 -> at cap).
        await(storage.applyOperation(tradeWithDetail(OP3, BOB, STALL, NEXT_DAY, 100,
                "{\"sold\":[],\"bought\":[],\"experience_sold\":500,\"experience_bought\":0,\"aur_spent\":0}",
                T2, 2L)));
        await(storage.markOperation(OP3, OperationStep.SETTLED, Optional.empty(), T3));

        // THIRD_DAY (rev 3):
        // OP4: ALICE buys offer "scroll" (20 Aur) at BAZAAR, left at RECORDED.
        await(storage.applyOperation(tradeWithDetail(OP4, ALICE, BAZAAR, THIRD_DAY, 0,
                "{\"sold\":[],\"bought\":[{\"offer\":\"scroll\",\"item\":\"minecraft:paper\",\"count\":2,\"aur\":20}],\"experience_sold\":0,\"experience_bought\":0,\"aur_spent\":20}",
                T3, 3L)));
        // OP5: CHARLIE buys XP at BAZAAR for 30 Aur, settled.
        await(storage.applyOperation(tradeWithDetail(op5, charlie, BAZAAR, THIRD_DAY, 0,
                "{\"sold\":[],\"bought\":[],\"experience_sold\":0,\"experience_bought\":150,\"aur_spent\":30}",
                T4, 3L)));
        await(storage.markOperation(op5, OperationStep.SETTLED, Optional.empty(), T5));
        // OP6: CHARLIE sells items at STALL on THIRD_DAY for 50 Aur (50% budget used), settled.
        await(storage.applyOperation(tradeWithDetail(op6, charlie, STALL, THIRD_DAY, 50,
                "{\"sold\":[{\"item\":\"minecraft:bone\",\"count\":10,\"aur\":50}],\"bought\":[],\"experience_sold\":0,\"experience_bought\":0,\"aur_spent\":0}",
                T5, 3L)));
        await(storage.markOperation(op6, OperationStep.SETTLED, Optional.empty(), T6));

        Map<Identifier, Integer> budgets = Map.of(STALL, 100, BAZAAR, 0, thirdShop, 50);
        TradeSummary summary = await(storage.loadTradeSummary(NEXT_DAY, THIRD_DAY, budgets, 2));

        assertEquals(List.of(
                new TradeSummary.DailyTotals(NEXT_DAY, 2, 2, 90, 100, 0, 0, 500, 0, List.of(2L)),
                new TradeSummary.DailyTotals(THIRD_DAY, 3, 2, 50, 0, 20, 30, 0, 150, List.of(3L))),
                summary.daily());

        // STALL: minted 240, sunk 0 -> total 240. Budget rows: (ALICE, NEXT_DAY)=90, (BOB, NEXT_DAY)=100, (CHARLIE, THIRD_DAY)=50.
        // bps: 9000, 10000, 5000 -> avg 8000. nearCap=2 (90,100), atCap=1 (100), max=100.
        // BAZAAR: minted 0, sunk 50 -> total 50.
        // thirdShop: minted 0, sunk 0.
        assertEquals(List.of(
                new TradeSummary.ShopTotals(STALL, 100, 3, 3, 240, 0, 500, 0, 3, 2, 1, 8000, 100),
                new TradeSummary.ShopTotals(BAZAAR, 0, 2, 2, 0, 50, 0, 150, 0, 0, 0, 0, 0),
                new TradeSummary.ShopTotals(thirdShop, 50, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
                summary.shops());

        // Top 2 sold items: rotten_flesh (90 aur, count 9, 1 distinct trade), bone (50 aur, count 10, 1 trade).
        assertEquals(List.of(
                new TradeSummary.ItemFlow("minecraft:rotten_flesh", 9, 90, 1),
                new TradeSummary.ItemFlow("minecraft:bone", 10, 50, 1)),
                summary.topSoldItems());

        assertEquals(List.of(
                new TradeSummary.OfferFlow(BAZAAR, Optional.of("scroll"), "minecraft:paper", 2, 20, 1)),
                summary.topBoughtOffers());

        // Earners: BOB=100, ALICE=90, CHARLIE=50. Total=240, unique=3, top5=240, top10%=ceil(0.3)=1 -> 100.
        assertEquals(new TradeSummary.Concentration(240, 3, 240, 100), summary.concentration());

        // Global trade health across all dates: OP4 is RECORDED (1), OP1 is RECONCILE (1); op7 is TURN_IN (ignored).
        assertEquals(new TradeSummary.OperationHealth(1, 1), summary.health());

        assertInstanceOf(IllegalArgumentException.class,
                failure(storage.loadTradeSummary(THIRD_DAY, NEXT_DAY, budgets, 10)));
        assertInstanceOf(IllegalArgumentException.class,
                failure(storage.loadTradeSummary(NEXT_DAY, THIRD_DAY, budgets, 0)));
    }

    @Test
    void listRecentTradeOperationsFiltersByShopPlayerStepsAndPaginatesByCursor() {
        // Non-trade op must not appear.
        await(storage.applyOperation(turnIn(OP4, ALICE, COURIER, 1, T4, CommissionStatus.COOLDOWN, Optional.of(T6))));

        // Tie-break at T2: OP3 > OP2 by UUID string DESC.
        await(storage.applyOperation(trade(OP1, ALICE, STALL, DAY, 10, List.of(), T1)));
        await(storage.applyOperation(trade(OP2, ALICE, STALL, DAY, 20, List.of(), T2)));
        await(storage.applyOperation(trade(OP3, BOB, BAZAAR, DAY, 30, List.of(), T2)));
        await(storage.markOperation(OP1, OperationStep.SETTLED, Optional.empty(), T5));
        await(storage.markOperation(OP2, OperationStep.RECONCILE, Optional.of("check"), T5));

        List<StoredOperation> page1 = await(storage.listRecentTradeOperations(
                Optional.empty(), Optional.empty(), Set.of(OperationStep.RECORDED, OperationStep.SETTLED, OperationStep.RECONCILE),
                Optional.empty(), 2));
        assertEquals(List.of(OP3, OP2), ids(page1));

        Operation last1 = page1.get(1).operation();
        List<StoredOperation> page2 = await(storage.listRecentTradeOperations(
                Optional.empty(), Optional.empty(), Set.of(OperationStep.RECORDED, OperationStep.SETTLED, OperationStep.RECONCILE),
                Optional.of(new OperationCursor(last1.createdAt(), last1.operationId())), 2));
        assertEquals(List.of(OP1), ids(page2));

        // Cursor before OP3 (at T2) returns OP2 then OP1.
        assertEquals(List.of(OP2, OP1), ids(await(storage.listRecentTradeOperations(
                Optional.empty(), Optional.empty(), Set.of(OperationStep.RECORDED, OperationStep.SETTLED, OperationStep.RECONCILE),
                Optional.of(new OperationCursor(T2, OP3)), 10))));

        assertEquals(List.of(OP2, OP1), ids(await(storage.listRecentTradeOperations(
                Optional.of(STALL), Optional.of(ALICE),
                Set.of(OperationStep.SETTLED, OperationStep.RECONCILE), Optional.empty(), 10))));

        assertEquals(List.of(), await(storage.listRecentTradeOperations(
                Optional.empty(), Optional.empty(), Set.of(), Optional.empty(), 10)));
        assertInstanceOf(IllegalArgumentException.class, failure(storage.listRecentTradeOperations(
                Optional.empty(), Optional.empty(), Set.of(OperationStep.RECORDED), Optional.empty(), 0)));
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

    private static Operation trade(UUID operationId, UUID character, Identifier shop, LocalDate day, int aurPaid,
            List<OperationEffect.TradeSettled.Purchase> purchases, Instant at) {
        return new Operation(operationId, character, OperationKind.TRADE, shop, 0, REVISION, WORLD, REWARD, 0,
                new OperationEffect.TradeSettled(shop, day, aurPaid, purchases, "{}"), at);
    }

    private static Operation tradeWithDetail(UUID operationId, UUID character, Identifier shop, LocalDate day, int aurPaid,
            String detailJson, Instant at, long revisionId) {
        return new Operation(operationId, character, OperationKind.TRADE, shop, 0, revisionId, WORLD, REWARD, 0,
                new OperationEffect.TradeSettled(shop, day, aurPaid, List.of(), detailJson), at);
    }

    private static OperationEffect.TradeSettled.Purchase purchase(String offer, String period, int count) {
        return new OperationEffect.TradeSettled.Purchase(offer, period, count);
    }

    /** A trade at STALL on DAY that moves the experience market by the deltas, and pays and buys as given. */
    private static Operation marketTrade(UUID operationId, UUID character, int aurPaid,
            List<OperationEffect.TradeSettled.Purchase> purchases, long marketPoints, long marketAur, Instant at) {
        return new Operation(operationId, character, OperationKind.TRADE, STALL, 0, REVISION, WORLD, REWARD, 0,
                new OperationEffect.TradeSettled(STALL, DAY, aurPaid, purchases, "{}", marketPoints, marketAur), at);
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
