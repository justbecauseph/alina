package town.lampas.alina.gametest;

import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;
import town.lampas.alina.Alina;
import town.lampas.alina.achievement.AchievementListener;
import town.lampas.alina.achievement.AchievementModule;
import town.lampas.alina.achievement.AchievementService;
import town.lampas.alina.achievement.AchievementStanding;
import town.lampas.alina.achievement.AchievementView;
import town.lampas.alina.achievement.AdvancementSync;
import town.lampas.alina.criteria.AlinaTriggers;
import town.lampas.alina.settlement.PlayerDataSaves;
import town.lampas.alina.settlement.SettlementRequest;
import town.lampas.alina.settlement.SettlementService;
import town.lampas.alina.storage.AchievementState;
import town.lampas.alina.storage.AlinaStorage;
import town.lampas.alina.storage.ApplyResult;
import town.lampas.alina.storage.CharacterState;
import town.lampas.alina.storage.CriterionKey;
import town.lampas.alina.storage.Operation;
import town.lampas.alina.storage.OperationEffect;
import town.lampas.alina.storage.OperationKind;
import town.lampas.alina.storage.OperationStep;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.dialogue.api.ConditionType;
import town.lampas.haruhi.dialogue.api.DialogueCondition;
import town.lampas.haruhi.dialogue.api.DialogueContext;
import town.lampas.haruhi.dialogue.api.DialogueFlags;
import town.lampas.haruhi.dialogue.api.DialogueRegistries;
import town.lampas.haruhi.dialogue.api.ParseContext;

