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
import town.lampas.alina.content.GuildContent;
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
import town.lampas.alina.storage.StoredBoard;

/**
 * Server GameTests owned by the Guild service (ALINA-PLAN §8.1 to §8.3 and §13). Each test builds a {@link GuildRig}: a
 * service over its own in-memory storage and settlement, a clock the test moves, and players outside the server's list.
 * The test ticks the rig once per game tick. Storage and settlement complete on storage threads and return to the server
 * thread on a later tick, so a test waits with {@code succeedWhen}: a check that is not met yet throws and is run again.
 *
 * <p>A test with several steps moves on with {@link #advance}, which fails the attempt, so each step runs on its own tick
 * and only the last step returns normally. A step that asserts before it acts must not act again on a retry, so each
 * action comes after the checks that gate it.
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
    private static final Identifier HALL = Alina.id("guild_test/hall");
    private static final Identifier NEW_ARRIVAL = Alina.id("guild_test/new_arrival");
    private static final String BUNDLE = "bundle.json";
    private static final String BUNDLE_NEXT = "bundle_next.json";
    private static final String ROTATION = "rotation.json";
    /** Feats only: its rotation pool is empty, so the board it draws is empty and not stored. */
    private static final String FEATS = "feats.json";
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
        assertReason(helper, (rig.service().checkAccept(player, COURIER)), Refusal.Reason.LOADING,
                "accept while the record loads");

        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record or today's board has not loaded");
            GuildView view = rig.service().view(player).orElseThrow();
            helper.assertValueEqual(view.reputation(), 0, "reputation of a new character");
            helper.assertTrue(view.commissions().isEmpty(), "a new character holds commissions: " + view.commissions());
            List<Identifier> entries = rig.service().boardEntries(player).orElseThrow();
            helper.assertValueEqual(entries.size(), 6, "entries: the two board commissions, then four feats");
            helper.assertTrue(List.copyOf(entries.subList(0, 2)).stream().sorted().toList()
                            .equals(List.of(COURIER, HORN).stream().sorted().toList()),
                    "the board holds the two repeatables in rotation: " + entries);
            helper.assertTrue(entries.subList(2, 6).equals(List.of(DIVE, FEAT_ITEMS, GREETER, HALL)),
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
                assertReason(helper, (rig.service().checkAccept(player, OFF_ROTATION)),
                        Refusal.Reason.NOT_ON_BOARD, "a repeatable that is not on today's board");
                helper.assertTrue(rig.service().accept(player, COURIER).isEmpty(), "the courier was refused");
                helper.assertTrue(rig.service().accept(player, HORN).isEmpty(), "the horn was refused");
                accepted.set(true);
            }
            assertReason(helper, (rig.service().checkAccept(player, FEAT_ITEMS)),
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
            helper.assertTrue(statusIs(rig, player, COURIER, CommissionStatus.ACTIVE), "the row in storage");
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
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertTrue(rig.service().checkAbandon(player, COURIER).isEmpty(),
                            "the courier is still being written: " + reasonOf(rig.service().checkAbandon(player, COURIER)));
                    helper.assertTrue(rig.service().abandon(player, COURIER).isEmpty(), "the abandon was refused");
                    advance(helper, step, 2);
                }
                case 2 -> {
                    // The abandon holds its slot until storage answers, so the feat waits for the written abandon.
                    helper.assertTrue(rig.messages(player).contains("Commission abandoned: Courier"),
                            "the abandon is still being written");
                    helper.assertValueEqual(rig.service().view(player).orElseThrow().standing(COURIER),
                            Standing.AVAILABLE, "standing after abandon");
                    helper.assertTrue(rig.service().accept(player, FEAT_ITEMS).isEmpty(),
                            "the freed slot did not take the feat");
                    advance(helper, step, 3);
                }
                default -> {
                    helper.assertTrue(stored(rig, player, COURIER).isEmpty(), "the abandoned row is still in storage");
                    helper.assertTrue(statusIs(rig, player, FEAT_ITEMS, CommissionStatus.ACTIVE), "the feat in storage");
                    helper.assertTrue(rig.messages(player).contains("Commission abandoned: Courier"),
                            "no abandoned message");
                }
            }
        });
    }

    @GameTest(maxTicks = 300)
    public void aFailedAcceptanceLeavesTheCommissionAvailableAndTellsThePlayer(GameTestHelper helper) {
        AtomicInteger accepts = new AtomicInteger();
        GuildRig rig = GuildRig.create(helper, BUNDLE, delegate -> failingFirst(delegate, "acceptCommission", accepts));
        ServerPlayer player = rig.joined();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record or today's board has not loaded");
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(rig.service().accept(player, COURIER).isEmpty(), "the courier was refused");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertValueEqual(rig.service().view(player).orElseThrow().standing(COURIER),
                            Standing.AVAILABLE, "standing after the failed write");
                    helper.assertTrue(stored(rig, player, COURIER).isEmpty(), "a row was written by the failed call");
                    helper.assertTrue(rig.messages(player).contains("The Guild could not record Courier. Try again."),
                            "the player was not told the acceptance failed");
                    helper.assertTrue(!rig.messages(player).contains("Commission accepted: Courier"),
                            "the player was told the failed acceptance succeeded");
                    helper.assertValueEqual(accepts.get(), 1, "acceptCommission calls after the failure");
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertTrue(rig.service().accept(player, COURIER).isEmpty(),
                            "the commission is not available again");
                    advance(helper, step, 3);
                }
                default -> helper.assertTrue(statusIs(rig, player, COURIER, CommissionStatus.ACTIVE), "the row after the second acceptance");
            }
        });
    }

    @GameTest(maxTicks = 600)
    public void anAbandonStillBeingWrittenHoldsItsSlotAgainstTheCap(GameTestHelper helper) {
        AtomicInteger abandons = new AtomicInteger();
        CompletableFuture<Object> gate = new CompletableFuture<>();
        GuildRig rig = GuildRig.create(helper, BUNDLE,
                delegate -> gatedFirst(delegate, "abandonCommission", abandons, gate));
        ServerPlayer player = rig.joined();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record or today's board has not loaded");
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(rig.service().accept(player, COURIER).isEmpty(), "the courier was refused");
                    helper.assertTrue(rig.service().accept(player, HORN).isEmpty(), "the horn was refused");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertTrue(rig.messages(player).contains("Commission accepted: Courier")
                                    && rig.messages(player).contains("Commission accepted: Horn"),
                            "the acceptances are not written yet");
                    helper.assertTrue(rig.service().abandon(player, COURIER).isEmpty(), "the abandon was refused");
                    // The abandon is still in flight, so its slot is not free: a third commission is refused.
                    assertReason(helper, rig.service().accept(player, FEAT_ITEMS), Refusal.Reason.CAP_REACHED,
                            "a feat accepted while the abandon is pending");
                    advance(helper, step, 2);
                }
                case 2 -> {
                    // Storage now answers the abandon with a failure.
                    gate.completeExceptionally(new IllegalStateException("injected abandonCommission failure"));
                    advance(helper, step, 3);
                }
                default -> {
                    helper.assertTrue(rig.messages(player).contains("The Guild could not abandon Courier. Try again."),
                            "the abandon failure was not reported");
                    helper.assertValueEqual(rig.service().view(player).orElseThrow().standing(COURIER),
                            Standing.ACTIVE, "the courier after the failed abandon");
                    helper.assertValueEqual(rig.service().view(player).orElseThrow().activeCount(), 2,
                            "active commissions after the failed abandon");
                    assertReason(helper, rig.service().checkAccept(player, FEAT_ITEMS), Refusal.Reason.CAP_REACHED,
                            "the cap after the failed abandon");
                    helper.assertTrue(statusIs(rig, player, COURIER, CommissionStatus.ACTIVE),
                            "the courier row after the failed abandon");
                }
            }
        });
    }

    @GameTest(maxTicks = 800)
    public void anAbandonThatFailsAfterAnActivationRemovedTheCommissionIsWithdrawnByTheNextLoad(GameTestHelper helper) {
        AtomicInteger abandons = new AtomicInteger();
        CompletableFuture<Object> gate = new CompletableFuture<>();
        GuildRig rig = GuildRig.create(helper, BUNDLE,
                delegate -> gatedFirst(delegate, "abandonCommission", abandons, gate));
        ServerPlayer player = rig.joined();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record or today's board has not loaded");
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(rig.service().accept(player, HORN).isEmpty(), "the horn was refused");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertTrue(rig.messages(player).contains("Commission accepted: Horn"),
                            "the acceptance is not written yet");
                    helper.assertTrue(rig.service().abandon(player, HORN).isEmpty(), "the abandon was refused");
                    // A revision without the horn is activated while the abandon is still in flight.
                    rig.useContent(BUNDLE_NEXT);
                    rig.service().contentActivated();
                    advance(helper, step, 2);
                }
                case 2 -> {
                    // Then storage fails the abandon. The horn is no longer in the content, so it is not put back.
                    gate.completeExceptionally(new IllegalStateException("injected abandonCommission failure"));
                    advance(helper, step, 3);
                }
                case 3 -> {
                    helper.assertTrue(rig.messages(player).contains("The Guild could not abandon Horn. Try again."),
                            "the abandon failure was not reported");
                    helper.assertValueEqual(rig.service().view(player).orElseThrow().standing(HORN),
                            Standing.AVAILABLE, "the horn is back in the record");
                    helper.assertTrue(statusIs(rig, player, HORN, CommissionStatus.ACTIVE),
                            "the row storage still holds");
                    rig.service().leave(player);
                    rig.service().join(player);
                    advance(helper, step, 4);
                }
                default -> {
                    helper.assertTrue(stored(rig, player, HORN).isEmpty(), "the next load did not withdraw the row");
                    helper.assertTrue(withdrawn(rig, player), "the player was not told of the withdrawal");
                }
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
                    advance(helper, phase, 1);
                }
                case 1 -> {
                    helper.assertTrue(rig.service().checkTurnIn(player, COURIER).isEmpty(),
                            "not ready to turn in: " + reasonOf(rig.service().checkTurnIn(player, COURIER)));
                    helper.assertTrue(rig.service().turnIn(player, COURIER).isEmpty(), "the turn-in was refused");
                    turnedAt.set(rig.service().now());
                    advance(helper, phase, 2);
                }
                case 2 -> {
                    boolean delivered = count(player, Items.EMERALD) == 5 && rig.settlement().pendingFor(player).isEmpty();
                    if (!delivered) {
                        // Settlement has the proof and has not delivered the reward: the commission is settling.
                        assertReason(helper, (rig.service().checkTurnIn(player, COURIER)),
                                Refusal.Reason.SETTLING, "a second turn-in while settling");
                        assertReason(helper, (rig.service().checkAbandon(player, COURIER)),
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
                    helper.assertTrue(statusIs(rig, player, COURIER, CommissionStatus.COOLDOWN), "the row in storage");
                    helper.assertValueEqual(rig.storage().loadCharacter(player.getUUID()).join().reputation(), 2,
                            "reputation in storage, applied once");
                    GuildView view = rig.service().view(player).orElseThrow();
                    helper.assertValueEqual(view.reputation(), 2, "reputation in the record");
                    helper.assertTrue(cooldownOf(rig, player, COURIER).equals(Optional.of(turnedAt.get().plus(COURIER_COOLDOWN))),
                            "the cooldown ends twenty hours after the turn-in");
                    assertReason(helper, (rig.service().checkAccept(player, COURIER)),
                            Refusal.Reason.COOLDOWN, "accept during the cooldown");
                    rig.clock().advance(COURIER_COOLDOWN.minusMinutes(1));
                    advance(helper, phase, 3);
                }
                case 3 -> {
                    // The day changed while the clock ran, so the new board is drawn first; the cooldown then refuses.
                    helper.assertTrue(rig.service().board().isPresent(), "the next board has not loaded");
                    assertReason(helper, (rig.service().checkAccept(player, COURIER)),
                            Refusal.Reason.COOLDOWN, "accept one minute before the cooldown ends");
                    rig.clock().advance(Duration.ofMinutes(1));
                    advance(helper, phase, 4);
                }
                case 4 -> {
                    helper.assertTrue(rig.service().checkAccept(player, COURIER).isEmpty(),
                            "not available at the end of the cooldown: " + reasonOf(rig.service().checkAccept(player, COURIER)));
                    helper.assertTrue(rig.service().accept(player, COURIER).isEmpty(), "accept after the cooldown");
                    advance(helper, phase, 5);
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
                    advance(helper, phase, 1);
                }
                case 1 -> {
                    helper.assertTrue(rig.service().checkTurnIn(player, HORN).isEmpty(),
                            "not ready to turn in: " + reasonOf(rig.service().checkTurnIn(player, HORN)));
                    helper.assertTrue(rig.service().turnIn(player, HORN).isEmpty(), "the turn-in was refused");
                    turnedAt.set(rig.service().now());
                    advance(helper, phase, 2);
                }
                default -> {
                    boolean delivered = count(player, Items.DIAMOND) == 1 && rig.settlement().pendingFor(player).isEmpty();
                    if (!delivered) {
                        assertReason(helper, (rig.service().checkTurnIn(player, HORN)),
                                Refusal.Reason.SETTLING, "a second turn-in while settling");
                        helper.assertTrue(false, "waiting for settlement to deliver the reward");
                    }
                    helper.assertValueEqual(count(player, Items.BUCKET), 0, "the bucket is the first met entry");
                    helper.assertValueEqual(count(player, Items.GLASS_BOTTLE), 1,
                            "the bottle is not taken: two bottles were not held");
                    helper.assertValueEqual(count(player, Items.EMERALD), 1, "the reward's emerald");
                    helper.assertValueEqual(rig.service().view(player).orElseThrow().reputation(), 1, "reputation");
                    helper.assertTrue(cooldownOf(rig, player, HORN).equals(Optional.of(nextReset(turnedAt.get()))),
                            "the cooldown ends at the next board reset");
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
                    advance(helper, phase, 1);
                }
                case 1 -> {
                    helper.assertTrue(rig.service().checkTurnIn(player, FEAT_ITEMS).isEmpty(),
                            "not ready to turn in: " + reasonOf(rig.service().checkTurnIn(player, FEAT_ITEMS)));
                    helper.assertTrue(rig.service().turnIn(player, FEAT_ITEMS).isEmpty(), "the turn-in was refused");
                    advance(helper, phase, 2);
                }
                default -> {
                    boolean delivered = rig.settlement().pendingFor(player).isEmpty()
                            && rig.storage().loadCharacter(player.getUUID()).join().reputation() == 3;
                    if (!delivered) {
                        assertReason(helper, (rig.service().checkTurnIn(player, FEAT_ITEMS)),
                                Refusal.Reason.SETTLING, "a second turn-in while settling");
                        helper.assertTrue(false, "waiting for settlement to deliver the reward");
                    }
                    helper.assertValueEqual(count(player, Items.PAPER), 0, "the feat's paper is taken");
                    GuildView view = rig.service().view(player).orElseThrow();
                    helper.assertValueEqual(view.standing(FEAT_ITEMS), Standing.COMPLETED, "standing of the feat");
                    helper.assertTrue(view.commissions().get(FEAT_ITEMS).completedAt().isPresent(),
                            "a completed feat has no completion time");
                    assertReason(helper, (rig.service().checkAccept(player, FEAT_ITEMS)),
                            Refusal.Reason.COMPLETED, "accept of a completed feat");
                    helper.assertTrue(!rig.service().boardEntries(player).orElseThrow().contains(FEAT_ITEMS),
                            "a completed feat is still listed");
                    helper.assertTrue(statusIs(rig, player, FEAT_ITEMS, CommissionStatus.COMPLETED), "the row in storage");
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
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertValueEqual(progress(rig, player, GREETER), 0, "an event before acceptance counted");
                    AlinaTriggers.MANUAL.trigger(player, "other");
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertValueEqual(progress(rig, player, GREETER), 0, "another key counted");
                    AlinaTriggers.MANUAL.trigger(player, "met");
                    advance(helper, step, 3);
                }
                case 3 -> {
                    helper.assertValueEqual(progress(rig, player, GREETER), 1, "the first event after acceptance");
                    eventAt.set(rig.ticks());
                    advance(helper, step, 4);
                }
                case 4 -> {
                    helper.assertValueEqual(storedProgress(rig, player, GREETER), 1,
                            "progress in storage after the flush");
                    helper.assertTrue(rig.ticks() - eventAt.get() <= GuildService.FLUSH_TICKS + FLUSH_SLACK,
                            "progress reached storage later than one flush period after the event");
                    advance(helper, step, 5);
                }
                case 5 -> {
                    AlinaTriggers.MANUAL.trigger(player, "met");
                    advance(helper, step, 6);
                }
                case 6 -> {
                    helper.assertValueEqual(progress(rig, player, GREETER), 2, "the second event");
                    helper.assertTrue(rig.messages(player).contains("Ready to turn in: Greeter"),
                            "no ready message at the count");
                    AlinaTriggers.MANUAL.trigger(player, "met");
                    advance(helper, step, 7);
                }
                case 7 -> {
                    helper.assertValueEqual(progress(rig, player, GREETER), 2, "an event after the count");
                    helper.assertTrue(rig.service().checkTurnIn(player, GREETER).isEmpty(),
                            "a met count is not ready: " + reasonOf(rig.service().checkTurnIn(player, GREETER)));
                    rig.service().leave(player);
                    rig.service().join(player);
                    advance(helper, step, 8);
                }
                default -> {
                    helper.assertValueEqual(progress(rig, player, GREETER), 2, "progress after the rejoin");
                    helper.assertValueEqual(rig.service().view(player).orElseThrow().standing(GREETER),
                            Standing.ACTIVE, "standing after the rejoin");
                }
            }
        });
    }

    @GameTest(maxTicks = 700)
    public void aFailedResetKeepsTheCommissionCountingAndWritesItsProgress(GameTestHelper helper) {
        AtomicInteger clears = new AtomicInteger();
        GuildRig rig = GuildRig.create(helper, BUNDLE, delegate -> failingFirst(delegate, "clearCommission", clears));
        ServerPlayer player = rig.joined();
        AtomicReference<CompletableFuture<Optional<Refusal>>> reset = new AtomicReference<>();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record or today's board has not loaded");
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(rig.service().accept(player, GREETER).isEmpty(), "the greeter was refused");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertTrue(rig.messages(player).contains("Commission accepted: Greeter"),
                            "the acceptance is not written yet");
                    AlinaTriggers.MANUAL.trigger(player, "met");
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertValueEqual(progress(rig, player, GREETER), 1, "the first event");
                    reset.set(rig.service().reset(player, GREETER));
                    advance(helper, step, 3);
                }
                case 3 -> {
                    helper.assertTrue(reset.get().isDone(), "the failed reset has not returned");
                    helper.assertTrue(reset.get().isCompletedExceptionally(), "the failed delete reported success");
                    helper.assertValueEqual(rig.service().view(player).orElseThrow().standing(GREETER),
                            Standing.ACTIVE, "standing after the failed reset");
                    helper.assertValueEqual(progress(rig, player, GREETER), 1, "progress after the failed reset");
                    AlinaTriggers.MANUAL.trigger(player, "met");
                    advance(helper, step, 4);
                }
                default -> {
                    helper.assertValueEqual(progress(rig, player, GREETER), 2,
                            "an event after the failed reset did not count");
                    helper.assertTrue(rig.messages(player).contains("Ready to turn in: Greeter"),
                            "no ready message at the count");
                    helper.assertValueEqual(storedProgress(rig, player, GREETER), 2,
                            "the progress after the failed reset was not written");
                }
            }
        });
    }

    @GameTest(maxTicks = 700)
    public void aSuccessfulResetLeavesNoProgressForTheNextAcceptance(GameTestHelper helper) {
        GuildRig rig = GuildRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        AtomicReference<CompletableFuture<Optional<Refusal>>> reset = new AtomicReference<>();
        AtomicLong since = new AtomicLong();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record or today's board has not loaded");
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(rig.service().accept(player, GREETER).isEmpty(), "the greeter was refused");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertTrue(rig.messages(player).contains("Commission accepted: Greeter"),
                            "the acceptance is not written yet");
                    reset.set(rig.service().reset(player, GREETER));
                    // The commission is still ACTIVE while the delete is written, so this event counts into the
                    // progress that the successful delete must drop.
                    AlinaTriggers.MANUAL.trigger(player, "met");
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertTrue(reset.get().isDone(), "the reset has not returned");
                    helper.assertTrue(!reset.get().isCompletedExceptionally(), "the reset failed");
                    helper.assertValueEqual(rig.service().view(player).orElseThrow().standing(GREETER),
                            Standing.AVAILABLE, "standing after the reset");
                    helper.assertTrue(rig.service().accept(player, GREETER).isEmpty(), "the reset commission was refused");
                    since.set(rig.ticks());
                    advance(helper, step, 3);
                }
                default -> {
                    helper.assertTrue(statusIs(rig, player, GREETER, CommissionStatus.ACTIVE), "the row of the new acceptance");
                    helper.assertTrue(rig.ticks() - since.get() >= GuildService.FLUSH_TICKS + FLUSH_SLACK,
                            "waiting for a flush after the new acceptance");
                    helper.assertValueEqual(progress(rig, player, GREETER), 0, "progress of the new acceptance");
                    helper.assertValueEqual(storedProgress(rig, player, GREETER), 0,
                            "the progress of the deleted acceptance was written to the new one");
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
    public void aStoredBoardIsServedAsStoredEvenWhenADrawWouldDiffer(GameTestHelper helper) {
        GuildRig rig = GuildRig.create(helper, BUNDLE);
        // A draw always holds both repeatables of this pool, so a board with one of them can only come from storage.
        rig.storage().storeBoard(new StoredBoard(LocalDate.of(2026, 11, 5), GuildRig.REVISION, 0L, List.of(HORN))).join();
        helper.succeedWhen(() -> {
            rig.tick();
            Optional<BoardView> board = rig.service().board();
            helper.assertTrue(board.isPresent(), "the board has not loaded");
            helper.assertValueEqual(board.get().commissions(), List.of(HORN), "the stored board");
            helper.assertTrue(board.get().stored(), "the stored board is not marked stored");
            helper.assertValueEqual(board.get().revisionId(), GuildRig.REVISION, "the stored revision");
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

    @GameTest(maxTicks = 400)
    public void aRestartAfterAMidDayContentChangeLoadsTheStoredBoard(GameTestHelper helper) {
        GuildRig rig = GuildRig.create(helper, BUNDLE);
        AtomicReference<GuildService> restarted = new AtomicReference<>();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            switch (step.get()) {
                case 0 -> {
                    rig.tick();
                    Optional<BoardView> today = rig.service().board();
                    helper.assertTrue(today.isPresent() && today.get().stored(), "today's board is not stored yet");
                    // A revision is activated mid-day, and the server restarts on it: the service has a new revision id.
                    rig.useContent(BUNDLE_NEXT);
                    restarted.set(rig.serviceOver(rig.storage(), rig::currentContent, () -> GuildRig.REVISION + 1));
                    advance(helper, step, 1);
                }
                default -> {
                    GuildService service = restarted.get();
                    service.tick();
                    Optional<BoardView> after = service.board();
                    helper.assertTrue(after.isPresent(), "the restarted board has not loaded");
                    helper.assertValueEqual(after.get().commissions(), List.of(COURIER),
                            "the stored board, less the commission the new content removed");
                    helper.assertTrue(!after.get().commissions().contains(NEW_ARRIVAL), "the restart redrew the board");
                    helper.assertValueEqual(after.get().revisionId(), GuildRig.REVISION, "the stored revision");
                }
            }
        });
    }

    @GameTest(maxTicks = 300)
    public void aBoardDrawnFromAnEmptyPoolIsDrawnAgainWhenTheContentChanges(GameTestHelper helper) {
        GuildRig rig = GuildRig.create(helper, FEATS);
        ServerPlayer player = rig.joined();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record or today's board has not loaded");
            switch (step.get()) {
                case 0 -> {
                    BoardView empty = rig.service().board().orElseThrow();
                    helper.assertTrue(empty.commissions().isEmpty() && !empty.stored(),
                            "a feats-only board is not an empty, unstored board: " + empty.commissions());
                    // The pool is empty again, so the board is redrawn at once: it is present with no tick in between.
                    rig.useContent(FEATS);
                    rig.service().contentActivated();
                    helper.assertTrue(rig.service().board().isPresent(),
                            "the board is missing right after an activation that leaves the pool empty");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    // The pool has repeatables now: the board is loaded, drawn and stored as at the start of the day.
                    rig.useContent(BUNDLE);
                    rig.service().contentActivated();
                    advance(helper, step, 2);
                }
                default -> {
                    BoardView drawn = rig.service().board().orElseThrow();
                    helper.assertTrue(drawn.stored(), "the redrawn board was not stored");
                    helper.assertValueEqual(drawn.commissions().stream().sorted().toList(),
                            List.of(COURIER, HORN).stream().sorted().toList(), "the redrawn board");
                    helper.assertValueEqual(rig.storage().loadBoard(LocalDate.of(2026, 11, 5)).join().orElseThrow()
                            .commissionIds().size(), 2, "the stored board");
                }
            }
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
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertValueEqual(rig.service().view(player).orElseThrow().standing(FEAT_ITEMS),
                            Standing.ACTIVE, "standing before the change");
                    helper.assertTrue(statusIs(rig, player, FEAT_ITEMS, CommissionStatus.ACTIVE), "the row before the change");
                    rig.useContent(BUNDLE_NEXT);
                    rig.service().contentActivated();
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertValueEqual(rig.service().view(player).orElseThrow().standing(FEAT_ITEMS),
                            Standing.AVAILABLE, "standing after the withdrawal");
                    helper.assertTrue(stored(rig, player, FEAT_ITEMS).isEmpty(), "the storage row is still there");
                    helper.assertTrue(rig.messages(player).contains("The Guild withdrew Paper Feat, so it is no longer active."),
                            "the player was not told of the withdrawal");
                    advance(helper, step, 3);
                }
                default -> helper.assertTrue(rig.service().view(player).isPresent(), "the record was lost");
            }
        });
    }

    @GameTest(maxTicks = 800)
    public void aRecordLoadedWithoutTheGuildContentKeepsItsActiveRows(GameTestHelper helper) {
        GuildRig rig = GuildRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        AtomicInteger step = new AtomicInteger();
        AtomicLong since = new AtomicLong();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record or today's board has not loaded");
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(rig.service().accept(player, HORN).isEmpty(), "the horn was refused");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertTrue(statusIs(rig, player, HORN, CommissionStatus.ACTIVE), "the row before the reload");
                    // The record loads while the active content is EMPTY: no bundle is active, or it has no alina:guild.
                    rig.service().leave(player);
                    rig.useContent(GuildContent.EMPTY);
                    rig.service().join(player);
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertTrue(statusIs(rig, player, HORN, CommissionStatus.ACTIVE), "the row after a load with EMPTY content");
                    helper.assertValueEqual(rig.service().view(player).orElseThrow().standing(HORN), Standing.ACTIVE,
                            "standing after a load with EMPTY content");
                    helper.assertTrue(!withdrawn(rig, player), "a withdrawal was sent for a load with EMPTY content");
                    rig.service().contentActivated();
                    since.set(rig.ticks());
                    advance(helper, step, 3);
                }
                case 3 -> {
                    // Any withdrawal would reach storage and the chat within a few ticks.
                    helper.assertTrue(rig.ticks() - since.get() >= 5, "waiting for any withdrawal to finish");
                    helper.assertTrue(statusIs(rig, player, HORN, CommissionStatus.ACTIVE), "the row after an activation with EMPTY content");
                    helper.assertTrue(!withdrawn(rig, player), "a withdrawal was sent for an activation with EMPTY content");
                    // haruhi's startup activation is not finished: a load under that state keeps the row too.
                    rig.setReady(false);
                    rig.useContent(BUNDLE_NEXT);
                    rig.service().leave(player);
                    rig.service().join(player);
                    advance(helper, step, 4);
                }
                case 4 -> {
                    helper.assertTrue(statusIs(rig, player, HORN, CommissionStatus.ACTIVE), "the row after a load before the startup activation finished");
                    helper.assertTrue(!withdrawn(rig, player), "a withdrawal was sent for a load before the startup finished");
                    // haruhi runs its activation listeners before it reports startup complete, so this is the startup
                    // activation itself: its section lacks the horn, and it withdraws it.
                    rig.service().contentActivated();
                    advance(helper, step, 5);
                }
                default -> {
                    helper.assertTrue(stored(rig, player, HORN).isEmpty(), "the startup activation did not withdraw the row");
                    helper.assertTrue(withdrawn(rig, player), "the player was not told of the withdrawal");
                }
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
                    advance(helper, step, 1);
                }
                case 1 -> {
                    move(player, IN_HIGH);
                    movedAt.set(rig.ticks());
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertValueEqual(entered.get(), 1, "volume_entered on entering the start volume");
                    movedAt.set(rig.ticks());
                    advance(helper, step, 3);
                }
                case 3 -> {
                    helper.assertTrue(rig.ticks() - movedAt.get() >= 2 * GuildService.DESCENT_CHECK_TICKS,
                            "waiting for the player to stay in the start volume");
                    helper.assertValueEqual(entered.get(), 1, "volume_entered again while the player stayed inside");
                    move(player, IN_BASIN);
                    movedAt.set(rig.ticks());
                    advance(helper, step, 4);
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
                    advance(helper, step, 1);
                }
                case 1 -> {
                    move(player, IN_HIGH);
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertValueEqual(entered.get(), 1, "volume_entered on entering the start volume");
                    player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 400));
                    helper.assertTrue(player.hasEffect(MobEffects.SLOW_FALLING), "the effect was not applied");
                    move(player, IN_BASIN);
                    movedAt.set(rig.ticks());
                    advance(helper, step, 3);
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
                    advance(helper, step, 1);
                }
                case 1 -> {
                    move(player, IN_HIGH);
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertValueEqual(entered.get(), 1, "volume_entered on entering the start volume");
                    CriteriaTriggers.USED_TOTEM.trigger(player, new ItemStack(Items.TOTEM_OF_UNDYING));
                    move(player, IN_BASIN);
                    movedAt.set(rig.ticks());
                    advance(helper, step, 3);
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

    @GameTest(maxTicks = 800)
    public void aPlayerWhoStandsInAVolumeWhenHeJoinsDoesNotEnterIt(GameTestHelper helper) {
        GuildRig rig = GuildRig.create(helper, BUNDLE);
        ServerPlayer player = rig.newPlayer();
        move(player, IN_HIGH);
        rig.service().join(player);
        AtomicInteger entered = new AtomicInteger();
        CriterionWatchers.watch(player.getUUID(), spec(helper, "alina:volume_entered",
                "{ \"volume\": \"alina:guild_test/high_dive\" }"), event -> entered.incrementAndGet());
        AtomicInteger step = new AtomicInteger();
        AtomicLong since = new AtomicLong();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(loaded(rig, player), "the record or today's board has not loaded");
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(rig.service().accept(player, DIVE).isEmpty(), "the dive was refused");
                    helper.assertTrue(rig.service().accept(player, HALL).isEmpty(), "the hall was refused");
                    since.set(rig.ticks());
                    advance(helper, step, 1);
                }
                case 1 -> {
                    // The player has stood in the start volume since the join: the descent checks must not count that.
                    helper.assertTrue(rig.ticks() - since.get() >= 2 * GuildService.DESCENT_CHECK_TICKS,
                            "waiting for the descent checks");
                    helper.assertValueEqual(entered.get(), 0, "volume_entered for a player who joined inside the volume");
                    helper.assertValueEqual(progress(rig, player, HALL), 0, "the hall counted the volume at the join");
                    helper.assertValueEqual(progress(rig, player, DIVE), 0, "the dive was armed at the join");
                    rig.service().leave(player);
                    rig.service().join(player);
                    since.set(rig.ticks());
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertTrue(rig.ticks() - since.get() >= 2 * GuildService.DESCENT_CHECK_TICKS,
                            "waiting for the descent checks after the rejoin");
                    helper.assertValueEqual(entered.get(), 0, "volume_entered for the rejoin");
                    helper.assertValueEqual(progress(rig, player, HALL), 0, "the hall counted the rejoin");
                    helper.assertValueEqual(progress(rig, player, DIVE), 0, "the dive was armed by the rejoin");
                    move(player, OUTSIDE);
                    since.set(rig.ticks());
                    advance(helper, step, 3);
                }
                case 3 -> {
                    helper.assertTrue(rig.ticks() - since.get() >= 2 * GuildService.DESCENT_CHECK_TICKS,
                            "waiting for the checks outside the volume");
                    move(player, IN_HIGH);
                    advance(helper, step, 4);
                }
                case 4 -> {
                    // A real entry still counts, and it arms the dive for the finish that follows.
                    helper.assertValueEqual(entered.get(), 1, "volume_entered for entering the volume");
                    helper.assertValueEqual(progress(rig, player, HALL), 1, "the hall did not count the entry");
                    move(player, IN_BASIN);
                    advance(helper, step, 5);
                }
                default -> {
                    helper.assertValueEqual(progress(rig, player, DIVE), 1, "the dive did not meet its proof");
                    helper.assertTrue(rig.messages(player).contains("Ready to turn in: Dive"), "no ready message");
                }
            }
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

    /** True when storage holds the commission with {@code status}. A missing row is false, so the check cannot throw. */
    private static boolean statusIs(GuildRig rig, ServerPlayer player, Identifier commission, CommissionStatus status) {
        return stored(rig, player, commission).map(CommissionState::status).filter(status::equals).isPresent();
    }

    /** The cooldown end the record's view shows for the commission, or empty when there is no view or no cooldown. */
    private static Optional<Instant> cooldownOf(GuildRig rig, ServerPlayer player, Identifier commission) {
        return rig.service().view(player).map(view -> view.commissions().get(commission))
                .flatMap(CommissionView::cooldownUntil);
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

    /** True when the player has been sent a withdrawal message. */
    private static boolean withdrawn(GuildRig rig, ServerPlayer player) {
        return rig.messages(player).stream().anyMatch(text -> text.startsWith("The Guild withdrew"));
    }

    private static Refusal.Reason reasonOf(Optional<Refusal> refusal) {
        return refusal.map(Refusal::reason).orElse(null);
    }

    /**
     * Asserts that {@code refusal} is a refusal for {@code expected}. An accepted operation (empty) fails the assertion,
     * where {@code assertValueEqual} would throw on the null reason.
     */
    private static void assertReason(GameTestHelper helper, Optional<Refusal> refusal, Refusal.Reason expected,
            String message) {
        helper.assertTrue(refusal.map(Refusal::reason).filter(expected::equals).isPresent(),
                message + ": expected a refusal for " + expected + ", got " + reasonOf(refusal));
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
     * A storage whose first call to {@code method} returns {@code gate}, which the test completes when it chooses, so that
     * call stays in flight until then. Other calls reach {@code delegate}. {@code calls} counts the calls to {@code method}.
     */
    private static AlinaStorage gatedFirst(AlinaStorage delegate, String method, AtomicInteger calls,
            CompletableFuture<Object> gate) {
        return (AlinaStorage) Proxy.newProxyInstance(AlinaStorage.class.getClassLoader(),
                new Class<?>[] {AlinaStorage.class},
                (proxy, called, args) -> {
                    if (called.getName().equals(method) && calls.incrementAndGet() == 1) {
                        return gate;
                    }
                    try {
                        return called.invoke(delegate, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    /**
     * A storage whose first call to {@code method} fails without writing, and whose other calls reach {@code delegate}.
     * {@code calls} counts the calls to {@code method}.
     */
    private static AlinaStorage failingFirst(AlinaStorage delegate, String method, AtomicInteger calls) {
        return (AlinaStorage) Proxy.newProxyInstance(AlinaStorage.class.getClassLoader(),
                new Class<?>[] {AlinaStorage.class},
                (proxy, called, args) -> {
                    if (called.getName().equals(method) && calls.incrementAndGet() == 1) {
                        return CompletableFuture.failedFuture(new IllegalStateException("injected " + method + " failure"));
                    }
                    try {
                        return called.invoke(delegate, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }
}
