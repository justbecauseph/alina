package town.lampas.alina.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.advancements.triggers.CriterionTrigger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import town.lampas.alina.Alina;
import town.lampas.alina.content.CriterionSpec;
import town.lampas.alina.criteria.AlinaTriggers;
import town.lampas.alina.criteria.CriterionWatchers;
import town.lampas.alina.guild.BoardView;
import town.lampas.alina.guild.CommissionView;
import town.lampas.alina.guild.GuildService;
import town.lampas.alina.guild.GuildView;
import town.lampas.alina.guild.Refusal;
import town.lampas.alina.guild.Standing;
import town.lampas.alina.storage.AlinaStorage;
import town.lampas.alina.storage.CommissionState;
import town.lampas.alina.storage.CommissionStatus;
import town.lampas.alina.storage.InMemoryStorage;

/**
 * Server GameTests owned by the Guild service (ALINA-PLAN §8.1 to §8.3 and §13). Each test builds a {@link GuildRig}: a
 * service over its own in-memory storage and settlement, a clock the test moves, and players outside the server's list.
 * The test ticks the rig once per game tick. Storage and settlement complete on storage threads and return to the server
 * thread on a later tick, so a test waits with {@code succeedWhen}: a check that is not met yet throws and is run again.
 *
 * <p>Turn-ins of item proofs use the planner and the reward, which the fixtures name, so they need the item planner and
 * {@code BoardDay} and {@code BoardDraw} to work as their javadoc says.
 */
@SuppressWarnings("removal")
public final class GuildGameTests {
    private static final Identifier COURIER = Alina.id("guild_test/courier");
    private static final Identifier HORN = Alina.id("guild_test/horn");
    private static final Identifier OFF_ROTATION = Alina.id("guild_test/off_rotation");
    private static final Identifier FEAT_ITEMS = Alina.id("guild_test/feat_items");
    private static final Identifier GREETER = Alina.id("guild_test/greeter");
    private static final Identifier DIVE = Alina.id("guild_test/dive");
    private static final Identifier NEW_ARRIVAL = Alina.id("guild_test/new_arrival");
    private static final String BUNDLE = "bundle.json";
    private static final String BUNDLE_NEXT = "bundle_next.json";
    private static final String ROTATION = "rotation.json";
    private static final ZoneId MANILA = ZoneId.of("Asia/Manila");
    /** The courier's cooldown: the content's default, PT20H. */
    private static final Duration COURIER_COOLDOWN = Duration.ofHours(20);
    /** Positions in the fixture's volumes, which are inclusive block boxes at x 500 to 503 and z 500 to 503. */
    private static final Vec3 OUTSIDE = new Vec3(0.5, 100.5, 0.5);
    private static final Vec3 IN_HIGH = new Vec3(501.5, 200.5, 501.5);
    private static final Vec3 IN_BASIN = new Vec3(501.5, 60.5, 501.5);
    /** Ticks a flush may take to reach storage after the event: the flush period, and a little for the storage thread. */
    private static final long FLUSH_SLACK = 2;

    // Load, view and board.

