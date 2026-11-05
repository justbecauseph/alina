package town.lampas.alina.gametest;

import com.mojang.serialization.DataResult;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;
import town.lampas.alina.Alina;
import town.lampas.alina.achievement.AchievementService;
import town.lampas.alina.achievement.AchievementStanding;
import town.lampas.alina.achievement.AchievementView;
import town.lampas.alina.criteria.AlinaTriggers;
import town.lampas.alina.settlement.SettlementRequest;
import town.lampas.alina.storage.AchievementState;
import town.lampas.alina.storage.AlinaStorage;
import town.lampas.alina.storage.ApplyResult;
import town.lampas.alina.storage.CharacterState;
import town.lampas.alina.storage.CriterionKey;
import town.lampas.alina.storage.OperationEffect;
import town.lampas.alina.storage.OperationKind;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;

/**
 * Server GameTests owned by the achievement service (ALINA-PLAN §9.2 and §9.3). Each test builds an {@link AchievementRig}:
 * a service over its own in-memory storage and settlement, a clock the test moves, and players outside the server's list.
 * The test ticks the rig once per game tick. Storage and settlement complete on storage threads and return to the server
 * thread on a later tick, so a test waits with {@code succeedWhen}: a check that is not met yet throws and is run again.
 *
 * <p>A test with several steps moves on with {@link #advance}, which fails the attempt, so each step runs on its own tick
 * and only the last step returns normally. Each step checks what it waits for before it acts, so an action runs once.
 */
@SuppressWarnings("removal")
public final class AchievementGameTests {
    private static final Identifier SIGNED_ON = Alina.id("test/signed_on");
    private static final Identifier QUIET_NOTE = Alina.id("test/quiet_note");
    private static final Identifier LOUD_NOTE = Alina.id("test/loud_note");
    private static final Identifier RULE_NOTE = Alina.id("test/rule_note");
    private static final Identifier TALLY = Alina.id("test/tally");
    private static final Identifier BOTH = Alina.id("test/both");
    private static final Identifier EITHER = Alina.id("test/either");
    private static final Identifier CLIMBER = Alina.id("test/climber");
    private static final Identifier DAILY = Alina.id("test/daily");
    private static final Identifier LATE_SEEN = Alina.id("test/late_seen");
    private static final Identifier META = Alina.id("test/meta");
    private static final Identifier RETRIED = Alina.id("test/retried");
    private static final Identifier FLUSH = Alina.id("test/flush");
    private static final Identifier BLOCKER = Alina.id("test/blocker");
    private static final String BUNDLE = "bundle.json";
    private static final String BUNDLE_NEXT = "bundle_next.json";
    /** The daily achievement's cooldown: PT1H in the fixture. */
    private static final Duration DAILY_COOLDOWN = Duration.ofHours(1);
    /** Ticks the held reward waits before its operation is released: more than one retry interval. */
    private static final long HELD_TICKS = 150;

    // Earns and counting.