/**
 * Server GameTests owned by the achievement service (ALINA-PLAN §9.2 and §9.3). Each test builds an {@link AchievementRig}:
 * a service over its own in-memory storage and settlement, a clock the test moves, and players outside the server's list.
 * The test ticks the rig once per game tick. Storage and settlement complete on storage threads and return to the server
 * thread on a later tick, so a test waits with {@code succeedWhen}: a check that is not met yet throws and is run again.
 *
 * <p>A test with several steps moves on with {@link #advance}, which fails the attempt, so each step runs on its own tick
 * and only the last step returns normally. Each step checks what it waits for before it acts, so an action runs once. A
 * staff call that the service refuses while a record loads or a write is pending is run again by the step, through
 * {@link #staff}.
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
    private static final Identifier LADDER = Alina.id("test/ladder");
    private static final Identifier CHAIN = Alina.id("test/chain");
    private static final Identifier PLAIN = Alina.id("test/plain");
    private static final Identifier PAIR = Alina.id("test/pair");
    private static final String BUNDLE = "bundle.json";
    private static final String BUNDLE_NEXT = "bundle_next.json";
    private static final String BUNDLE_REPEAT = "bundle_repeat.json";
    /** The daily achievement with count 10, then the same with count 5, and quiet_note with a repeat and count 2. */
    private static final String DAILY_TEN = "daily_ten.json";
    private static final String DAILY_FIVE = "daily_five.json";
    private static final String QUIET_TWICE = "quiet_twice.json";
    /** The daily achievement's cooldown: PT1H in the fixture. */
    private static final Duration DAILY_COOLDOWN = Duration.ofHours(1);
    /** Ticks the held reward waits before its operation is released: more than one retry interval. */
    private static final long HELD_TICKS = 150;
    /** Ticks a check waits so that a cycle check (every 20 ticks) and a reload have run. */
    private static final long SETTLE_TICKS = 40;
    /** Ticks a record may take to load after its player comes online: the first tick they are online applies the result. */
    private static final long ONLINE_LOAD_TICKS = 3;

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
                    helper.assertTrue(operations(rig).isEmpty(), "a no-reward earn went through settlement");
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
     * passed, its reset reaches storage, and it is earned again with an earn count of 2. Nothing is earned at a rejoin
     * in the new cycle.
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
                            "the count during the cooldown, which the earn left at its target");
                    helper.assertValueEqual(earnCountOf(rig, player, DAILY), 1, "an event in the cooldown earned daily again");
                    rig.clock().advance(Duration.ofMinutes(1));
                    advance(helper, step, 3);
                }
                case 3 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, DAILY), 0, "daily after the cooldown");
                    helper.assertValueEqual(viewCount(rig, player, DAILY, "daily"), 0, "the count of the new cycle");
                    helper.assertValueEqual(earnCountOf(rig, player, DAILY), 1, "the earn count kept by the new cycle");
                    // The reset reaches storage: no availability and no counts, whatever the record shows.
                    helper.assertTrue(storedState(rig, player, DAILY).flatMap(AchievementState::availableAt).isEmpty(),
                            "the stored availability after the reset");
                    helper.assertValueEqual(storedCount(rig, player, DAILY, "daily"), 0, "the stored count after the reset");
                    advance(helper, step, 4);
                }
                case 4 -> {
                    rig.service().leave(player);
                    rig.service().join(player);
                    advance(helper, step, 5);
                }
                case 5 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, DAILY), 0, "daily earned at load in the new cycle");
                    helper.assertValueEqual(viewCount(rig, player, DAILY, "daily"), 0, "the count after a rejoin");
                    helper.assertValueEqual(earnCountOf(rig, player, DAILY), 1, "the earn count after a rejoin");
                    AlinaTriggers.MANUAL.trigger(player, "daily");
                    advance(helper, step, 6);
                }
                default -> {
                    helper.assertValueEqual(earnedTiers(rig, player, DAILY), 1, "daily in the second cycle");
                    helper.assertValueEqual(earnCountOf(rig, player, DAILY), 2, "the earn count after the second earn");
                    helper.assertValueEqual(storedState(rig, player, DAILY).map(AchievementState::earnCount).orElse(0), 2,
                            "the earn count in storage");
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

    /**
     * A join before the player is in the server's list: the record loads while the player is offline, and its loaded state is
     * applied on the first tick the player is online. A retry interval would make the record come about a hundred ticks later.
     */
    @GameTest(maxTicks = 400)
    public void aRecordLoadedBeforeItsPlayerIsOnlineIsAppliedWhenThePlayerIs(GameTestHelper helper) {
        AtomicBoolean loadCompleted = new AtomicBoolean();
        AchievementRig rig = AchievementRig.create(helper, BUNDLE, delegate -> observingLoads(delegate, loadCompleted));
        ServerPlayer player = rig.newOfflinePlayer();
        rig.service().join(player);
        AtomicLong waitUntil = new AtomicLong();
        AtomicLong cameOnlineAt = new AtomicLong();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(loadCompleted.get(), "the load has not completed in storage");
                    // The server runs the load's completion on a later tick than storage's, so the test waits for it.
                    waitUntil.set(rig.ticks() + SETTLE_TICKS);
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertTrue(rig.ticks() >= waitUntil.get(), "waiting for the load's completion on the server");
                    helper.assertTrue(!loaded(rig, player), "the record loaded while its player was offline");
                    rig.comeOnline(player);
                    cameOnlineAt.set(rig.ticks());
                    advance(helper, step, 2);
                }
                default -> {
                    helper.assertTrue(loaded(rig, player), "the record has not loaded " + (rig.ticks() - cameOnlineAt.get())
                            + " ticks after its player came online");
                    helper.assertTrue(rig.ticks() - cameOnlineAt.get() <= ONLINE_LOAD_TICKS, "the record loaded late");
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

    /**
     * An unfiltered {@code alina:achievement_earned} criterion counts other achievements' earns and not its own: the chain
     * reaches tier 1 with one count from one other earn (quiet_note, which nothing else counts), and no further tier from its
     * own tier earn.
     */
    @GameTest(maxTicks = 400)
    public void anAchievementsOwnEarnDoesNotCountTowardsItsOwnCriteria(GameTestHelper helper) {
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
                    helper.assertValueEqual(earnedTiers(rig, player, QUIET_NOTE), 1, "quiet_note");
                    helper.assertValueEqual(viewCount(rig, player, CHAIN, "earned"), 1,
                            "the chain's count: one earn of another achievement, and none of its own");
                    helper.assertValueEqual(earnedTiers(rig, player, CHAIN), 1, "the chain after one other earn");
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

    /**
     * An earn whose reward begin is refused is not announced, and is not counted by a meta-achievement, until its reward is
     * begun. Once it is begun and delivered it is announced once, and a rejoin does not announce or reward it again.
     */
    @GameTest(maxTicks = 900)
    public void anEarnIsAnnouncedOnceWhenItsBeginIsRefusedAndTheRecordReloads(GameTestHelper helper) {
        CompletableFuture<Void> release = new CompletableFuture<>();
        AchievementRig rig = AchievementRig.create(helper, BUNDLE, delegate -> holdingApplies(delegate, release, op -> true));
        ServerPlayer player = rig.joined();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record has not loaded");
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(rig.settlement().begin(player, blocker()).isPresent(),
                            "the blocking operation was refused");
                    AlinaTriggers.MANUAL.trigger(player, "retry");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, RETRIED), 1, "the earn is recorded while refused");
                    helper.assertTrue(rig.messages(player).stream().noneMatch(text -> text.contains("Retried")),
                            "the earn was announced before its reward was begun");
                    helper.assertValueEqual(viewCount(rig, player, CHAIN, "earned"), 0,
                            "a meta-achievement counted an earn whose reward was not begun");
                    release.complete(null);
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertValueEqual(count(player, Items.EMERALD), 5, "the reward, once");
                    rig.service().leave(player);
                    rig.service().join(player);
                    advance(helper, step, 3);
                }
                default -> {
                    helper.assertTrue(loaded(rig, player), "the record has not reloaded after the rejoin");
                    helper.assertValueEqual(count(player, Items.EMERALD), 5, "the reward given once, across the rejoin");
                    helper.assertValueEqual(rig.messages(player).stream().filter(text -> text.contains("Retried")).count(), 1L,
                            "the announcements of Retried");
                    helper.assertValueEqual(earnedTiers(rig, player, CHAIN), 1,
                            "the meta-achievement counted the earn once");
                    helper.assertValueEqual(viewCount(rig, player, CHAIN, "earned"), 1, "the meta count");
                    helper.assertValueEqual(storedState(rig, player, RETRIED).map(AchievementState::earnCount).orElse(0), 1,
                            "the earn count in storage");
                }
            }
        });
    }

    // Counts in storage, leave and join, and content activation.

    /** Counts reach storage behind the flush, and a leave writes them before the rejoin's load reads them. */
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
                    // Before the periodic flush, so only the leave can write the count.
                    helper.assertTrue(rig.ticks() < AchievementService.FLUSH_TICKS, "the periodic flush has run");
                    helper.assertValueEqual(storedCount(rig, player, FLUSH, "step"), 0, "the count in storage before the leave");
                    rig.service().leave(player);
                    rig.service().join(player);
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertValueEqual(viewCount(rig, player, FLUSH, "step"), 2, "the count after a rejoin");
                    helper.assertValueEqual(storedCount(rig, player, FLUSH, "step"), 2, "the count in storage after the leave");
                    AlinaTriggers.MANUAL.trigger(player, "step");
                    AlinaTriggers.MANUAL.trigger(player, "step");
                    AlinaTriggers.MANUAL.trigger(player, "step");
                    advance(helper, step, 3);
                }
                default -> helper.assertValueEqual(earnedTiers(rig, player, FLUSH), 1, "flush after five steps");
            }
        });
    }

    /** Stopping the service writes every record's counts, and the test waits for the writes it returns. */
    @GameTest(maxTicks = 400)
    public void stopWritesTheCountsOfEveryRecord(GameTestHelper helper) {
        AchievementRig rig = AchievementRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(loaded(rig, player), "the record has not loaded");
                    AlinaTriggers.MANUAL.trigger(player, "step");
                    AlinaTriggers.MANUAL.trigger(player, "step");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertValueEqual(viewCount(rig, player, FLUSH, "step"), 2, "the count in the record");
                    helper.assertTrue(rig.ticks() < AchievementService.FLUSH_TICKS, "the periodic flush has run");
                    helper.assertValueEqual(storedCount(rig, player, FLUSH, "step"), 0, "the count in storage before stop");
                    rig.service().stop().join();
                    advance(helper, step, 2);
                }
                default -> helper.assertValueEqual(storedCount(rig, player, FLUSH, "step"), 2,
                        "the count in storage after stop");
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

    /**
     * A failed count write does not put back the counts a revoke removed while it was in flight: the revoke's delete runs
     * after the failed write, and the counts stay gone after a leave and a rejoin.
     */
    @GameTest(maxTicks = 900)
    public void aFailedCountWriteDoesNotBringBackCountsThatARevokeRemoved(GameTestHelper helper) {
        AtomicReference<CompletableFuture<Void>> held = new AtomicReference<>();
        AchievementRig rig = AchievementRig.create(helper, BUNDLE, delegate -> holdingFirstCountWrite(delegate, held));
        ServerPlayer player = rig.joined();
        AtomicReference<CompletableFuture<Optional<String>>> revoked = new AtomicReference<>();
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
                    // The periodic flush is the first count write, and it is held until the test fails it.
                    helper.assertTrue(held.get() != null, "the count write has not started");
                    revoked.set(rig.service().revoke(player, FLUSH, OptionalInt.empty()));
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertTrue(!revoked.get().isDone(), "the revoke finished before the count write");
                    held.get().completeExceptionally(new IllegalStateException("injected count write failure"));
                    advance(helper, step, 3);
                }
                case 3 -> {
                    helper.assertTrue(finished(helper, revoked.get()).isEmpty(), "the revoke was refused");
                    rig.service().leave(player);
                    rig.service().join(player);
                    advance(helper, step, 4);
                }
                default -> {
                    helper.assertValueEqual(viewCount(rig, player, FLUSH, "step"), 0, "the count after the revoke and a rejoin");
                    helper.assertValueEqual(storedCount(rig, player, FLUSH, "step"), 0, "the count in storage after the revoke");
                }
            }
        });
    }

    // Staff controls.

    /**
     * Staff grant and revoke, without a tier and with one, through the same earn path and the same storage writes. A revoke
     * completes once its writes have finished, and a call refused while a write is pending is run again.
     */
    @GameTest(maxTicks = 1200)
    public void staffGrantAndRevokeWorkWithAndWithoutATier(GameTestHelper helper) {
        AchievementRig rig = AchievementRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        Pending grantSigned = new Pending();
        Pending revokeSigned = new Pending();
        Pending grantClimber = new Pending();
        Pending revokeClimberTwo = new Pending();
        Pending revokeClimber = new Pending();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record has not loaded");
            AchievementService service = rig.service();
            switch (step.get()) {
                case 0 -> {
                    staff(helper, grantSigned, () -> service.grant(player, SIGNED_ON, OptionalInt.empty()));
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, SIGNED_ON), 1, "signed_on after the grant");
                    helper.assertValueEqual(count(player, Items.EMERALD), 5, "the reward of the grant");
                    helper.assertTrue(rig.settlement().pendingFor(player).isEmpty(), "the reward is still in the escrow");
                    advance(helper, step, 2);
                }
                case 2 -> {
                    staff(helper, revokeSigned, () -> service.revoke(player, SIGNED_ON, OptionalInt.empty()));
                    advance(helper, step, 3);
                }
                case 3 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, SIGNED_ON), 0, "signed_on after the revoke");
                    helper.assertValueEqual(earnCountOf(rig, player, SIGNED_ON), 0, "the earn count after the revoke");
                    helper.assertTrue(storedState(rig, player, SIGNED_ON).isEmpty(), "the state of signed_on is still in storage");
                    helper.assertValueEqual(count(player, Items.EMERALD), 5, "a revoke takes no reward back");
                    helper.assertTrue(refusalOf(helper, service.grant(player, Alina.id("test/nope"), OptionalInt.empty()))
                            .get().startsWith("Unknown achievement"), "an unknown achievement was granted");
                    advance(helper, step, 4);
                }
                case 4 -> {
                    staff(helper, grantClimber, () -> service.grant(player, CLIMBER, OptionalInt.of(2)));
                    advance(helper, step, 5);
                }
                case 5 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, CLIMBER), 2, "the climber tiers after the grant");
                    helper.assertTrue(refusalOf(helper, service.grant(player, CLIMBER, OptionalInt.of(1))).get()
                            .contains("already earned"), "tier 1 was granted again");
                    helper.assertValueEqual(count(player, Items.DIAMOND), 1, "the diamond of the tier 2 reward");
                    helper.assertTrue(rig.settlement().pendingFor(player).isEmpty(), "the tier reward is still in the escrow");
                    advance(helper, step, 6);
                }
                case 6 -> {
                    staff(helper, revokeClimberTwo, () -> service.revoke(player, CLIMBER, OptionalInt.of(2)));
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
                    staff(helper, revokeClimber, () -> service.revoke(player, CLIMBER, OptionalInt.empty()));
                    advance(helper, step, 9);
                }
                default -> {
                    helper.assertValueEqual(earnedTiers(rig, player, CLIMBER), 0, "climber after revoking all of it");
                    helper.assertValueEqual(viewCount(rig, player, CLIMBER, "climb"), 0, "the count after revoking all");
                    helper.assertTrue(storedState(rig, player, CLIMBER).isEmpty(), "the state of climber is still in storage");
                    helper.assertValueEqual(count(player, Items.DIAMOND), 1, "a revoke takes no tier reward back");
                }
            }
        });
    }

    /**
     * A revoke whose storage write fails changes nothing in the record, and its future says so. A second revoke, once the
     * storage works again, removes the achievement.
     */
    @GameTest(maxTicks = 900)
    public void aFailedRevokeChangesNothingAndIsReported(GameTestHelper helper) {
        // The delete is held and failed by the test, so the revoke is in flight while the record still holds the achievement.
        AtomicReference<CompletableFuture<Void>> held = new AtomicReference<>();
        AchievementRig rig = AchievementRig.create(helper, BUNDLE, delegate -> holdingFirstCall(delegate, "deleteAchievement", held));
        ServerPlayer player = rig.joined();
        AtomicReference<CompletableFuture<Optional<String>>> first = new AtomicReference<>();
        Pending second = new Pending();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record has not loaded");
            switch (step.get()) {
                case 0 -> {
                    AlinaTriggers.MANUAL.trigger(player, "note");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, QUIET_NOTE), 1, "quiet_note before the revoke");
                    helper.assertValueEqual(storedCount(rig, player, QUIET_NOTE, "note"), 1, "the count in storage");
                    first.set(rig.service().revoke(player, QUIET_NOTE, OptionalInt.empty()));
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertTrue(held.get() != null, "the delete has not started");
                    helper.assertTrue(!first.get().isDone(), "the revoke finished before its write");
                    helper.assertValueEqual(earnedTiers(rig, player, QUIET_NOTE), 1, "the record changed before the write");
                    held.get().completeExceptionally(new IllegalStateException("injected delete failure"));
                    advance(helper, step, 3);
                }
                case 3 -> {
                    Optional<String> refusal = refusalOf(helper, first.get());
                    helper.assertTrue(refusal.get().startsWith("Could not revoke " + QUIET_NOTE), "the failure text: " + refusal);
                    helper.assertValueEqual(earnedTiers(rig, player, QUIET_NOTE), 1, "the record after the failed revoke");
                    helper.assertValueEqual(earnCountOf(rig, player, QUIET_NOTE), 1, "the earn count after the failed revoke");
                    advance(helper, step, 4);
                }
                case 4 -> {
                    staff(helper, second, () -> rig.service().revoke(player, QUIET_NOTE, OptionalInt.empty()));
                    advance(helper, step, 5);
                }
                default -> {
                    helper.assertValueEqual(earnedTiers(rig, player, QUIET_NOTE), 0, "quiet_note after the second revoke");
                    helper.assertTrue(storedState(rig, player, QUIET_NOTE).isEmpty(), "the state is still in storage");
                }
            }
        });
    }

    /** Staff calls are refused while the record's first load is in flight, and accepted once it has loaded. */
    @GameTest(maxTicks = 600)
    public void aStaffCallIsRefusedWhileTheRecordLoads(GameTestHelper helper) {
        AtomicReference<CompletableFuture<Void>> gate = new AtomicReference<>(new CompletableFuture<>());
        AchievementRig rig = AchievementRig.create(helper, BUNDLE, delegate -> gatedLoads(delegate, gate));
        ServerPlayer player = rig.newPlayer();
        rig.service().join(player);
        Pending grantQuiet = new Pending();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(refusalOf(helper, rig.service().revoke(player, SIGNED_ON, OptionalInt.empty())).get()
                            .contains("still loading"), "a revoke was accepted while the record loads");
                    helper.assertTrue(refusalOf(helper, rig.service().grant(player, QUIET_NOTE, OptionalInt.empty())).get()
                            .contains("still loading"), "a grant was accepted while the record loads");
                    gate.get().complete(null);
                    advance(helper, step, 1);
                }
                default -> {
                    helper.assertTrue(loaded(rig, player), "the record has not loaded");
                    staff(helper, grantQuiet, () -> rig.service().grant(player, QUIET_NOTE, OptionalInt.empty()));
                    helper.assertValueEqual(earnedTiers(rig, player, QUIET_NOTE), 1, "quiet_note after the grant");
                }
            }
        });
    }

    /**
     * A settlement result that lands while a record's first load is in flight drops that load, which may have read storage
     * before the result. The record loads again and counts the reward once.
     */
    @GameTest(maxTicks = 600)
    public void aSettlementResultDuringTheFirstLoadIsLoadedAgain(GameTestHelper helper) {
        AtomicReference<CompletableFuture<Void>> gate = new AtomicReference<>(new CompletableFuture<>());
        AchievementRig rig = AchievementRig.create(helper, BUNDLE, delegate -> gatedLoads(delegate, gate));
        ServerPlayer player = rig.newPlayer();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            switch (step.get()) {
                case 0 -> {
                    // An operation from an earlier session: it is in the escrow when the player joins.
                    helper.assertTrue(rig.settlement().begin(player, signedReward()).isPresent(), "the reward was refused");
                    rig.service().join(player);
                    advance(helper, step, 1);
                }
                case 1 -> {
                    // Delivery follows the storage result in the same server task, so the result has arrived while the
                    // load is held. Then the load is let go.
                    helper.assertValueEqual(count(player, Items.EMERALD), 5, "the operation was not applied and delivered");
                    gate.get().complete(null);
                    advance(helper, step, 2);
                }
                default -> {
                    helper.assertTrue(loaded(rig, player), "the record has not loaded");
                    helper.assertValueEqual(earnedTiers(rig, player, SIGNED_ON), 1, "signed_on after the reload");
                    helper.assertValueEqual(earnCountOf(rig, player, SIGNED_ON), 1, "the earn count after the reload");
                    helper.assertValueEqual(count(player, Items.EMERALD), 5, "the reward, once");
                    helper.assertValueEqual(storedState(rig, player, SIGNED_ON).map(AchievementState::earnCount).orElse(0), 1,
                            "the earn count in storage");
                }
            }
        });
    }

    /**
     * A staff revoke of an achievement whose earn is in the escrow, its reward not yet applied, is refused until the reward has
     * been delivered.
     */
    @GameTest(maxTicks = 900)
    public void aStaffCallIsRefusedWhileAnEarnIsBeingDelivered(GameTestHelper helper) {
        CompletableFuture<Void> release = new CompletableFuture<>();
        AchievementRig rig = AchievementRig.create(helper, BUNDLE, delegate -> holdingApplies(delegate, release, op -> true));
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
                case 1 -> {
                    helper.assertTrue(rig.settlement().pendingFor(player).size() == 1, "the reward is not in the escrow");
                    helper.assertTrue(refusalOf(helper, rig.service().revoke(player, SIGNED_ON, OptionalInt.empty())).get()
                            .contains("being recorded or delivered"), "a revoke was accepted while its reward is in the escrow");
                    release.complete(null);
                    advance(helper, step, 2);
                }
                default -> helper.assertValueEqual(count(player, Items.EMERALD), 5, "the reward, once");
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
        AchievementRig rig = AchievementRig.create(helper, BUNDLE, delegate -> holdingApplies(delegate, release, op -> true));
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

    /**
     * A refused reward begin with the advancement sync attached. The blocking operation goes into the escrow after the record
     * has loaded, so the record does not take it for the earn's own operation. A reset packet built while the begin is refused
     * shows the earn done, without a toast. Once the blocking operation has gone, the begin is tried again and succeeds: the earn
     * is durable, the listeners are told once, and the sync sends the earned holder's done progress with toasts on.
     */
    @GameTest(maxTicks = 900)
    public void aRefusedBeginRetriedAfterAResetToastsItsHolder(GameTestHelper helper) {
        CompletableFuture<Void> release = new CompletableFuture<>();
        AchievementRig rig = AchievementRig.create(helper, BUNDLE, delegate -> holdingApplies(delegate, release, op -> true));
        AdvancementSync sync = rig.attachSync();
        ServerPlayer player = rig.joined();
        List<Integer> told = new ArrayList<>();
        rig.service().addListener(new AchievementListener() {
            @Override
            public void earned(ServerPlayer earner, Identifier achievement, int tier) {
                if (earner == player && achievement.equals(RETRIED)) {
                    told.add(tier);
                }
            }
        });
        Identifier holder = AdvancementSync.holderId(RETRIED, 1);
        List<ClientboundUpdateAdvancementsPacket> sent = new ArrayList<>();
        AtomicLong releaseAt = new AtomicLong();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            sent.addAll(rig.updates(player));
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
                    // Settling is true only when the record holds an operation of the achievement in the escrow: here the
                    // escrow holds only the blocker, which the record does not know, so the earn's own begin is still to come.
                    helper.assertFalse(standing(rig, player, RETRIED).map(AchievementStanding::settling).orElse(true),
                            "the record took the blocking operation for the earn's own");
                    helper.assertValueEqual(rig.settlement().pendingFor(player).size(), 1,
                            "the escrow holds more than the blocking operation");
                    Map<Identifier, AdvancementProgress> progress = new HashMap<>();
                    sync.appendToReset(player, new ArrayList<>(), progress);
                    helper.assertTrue(progress.containsKey(holder) && progress.get(holder).isDone(),
                            "the reset does not show the earn done");
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertTrue(told.isEmpty(), "the earn was told before its reward was begun");
                    helper.assertTrue(sent.stream().noneMatch(ClientboundUpdateAdvancementsPacket::shouldShowAdvancements),
                            "a toast was sent before the earn was durable");
                    helper.assertValueEqual(count(player, Items.EMERALD), 0, "the reward came while the begin was refused");
                    helper.assertTrue(rig.ticks() >= releaseAt.get(), "waiting for a retry while the begin is refused");
                    release.complete(null);
                    advance(helper, step, 3);
                }
                default -> {
                    helper.assertValueEqual(told, List.of(1), "the earns told");
                    // Chain counts every earn, so the durable earn of retried earns chain's first tier, with a toast of its
                    // own. Only the packets that carry retried's holder are this earn's.
                    List<ClientboundUpdateAdvancementsPacket> toasts = sent.stream()
                            .filter(update -> update.shouldShowAdvancements() && update.getProgress().containsKey(holder))
                            .toList();
                    helper.assertValueEqual(toasts.size(), 1, "the packets with toasts for retried");
                    ClientboundUpdateAdvancementsPacket toast = toasts.get(0);
                    helper.assertFalse(toast.shouldReset(), "the earn's packet has the reset flag");
                    helper.assertValueEqual(toast.getProgress().keySet(), Set.of(holder), "the earn's progress entries");
                    helper.assertTrue(toast.getProgress().get(holder).isDone(), "the earn's progress is not done");
                    helper.assertValueEqual(count(player, Items.EMERALD), 5, "the reward, five emeralds");
                    helper.assertTrue(rig.settlement().pendingFor(player).isEmpty(), "the escrow still holds an operation");
                    helper.assertValueEqual(storedState(rig, player, RETRIED).map(AchievementState::earnCount).orElse(0),
                            1, "the earn count in storage");
                }
            }
        });
    }

    /**
     * Storage applies a reward that a load read while it was unapplied, and that the record counted from its earn: a load mid-
     * settlement is followed by the reward's apply, and nothing is counted twice or lost.
     */
    @GameTest(maxTicks = 900)
    public void aRewardAppliedAfterALoadIsCountedOnceAndNotLostByALaterWrite(GameTestHelper helper) {
        CompletableFuture<Void> release = new CompletableFuture<>();
        AchievementRig rig = AchievementRig.create(helper, BUNDLE, delegate -> holdingApplies(delegate, release, op -> true));
        ServerPlayer player = rig.joined();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record has not loaded");
            switch (step.get()) {
                case 0 -> {
                    AlinaTriggers.MANUAL.trigger(player, "ladder");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertValueEqual(rig.settlement().pendingFor(player).size(), 1, "the first rung is not in the escrow");
                    rig.service().leave(player);
                    rig.service().join(player);
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, LADDER), 1, "the first rung at load, from the escrow");
                    AlinaTriggers.MANUAL.trigger(player, "ladder");
                    advance(helper, step, 3);
                }
                case 3 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, LADDER), 2, "the second rung");
                    helper.assertValueEqual(rig.settlement().pendingFor(player).size(), 1, "the first rung settled early");
                    release.complete(null);
                    advance(helper, step, 4);
                }
                default -> {
                    helper.assertValueEqual(storedState(rig, player, LADDER).map(AchievementState::earnCount).orElse(0), 2,
                            "the earn count in storage");
                    helper.assertValueEqual(storedState(rig, player, LADDER).map(AchievementState::tier).orElse(-1), 2,
                            "the stored tier");
                    helper.assertValueEqual(count(player, Items.DIAMOND), 1, "the first rung's reward, once");
                }
            }
        });
    }

    /**
     * A later tier's reward begins only once the earlier tier's reward is applied: storage applies them in tier order, so the
     * completing tier's availability is written last.
     */
    @GameTest(maxTicks = 900)
    public void aLaterTierRewardWaitsForTheEarlierTierToBeApplied(GameTestHelper helper) {
        CompletableFuture<Void> release = new CompletableFuture<>();
        AchievementRig rig = AchievementRig.create(helper, BUNDLE,
                delegate -> holdingApplies(delegate, release, op -> op.effect() instanceof OperationEffect.AchievementEarned earned
                        && earned.achievementId().equals(PAIR)));
        ServerPlayer player = rig.joined();
        AtomicLong waitUntil = new AtomicLong();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record has not loaded");
            switch (step.get()) {
                case 0 -> {
                    AlinaTriggers.MANUAL.trigger(player, "pair");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertValueEqual(rig.settlement().pendingFor(player).size(), 1, "the first tier is not in the escrow");
                    AlinaTriggers.MANUAL.trigger(player, "pair");
                    waitUntil.set(rig.ticks() + SETTLE_TICKS);
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, PAIR), 2, "the second tier is earned");
                    helper.assertTrue(rig.ticks() >= waitUntil.get(), "waiting for the second tier's reward to be begun");
                    helper.assertValueEqual(rig.settlement().pendingFor(player).size(), 1,
                            "the second tier's reward was begun before the first was applied");
                    release.complete(null);
                    advance(helper, step, 3);
                }
                default -> {
                    helper.assertTrue(rig.settlement().pendingFor(player).isEmpty(), "an operation is still in the escrow");
                    helper.assertValueEqual(storedState(rig, player, PAIR).map(AchievementState::tier).orElse(-1), 2,
                            "the stored tier");
                    helper.assertValueEqual(storedState(rig, player, PAIR).map(AchievementState::earnCount).orElse(0), 2,
                            "the earn count in storage");
                }
            }
        });
    }

    /**
     * A cycle does not start while a reward of the old cycle is undelivered: the inventory is full, so the first rung's
     * reward stays in the escrow through the cooldown, and the new cycle starts only after the reward has been delivered.
     */
    @GameTest(maxTicks = 1200)
    public void aCycleWaitsForAnUndeliveredRewardOfTheOldCycle(GameTestHelper helper) {
        AchievementRig rig = AchievementRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        AtomicLong waitUntil = new AtomicLong();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record has not loaded");
            switch (step.get()) {
                case 0 -> {
                    fillMainInventory(player);
                    AlinaTriggers.MANUAL.trigger(player, "ladder");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, LADDER), 1, "the first rung");
                    // Applied by storage, but the stacks do not fit, so the operation stays in the escrow.
                    helper.assertTrue(rig.settlement().pendingFor(player).stream().anyMatch(op -> op.applied().isPresent()),
                            "the first rung's reward is not applied");
                    AlinaTriggers.MANUAL.trigger(player, "ladder");
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, LADDER), 2, "the second rung");
                    rig.clock().advance(DAILY_COOLDOWN.plusSeconds(1));
                    waitUntil.set(rig.ticks() + SETTLE_TICKS);
                    advance(helper, step, 3);
                }
                case 3 -> {
                    helper.assertTrue(rig.ticks() >= waitUntil.get(), "waiting for the cycle checks after the cooldown");
                    helper.assertValueEqual(earnedTiers(rig, player, LADDER), 2,
                            "the cycle restarted while the first rung's reward was undelivered");
                    emptyMainInventory(player);
                    advance(helper, step, 4);
                }
                case 4 -> {
                    helper.assertValueEqual(count(player, Items.DIAMOND), 1, "the first rung's reward, delivered");
                    helper.assertValueEqual(earnedTiers(rig, player, LADDER), 0, "the new cycle has not started");
                    AlinaTriggers.MANUAL.trigger(player, "ladder");
                    advance(helper, step, 5);
                }
                default -> {
                    helper.assertValueEqual(earnedTiers(rig, player, LADDER), 1, "the first rung of the new cycle");
                    helper.assertValueEqual(count(player, Items.DIAMOND), 2, "the first rung's reward in the new cycle");
                    helper.assertValueEqual(earnCountOf(rig, player, LADDER), 3, "the earn count after the new cycle's rung");
                }
            }
        });
    }

    /**
     * A cycle reset deletes the counts before it writes the state. The first reset call fails, and the record leaves before
     * the retry. On the rejoin the cycle starts again, so the achievement is not earned from the old counts.
     */
    @GameTest(maxTicks = 900)
    public void aFailedResetIsStartedAgainAfterARejoinAndNotEarnedFromTheOldCounts(GameTestHelper helper) {
        AtomicBoolean failed = new AtomicBoolean();
        AchievementRig rig = AchievementRig.create(helper, BUNDLE, delegate -> failingOnce(delegate, "resetCriterionProgress", failed));
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
                    helper.assertTrue(storedState(rig, player, DAILY).flatMap(AchievementState::availableAt).isPresent(),
                            "the earn is not in storage");
                    rig.clock().advance(DAILY_COOLDOWN.plusSeconds(1));
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertTrue(failed.get(), "the cycle has not started its reset");
                    rig.service().leave(player);
                    rig.service().join(player);
                    advance(helper, step, 3);
                }
                case 3 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, DAILY), 0, "daily earned from the old counts at load");
                    helper.assertValueEqual(viewCount(rig, player, DAILY, "daily"), 0, "the count of the new cycle");
                    helper.assertValueEqual(earnCountOf(rig, player, DAILY), 1, "the earn count after the rejoin");
                    helper.assertTrue(storedState(rig, player, DAILY).flatMap(AchievementState::availableAt).isEmpty(),
                            "the reset did not reach storage");
                    helper.assertValueEqual(storedCount(rig, player, DAILY, "daily"), 0, "the old counts are still in storage");
                }
            }
        });
    }

    /**
     * A failed count write does not bring back the counts that a cycle start removed while the write was in flight. The periodic
     * flush takes the count of a daily achievement and is held; the cooldown passes and the cycle starts; the held write fails.
     * The reset then writes storage without the count, and a rejoin finds the achievement not earned.
     */
    @GameTest(maxTicks = 900)
    public void aFailedCountWriteDoesNotBringBackCountsThatACycleStartRemoved(GameTestHelper helper) {
        AtomicBoolean armed = new AtomicBoolean();
        AtomicReference<CompletableFuture<Void>> held = new AtomicReference<>();
        AchievementRig rig = AchievementRig.create(helper, BUNDLE,
                delegate -> holdingArmedCountWrite(delegate, armed, held));
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
                    helper.assertTrue(storedState(rig, player, DAILY).flatMap(AchievementState::availableAt).isPresent(),
                            "the earn is not in storage");
                    // The next periodic flush writes daily's count, and that write is held until the test fails it.
                    armed.set(true);
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertTrue(held.get() != null, "the periodic flush has not written the count");
                    rig.clock().advance(DAILY_COOLDOWN.plusSeconds(1));
                    advance(helper, step, 3);
                }
                case 3 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, DAILY), 0, "daily has not started its new cycle");
                    held.get().completeExceptionally(new IllegalStateException("injected count write failure"));
                    advance(helper, step, 4);
                }
                case 4 -> {
                    helper.assertTrue(storedState(rig, player, DAILY).flatMap(AchievementState::availableAt).isEmpty(),
                            "the reset has not reached storage");
                    rig.service().leave(player);
                    rig.service().join(player);
                    advance(helper, step, 5);
                }
                default -> {
                    helper.assertValueEqual(earnedTiers(rig, player, DAILY), 0, "daily earned from a count the cycle removed");
                    helper.assertValueEqual(viewCount(rig, player, DAILY, "daily"), 0, "the count after the rejoin");
                    helper.assertValueEqual(storedCount(rig, player, DAILY, "daily"), 0, "the count in storage");
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

    /**
     * The condition is evaluated through the rig's service, which {@link AchievementModule#conditionViews} points it at. A
     * daily achievement stays true through its new cycle and stops when staff revoke it. A ladder's tiers stay true through
     * the restart of its completed cycle. Cooldowns run on the rig's clock, so the cycles start at fixed ticks.
     */
    @GameTest(maxTicks = 900)
    public void theAchievementConditionIsEvaluatedInEveryCycle(GameTestHelper helper) {
        AchievementRig rig = AchievementRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        ContentBundle bundle = parseOrFail(helper, BUNDLE);
        conditionsRead(rig, player);
        Pending revokeDaily = new Pending();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record has not loaded");
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(!conditionHolds(helper, player, bundle, DAILY, OptionalInt.empty()),
                            "daily is true before it is earned");
                    helper.assertTrue(!conditionHolds(helper, player, bundle, LADDER, OptionalInt.of(2)),
                            "tier 2 of the ladder is true before it is earned");
                    AlinaTriggers.MANUAL.trigger(player, "daily");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertTrue(conditionHolds(helper, player, bundle, DAILY, OptionalInt.empty()),
                            "daily is false after it is earned");
                    rig.clock().advance(DAILY_COOLDOWN.plusSeconds(1));
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, DAILY), 0, "daily has not started its new cycle");
                    helper.assertTrue(conditionHolds(helper, player, bundle, DAILY, OptionalInt.empty()),
                            "daily is false in its new cycle, though a cycle was completed");
                    advance(helper, step, 3);
                }
                case 3 -> {
                    // The revoke is its own step, so a retry does not check the condition after it has begun.
                    staff(helper, revokeDaily, () -> rig.service().revoke(player, DAILY, OptionalInt.empty()));
                    advance(helper, step, 4);
                }
                case 4 -> {
                    helper.assertTrue(!conditionHolds(helper, player, bundle, DAILY, OptionalInt.empty()),
                            "daily is true after a staff revoke");
                    AlinaTriggers.MANUAL.trigger(player, "ladder");
                    AlinaTriggers.MANUAL.trigger(player, "ladder");
                    advance(helper, step, 5);
                }
                case 5 -> {
                    helper.assertTrue(conditionHolds(helper, player, bundle, LADDER, OptionalInt.of(2)),
                            "tier 2 is false after both tiers are earned");
                    helper.assertTrue(conditionHolds(helper, player, bundle, LADDER, OptionalInt.of(1)),
                            "tier 1 is false after both tiers are earned");
                    rig.clock().advance(DAILY_COOLDOWN.plusSeconds(1));
                    advance(helper, step, 6);
                }
                default -> {
                    // The completed cycle has restarted: each tier was earned in a completed cycle, so the condition holds for it.
                    helper.assertValueEqual(earnedTiers(rig, player, LADDER), 0, "the ladder has not started its new cycle");
                    helper.assertTrue(conditionHolds(helper, player, bundle, LADDER, OptionalInt.of(2)),
                            "tier 2 is false in the new cycle, though a cycle was completed");
                    helper.assertTrue(conditionHolds(helper, player, bundle, LADDER, OptionalInt.of(1)),
                            "tier 1 is false in the new cycle, though a cycle was completed");
                    helper.assertTrue(conditionHolds(helper, player, bundle, LADDER, OptionalInt.empty()),
                            "the ladder is false in the new cycle, though a cycle was completed");
                    CONDITION_SERVICES.remove(player);
                }
            }
        });
    }

    /**
     * A staff revoke of tier 3 of a completed first cycle takes that tier's earn out of the earn count, so the condition no longer
     * reads the completed cycle as tier 3 earned. Tier 2 stays earned.
     */
    @GameTest(maxTicks = 900)
    public void aRevokedTierOfACompletedFirstCycleIsNotEarnedByTheCondition(GameTestHelper helper) {
        AchievementRig rig = AchievementRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        ContentBundle bundle = parseOrFail(helper, BUNDLE);
        conditionsRead(rig, player);
        Pending revokeTier = new Pending();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record has not loaded");
            switch (step.get()) {
                case 0 -> {
                    AlinaTriggers.MANUAL.trigger(player, "climb");
                    AlinaTriggers.MANUAL.trigger(player, "climb");
                    AlinaTriggers.MANUAL.trigger(player, "climb");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, CLIMBER), 3, "the three tiers after three events");
                    helper.assertValueEqual(earnCountOf(rig, player, CLIMBER), 3, "the earn count of the completed cycle");
                    helper.assertTrue(conditionHolds(helper, player, bundle, CLIMBER, OptionalInt.of(3)),
                            "tier 3 is false before the revoke");
                    advance(helper, step, 2);
                }
                case 2 -> {
                    // The revoke is its own step: a call that is refused, or not yet finished, is run again from here, and
                    // the checks of the step before must not run after it. Refused while an earn is pending or in the escrow.
                    staff(helper, revokeTier, () -> rig.service().revoke(player, CLIMBER, OptionalInt.of(3)));
                    advance(helper, step, 3);
                }
                default -> {
                    helper.assertValueEqual(earnedTiers(rig, player, CLIMBER), 2, "the tiers after revoking tier 3");
                    helper.assertValueEqual(earnCountOf(rig, player, CLIMBER), 2, "the earn count after revoking tier 3");
                    helper.assertValueEqual(storedState(rig, player, CLIMBER).map(AchievementState::tier).orElse(-1), 2,
                            "the stored tier after revoking tier 3");
                    helper.assertTrue(!conditionHolds(helper, player, bundle, CLIMBER, OptionalInt.of(3)),
                            "tier 3 is true after its revoke");
                    helper.assertTrue(conditionHolds(helper, player, bundle, CLIMBER, OptionalInt.of(2)),
                            "tier 2 is false after the revoke of tier 3");
                    CONDITION_SERVICES.remove(player);
                }
            }
        });
    }

    // Content activation.

    /**
     * Listeners hear a content activation before the service starts the cycles and earns of the new content, so an earn the
     * activation makes is told after it.
     */
    @GameTest(maxTicks = 600)
    public void contentActivationTellsTheListenersBeforeItEarns(GameTestHelper helper) {
        AchievementRig rig = AchievementRig.create(helper, BUNDLE_NEXT);
        ServerPlayer player = rig.newPlayer();
        List<String> told = new ArrayList<>();
        rig.service().addListener(new AchievementListener() {
            @Override
            public void contentActivated() {
                told.add("activated");
            }

            @Override
            public void earned(ServerPlayer earner, Identifier achievement, int tier) {
                told.add("earned " + achievement);
            }
        });
        CompletableFuture<Void> seeded = rig.storage().writeCriterionProgress(player.getUUID(),
                Map.of(new CriterionKey(SIGNED_ON, "met"), 1), rig.clock().instant());
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(seeded.isDone(), "the counts are not in storage yet");
                    rig.service().join(player);
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertTrue(loaded(rig, player), "the record has not loaded");
                    helper.assertTrue(rig.service().view(player).orElseThrow().achievements().get(SIGNED_ON) == null,
                            "signed_on is in the content before its activation");
                    rig.useContent(BUNDLE);
                    rig.service().contentActivated();
                    advance(helper, step, 2);
                }
                default -> {
                    helper.assertValueEqual(earnedTiers(rig, player, SIGNED_ON), 1, "signed_on after the activation");
                    helper.assertTrue(told.contains("earned " + SIGNED_ON), "the earn was not told: " + told);
                    helper.assertTrue(told.indexOf("activated") >= 0
                            && told.indexOf("activated") < told.indexOf("earned " + SIGNED_ON),
                            "the earn was told before the activation: " + told);
                }
            }
        });
    }

    /**
     * A tiered achievement earned in full before its content gained a repeat is not earned again by the activation, and its
     * next cycle starts a cooldown after its last earn.
     */
    @GameTest(maxTicks = 600)
    public void aTieredRepeatAddedAfterItWasEarnedStartsItsNextCycle(GameTestHelper helper) {
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
                    helper.assertValueEqual(earnedTiers(rig, player, CLIMBER), 1, "the first tier");
                    AlinaTriggers.MANUAL.trigger(player, "climb");
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, CLIMBER), 2, "the second tier");
                    AlinaTriggers.MANUAL.trigger(player, "climb");
                    advance(helper, step, 3);
                }
                case 3 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, CLIMBER), 3, "the last tier");
                    helper.assertTrue(rig.settlement().pendingFor(player).isEmpty(), "the rewards are still in the escrow");
                    helper.assertValueEqual(storedState(rig, player, CLIMBER).map(AchievementState::tier).orElse(-1), 3,
                            "the stored tier before the repeat");
                    rig.useContent(BUNDLE_REPEAT);
                    rig.service().contentActivated();
                    advance(helper, step, 4);
                }
                case 4 -> {
                    helper.assertValueEqual(earnCountOf(rig, player, CLIMBER), 3, "the earn count after the activation");
                    helper.assertValueEqual(earnedTiers(rig, player, CLIMBER), 3, "the climber tiers after the activation");
                    rig.clock().advance(DAILY_COOLDOWN.plusSeconds(1));
                    advance(helper, step, 5);
                }
                case 5 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, CLIMBER), 0, "the new cycle has not started");
                    helper.assertValueEqual(earnCountOf(rig, player, CLIMBER), 3, "the earn count after the new cycle");
                    AlinaTriggers.MANUAL.trigger(player, "climb");
                    advance(helper, step, 6);
                }
                default -> {
                    helper.assertValueEqual(earnedTiers(rig, player, CLIMBER), 1, "the first tier of the new cycle");
                    helper.assertValueEqual(earnCountOf(rig, player, CLIMBER), 4, "the earn count after the new cycle's first tier");
                }
            }
        });
    }

    /**
     * An untiered achievement earned while it had no repeat is complete in its current cycle once its content gains one: it is
     * not earned again at once, and its next cycle starts once the cooldown has passed from its last earn.
     */
    @GameTest(maxTicks = 900)
    public void anUntieredEarnBeforeARepeatIsCompleteUntilTheCooldownEnds(GameTestHelper helper) {
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
                case 1 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, QUIET_NOTE), 1, "quiet_note before the repeat");
                    helper.assertValueEqual(earnCountOf(rig, player, QUIET_NOTE), 1, "the earn count before the repeat");
                    helper.assertTrue(availableAtOf(rig, player, QUIET_NOTE).isEmpty(), "quiet_note has an availableAt before the repeat");
                    rig.useContent(BUNDLE_REPEAT);
                    rig.service().contentActivated();
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, QUIET_NOTE), 1, "quiet_note after the repeat was added");
                    helper.assertValueEqual(earnCountOf(rig, player, QUIET_NOTE), 1, "quiet_note earned again at once");
                    helper.assertTrue(availableAtOf(rig, player, QUIET_NOTE).equals(Optional.of(rig.clock().instant()
                            .plus(DAILY_COOLDOWN))), "the cooldown does not run from the last earn: " + availableAtOf(rig, player,
                            QUIET_NOTE));
                    // An event in the cooldown counts for nothing: the cycle is complete.
                    AlinaTriggers.MANUAL.trigger(player, "note");
                    rig.clock().advance(DAILY_COOLDOWN.minusMinutes(1));
                    advance(helper, step, 3);
                }
                case 3 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, QUIET_NOTE), 1, "quiet_note during its cooldown");
                    helper.assertValueEqual(earnCountOf(rig, player, QUIET_NOTE), 1, "quiet_note earned again in its cooldown");
                    rig.clock().advance(Duration.ofMinutes(1));
                    advance(helper, step, 4);
                }
                case 4 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, QUIET_NOTE), 0, "quiet_note after its cooldown");
                    helper.assertValueEqual(viewCount(rig, player, QUIET_NOTE, "note"), 0, "the count of the new cycle");
                    helper.assertValueEqual(earnCountOf(rig, player, QUIET_NOTE), 1, "the earn count kept by the new cycle");
                    AlinaTriggers.MANUAL.trigger(player, "note");
                    advance(helper, step, 5);
                }
                default -> {
                    helper.assertValueEqual(earnedTiers(rig, player, QUIET_NOTE), 1, "quiet_note in the new cycle");
                    helper.assertValueEqual(earnCountOf(rig, player, QUIET_NOTE), 2, "the earn count after the new cycle's earn");
                }
            }
        });
    }

    /**
     * An untiered earn stores tier 1, and the cycle start that follows stores tier 0 with the earn count kept. The stored tier is
     * what says an achievement without tiers is earned in its current cycle.
     */
    @GameTest(maxTicks = 600)
    public void anUntieredEarnStoresTierOneAndACycleStartStoresTierZero(GameTestHelper helper) {
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
                    helper.assertValueEqual(earnedTiers(rig, player, DAILY), 1, "the daily before its cooldown ends");
                    helper.assertValueEqual(storedTier(rig, player, DAILY), 1, "the stored tier of an untiered earn");
                    rig.clock().advance(DAILY_COOLDOWN.plusSeconds(1));
                    advance(helper, step, 2);
                }
                default -> {
                    helper.assertValueEqual(earnedTiers(rig, player, DAILY), 0, "the daily's new cycle has not started");
                    helper.assertValueEqual(storedTier(rig, player, DAILY), 0, "the stored tier of a new cycle");
                    helper.assertValueEqual(storedState(rig, player, DAILY).map(AchievementState::earnCount).orElse(0), 1,
                            "the earn count kept by the new cycle");
                }
            }
        });
    }

    /**
     * Storage applies a reward and its answer is lost: the player leaves before the answer reaches the escrow, so the escrow
     * keeps the reward unapplied. On the next join settlement applies it again, and storage answers that it was applied before.
     * The earn is counted once, in the record and in storage, and the reward is delivered once.
     */
    @GameTest(maxTicks = 900)
    public void aRewardAppliedAgainAfterItsAnswerWasLostIsNotCountedTwice(GameTestHelper helper) {
        AtomicReference<CompletableFuture<Void>> answer = new AtomicReference<>();
        AchievementRig rig = AchievementRig.create(helper, BUNDLE, delegate -> answerHeldOnce(delegate, answer));
        ServerPlayer player = rig.joined();
        AtomicLong leftAt = new AtomicLong();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(loaded(rig, player), "the record has not loaded");
                    AlinaTriggers.MANUAL.trigger(player, "signed");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertTrue(answer.get() != null, "storage was not asked to apply the reward");
                    rig.goOffline(player);
                    rig.service().leave(player);
                    answer.get().complete(null);
                    leftAt.set(rig.ticks() + SETTLE_TICKS);
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertTrue(rig.ticks() >= leftAt.get(), "waiting for the lost answer to be dropped");
                    helper.assertValueEqual(rig.settlement().pendingFor(player).size(), 1, "the reward is not in the escrow");
                    helper.assertValueEqual(count(player, Items.EMERALD), 0, "the reward was delivered before the join");
                    rig.comeOnline(player);
                    rig.service().join(player);
                    advance(helper, step, 3);
                }
                default -> {
                    helper.assertTrue(loaded(rig, player), "the record has not loaded");
                    helper.assertTrue(rig.settlement().pendingFor(player).isEmpty(), "the reward is still in the escrow");
                    helper.assertValueEqual(count(player, Items.EMERALD), 5, "the reward, once");
                    helper.assertValueEqual(earnCountOf(rig, player, SIGNED_ON), 1, "the earn count in the record");
                    helper.assertValueEqual(storedState(rig, player, SIGNED_ON).map(AchievementState::earnCount).orElse(0), 1,
                            "the earn count in storage");
                }
            }
        });
    }

    /**
     * A repeatable's new cycle, with seven of its ten counts, meets the count an activation lowers it to: the cycle is earned and
     * its counts are kept, instead of being reset as if the earn were a pre-repeat one.
     */
    @GameTest(maxTicks = 900)
    public void aNewCycleThatAnActivationLowersTheCountOfIsEarnedAndKeepsItsCounts(GameTestHelper helper) {
        AchievementRig rig = AchievementRig.create(helper, DAILY_TEN);
        ServerPlayer player = rig.joined();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record has not loaded");
            switch (step.get()) {
                case 0 -> {
                    triggerDaily(player, 10);
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, DAILY), 1, "the daily, earned at its count");
                    rig.clock().advance(DAILY_COOLDOWN.plusSeconds(1));
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, DAILY), 0, "the new cycle has not started");
                    triggerDaily(player, 7);
                    advance(helper, step, 3);
                }
                case 3 -> {
                    helper.assertValueEqual(viewCount(rig, player, DAILY, "daily"), 7, "the count of the new cycle");
                    rig.useContent(DAILY_FIVE);
                    rig.service().contentActivated();
                    advance(helper, step, 4);
                }
                default -> {
                    helper.assertValueEqual(earnedTiers(rig, player, DAILY), 1, "the daily after the activation");
                    helper.assertValueEqual(earnCountOf(rig, player, DAILY), 2, "the earn count after the new earn");
                    helper.assertValueEqual(viewCount(rig, player, DAILY, "daily"), 7, "the counts of the new cycle were reset");
                    helper.assertValueEqual(storedState(rig, player, DAILY).map(AchievementState::earnCount).orElse(0), 2,
                            "the earn count in storage");
                }
            }
        });
    }

    /**
     * The same as above, with the player offline during the activation: the earn is made when the player joins again, from the
     * counts the record wrote when the player left.
     */
    @GameTest(maxTicks = 900)
    public void aNewCycleThatAnActivationLowersTheCountOfIsEarnedWhenThePlayerJoinsAgain(GameTestHelper helper) {
        AchievementRig rig = AchievementRig.create(helper, DAILY_TEN);
        ServerPlayer player = rig.joined();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(loaded(rig, player), "the record has not loaded");
                    triggerDaily(player, 10);
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, DAILY), 1, "the daily, earned at its count");
                    rig.clock().advance(DAILY_COOLDOWN.plusSeconds(1));
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, DAILY), 0, "the new cycle has not started");
                    triggerDaily(player, 7);
                    advance(helper, step, 3);
                }
                case 3 -> {
                    helper.assertValueEqual(viewCount(rig, player, DAILY, "daily"), 7, "the count of the new cycle");
                    rig.goOffline(player);
                    rig.service().leave(player);
                    rig.useContent(DAILY_FIVE);
                    rig.service().contentActivated();
                    advance(helper, step, 4);
                }
                case 4 -> {
                    rig.comeOnline(player);
                    rig.service().join(player);
                    advance(helper, step, 5);
                }
                default -> {
                    helper.assertTrue(loaded(rig, player), "the record has not loaded");
                    helper.assertValueEqual(earnedTiers(rig, player, DAILY), 1, "the daily after the join");
                    helper.assertValueEqual(earnCountOf(rig, player, DAILY), 2, "the earn count after the join");
                    helper.assertValueEqual(viewCount(rig, player, DAILY, "daily"), 7, "the counts of the new cycle were reset");
                    helper.assertValueEqual(storedCount(rig, player, DAILY, "daily"), 7, "the counts in storage");
                }
            }
        });
    }

    /**
     * An earn made before its achievement gained a repeat and a higher count stays earned until its cooldown ends from that earn:
     * an event in the cooldown counts for nothing, and the next cycle then starts with no counts.
     */
    @GameTest(maxTicks = 900)
    public void aPastEarnOfAnAchievementThatGainsARepeatStaysEarnedUntilItsCooldownEnds(GameTestHelper helper) {
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
                case 1 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, QUIET_NOTE), 1, "quiet_note before the activation");
                    rig.useContent(QUIET_TWICE);
                    rig.service().contentActivated();
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, QUIET_NOTE), 1, "quiet_note after the activation");
                    helper.assertValueEqual(earnCountOf(rig, player, QUIET_NOTE), 1, "the earn count after the activation");
                    helper.assertTrue(availableAtOf(rig, player, QUIET_NOTE).isPresent(), "quiet_note has no availableAt");
                    AlinaTriggers.MANUAL.trigger(player, "note");
                    rig.clock().advance(DAILY_COOLDOWN.minusMinutes(1));
                    advance(helper, step, 3);
                }
                case 3 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, QUIET_NOTE), 1, "quiet_note before its cooldown ends");
                    helper.assertValueEqual(earnCountOf(rig, player, QUIET_NOTE), 1, "quiet_note earned again before its cooldown");
                    rig.clock().advance(Duration.ofMinutes(1));
                    advance(helper, step, 4);
                }
                default -> {
                    helper.assertValueEqual(earnedTiers(rig, player, QUIET_NOTE), 0, "quiet_note after its cooldown");
                    helper.assertValueEqual(viewCount(rig, player, QUIET_NOTE, "note"), 0, "the count of the new cycle");
                    helper.assertValueEqual(earnCountOf(rig, player, QUIET_NOTE), 1, "the earn count kept by the new cycle");
                }
            }
        });
    }

    /**
     * A later tier's reward waits until the earlier tier's direct write is durable. The earlier write fails once; the player reaches
     * the second tier and leaves before the retry, then joins again. Each tier is earned once and the count is two.
     */
    @GameTest(maxTicks = 1200)
    public void aLaterTierRewardWaitsForAnEarlierDirectWriteThatFailed(GameTestHelper helper) {
        AtomicBoolean armed = new AtomicBoolean(true);
        AtomicBoolean fired = new AtomicBoolean();
        AchievementRig rig = AchievementRig.create(helper, BUNDLE,
                delegate -> failingWhileArmed(delegate, "writeAchievement", armed, fired));
        ServerPlayer player = rig.joined();
        List<Integer> told = new ArrayList<>();
        rig.service().addListener(new AchievementListener() {
            @Override
            public void earned(ServerPlayer earner, Identifier achievement, int tier) {
                if (achievement.equals(CLIMBER)) {
                    told.add(tier);
                }
            }
        });
        AtomicLong waitUntil = new AtomicLong();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(loaded(rig, player), "the record has not loaded");
                    AlinaTriggers.MANUAL.trigger(player, "climb");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertTrue(fired.get(), "the first tier's write has not failed yet");
                    helper.assertValueEqual(earnedTiers(rig, player, CLIMBER), 1, "the first tier");
                    AlinaTriggers.MANUAL.trigger(player, "climb");
                    waitUntil.set(rig.ticks() + SETTLE_TICKS);
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertTrue(rig.ticks() >= waitUntil.get(), "waiting for the second tier to be held");
                    helper.assertValueEqual(earnedTiers(rig, player, CLIMBER), 1, "the second tier while the first waits");
                    helper.assertTrue(rig.settlement().pendingFor(player).isEmpty(), "the second tier's reward was begun");
                    rig.goOffline(player);
                    rig.service().leave(player);
                    rig.comeOnline(player);
                    rig.service().join(player);
                    advance(helper, step, 3);
                }
                default -> {
                    helper.assertTrue(loaded(rig, player), "the record has not loaded");
                    helper.assertValueEqual(earnedTiers(rig, player, CLIMBER), 2, "the second tier after the rejoin");
                    helper.assertValueEqual(told, List.of(1, 2), "the earns told");
                    helper.assertValueEqual(earnCountOf(rig, player, CLIMBER), 2, "the earn count");
                    helper.assertValueEqual(storedState(rig, player, CLIMBER).map(AchievementState::earnCount).orElse(0), 2,
                            "the earn count in storage");
                }
            }
        });
    }

    /**
     * An operation of another world in the escrow, for the same achievement and tier, does not stop the achievement's own reward.
     * The reward is begun and delivered, and the other world's operation stays in the escrow for reconciliation.
     */
    @GameTest(maxTicks = 900)
    public void aForeignWorldRewardDoesNotBlockTheSameEarn(GameTestHelper helper) {
        AchievementRig rig = AchievementRig.create(helper, BUNDLE);
        ServerPlayer player = rig.newPlayer();
        MinecraftServer server = helper.getLevel().getServer();
        SettlementService elsewhere = new SettlementService(server, rig.storage(), UUID.randomUUID(), () -> List.of(player),
                (who, expected) -> PlayerDataSaves.saveAndVerify(server, who, expected));
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(elsewhere.begin(player, foreignRetried()).isPresent(), "the other world's operation was refused");
                    rig.service().join(player);
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertTrue(loaded(rig, player), "the record has not loaded");
                    AlinaTriggers.MANUAL.trigger(player, "retry");
                    advance(helper, step, 2);
                }
                default -> {
                    helper.assertValueEqual(earnedTiers(rig, player, RETRIED), 1, "retried, earned");
                    helper.assertValueEqual(count(player, Items.EMERALD), 5, "the reward, once");
                    helper.assertValueEqual(rig.settlement().pendingFor(player).size(), 1, "the escrow's operations");
                    helper.assertTrue(!rig.settlement().pendingFor(player).get(0).worldId().equals(rig.settlement().worldId()),
                            "the operation left in the escrow is not the other world's");
                }
            }
        });
    }

    /**
     * A tiered revoke whose count write succeeds and whose state write fails leaves storage's counts lowered. The record takes the
     * lowered counts too, keeps its tiers, and the completion says the counts were lowered and the tiers were not.
     */
    @GameTest(maxTicks = 900)
    public void aRevokeThatLowersCountsAndThenFailsSaysTheCountsWereLowered(GameTestHelper helper) {
        AtomicBoolean armed = new AtomicBoolean();
        AtomicBoolean fired = new AtomicBoolean();
        AchievementRig rig = AchievementRig.create(helper, BUNDLE, delegate -> failingTierOneWrite(delegate, armed, fired));
        ServerPlayer player = rig.joined();
        AtomicReference<CompletableFuture<Optional<String>>> revoke = new AtomicReference<>();
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
                    helper.assertValueEqual(earnedTiers(rig, player, CLIMBER), 1, "the first tier");
                    AlinaTriggers.MANUAL.trigger(player, "climb");
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, CLIMBER), 2, "the second tier");
                    AlinaTriggers.MANUAL.trigger(player, "climb");
                    advance(helper, step, 3);
                }
                case 3 -> {
                    helper.assertValueEqual(earnedTiers(rig, player, CLIMBER), 3, "the last tier");
                    helper.assertTrue(rig.settlement().pendingFor(player).isEmpty(), "a reward is still in the escrow");
                    // The last tier's own write must land before the failure is armed, so the failure is the revoke's.
                    helper.assertValueEqual(storedTier(rig, player, CLIMBER), 3, "the last tier in storage");
                    armed.set(true);
                    CompletableFuture<Optional<String>> started = rig.service().revoke(player, CLIMBER, OptionalInt.of(2));
                    helper.assertTrue(!started.isDone(), "the revoke was refused, tried again: "
                            + (started.isDone() ? started.join() : ""));
                    revoke.set(started);
                    advance(helper, step, 4);
                }
                case 4 -> {
                    String text = refusalOf(helper, revoke.get()).get();
                    helper.assertTrue(fired.get() && text.contains("counts were lowered"),
                            "the revoke did not say the counts were lowered: " + text);
                    helper.assertValueEqual(viewCount(rig, player, CLIMBER, "climb"), 1, "the record's count");
                    helper.assertValueEqual(earnedTiers(rig, player, CLIMBER), 3, "the tiers after the failed revoke");
                    rig.goOffline(player);
                    rig.service().leave(player);
                    rig.comeOnline(player);
                    rig.service().join(player);
                    advance(helper, step, 5);
                }
                default -> {
                    helper.assertValueEqual(viewCount(rig, player, CLIMBER, "climb"), 1, "the count after a rejoin");
                    helper.assertValueEqual(earnedTiers(rig, player, CLIMBER), 3, "the tiers after a rejoin");
                    helper.assertValueEqual(storedCount(rig, player, CLIMBER, "climb"), 1, "the count in storage");
                }
            }
        });
    }

    // Helpers.

    /** Triggers the daily achievement's criterion {@code times} times, one event each. */
    private static void triggerDaily(ServerPlayer player, int times) {
        for (int event = 0; event < times; event++) {
            AlinaTriggers.MANUAL.trigger(player, "daily");
        }
    }

    /** The stored tier of an achievement, or -1 when storage has no state for it. */
    private static int storedTier(AchievementRig rig, ServerPlayer player, Identifier id) {
        return storedState(rig, player, id).map(AchievementState::tier).orElse(-1);
    }

    /** An operation of another world for the achievement {@link #RETRIED}, tier 1: the same kind, subject and tier as its earn. */
    private static SettlementRequest foreignRetried() {
        return new SettlementRequest(
                OperationKind.ACHIEVEMENT_REWARD,
                RETRIED,
                1,
                AchievementRig.REVISION,
                Optional.empty(),
                0,
                new OperationEffect.AchievementEarned(RETRIED, 1, Optional.empty()),
                player -> List.of(),
                Optional.empty(),
                Optional.empty());
    }

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

    private static Optional<Instant> availableAtOf(AchievementRig rig, ServerPlayer player, Identifier id) {
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

    private static List<OperationStep> operations(AchievementRig rig) {
        return rig.storage().listOperations(EnumSet.allOf(OperationStep.class), 10).join().stream()
                .map(stored -> stored.step())
                .toList();
    }

    /** A staff call of a step, started once: the call is refused at once while a record loads or a write is pending. */
    private static final class Pending {
        private CompletableFuture<Optional<String>> call;
    }

    /**
     * Starts a staff call once and waits for it. A call the service refuses at once is run again on the next tick, because
     * the step's check is not met yet; a call that completes later is waited for. The step passes when the call was accepted.
     */
    private static void staff(GameTestHelper helper, Pending pending, Supplier<CompletableFuture<Optional<String>>> start) {
        if (pending.call == null) {
            CompletableFuture<Optional<String>> started = start.get();
            if (started.isDone() && !started.isCompletedExceptionally() && started.join().isPresent()) {
                helper.assertTrue(false, "refused, tried again: " + started.join().get());
            }
            pending.call = started;
        }
        helper.assertTrue(finished(helper, pending.call).isEmpty(), "the staff call was refused");
    }

    /** The outcome of a finished staff call. A call that has not finished, or failed, fails the attempt. */
    private static Optional<String> finished(GameTestHelper helper, CompletableFuture<Optional<String>> call) {
        helper.assertTrue(call.isDone(), "the staff call has not finished");
        helper.assertTrue(!call.isCompletedExceptionally(), "the staff call failed");
        return call.join();
    }

    /** The refusal a finished staff call gave. A call that was accepted fails the attempt. */
    private static Optional<String> refusalOf(GameTestHelper helper, CompletableFuture<Optional<String>> call) {
        Optional<String> outcome = finished(helper, call);
        helper.assertTrue(outcome.isPresent(), "the call was accepted, but a refusal was expected");
        return outcome;
    }

    /**
     * An operation of the achievement's kind, subject and tier. While it is in the escrow, the achievement's own begin is
     * refused. Its effect names another achievement, so the test's achievement keeps its own stored state.
     *
     * <p>Begin it only once the record has loaded. A record that loads with it in the escrow takes it for the achievement's own
     * earn being settled, as the service's contract says of every escrowed operation at load: the achievement shows as earned,
     * its criteria are not watched, and no earn or begin of it is made. When the blocker settles, its effect is counted for the
     * other achievement and the test's achievement is left unearned.
     */
    private static SettlementRequest blocker() {
        return new SettlementRequest(
                OperationKind.ACHIEVEMENT_REWARD,
                RETRIED,
                1,
                AchievementRig.REVISION,
                Optional.empty(),
                0,
                new OperationEffect.AchievementEarned(BLOCKER, 1, Optional.empty()),
                player -> List.of(),
                Optional.empty(),
                Optional.empty());
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

    /** Fills the main inventory with stone, so a reward's stacks have nowhere to go. */
    private static void fillMainInventory(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
            inventory.setItem(slot, new ItemStack(Items.STONE, 64));
        }
    }

    private static void emptyMainInventory(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
            inventory.setItem(slot, ItemStack.EMPTY);
        }
    }

    /**
     * Storage whose {@code applyOperation} calls for the held operations reach {@code delegate} only after {@code release}
     * completes, so storage has not applied the operation, and the escrow holds it unapplied, until the test releases it.
     * Other calls reach {@code delegate} at once.
     */
    @SuppressWarnings("unchecked")
    private static AlinaStorage holdingApplies(AlinaStorage delegate, CompletableFuture<Void> release,
            Predicate<Operation> held) {
        return (AlinaStorage) Proxy.newProxyInstance(AlinaStorage.class.getClassLoader(),
                new Class<?>[] {AlinaStorage.class},
                (proxy, called, args) -> {
                    if (!called.getName().equals("applyOperation") || !held.test((Operation) args[0])) {
                        return invoke(delegate, called, args);
                    }
                    return release.thenCompose(ignored -> {
                        try {
                            return (CompletableFuture<ApplyResult>) invoke(delegate, called, args);
                        } catch (Throwable failure) {
                            return CompletableFuture.failedFuture(failure);
                        }
                    });
                });
    }

    /**
     * Storage whose first {@code writeCriterionProgress} call returns {@code held}, which the test completes, and which never
     * reaches {@code delegate}. Other calls reach {@code delegate} at once.
     */
    private static AlinaStorage holdingFirstCountWrite(AlinaStorage delegate, AtomicReference<CompletableFuture<Void>> held) {
        return intercepting(delegate, (called, args) -> {
            if (!called.equals("writeCriterionProgress") || held.get() != null) {
                return null;
            }
            CompletableFuture<Void> gate = new CompletableFuture<>();
            held.set(gate);
            return gate;
        });
    }

    /**
     * Storage whose first {@code writeCriterionProgress} call after {@code armed} is set returns {@code held}, which the test
     * completes, and which never reaches {@code delegate}. Other calls reach {@code delegate} at once.
     */
    private static AlinaStorage holdingArmedCountWrite(AlinaStorage delegate, AtomicBoolean armed,
            AtomicReference<CompletableFuture<Void>> held) {
        return intercepting(delegate, (called, args) -> {
            if (!called.equals("writeCriterionProgress") || !armed.get() || held.get() != null) {
                return null;
            }
            CompletableFuture<Void> gate = new CompletableFuture<>();
            held.set(gate);
            return gate;
        });
    }

    /**
     * Storage whose first call of {@code method} returns {@code held}, which the test completes, and which never reaches
     * {@code delegate}. Other calls reach {@code delegate} at once.
     */
    private static AlinaStorage holdingFirstCall(AlinaStorage delegate, String method, AtomicReference<CompletableFuture<Void>> held) {
        return intercepting(delegate, (called, args) -> {
            if (!called.equals(method) || held.get() != null) {
                return null;
            }
            CompletableFuture<Void> gate = new CompletableFuture<>();
            held.set(gate);
            return gate;
        });
    }

    /**
     * Storage whose first {@code applyOperation} call reaches {@code delegate} at once, so storage applies the operation, but whose
     * answer is held: the answer completes when the test completes the gate in {@code held}. Later calls reach {@code delegate}.
     */
    @SuppressWarnings("unchecked")
    private static AlinaStorage answerHeldOnce(AlinaStorage delegate, AtomicReference<CompletableFuture<Void>> held) {
        AtomicBoolean first = new AtomicBoolean(true);
        return (AlinaStorage) Proxy.newProxyInstance(AlinaStorage.class.getClassLoader(),
                new Class<?>[] {AlinaStorage.class},
                (proxy, called, args) -> {
                    Object result = invoke(delegate, called, args);
                    if (!called.getName().equals("applyOperation") || !first.compareAndSet(true, false)) {
                        return result;
                    }
                    CompletableFuture<Void> gate = new CompletableFuture<>();
                    held.set(gate);
                    return ((CompletableFuture<ApplyResult>) result)
                            .thenCompose(answer -> gate.thenApply(ignored -> answer));
                });
    }

    /**
     * Storage that fails the first {@code method} call made after {@code armed} is set, with {@code fired} set when it does, and
     * passes every other call on.
     */
    private static AlinaStorage failingWhileArmed(AlinaStorage delegate, String method, AtomicBoolean armed, AtomicBoolean fired) {
        return intercepting(delegate, (called, args) -> called.equals(method) && armed.get() && fired.compareAndSet(false, true)
                ? CompletableFuture.failedFuture(new IllegalStateException("injected failure of " + method))
                : null);
    }

    /**
     * Storage that fails the first {@code writeAchievement} made after {@code armed} is set whose state has tier 1, with
     * {@code fired} set when it does. A tiered revoke of tier 2 of a three-tier achievement writes that state; no earn writes it.
     */
    private static AlinaStorage failingTierOneWrite(AlinaStorage delegate, AtomicBoolean armed, AtomicBoolean fired) {
        return intercepting(delegate, (called, args) -> called.equals("writeAchievement") && armed.get()
                && ((AchievementState) args[1]).tier() == 1 && fired.compareAndSet(false, true)
                ? CompletableFuture.failedFuture(new IllegalStateException("injected failure of writeAchievement"))
                : null);
    }

    /** Storage that fails the first call of {@code method} with an injected failure, and passes every other call on. */
    private static AlinaStorage failingOnce(AlinaStorage delegate, String method, AtomicBoolean fired) {
        return intercepting(delegate, (called, args) -> called.equals(method) && fired.compareAndSet(false, true)
                ? CompletableFuture.failedFuture(new IllegalStateException("injected failure of " + method))
                : null);
    }

    /** Storage whose {@code loadCharacter} calls set {@code completed} when they complete. Every call reaches {@code delegate}. */
    @SuppressWarnings("unchecked")
    private static AlinaStorage observingLoads(AlinaStorage delegate, AtomicBoolean completed) {
        return (AlinaStorage) Proxy.newProxyInstance(AlinaStorage.class.getClassLoader(),
                new Class<?>[] {AlinaStorage.class},
                (proxy, called, args) -> {
                    Object result = invoke(delegate, called, args);
                    if (called.getName().equals("loadCharacter")) {
                        ((CompletableFuture<CharacterState>) result).whenComplete((state, failure) -> completed.set(true));
                    }
                    return result;
                });
    }

    /**
     * Storage whose {@code loadCharacter} calls complete with the delegate's snapshot only once the gate that was current when
     * the call was made completes. The snapshot is read at once, as a storage read is.
     */
    @SuppressWarnings("unchecked")
    private static AlinaStorage gatedLoads(AlinaStorage delegate, AtomicReference<CompletableFuture<Void>> gate) {
        return (AlinaStorage) Proxy.newProxyInstance(AlinaStorage.class.getClassLoader(),
                new Class<?>[] {AlinaStorage.class},
                (proxy, called, args) -> {
                    CompletableFuture<Void> hold = gate.get();
                    Object result = invoke(delegate, called, args);
                    if (!called.getName().equals("loadCharacter")) {
                        return result;
                    }
                    return ((CompletableFuture<CharacterState>) result)
                            .thenCompose(state -> hold.thenApply(ignored -> state));
                });
    }

    /**
     * Storage whose calls go through {@code interceptor}: a non-null answer is returned in place of the call, and null passes
     * the call on to {@code delegate}.
     */
    private static AlinaStorage intercepting(AlinaStorage delegate,
            BiFunction<String, Object[], CompletableFuture<?>> interceptor) {
        return (AlinaStorage) Proxy.newProxyInstance(AlinaStorage.class.getClassLoader(),
                new Class<?>[] {AlinaStorage.class},
                (proxy, called, args) -> {
                    CompletableFuture<?> answer = interceptor.apply(called.getName(), args);
                    return answer != null ? answer : invoke(delegate, called, args);
                });
    }

    private static Object invoke(AlinaStorage delegate, Method called, Object[] args) throws Throwable {
        try {
            return called.invoke(delegate, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    /**
     * The services the condition tests read, by player. {@link AchievementModule#conditionViews} is one static, and GameTests
     * run together, so a test that reset it would leave another test's condition reading the server's own service. Players are
     * keyed by identity, because two mock players can share an id.
     */
    private static final Map<ServerPlayer, AchievementService> CONDITION_SERVICES =
            Collections.synchronizedMap(new IdentityHashMap<>());

    /** Points the condition at the rig's service for {@code player}. A test removes the player from {@link #CONDITION_SERVICES}. */
    private static void conditionsRead(AchievementRig rig, ServerPlayer player) {
        CONDITION_SERVICES.put(player, rig.service());
        AchievementModule.conditionViews(who -> Optional.ofNullable(CONDITION_SERVICES.get(who))
                .flatMap(service -> service.view(who)));
    }

    /** The condition of a fixture's achievement, parsed through haruhi and evaluated for the player. */
    private static boolean conditionHolds(GameTestHelper helper, ServerPlayer player, ContentBundle bundle, Identifier achievement,
            OptionalInt tier) {
        String args = tier.isPresent()
                ? "{\"achievement\": \"" + achievement + "\", \"tier\": " + tier.getAsInt() + "}"
                : "{\"achievement\": \"" + achievement + "\"}";
        DialogueCondition condition = condition(helper, "alina:achievement", args,
                new ParseContext(bundle, GRAPH, "node"));
        return condition.test(new Ctx(player, bundle));
    }

    private static DialogueCondition condition(GameTestHelper helper, String type, String args, ParseContext context) {
        Optional<ConditionType> conditionType = DialogueRegistries.condition(Identifier.parse(type));
        helper.assertTrue(conditionType.isPresent(), type + " is not a registered condition");
        DataResult<DialogueCondition> parsed = conditionType.get()
                .parse(JsonParser.parseString(args).getAsJsonObject(), context);
        helper.assertTrue(parsed.result().isPresent(), type + " " + args + " should parse: " + errorOf(parsed));
        return parsed.result().get();
    }

    /** The graph a condition's context names. The achievement condition reads no graph. */
    private static final Identifier GRAPH = Alina.id("test/graph");

    /** The context a condition sees, for a player the test drives directly. */
    private record Ctx(ServerPlayer player, ContentBundle content) implements DialogueContext {
        @Override
        public Optional<Entity> interlocutor() {
            return Optional.empty();
        }

        @Override
        public Identifier graphId() {
            return GRAPH;
        }

        @Override
        public String nodeId() {
            return "node";
        }

        @Override
        public DialogueFlags flags() {
            throw new UnsupportedOperationException("the achievement condition reads no flags");
        }

        @Override
        public int visits() {
            return 0;
        }

        @Override
        public void goTo(String nodeId) {
            throw new UnsupportedOperationException("the achievement condition moves no node");
        }

        @Override
        public void end() {
            throw new UnsupportedOperationException("the achievement condition ends no conversation");
        }

        @Override
        public void openGraph(Identifier graphId) {
            throw new UnsupportedOperationException("the achievement condition opens no graph");
        }
    }

    /** The operation of signed_on's reward, as a settlement request: five emeralds and two reputation. */
    private static SettlementRequest signedReward() {
        return new SettlementRequest(
                OperationKind.ACHIEVEMENT_REWARD,
                SIGNED_ON,
                1,
                AchievementRig.REVISION,
                Optional.of(Alina.id("test/five_emeralds")),
                2,
                new OperationEffect.AchievementEarned(SIGNED_ON, 1, Optional.empty()),
                player -> List.of(),
                Optional.empty(),
                Optional.empty());
    }

    /** The live fixture, parsed as the server would parse it. */
    private static ContentBundle parseOrFail(GameTestHelper helper, String fixture) {
        DataResult<ContentBundle> result = parse(helper, fixture);
        helper.assertTrue(result.result().isPresent(), "the fixture " + fixture + " was refused:\n" + errorOf(result));
        return result.result().get();
    }

    /** Parses a fixture through haruhi, as content is parsed. */
    private static DataResult<ContentBundle> parse(GameTestHelper helper, String fixture) {
        return HaruhiServices.content().parseAndValidate(AchievementRig.fixture(helper, fixture));
    }

    private static String errorOf(DataResult<?> result) {
        return result.error().map(error -> error.message()).orElse("");
    }
}