    @GameTest(maxTicks = 300)
    public void aJoinedCharacterLoadsItsRecordAndTodaysBoard(GameTestHelper helper) {
        GuildRig rig = GuildRig.create(helper, BUNDLE);
        ServerPlayer player = rig.newPlayer();
        helper.assertTrue(rig.service().view(player).isEmpty(), "a view before join");
        rig.service().join(player);
        helper.assertTrue(rig.service().view(player).isEmpty(), "a view before the load completes");
        helper.assertValueEqual(reasonOf(rig.service().checkAccept(player, COURIER)), Refusal.Reason.LOADING,
                "accept while the record loads");

        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record or today's board has not loaded");
            GuildView view = rig.service().view(player).orElseThrow();
            helper.assertValueEqual(view.reputation(), 0, "reputation of a new character");
            helper.assertTrue(view.commissions().isEmpty(), "a new character holds commissions: " + view.commissions());
            List<Identifier> entries = rig.service().boardEntries(player).orElseThrow();
            helper.assertValueEqual(entries.size(), 5, "entries: the two board commissions, then three feats");
            helper.assertTrue(List.copyOf(entries.subList(0, 2)).stream().sorted().toList()
                            .equals(List.of(COURIER, HORN).stream().sorted().toList()),
                    "the board holds the two repeatables in rotation: " + entries);
            helper.assertTrue(entries.subList(2, 5).equals(List.of(DIVE, FEAT_ITEMS, GREETER)),
                    "the feats are not listed by id text: " + entries);
        });
    }

    @GameTest(maxTicks = 300)
    public void theBoardGatesRepeatablesAndTheCapLimitsActiveCommissions(GameTestHelper helper) {
        GuildRig rig = GuildRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        AtomicBoolean accepted = new AtomicBoolean();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record or today's board has not loaded");
            if (!accepted.get()) {
                helper.assertValueEqual(reasonOf(rig.service().checkAccept(player, OFF_ROTATION)),
                        Refusal.Reason.NOT_ON_BOARD, "a repeatable that is not on today's board");
                helper.assertTrue(rig.service().accept(player, COURIER).isEmpty(), "the courier was refused");
                helper.assertTrue(rig.service().accept(player, HORN).isEmpty(), "the horn was refused");
                accepted.set(true);
            }
            helper.assertValueEqual(reasonOf(rig.service().checkAccept(player, FEAT_ITEMS)),
                    Refusal.Reason.CAP_REACHED, "a third commission with active_cap 2");
        });
    }

    // Accept, abandon and failures.

    @GameTest(maxTicks = 300)
    public void acceptRecordsTheCommissionInStorageAndFiresItsTrigger(GameTestHelper helper) {
        GuildRig rig = GuildRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        AtomicInteger fired = new AtomicInteger();
        CriterionWatchers.watch(player.getUUID(), spec(helper, "alina:commission_accepted",
                "{ \"commission\": \"alina:guild_test/courier\", \"kind\": \"repeatable\" }"),
                event -> fired.incrementAndGet());
        AtomicBoolean asked = new AtomicBoolean();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record or today's board has not loaded");
            if (!asked.get()) {
                helper.assertTrue(rig.service().accept(player, COURIER).isEmpty(), "the courier was refused");
                asked.set(true);
            }
            GuildView view = rig.service().view(player).orElseThrow();
            helper.assertValueEqual(view.standing(COURIER), Standing.ACTIVE, "standing in the record");
            helper.assertValueEqual(view.activeCount(), 1, "active commissions");
            helper.assertValueEqual(view.commissions().get(COURIER).acceptedAt(), GuildRig.START,
                    "accepted at the service clock");
            helper.assertValueEqual(stored(rig, player, COURIER).map(CommissionState::status).orElse(null),
                    CommissionStatus.ACTIVE, "the row in storage");
            helper.assertValueEqual(fired.get(), 1, "commission_accepted events for the courier");
            helper.assertTrue(rig.messages(player).contains("Commission accepted: Courier"),
                    "no accepted message");
        });
    }

    @GameTest(maxTicks = 400)
    public void abandonFreesTheSlotAndDeletesTheRow(GameTestHelper helper) {
        GuildRig rig = GuildRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record or today's board has not loaded");
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(rig.service().accept(player, COURIER).isEmpty(), "the courier was refused");
                    helper.assertTrue(rig.service().accept(player, HORN).isEmpty(), "the horn was refused");
                    step.set(1);
                }
                case 1 -> {
                    helper.assertTrue(rig.service().checkAbandon(player, COURIER).isEmpty(),
                            "the courier is still being written: " + reasonOf(rig.service().checkAbandon(player, COURIER)));
                    helper.assertTrue(rig.service().abandon(player, COURIER).isEmpty(), "the abandon was refused");
                    step.set(2);
                }
                case 2 -> {
                    helper.assertValueEqual(rig.service().view(player).orElseThrow().standing(COURIER),
                            Standing.AVAILABLE, "standing after abandon");
                    helper.assertTrue(rig.service().accept(player, FEAT_ITEMS).isEmpty(),
                            "the freed slot did not take the feat");
                    step.set(3);
                }
                default -> {
                    helper.assertTrue(stored(rig, player, COURIER).isEmpty(), "the abandoned row is still in storage");
                    helper.assertValueEqual(stored(rig, player, FEAT_ITEMS).map(CommissionState::status).orElse(null),
                            CommissionStatus.ACTIVE, "the feat in storage");
                    helper.assertTrue(rig.messages(player).contains("Commission abandoned: Courier"),
                            "no abandoned message");
                }
            }
        });
    }

    @GameTest(maxTicks = 300)
    public void aFailedAcceptanceLeavesTheCommissionAvailableAndTellsThePlayer(GameTestHelper helper) {
        AtomicInteger accepts = new AtomicInteger();
        GuildRig rig = GuildRig.create(helper, BUNDLE, delegate -> failingAccept(delegate, accepts));
        ServerPlayer player = rig.joined();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record or today's board has not loaded");
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(rig.service().accept(player, COURIER).isEmpty(), "the courier was refused");
                    step.set(1);
                }
                case 1 -> {
                    helper.assertValueEqual(rig.service().view(player).orElseThrow().standing(COURIER),
                            Standing.AVAILABLE, "standing after the failed write");
                    helper.assertTrue(stored(rig, player, COURIER).isEmpty(), "a row was written by the failed call");
                    helper.assertTrue(rig.messages(player).stream().anyMatch(text -> text.contains("Courier")),
                            "the player was not told the acceptance failed");
                    helper.assertValueEqual(accepts.get(), 1, "acceptCommission calls after the failure");
                    step.set(2);
                }
                case 2 -> {
                    helper.assertTrue(rig.service().accept(player, COURIER).isEmpty(),
                            "the commission is not available again");
                    step.set(3);
                }
                default -> helper.assertValueEqual(stored(rig, player, COURIER).map(CommissionState::status).orElse(null),
                        CommissionStatus.ACTIVE, "the row after the second acceptance");
            }
        });
    }

    // Turn-ins through settlement.

    @GameTest(maxTicks = 1500)
    public void anAllOfTurnInTakesExactlyThePlannedItemsAndCoolsDownForTwentyHours(GameTestHelper helper) {
        GuildRig rig = GuildRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        player.getInventory().setItem(0, new ItemStack(Items.PAPER, 3));
        player.getInventory().setItem(1, new ItemStack(Items.STICK));
        AtomicInteger phase = new AtomicInteger();
        AtomicReference<Instant> turnedAt = new AtomicReference<>();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record or today's board has not loaded");
            switch (phase.get()) {
                case 0 -> {
                    helper.assertTrue(rig.service().accept(player, COURIER).isEmpty(), "the courier was refused");
                    phase.set(1);
                }
                case 1 -> {
                    helper.assertTrue(rig.service().checkTurnIn(player, COURIER).isEmpty(),
                            "not ready to turn in: " + reasonOf(rig.service().checkTurnIn(player, COURIER)));
                    helper.assertTrue(rig.service().turnIn(player, COURIER).isEmpty(), "the turn-in was refused");
                    turnedAt.set(rig.service().now());
                    phase.set(2);
                }
                case 2 -> {
                    boolean delivered = count(player, Items.EMERALD) == 5 && rig.settlement().pendingFor(player).isEmpty();
                    if (!delivered) {
                        // Settlement has the proof and has not delivered the reward: the commission is settling.
                        helper.assertValueEqual(reasonOf(rig.service().checkTurnIn(player, COURIER)),
                                Refusal.Reason.SETTLING, "a second turn-in while settling");
                        helper.assertValueEqual(reasonOf(rig.service().checkAbandon(player, COURIER)),
                                Refusal.Reason.SETTLING, "an abandon while settling");
                        helper.assertValueEqual(count(player, Items.PAPER), 1, "paper while settling");
                        helper.assertValueEqual(count(player, Items.STICK), 1, "the unchecked stick while settling");
                        helper.assertValueEqual(count(player, Items.EMERALD), 0, "reward before delivery");
                        helper.assertTrue(false, "waiting for settlement to deliver the reward");
                    }
                    helper.assertValueEqual(count(player, Items.PAPER), 1, "the plan takes two of the three paper");
                    helper.assertValueEqual(count(player, Items.STICK), 1, "the stick is checked, not taken");
                    helper.assertValueEqual(count(player, Items.EMERALD), 5, "the reward, five emeralds");
                    helper.assertTrue(rig.messages(player).contains("Commission complete: Courier"),
                            "no commission complete message");
                    helper.assertValueEqual(stored(rig, player, COURIER).map(CommissionState::status).orElse(null),
                            CommissionStatus.COOLDOWN, "the row in storage");
                    helper.assertValueEqual(rig.storage().loadCharacter(player.getUUID()).join().reputation(), 2,
                            "reputation in storage, applied once");
                    GuildView view = rig.service().view(player).orElseThrow();
                    helper.assertValueEqual(view.reputation(), 2, "reputation in the record");
                    helper.assertValueEqual(view.commissions().get(COURIER).cooldownUntil().orElse(null),
                            turnedAt.get().plus(COURIER_COOLDOWN), "the cooldown ends twenty hours after the turn-in");
                    helper.assertValueEqual(reasonOf(rig.service().checkAccept(player, COURIER)),
                            Refusal.Reason.COOLDOWN, "accept during the cooldown");
                    rig.clock().advance(COURIER_COOLDOWN.minusMinutes(1));
                    phase.set(3);
                }
                case 3 -> {
                    // The day changed while the clock ran, so the new board is drawn first; the cooldown then refuses.
                    helper.assertTrue(rig.service().board().isPresent(), "the next board has not loaded");
                    helper.assertValueEqual(reasonOf(rig.service().checkAccept(player, COURIER)),
                            Refusal.Reason.COOLDOWN, "accept one minute before the cooldown ends");
                    rig.clock().advance(Duration.ofMinutes(1));
                    phase.set(4);
                }
                case 4 -> {
                    helper.assertTrue(rig.service().checkAccept(player, COURIER).isEmpty(),
                            "not available at the end of the cooldown: " + reasonOf(rig.service().checkAccept(player, COURIER)));
                    helper.assertTrue(rig.service().accept(player, COURIER).isEmpty(), "accept after the cooldown");
                    phase.set(5);
                }
                default -> helper.assertValueEqual(rig.service().view(player).orElseThrow().standing(COURIER),
                        Standing.ACTIVE, "standing after the second acceptance");
            }
        });
    }

    @GameTest(maxTicks = 800)
    public void anAnyOfTurnInTakesOnlyTheFirstMetEntryAndCoolsDownUntilTheBoardReset(GameTestHelper helper) {
        GuildRig rig = GuildRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        player.getInventory().setItem(0, new ItemStack(Items.GLASS_BOTTLE));
        player.getInventory().setItem(1, new ItemStack(Items.BUCKET));
        AtomicInteger phase = new AtomicInteger();
        AtomicReference<Instant> turnedAt = new AtomicReference<>();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record or today's board has not loaded");
            switch (phase.get()) {
                case 0 -> {
                    helper.assertTrue(rig.service().accept(player, HORN).isEmpty(), "the horn was refused");
                    phase.set(1);
                }
                case 1 -> {
                    helper.assertTrue(rig.service().checkTurnIn(player, HORN).isEmpty(),
                            "not ready to turn in: " + reasonOf(rig.service().checkTurnIn(player, HORN)));
                    helper.assertTrue(rig.service().turnIn(player, HORN).isEmpty(), "the turn-in was refused");
                    turnedAt.set(rig.service().now());
                    phase.set(2);
                }
                default -> {
                    boolean delivered = count(player, Items.DIAMOND) == 1 && rig.settlement().pendingFor(player).isEmpty();
                    if (!delivered) {
                        helper.assertValueEqual(reasonOf(rig.service().checkTurnIn(player, HORN)),
                                Refusal.Reason.SETTLING, "a second turn-in while settling");
                        helper.assertTrue(false, "waiting for settlement to deliver the reward");
                    }
                    helper.assertValueEqual(count(player, Items.BUCKET), 0, "the bucket is the first met entry");
                    helper.assertValueEqual(count(player, Items.GLASS_BOTTLE), 1,
                            "the bottle is not taken: two bottles were not held");
                    helper.assertValueEqual(count(player, Items.EMERALD), 1, "the reward's emerald");
                    helper.assertValueEqual(rig.service().view(player).orElseThrow().reputation(), 1, "reputation");
                    helper.assertValueEqual(rig.service().view(player).orElseThrow().commissions().get(HORN).cooldownUntil()
                            .orElse(null), nextReset(turnedAt.get()), "the cooldown ends at the next board reset");
                }
            }
        });
    }

    @GameTest(maxTicks = 500)
    public void aFeatTurnInBecomesCompletedAndLeavesTheBoard(GameTestHelper helper) {
        GuildRig rig = GuildRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        player.getInventory().setItem(0, new ItemStack(Items.PAPER));
        AtomicInteger phase = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record or today's board has not loaded");
            switch (phase.get()) {
                case 0 -> {
                    helper.assertTrue(rig.service().accept(player, FEAT_ITEMS).isEmpty(), "the feat was refused");
                    phase.set(1);
                }
                case 1 -> {
                    helper.assertTrue(rig.service().checkTurnIn(player, FEAT_ITEMS).isEmpty(),
                            "not ready to turn in: " + reasonOf(rig.service().checkTurnIn(player, FEAT_ITEMS)));
                    helper.assertTrue(rig.service().turnIn(player, FEAT_ITEMS).isEmpty(), "the turn-in was refused");
                    phase.set(2);
                }
                default -> {
                    boolean delivered = rig.settlement().pendingFor(player).isEmpty()
                            && rig.storage().loadCharacter(player.getUUID()).join().reputation() == 3;
                    if (!delivered) {
                        helper.assertValueEqual(reasonOf(rig.service().checkTurnIn(player, FEAT_ITEMS)),
                                Refusal.Reason.SETTLING, "a second turn-in while settling");
                        helper.assertTrue(false, "waiting for settlement to deliver the reward");
                    }
                    helper.assertValueEqual(count(player, Items.PAPER), 0, "the feat's paper is taken");
                    GuildView view = rig.service().view(player).orElseThrow();
                    helper.assertValueEqual(view.standing(FEAT_ITEMS), Standing.COMPLETED, "standing of the feat");
                    helper.assertTrue(view.commissions().get(FEAT_ITEMS).completedAt().isPresent(),
                            "a completed feat has no completion time");
                    helper.assertValueEqual(reasonOf(rig.service().checkAccept(player, FEAT_ITEMS)),
                            Refusal.Reason.COMPLETED, "accept of a completed feat");
                    helper.assertTrue(!rig.service().boardEntries(player).orElseThrow().contains(FEAT_ITEMS),
                            "a completed feat is still listed");
                    helper.assertValueEqual(stored(rig, player, FEAT_ITEMS).map(CommissionState::status).orElse(null),
                            CommissionStatus.COMPLETED, "the row in storage");
                }
            }
        });
    }

    // Criterion proofs.

    @GameTest(maxTicks = 700)
    public void aCriterionCountsOnlyEventsAfterAcceptanceAndKeepsItsProgressAcrossALeave(GameTestHelper helper) {
        GuildRig rig = GuildRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        AtomicInteger step = new AtomicInteger();
        AtomicLong eventAt = new AtomicLong();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record or today's board has not loaded");
            switch (step.get()) {
                case 0 -> {
                    AlinaTriggers.MANUAL.trigger(player, "met");
                    helper.assertTrue(rig.service().accept(player, GREETER).isEmpty(), "the greeter was refused");
                    step.set(1);
                }
                case 1 -> {
                    helper.assertValueEqual(progress(rig, player, GREETER), 0,
                            "an event before acceptance counted");
                    AlinaTriggers.MANUAL.trigger(player, "other");
                    helper.assertValueEqual(progress(rig, player, GREETER), 0, "another key counted");
                    AlinaTriggers.MANUAL.trigger(player, "met");
                    helper.assertValueEqual(progress(rig, player, GREETER), 1, "the first event after acceptance");
                    eventAt.set(rig.ticks());
                    step.set(2);
                }
                case 2 -> {
                    helper.assertValueEqual(storedProgress(rig, player, GREETER), 1,
                            "progress in storage after the flush");
                    helper.assertTrue(rig.ticks() - eventAt.get() <= GuildService.FLUSH_TICKS + FLUSH_SLACK,
                            "progress reached storage later than one flush period after the event");
                    step.set(3);
                }
                case 3 -> {
                    AlinaTriggers.MANUAL.trigger(player, "met");
                    helper.assertValueEqual(progress(rig, player, GREETER), 2, "the second event");
                    helper.assertTrue(rig.messages(player).contains("Ready to turn in: Greeter"),
                            "no ready message at the count");
                    AlinaTriggers.MANUAL.trigger(player, "met");
                    helper.assertValueEqual(progress(rig, player, GREETER), 2, "an event after the count");
                    helper.assertTrue(rig.service().checkTurnIn(player, GREETER).isEmpty(),
                            "a met count is not ready: " + reasonOf(rig.service().checkTurnIn(player, GREETER)));
                    rig.service().leave(player);
                    rig.service().join(player);
                    step.set(4);
                }
                default -> {
                    helper.assertValueEqual(progress(rig, player, GREETER), 2, "progress after the rejoin");
                    helper.assertValueEqual(rig.service().view(player).orElseThrow().standing(GREETER),
                            Standing.ACTIVE, "standing after the rejoin");
                }
            }
        });
    }

    // Rotation and content activation.

    @GameTest(maxTicks = 300)
    public void twoServicesOverOneStorageHoldTheSameBoardAndSoDoesAFreshStorage(GameTestHelper helper) {
        GuildRig rig = GuildRig.create(helper, ROTATION);
        GuildService first = rig.service();
        GuildService second = rig.serviceOver(rig.storage(), rig::currentContent);
        GuildService fresh = rig.serviceOver(new InMemoryStorage(), rig::currentContent);
        helper.succeedWhen(() -> {
            rig.tick();
            second.tick();
            fresh.tick();
            Optional<BoardView> a = first.board();
            Optional<BoardView> b = second.board();
            Optional<BoardView> c = fresh.board();
            helper.assertTrue(a.isPresent() && b.isPresent() && c.isPresent(), "a board has not loaded");
            helper.assertValueEqual(a.get().commissions().size(), 2, "the board holds board_size commissions");
            helper.assertTrue(a.get().stored(), "the board was not stored");
            helper.assertValueEqual(a.get().date(), LocalDate.of(2026, 11, 5), "the board day");
            helper.assertTrue(b.get().commissions().equals(a.get().commissions()),
                    "a service over the same storage drew " + b.get().commissions() + ", not " + a.get().commissions());
            helper.assertTrue(c.get().commissions().equals(a.get().commissions()),
                    "a fresh storage drew " + c.get().commissions() + ", not " + a.get().commissions());
        });
    }

    @GameTest(maxTicks = 300)
    public void aMidDayContentChangeKeepsTheStoredBoardMinusTheRemovedCommission(GameTestHelper helper) {
        GuildRig rig = GuildRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        AtomicBoolean changed = new AtomicBoolean();
        AtomicReference<List<Identifier>> before = new AtomicReference<>();
        helper.succeedWhen(() -> {
            rig.tick();
            if (!changed.get()) {
                helper.assertTrue(loaded(rig, player), "the record or today's board has not loaded");
                before.set(rig.service().board().orElseThrow().commissions());
                helper.assertTrue(before.get().contains(HORN), "the horn is not on the board: " + before.get());
                rig.useContent(BUNDLE_NEXT);
                rig.service().contentActivated();
                changed.set(true);
            }
            List<Identifier> expected = before.get().stream().filter(id -> !id.equals(HORN)).toList();
            helper.assertValueEqual(rig.service().board().orElseThrow().commissions(), expected,
                    "the board after the change");
            helper.assertTrue(!rig.service().board().orElseThrow().commissions().contains(NEW_ARRIVAL),
                    "the new commission was added to the board mid-day");
            helper.assertValueEqual(rig.storage().loadBoard(LocalDate.of(2026, 11, 5)).join().orElseThrow()
                    .commissionIds(), before.get(), "the stored board");
        });
    }

    @GameTest(maxTicks = 300)
    public void anActiveCommissionTheNewContentLacksIsWithdrawnAndTheStorageRowDeleted(GameTestHelper helper) {
        GuildRig rig = GuildRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record or today's board has not loaded");
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(rig.service().accept(player, FEAT_ITEMS).isEmpty(), "the feat was refused");
                    step.set(1);
                }
                case 1 -> {
                    helper.assertValueEqual(rig.service().view(player).orElseThrow().standing(FEAT_ITEMS),
                            Standing.ACTIVE, "standing before the change");
                    helper.assertValueEqual(stored(rig, player, FEAT_ITEMS).map(CommissionState::status).orElse(null),
                            CommissionStatus.ACTIVE, "the row before the change");
                    rig.useContent(BUNDLE_NEXT);
                    rig.service().contentActivated();
                    step.set(2);
                }
                case 2 -> {
                    helper.assertValueEqual(rig.service().view(player).orElseThrow().standing(FEAT_ITEMS),
                            Standing.AVAILABLE, "standing after the withdrawal");
                    helper.assertTrue(stored(rig, player, FEAT_ITEMS).isEmpty(), "the storage row is still there");
                    helper.assertTrue(rig.messages(player).contains("The Guild withdrew Paper Feat, so it is no longer active."),
                            "the player was not told of the withdrawal");
                }
                default -> helper.assertTrue(rig.service().view(player).isPresent(), "the record was lost");
            }
        });
    }

    // Descent (ALINA-PLAN §8.3).

    @GameTest(maxTicks = 500)
    public void aDiveFromTheStartVolumeToTheFinishVolumeMeetsTheProof(GameTestHelper helper) {
        GuildRig rig = GuildRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        player.setPos(OUTSIDE.x, OUTSIDE.y, OUTSIDE.z);
        AtomicInteger entered = new AtomicInteger();
        CriterionWatchers.watch(player.getUUID(), spec(helper, "alina:volume_entered",
                "{ \"volume\": \"alina:guild_test/high_dive\" }"), event -> entered.incrementAndGet());
        AtomicInteger step = new AtomicInteger();
        AtomicLong movedAt = new AtomicLong();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record or today's board has not loaded");
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(rig.service().accept(player, DIVE).isEmpty(), "the dive was refused");
                    step.set(1);
                }
                case 1 -> {
                    move(player, IN_HIGH);
                    movedAt.set(rig.ticks());
                    step.set(2);
                }
                case 2 -> {
                    helper.assertValueEqual(entered.get(), 1, "volume_entered on entering the start volume");
                    movedAt.set(rig.ticks());
                    step.set(3);
                }
                case 3 -> {
                    helper.assertTrue(rig.ticks() - movedAt.get() >= 2 * GuildService.DESCENT_CHECK_TICKS,
                            "waiting for the player to stay in the start volume");
                    helper.assertValueEqual(entered.get(), 1, "volume_entered again while the player stayed inside");
                    move(player, IN_BASIN);
                    movedAt.set(rig.ticks());
                    step.set(4);
                }
                default -> {
                    helper.assertValueEqual(progress(rig, player, DIVE), 1, "progress after reaching the finish");
                    helper.assertValueEqual(rig.service().view(player).orElseThrow().standing(DIVE),
                            Standing.ACTIVE, "standing of the met dive");
                    helper.assertTrue(rig.messages(player).contains("Ready to turn in: Dive"), "no ready message");
                }
            }
        });
    }

    @GameTest(maxTicks = 500)
    public void slowFallingBetweenTheVolumesDoesNotMeetTheProof(GameTestHelper helper) {
        GuildRig rig = GuildRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        player.setPos(OUTSIDE.x, OUTSIDE.y, OUTSIDE.z);
        AtomicInteger entered = new AtomicInteger();
        CriterionWatchers.watch(player.getUUID(), spec(helper, "alina:volume_entered",
                "{ \"volume\": \"alina:guild_test/high_dive\" }"), event -> entered.incrementAndGet());
        AtomicInteger step = new AtomicInteger();
        AtomicLong movedAt = new AtomicLong();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record or today's board has not loaded");
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(rig.service().accept(player, DIVE).isEmpty(), "the dive was refused");
                    step.set(1);
                }
                case 1 -> {
                    move(player, IN_HIGH);
                    step.set(2);
                }
                case 2 -> {
                    helper.assertValueEqual(entered.get(), 1, "volume_entered on entering the start volume");
                    player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 400));
                    helper.assertTrue(player.hasEffect(MobEffects.SLOW_FALLING), "the effect was not applied");
                    move(player, IN_BASIN);
                    movedAt.set(rig.ticks());
                    step.set(3);
                }
                default -> {
                    helper.assertTrue(rig.ticks() - movedAt.get() >= 2 * GuildService.DESCENT_CHECK_TICKS,
                            "waiting for the checks at the finish");
                    helper.assertValueEqual(progress(rig, player, DIVE), 0,
                            "Slow Falling in between did not disarm the dive");
                }
            }
        });
    }

    @GameTest(maxTicks = 500)
    public void aTotemUseBetweenTheVolumesDoesNotMeetTheProof(GameTestHelper helper) {
        GuildRig rig = GuildRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        player.setPos(OUTSIDE.x, OUTSIDE.y, OUTSIDE.z);
        AtomicInteger entered = new AtomicInteger();
        CriterionWatchers.watch(player.getUUID(), spec(helper, "alina:volume_entered",
                "{ \"volume\": \"alina:guild_test/high_dive\" }"), event -> entered.incrementAndGet());
        AtomicInteger step = new AtomicInteger();
        AtomicLong movedAt = new AtomicLong();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record or today's board has not loaded");
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(rig.service().accept(player, DIVE).isEmpty(), "the dive was refused");
                    step.set(1);
                }
                case 1 -> {
                    move(player, IN_HIGH);
                    step.set(2);
                }
                case 2 -> {
                    helper.assertValueEqual(entered.get(), 1, "volume_entered on entering the start volume");
                    CriteriaTriggers.USED_TOTEM.trigger(player, new ItemStack(Items.TOTEM_OF_UNDYING));
                    move(player, IN_BASIN);
                    movedAt.set(rig.ticks());
                    step.set(3);
                }
                default -> {
                    helper.assertTrue(rig.ticks() - movedAt.get() >= 2 * GuildService.DESCENT_CHECK_TICKS,
                            "waiting for the checks at the finish");
                    helper.assertValueEqual(progress(rig, player, DIVE), 0,
                            "a totem use in between did not disarm the dive");
                }
            }
        });
    }

    // Helpers.

    /** True once the player's record and today's board have loaded. */
    private static boolean loaded(GuildRig rig, ServerPlayer player) {
        return rig.service().view(player).isPresent() && rig.service().board().isPresent();
    }

    private static void move(ServerPlayer player, Vec3 to) {
        player.setPos(to.x, to.y, to.z);
    }

    private static Optional<CommissionState> stored(GuildRig rig, ServerPlayer player, Identifier commission) {
        return Optional.ofNullable(rig.storage().loadCharacter(player.getUUID()).join().commissions().get(commission));
    }

    /** The progress storage holds, 0 while no row or no progress is written. */
    private static int storedProgress(GuildRig rig, ServerPlayer player, Identifier commission) {
        return stored(rig, player, commission).map(CommissionState::progress).orElse(0);
    }

    /** The progress of the record's commission, or -1 while the record or the commission has no view. */
    private static int progress(GuildRig rig, ServerPlayer player, Identifier commission) {
        return rig.service().view(player).map(view -> view.commissions().get(commission))
                .map(CommissionView::progress).orElse(-1);
    }

    private static Refusal.Reason reasonOf(Optional<Refusal> refusal) {
        return refusal.map(Refusal::reason).orElse(null);
    }

    /** Counts the item in every slot of the player's inventory, the armour and the offhand included. */
    private static int count(ServerPlayer player, Item item) {
        Inventory inventory = player.getInventory();
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** The first 04:00 in Asia/Manila after {@code after}, worked out from the clock time alone. */
    private static Instant nextReset(Instant after) {
        ZonedDateTime local = after.atZone(MANILA);
        ZonedDateTime reset = local.toLocalDate().atTime(LocalTime.of(4, 0)).atZone(MANILA);
        if (!reset.isAfter(local)) {
            reset = reset.plusDays(1);
        }
        return reset.toInstant();
    }

    /** A spec for {@code trigger} with the conditions parsed as content parses them. */
    private static CriterionSpec spec(GameTestHelper helper, String trigger, String conditions) {
        Identifier id = Identifier.parse(trigger);
        CriterionTrigger<?> type = BuiltInRegistries.TRIGGER_TYPES.getValue(id);
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().getServer().registryAccess());
        CriterionTriggerInstance instance = type.codec().parse(ops, JsonParser.parseString(conditions))
                .getOrThrow(message -> new IllegalStateException("conditions do not parse: " + message));
        return new CriterionSpec(id, instance, 1);
    }

    /**
     * A storage whose first acceptCommission fails without writing, and whose other calls reach {@code delegate}.
     * {@code accepts} counts the acceptCommission calls.
     */
    private static AlinaStorage failingAccept(AlinaStorage delegate, AtomicInteger accepts) {
        return (AlinaStorage) Proxy.newProxyInstance(AlinaStorage.class.getClassLoader(),
                new Class<?>[] {AlinaStorage.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("acceptCommission") && accepts.incrementAndGet() == 1) {
                        return CompletableFuture.failedFuture(new IllegalStateException("injected accept failure"));
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }
}