    @GameTest(maxTicks = 600)
    public void aCountOneEventEarnsAndItsRewardIsDeliveredOnce(GameTestHelper helper) {
        AchievementRig rig = AchievementRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record has not loaded");
            switch (step.get()) {
                case 0 -> {
                    AlinaTriggers.MANUAL.trigger(player, "signed");
                    advance(helper, step, 1);
                }
                default -> {
                    helper.assertValueEqual(earnedTiers(rig, player, SIGNED_ON), 1, "signed_on in the record");
                    helper.assertTrue(rig.settlement().pendingFor(player).isEmpty(), "the reward is still in the escrow");
                    helper.assertValueEqual(count(player, Items.EMERALD), 5, "the reward, five emeralds");
                    helper.assertValueEqual(stored(rig, player).reputation(), 2, "reputation in storage, applied once");
                    helper.assertValueEqual(storedState(rig, player, SIGNED_ON).map(AchievementState::earnCount).orElse(0),
                            1, "the earn count in storage");
                }
            }
        });
    }

    @GameTest(maxTicks = 400)
    public void aNoRewardEarnIsWrittenThrough(GameTestHelper helper) {
        AchievementRig rig = AchievementRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record has not loaded");
            switch (step.get()) {
                case 0 -> {
                    AlinaTriggers.MANUAL.trigger(player, "note");
                    advance(helper, step, 1);
                }
                default -> {
                    helper.assertValueEqual(earnedTiers(rig, player, QUIET_NOTE), 1, "quiet_note in the record");
                    helper.assertValueEqual(storedState(rig, player, QUIET_NOTE).map(AchievementState::earnCount).orElse(0),
                            1, "the earn count written to storage");
                    helper.assertValueEqual(storedCount(rig, player, QUIET_NOTE, "note"), 1, "the count written to storage");
                    helper.assertTrue(rig.settlement().pendingFor(player).isEmpty(), "a reward went to the escrow");
                }
            }
        });
    }

    @GameTest(maxTicks = 400)
    public void aCountedCriterionOfThreeEarnsOnTheThird(GameTestHelper helper) {
        AchievementRig rig = AchievementRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record has not loaded");
            switch (step.get()) {
                case 0 -> {
                    AlinaTriggers.MANUAL.trigger(player, "tally");
                    AlinaTriggers.MANUAL.trigger(player, "tally");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertValueEqual(viewCount(rig, player, TALLY, "tally"), 2, "the count after two events");
                    helper.assertValueEqual(earnedTiers(rig, player, TALLY), 0, "earned after two events of three");
                    AlinaTriggers.MANUAL.trigger(player, "tally");
                    advance(helper, step, 2);
                }
                default -> {
                    helper.assertValueEqual(earnedTiers(rig, player, TALLY), 1, "tally after three events");
                    helper.assertValueEqual(storedCount(rig, player, TALLY, "tally"), 3, "the count in storage");
                }
            }
        });
    }

    /** An event before the record has loaded is not counted: no watch is open yet, so only the events after load count. */
    @GameTest(maxTicks = 400)
    public void anEventBeforeTheRecordLoadsDoesNotCount(GameTestHelper helper) {
        AchievementRig rig = AchievementRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        AlinaTriggers.MANUAL.trigger(player, "tally");
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record has not loaded");
            switch (step.get()) {
                case 0 -> {
                    helper.assertValueEqual(viewCount(rig, player, TALLY, "tally"), 0,
                            "the event before the load was counted");
                    AlinaTriggers.MANUAL.trigger(player, "tally");
                    AlinaTriggers.MANUAL.trigger(player, "tally");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertValueEqual(viewCount(rig, player, TALLY, "tally"), 2, "the count after two events");
                    helper.assertValueEqual(earnedTiers(rig, player, TALLY), 0,
                            "earned, so the event before the load counted");
                    AlinaTriggers.MANUAL.trigger(player, "tally");
                    advance(helper, step, 2);
                }
                default -> helper.assertValueEqual(earnedTiers(rig, player, TALLY), 1, "tally after three counted events");
            }
        });
    }

    @GameTest(maxTicks = 400)
    public void anAndRequirementNeedsEveryCriterion(GameTestHelper helper) {
        AchievementRig rig = AchievementRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record has not loaded");
            switch (step.get()) {
                case 0 -> {
                    AlinaTriggers.MANUAL.trigger(player, "both_a");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, BOTH), 0, "earned with one half of the AND");
                    AlinaTriggers.MANUAL.trigger(player, "both_b");
                    advance(helper, step, 2);
                }
                default -> helper.assertValueEqual(earnedTiers(rig, player, BOTH), 1, "both after both halves");
            }
        });
    }

    @GameTest(maxTicks = 400)
    public void anOrRequirementNeedsOneOfItsCriteria(GameTestHelper helper) {
        AchievementRig rig = AchievementRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record has not loaded");
            switch (step.get()) {
                case 0 -> {
                    AlinaTriggers.MANUAL.trigger(player, "either_d");
                    advance(helper, step, 1);
                }
                default -> helper.assertValueEqual(earnedTiers(rig, player, EITHER), 1, "either after one of the halves");
            }
        });
    }

    // Tiers, repeats and meta-achievements.

    @GameTest(maxTicks = 600)
    public void aTieredAchievementEarnsEachTierInOrderWithItsTierReward(GameTestHelper helper) {
        AchievementRig rig = AchievementRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record has not loaded");
            switch (step.get()) {
                case 0 -> {
                    AlinaTriggers.MANUAL.trigger(player, "climb");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, CLIMBER), 1, "the first tier after one event");
                    AlinaTriggers.MANUAL.trigger(player, "climb");
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, CLIMBER), 2, "the second tier after two events");
                    helper.assertTrue(rig.settlement().pendingFor(player).isEmpty(), "the tier reward is still in the escrow");
                    helper.assertValueEqual(count(player, Items.DIAMOND), 1, "the diamond of the tier 2 reward");
                    helper.assertValueEqual(count(player, Items.EMERALD), 1, "the emerald of the tier 2 reward");
                    AlinaTriggers.MANUAL.trigger(player, "climb");
                    advance(helper, step, 3);
                }
                default -> {
                    helper.assertValueEqual(earnedTiers(rig, player, CLIMBER), 3, "the third tier after three events");
                    helper.assertValueEqual(storedState(rig, player, CLIMBER).map(AchievementState::tier).orElse(-1), 3,
                            "the stored tier");
                }
            }
        });
    }

    /**
     * A repeatable is earned, stays earned through its cooldown with no count, starts a new cycle once the cooldown has
     * passed, and is earned again with an earn count of 2.
     */
    @GameTest(maxTicks = 900)
    public void aRepeatableCoolsDownStartsANewCycleAndIsEarnedAgain(GameTestHelper helper) {
        AchievementRig rig = AchievementRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record has not loaded");
            switch (step.get()) {
                case 0 -> {
                    AlinaTriggers.MANUAL.trigger(player, "daily");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, DAILY), 1, "daily after the event");
                    helper.assertValueEqual(earnCountOf(rig, player, DAILY), 1, "the earn count after the event");
                    helper.assertTrue(availableAtOf(rig, player, DAILY).equals(Optional.of(rig.clock().instant()
                            .plus(DAILY_COOLDOWN))), "the cooldown does not end an hour after the earn");
                    AlinaTriggers.MANUAL.trigger(player, "daily");
                    rig.clock().advance(DAILY_COOLDOWN.minusMinutes(1));
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, DAILY), 1, "daily during the cooldown");
                    helper.assertValueEqual(viewCount(rig, player, DAILY, "daily"), 1,
                            "the count during the cooldown: an event in the cooldown counts nothing");
                    rig.clock().advance(Duration.ofMinutes(1));
                    advance(helper, step, 3);
                }
                case 3 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, DAILY), 0, "daily after the cooldown");
                    helper.assertValueEqual(viewCount(rig, player, DAILY, "daily"), 0, "the count of the new cycle");
                    helper.assertValueEqual(earnCountOf(rig, player, DAILY), 1, "the earn count kept by the new cycle");
                    AlinaTriggers.MANUAL.trigger(player, "daily");
                    advance(helper, step, 4);
                }
                default -> {
                    helper.assertValueEqual(earnedTiers(rig, player, DAILY), 1, "daily in the second cycle");
                    helper.assertValueEqual(earnCountOf(rig, player, DAILY), 2, "the earn count after the second earn");
                }
            }
        });
    }

    /** An achievement whose counts are met but which storage does not hold as earned is earned when its record loads. */
    @GameTest(maxTicks = 400)
    public void countsMetButNotEarnedInStorageAreEarnedAtLoad(GameTestHelper helper) {
        AchievementRig rig = AchievementRig.create(helper, BUNDLE);
        ServerPlayer player = rig.newPlayer();
        CompletableFuture<Void> seeded = rig.storage().writeCriterionProgress(player.getUUID(),
                Map.of(new CriterionKey(LATE_SEEN, "seen"), 2), rig.clock().instant());
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(seeded.isDone(), "the counts are not in storage yet");
                    rig.service().join(player);
                    advance(helper, step, 1);
                }
                default -> {
                    helper.assertTrue(loaded(rig, player), "the record has not loaded");
                    helper.assertValueEqual(earnedTiers(rig, player, LATE_SEEN), 1, "late_seen at load");
                    helper.assertValueEqual(storedState(rig, player, LATE_SEEN).map(AchievementState::earnCount).orElse(0),
                            1, "the earn written to storage");
                }
            }
        });
    }

    @GameTest(maxTicks = 400)
    public void aMetaAchievementEarnsWhenAnotherAchievementIsEarned(GameTestHelper helper) {
        AchievementRig rig = AchievementRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record has not loaded");
            switch (step.get()) {
                case 0 -> {
                    AlinaTriggers.MANUAL.trigger(player, "signed");
                    advance(helper, step, 1);
                }
                default -> {
                    helper.assertValueEqual(earnedTiers(rig, player, SIGNED_ON), 1, "signed_on");
                    helper.assertValueEqual(earnedTiers(rig, player, META), 1,
                            "meta, which counts the alina:achievement_earned of signed_on");
                }
            }
        });
    }

    // Announcements.

    /**
     * An earn announces in chat when the achievement's announce is set and the announce-advancements rule is on, and does not
     * when either is off.
     */
    @GameTest(maxTicks = 400)
    public void theAnnouncementFollowsTheAchievementAndTheGameRule(GameTestHelper helper) {
        AchievementRig rig = AchievementRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        GameRules rules = helper.getLevel().getGameRules();
        boolean original = rules.get(GameRules.SHOW_ADVANCEMENT_MESSAGES);
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record has not loaded");
            switch (step.get()) {
                case 0 -> {
                    AlinaTriggers.MANUAL.trigger(player, "loud");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertTrue(rig.messages(player).stream().anyMatch(text -> text.contains("Loud Note")),
                            "no announcement of Loud Note: " + rig.messages(player));
                    AlinaTriggers.MANUAL.trigger(player, "note");
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, QUIET_NOTE), 1, "quiet_note after its event");
                    helper.assertTrue(rig.messages(player).stream().noneMatch(text -> text.contains("Quiet Note")),
                            "an announcement of Quiet Note, whose announce is off");
                    rules.set(GameRules.SHOW_ADVANCEMENT_MESSAGES, false, helper.getLevel().getServer());
                    AlinaTriggers.MANUAL.trigger(player, "rule");
                    advance(helper, step, 3);
                }
                default -> {
                    helper.assertValueEqual(earnedTiers(rig, player, RULE_NOTE), 1, "rule_note with the rule off");
                    helper.assertTrue(rig.messages(player).stream().noneMatch(text -> text.contains("Rule Note")),
                            "an announcement with the rule off");
                    rules.set(GameRules.SHOW_ADVANCEMENT_MESSAGES, original, helper.getLevel().getServer());
                }
            }
        });
    }

    // Counts in storage, leave and join, and content activation.

    /** Counts reach storage behind the flush, and survive a leave and a join. */
    @GameTest(maxTicks = 900)
    public void countsReachStorageAfterTheFlushAndSurviveLeaveAndJoin(GameTestHelper helper) {
        AchievementRig rig = AchievementRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record has not loaded");
            switch (step.get()) {
                case 0 -> {
                    AlinaTriggers.MANUAL.trigger(player, "step");
                    AlinaTriggers.MANUAL.trigger(player, "step");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertValueEqual(viewCount(rig, player, FLUSH, "step"), 2, "the count in the record");
                    helper.assertValueEqual(storedCount(rig, player, FLUSH, "step"), 2, "the count in storage after the flush");
                    rig.service().leave(player);
                    rig.service().join(player);
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertValueEqual(viewCount(rig, player, FLUSH, "step"), 2, "the count after a rejoin");
                    AlinaTriggers.MANUAL.trigger(player, "step");
                    AlinaTriggers.MANUAL.trigger(player, "step");
                    AlinaTriggers.MANUAL.trigger(player, "step");
                    advance(helper, step, 3);
                }
                default -> helper.assertValueEqual(earnedTiers(rig, player, FLUSH), 1, "flush after five steps");
            }
        });
    }

    /** Activating content keeps the counts, which are keyed by criterion name. */
    @GameTest(maxTicks = 600)
    public void contentActivationKeepsCountsByCriterionName(GameTestHelper helper) {
        AchievementRig rig = AchievementRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record has not loaded");
            switch (step.get()) {
                case 0 -> {
                    AlinaTriggers.MANUAL.trigger(player, "step");
                    AlinaTriggers.MANUAL.trigger(player, "step");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    rig.useContent(BUNDLE_NEXT);
                    rig.service().contentActivated();
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertValueEqual(viewCount(rig, player, FLUSH, "step"), 2, "the count after activation");
                    helper.assertValueEqual(earnedTiers(rig, player, FLUSH), 0, "earned before the new target");
                    AlinaTriggers.MANUAL.trigger(player, "step");
                    advance(helper, step, 3);
                }
                case 3 -> {
                    helper.assertValueEqual(viewCount(rig, player, FLUSH, "step"), 3, "the count after one more step");
                    helper.assertValueEqual(earnedTiers(rig, player, FLUSH), 0, "earned at 3 of the new target of 4");
                    AlinaTriggers.MANUAL.trigger(player, "step");
                    advance(helper, step, 4);
                }
                default -> helper.assertValueEqual(earnedTiers(rig, player, FLUSH), 1, "flush at the new target");
            }
        });
    }

    // Staff controls.

    /** Staff grant and revoke, without a tier and with one, through the same earn path and the same storage writes. */
    @GameTest(maxTicks = 1200)
    public void staffGrantAndRevokeWorkWithAndWithoutATier(GameTestHelper helper) {
        AchievementRig rig = AchievementRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record has not loaded");
            AchievementService service = rig.service();
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(outcome(service.grant(player, SIGNED_ON, OptionalInt.empty())).isEmpty(),
                            "the grant of signed_on was refused");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, SIGNED_ON), 1, "signed_on after the grant");
                    helper.assertValueEqual(count(player, Items.EMERALD), 5, "the reward of the grant");
                    helper.assertTrue(rig.settlement().pendingFor(player).isEmpty(), "the reward is still in the escrow");
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertTrue(outcome(service.revoke(player, SIGNED_ON, OptionalInt.empty())).isEmpty(),
                            "the revoke of signed_on was refused");
                    advance(helper, step, 3);
                }
                case 3 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, SIGNED_ON), 0, "signed_on after the revoke");
                    helper.assertValueEqual(earnCountOf(rig, player, SIGNED_ON), 0, "the earn count after the revoke");
                    helper.assertTrue(stored(rig, player).achievements().get(SIGNED_ON) == null,
                            "the state of signed_on is still in storage");
                    helper.assertValueEqual(count(player, Items.EMERALD), 5, "the reward of the revoked grant was taken back");
                    helper.assertTrue(outcome(service.grant(player, Alina.id("test/nope"), OptionalInt.empty())).isPresent(),
                            "an unknown achievement was granted");
                    advance(helper, step, 4);
                }
                case 4 -> {
                    helper.assertTrue(outcome(service.grant(player, CLIMBER, OptionalInt.of(2))).isEmpty(),
                            "the grant of tier 2 was refused");
                    advance(helper, step, 5);
                }
                case 5 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, CLIMBER), 2, "the climber tiers after the grant");
                    helper.assertTrue(outcome(service.grant(player, CLIMBER, OptionalInt.of(1))).isPresent(),
                            "tier 1 was granted again");
                    helper.assertValueEqual(count(player, Items.DIAMOND), 1, "the diamond of the tier 2 reward");
                    helper.assertTrue(rig.settlement().pendingFor(player).isEmpty(), "the tier reward is still in the escrow");
                    advance(helper, step, 6);
                }
                case 6 -> {
                    helper.assertTrue(outcome(service.revoke(player, CLIMBER, OptionalInt.of(2))).isEmpty(),
                            "the revoke of tier 2 was refused");
                    advance(helper, step, 7);
                }
                case 7 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, CLIMBER), 1, "the climber tiers after revoking tier 2");
                    helper.assertValueEqual(viewCount(rig, player, CLIMBER, "climb"), 1,
                            "the count at tier 2's count minus 1");
                    helper.assertValueEqual(storedState(rig, player, CLIMBER).map(AchievementState::tier).orElse(-1), 1,
                            "the stored tier after revoking tier 2");
                    advance(helper, step, 8);
                }
                case 8 -> {
                    helper.assertTrue(outcome(service.revoke(player, CLIMBER, OptionalInt.empty())).isEmpty(),
                            "the revoke of climber was refused");
                    advance(helper, step, 9);
                }
                default -> {
                    helper.assertValueEqual(earnedTiers(rig, player, CLIMBER), 0, "climber after revoking all of it");
                    helper.assertValueEqual(viewCount(rig, player, CLIMBER, "climb"), 0, "the count after revoking all");
                    helper.assertTrue(stored(rig, player).achievements().get(CLIMBER) == null,
                            "the state of climber is still in storage");
                    helper.assertValueEqual(count(player, Items.DIAMOND), 1, "the tier reward was taken back by a revoke");
                }
            }
        });
    }

    // Settlement.

    /**
     * An earn whose reward begin is refused (an operation of the same kind, subject and tier is in the escrow) stays pending,
     * and is begun again once that operation has gone. Storage holds the blocking operation until the test releases it.
     */
    @GameTest(maxTicks = 900)
    public void aRefusedSettlementBeginIsTriedAgain(GameTestHelper helper) {
        CompletableFuture<Void> release = new CompletableFuture<>();
        AchievementRig rig = AchievementRig.create(helper, BUNDLE, delegate -> holdingApplies(delegate, release));
        ServerPlayer player = rig.joined();
        AtomicInteger step = new AtomicInteger();
        AtomicLong releaseAt = new AtomicLong();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record has not loaded");
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(rig.settlement().begin(player, blocker()).isPresent(),
                            "the blocking operation was refused");
                    AlinaTriggers.MANUAL.trigger(player, "retry");
                    releaseAt.set(rig.ticks() + HELD_TICKS);
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, RETRIED), 1, "the earn is recorded while refused");
                    helper.assertValueEqual(count(player, Items.EMERALD), 0, "the reward came while the begin was refused");
                    helper.assertTrue(rig.ticks() >= releaseAt.get(), "waiting for the retry and the release");
                    release.complete(null);
                    advance(helper, step, 2);
                }
                default -> {
                    helper.assertValueEqual(count(player, Items.EMERALD), 5, "the reward, five emeralds");
                    helper.assertTrue(rig.settlement().pendingFor(player).isEmpty(), "the escrow still holds an operation");
                    helper.assertValueEqual(storedState(rig, player, RETRIED).map(AchievementState::earnCount).orElse(0),
                            1, "the earn count in storage");
                }
            }
        });
    }

    // Dialogue condition.

    @GameTest(maxTicks = 100)
    public void aConditionNamingAnAchievementOfTheBundleParses(GameTestHelper helper) {
        helper.succeedWhen(() -> {
            DataResult<ContentBundle> result = parse(helper, "conditions.json");
            helper.assertTrue(result.result().isPresent(), "the bundle was refused:\n" + errorOf(result));
        });
    }

    @GameTest(maxTicks = 100)
    public void aConditionRefusesBadArgumentsAndListsEveryProblem(GameTestHelper helper) {
        helper.succeedWhen(() -> {
            DataResult<ContentBundle> result = parse(helper, "conditions_refused.json");
            String message = errorOf(result);
            helper.assertTrue(result.result().isEmpty(), "the bundle with bad conditions was accepted");
            helper.assertTrue(message.contains("alina:achievement: 'achievement' names 'alina:test/nope', which is not an "
                    + "achievement of alina:achievements in this bundle"), "no unknown achievement problem in:\n" + message);
            helper.assertTrue(message.contains("alina:achievement: achievement alina:test/signed_on has no tiers, so 'tier' "
                    + "cannot be given"), "no tier-on-untiered problem in:\n" + message);
            helper.assertTrue(message.contains("alina:achievement: 'tier' must be from 1 to 3 for alina:test/climber, not 4"),
                    "no tier range problem in:\n" + message);
            helper.assertTrue(message.contains("alina:achievement: no argument named 'tiers'"),
                    "no misspelt key problem in:\n" + message);
            helper.assertTrue(message.contains("alina:achievement: 'achievement' is required"),
                    "no missing id problem in:\n" + message);
        });
    }

    @GameTest(maxTicks = 100)
    public void aConditionNeedsTheBundleToHaveAnAchievementsSection(GameTestHelper helper) {
        helper.succeedWhen(() -> {
            DataResult<ContentBundle> result = parse(helper, "conditions_no_section.json");
            String message = errorOf(result);
            helper.assertTrue(result.result().isEmpty(), "a bundle without alina:achievements was accepted");
            helper.assertTrue(message.contains("the bundle has no alina:achievements section, so 'achievement' cannot name "
                    + "an achievement"), "no missing section problem in:\n" + message);
        });
    }

    // Helpers.

    /**
     * Moves a multi-step test to step {@code next} and fails this attempt, so the next tick runs that step. A step that
     * returns normally ends the test, so only the last step can succeed.
     */
    private static void advance(GameTestHelper helper, AtomicInteger step, int next) {
        step.set(next);
        helper.assertTrue(false, "advancing to step " + next);
    }

    /** True once the player's record has loaded. */
    private static boolean loaded(AchievementRig rig, ServerPlayer player) {
        return rig.service().view(player).isPresent();
    }

    private static Optional<AchievementStanding> standing(AchievementRig rig, ServerPlayer player, Identifier id) {
        return rig.service().view(player).map(AchievementView::achievements).map(map -> map.get(id));
    }

    /** The earned tiers the record shows, or -1 while there is no view or no standing for the achievement. */
    private static int earnedTiers(AchievementRig rig, ServerPlayer player, Identifier id) {
        return standing(rig, player, id).map(AchievementStanding::earnedTiers).orElse(-1);
    }

    private static int earnCountOf(AchievementRig rig, ServerPlayer player, Identifier id) {
        return standing(rig, player, id).map(AchievementStanding::earnCount).orElse(-1);
    }

    private static Optional<java.time.Instant> availableAtOf(AchievementRig rig, ServerPlayer player, Identifier id) {
        return standing(rig, player, id).flatMap(AchievementStanding::availableAt);
    }

    /** The count the record holds for a criterion, or -1 while there is no view. */
    private static int viewCount(AchievementRig rig, ServerPlayer player, Identifier id, String criterion) {
        return standing(rig, player, id).map(standing -> standing.count(criterion)).orElse(-1);
    }

    /** The stored state, loaded now. The storage calls of a test complete on storage threads, so this waits for them. */
    private static CharacterState stored(AchievementRig rig, ServerPlayer player) {
        return rig.storage().loadCharacter(player.getUUID()).join();
    }

    private static Optional<AchievementState> storedState(AchievementRig rig, ServerPlayer player, Identifier id) {
        return Optional.ofNullable(stored(rig, player).achievements().get(id));
    }

    private static int storedCount(AchievementRig rig, ServerPlayer player, Identifier id, String criterion) {
        return stored(rig, player).criterionProgress().getOrDefault(new CriterionKey(id, criterion), 0);
    }

    /** The refusal of a staff call: empty when it was done, and a refusal for a call that has not completed. */
    private static Optional<String> outcome(CompletableFuture<Optional<String>> future) {
        return future.isDone() && !future.isCompletedExceptionally() ? future.join() : Optional.of("not completed");
    }

    /** Counts the item in every slot of the player's inventory, the armour and the offhand included. */
    private static int count(ServerPlayer player, Item item) {
        var inventory = player.getInventory();
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * An operation of the achievement's kind, subject and tier. While it is in the escrow, the achievement's own begin is
     * refused. Its effect names another achievement, so the test's achievement keeps its own stored state.
     */
    private static SettlementRequest blocker() {
        return new SettlementRequest(
                OperationKind.ACHIEVEMENT_REWARD,
                RETRIED,
                0,
                AchievementRig.REVISION,
                Optional.empty(),
                0,
                new OperationEffect.AchievementEarned(BLOCKER, 0, Optional.empty()),
                player -> List.of(),
                Optional.empty(),
                Optional.empty());
    }

    /**
     * A storage whose {@code applyOperation} calls complete with the delegate's result only after {@code release} completes,
     * so an operation stays in the escrow until the test releases it. Other calls reach {@code delegate} at once.
     */
    @SuppressWarnings("unchecked")
    private static AlinaStorage holdingApplies(AlinaStorage delegate, CompletableFuture<Void> release) {
        return (AlinaStorage) Proxy.newProxyInstance(AlinaStorage.class.getClassLoader(),
                new Class<?>[] {AlinaStorage.class},
                (proxy, called, args) -> {
                    Object result;
                    try {
                        result = called.invoke(delegate, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                    if (!called.getName().equals("applyOperation")) {
                        return result;
                    }
                    return ((CompletableFuture<ApplyResult>) result)
                            .thenCompose(applied -> release.thenApply(ignored -> applied));
                });
    }

    /** Parses a fixture through haruhi, as content is parsed. */
    private static DataResult<ContentBundle> parse(GameTestHelper helper, String fixture) {
        return HaruhiServices.content().parseAndValidate(AchievementRig.fixture(helper, fixture));
    }

    private static String errorOf(DataResult<ContentBundle> result) {
        return result.error().map(error -> error.message()).orElse("");
    }
}
