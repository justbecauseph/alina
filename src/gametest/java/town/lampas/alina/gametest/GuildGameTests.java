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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import town.lampas.alina.Alina;
import town.lampas.alina.content.Board;
import town.lampas.alina.content.Commission;
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
    /** The implicit board of BUNDLE, ROTATION and FEATS, which have no boards key. */
    private static final Identifier BOARD = GuildContent.DEFAULT_BOARD;
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
    /** Four boards: hunters and town draw three of six repeatables each, quarry draws two of two, dock has no repeatable. */
    private static final String BOARDS = "boards.json";
    /** The same content without quarry_1, which the quarry board then loses. */
    private static final String BOARDS_NEXT = "boards_next.json";
    private static final Identifier HUNTERS = Alina.id("guild/hunters");
    private static final Identifier TOWN = Alina.id("guild/town");
    private static final Identifier QUARRY = Alina.id("guild/quarry");
    private static final Identifier DOCK = Alina.id("guild/dock");
    private static final Identifier HUNT_FEAT = Alina.id("guild_test/hunt_feat");
    private static final Identifier TOWN_FEAT = Alina.id("guild_test/town_feat");
    private static final Identifier DOCK_FEAT = Alina.id("guild_test/dock_feat");
    private static final Identifier QUARRY_1 = Alina.id("guild_test/quarry_1");
    private static final Identifier QUARRY_2 = Alina.id("guild_test/quarry_2");
    /** The board day of {@link GuildRig#START}. */
    private static final LocalDate BOARD_DAY = LocalDate.of(2026, 11, 5);
    private static final ZoneId MANILA = ZoneId.of("Asia/Manila");
    /** The courier's cooldown: the content's default, PT20H. */
    private static final Duration COURIER_COOLDOWN = Duration.ofHours(20);
    /** Positions in the fixture's volumes, which are inclusive block boxes at x 500 to 503 and z 500 to 503. */
    private static final Vec3 OUTSIDE = new Vec3(0.5, 100.5, 0.5);
    private static final Vec3 IN_HIGH = new Vec3(501.5, 200.5, 501.5);
    private static final Vec3 IN_BASIN = new Vec3(501.5, 60.5, 501.5);
    /** Between the volumes: above the basin and below the start volume, outside both. */
    private static final Vec3 BETWEEN = new Vec3(501.5, 130.5, 501.5);
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
            List<Identifier> entries = rig.service().boardEntries(player, BOARD).orElseThrow();
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
                    // The abandon has been written, so the slot it freed is free for the feat.
                    helper.assertTrue(rig.messages(player).contains("Commission abandoned: Courier"),
                            "the abandon is not written yet");
                    helper.assertValueEqual(rig.service().view(player).orElseThrow().standing(COURIER),
                            Standing.AVAILABLE, "standing after abandon");
                    helper.assertTrue(rig.service().checkAccept(player, FEAT_ITEMS).isEmpty(),
                            "the freed slot refused the feat: " + reasonOf(rig.service().checkAccept(player, FEAT_ITEMS)));
                    helper.assertTrue(rig.service().accept(player, FEAT_ITEMS).isEmpty(),
                            "the feat was refused after the abandon");
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

    /**
     * An abandon frees its slot at once. While its first write is held, a feat is accepted into the slot; when the write
     * then fails, the slot is taken, so the abandon is written again rather than put back, and the cap ends at active_cap.
     */
    @GameTest(maxTicks = 600)
    public void anAbandonFreesItsSlotAtOnceAndAFailedWriteWhoseSlotIsTakenIsWrittenAgain(GameTestHelper helper) {
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
                    helper.assertTrue(rig.service().checkAbandon(player, COURIER).isEmpty(),
                            "the courier cannot be abandoned: " + reasonOf(rig.service().checkAbandon(player, COURIER)));
                    helper.assertTrue(rig.service().abandon(player, COURIER).isEmpty(), "the abandon was refused");
                    advance(helper, step, 2);
                }
                case 2 -> {
                    // The abandon's write is held, and its slot is already free: a feat is accepted into it.
                    helper.assertTrue(rig.service().checkAccept(player, FEAT_ITEMS).isEmpty(),
                            "a feat refused while the abandon is written: " + reasonOf(rig.service().checkAccept(player, FEAT_ITEMS)));
                    helper.assertTrue(rig.service().accept(player, FEAT_ITEMS).isEmpty(),
                            "the feat was refused while the abandon is written");
                    advance(helper, step, 3);
                }
                case 3 -> {
                    // Storage now answers the first abandon with a failure. The slot is taken, so it is not put back.
                    gate.completeExceptionally(new IllegalStateException("injected abandonCommission failure"));
                    advance(helper, step, 4);
                }
                default -> {
                    helper.assertValueEqual(abandons.get(), 2, "abandonCommission calls: the failed write and its retry");
                    helper.assertTrue(rig.messages(player).contains("Commission abandoned: Courier"),
                            "the retried abandon was not reported as done");
                    helper.assertTrue(!rig.messages(player).contains("The Guild could not abandon Courier. Try again."),
                            "the failed write was reported to the player");
                    GuildView view = rig.service().view(player).orElseThrow();
                    helper.assertValueEqual(view.standing(COURIER), Standing.AVAILABLE, "the courier after the retry");
                    helper.assertValueEqual(view.activeCount(), 2, "active commissions in the record");
                    helper.assertTrue(stored(rig, player, COURIER).isEmpty(), "the courier row is still in storage");
                    helper.assertValueEqual(activeInStorage(rig, player), 2, "active commissions in storage");
                    helper.assertTrue(statusIs(rig, player, HORN, CommissionStatus.ACTIVE)
                            && statusIs(rig, player, FEAT_ITEMS, CommissionStatus.ACTIVE), "the rows after the retry");
                }
            }
        });
    }

    @GameTest(maxTicks = 500)
    public void aFailedAbandonWithAFreeSlotPutsTheRowBackWithItsProgressAndTracking(GameTestHelper helper) {
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
                    helper.assertTrue(rig.service().checkAbandon(player, GREETER).isEmpty(),
                            "the greeter cannot be abandoned: " + reasonOf(rig.service().checkAbandon(player, GREETER)));
                    helper.assertTrue(rig.service().abandon(player, GREETER).isEmpty(), "the abandon was refused");
                    advance(helper, step, 3);
                }
                case 3 -> {
                    gate.completeExceptionally(new IllegalStateException("injected abandonCommission failure"));
                    advance(helper, step, 4);
                }
                case 4 -> {
                    // The slot is free, so the row goes back at once and is not written again.
                    helper.assertTrue(rig.messages(player).contains("The Guild could not abandon Greeter. Try again."),
                            "the abandon failure was not reported");
                    helper.assertValueEqual(abandons.get(), 1, "abandonCommission calls for a free slot");
                    helper.assertValueEqual(rig.service().view(player).orElseThrow().standing(GREETER),
                            Standing.ACTIVE, "the greeter after the failed abandon");
                    helper.assertValueEqual(progress(rig, player, GREETER), 1, "the progress after the failed abandon");
                    helper.assertTrue(statusIs(rig, player, GREETER, CommissionStatus.ACTIVE),
                            "the row storage still holds");
                    // The restored tracking counts the next event.
                    AlinaTriggers.MANUAL.trigger(player, "met");
                    advance(helper, step, 5);
                }
                default -> {
                    helper.assertValueEqual(progress(rig, player, GREETER), 2, "the event after the restored row");
                    helper.assertTrue(rig.messages(player).contains("Ready to turn in: Greeter"),
                            "no ready message at the count");
                    helper.assertValueEqual(storedProgress(rig, player, GREETER), 2,
                            "the progress of the restored row was not written");
                }
            }
        });
    }

    @GameTest(maxTicks = 600)
    public void aSecondFailedAbandonIsReportedAndNotWrittenAThirdTime(GameTestHelper helper) {
        AtomicInteger abandons = new AtomicInteger();
        CompletableFuture<Object> gate = new CompletableFuture<>();
        GuildRig rig = GuildRig.create(helper, BUNDLE, delegate -> answeredInOrder(delegate, "abandonCommission", abandons,
                List.of(gate, CompletableFuture.<Object>failedFuture(
                        new IllegalStateException("injected abandonCommission failure")))));
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
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertTrue(rig.service().checkAccept(player, FEAT_ITEMS).isEmpty(),
                            "a feat refused while the abandon is written: " + reasonOf(rig.service().checkAccept(player, FEAT_ITEMS)));
                    helper.assertTrue(rig.service().accept(player, FEAT_ITEMS).isEmpty(),
                            "the feat was refused while the abandon is written");
                    advance(helper, step, 3);
                }
                case 3 -> {
                    // The first write fails, the slot is taken, and the retry fails as well.
                    gate.completeExceptionally(new IllegalStateException("injected abandonCommission failure"));
                    advance(helper, step, 4);
                }
                default -> {
                    helper.assertValueEqual(abandons.get(), 2, "abandonCommission calls: the write and one retry");
                    helper.assertTrue(rig.messages(player).contains("The Guild could not abandon Courier. Try again."),
                            "the second failure was not reported");
                    helper.assertTrue(!rig.messages(player).contains("Commission abandoned: Courier"),
                            "a failed abandon was reported as done");
                    helper.assertTrue(statusIs(rig, player, COURIER, CommissionStatus.ACTIVE),
                            "storage no longer holds the courier row after two failed writes");
                }
            }
        });
    }

    /**
     * A revision without the horn is activated while the horn's abandon is written, and the write then fails. The content
     * no longer has the horn, so the abandon is written again, not put back, and the second write deletes the row.
     */
    @GameTest(maxTicks = 800)
    public void anAbandonThatFailsAfterAnActivationRemovedTheCommissionIsWrittenAgain(GameTestHelper helper) {
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
                    // Then storage fails the abandon, and the retry is written.
                    gate.completeExceptionally(new IllegalStateException("injected abandonCommission failure"));
                    advance(helper, step, 3);
                }
                default -> {
                    helper.assertValueEqual(abandons.get(), 2, "abandonCommission calls: the failed write and its retry");
                    helper.assertTrue(rig.messages(player).contains("Commission abandoned: Horn"),
                            "the retried abandon was not reported as done");
                    helper.assertTrue(!rig.messages(player).contains("The Guild could not abandon Horn. Try again."),
                            "the failed write was reported to the player");
                    helper.assertTrue(stored(rig, player, HORN).isEmpty(), "the row is still in storage without a rejoin");
                    helper.assertTrue(!withdrawn(rig, player), "the abandoned horn was reported as withdrawn");
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
                    helper.assertTrue(rig.service().board(BOARD).isPresent(), "the next board has not loaded");
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
                    helper.assertTrue(!rig.service().boardEntries(player, BOARD).orElseThrow().contains(FEAT_ITEMS),
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
        AtomicLong failedAt = new AtomicLong();
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
                    // The reset fails a tick or two after the step, so no flush may be due before then: the step waits
                    // until the next flush is more than a few ticks away.
                    long toFlush = GuildService.FLUSH_TICKS - rig.ticks() % GuildService.FLUSH_TICKS;
                    helper.assertTrue(toFlush > FLUSH_SLACK + 3, "waiting for a tick clear of a flush");
                    // The event and the reset share this step, so the count is unwritten when the reset fails.
                    AlinaTriggers.MANUAL.trigger(player, "met");
                    reset.set(rig.service().reset(player, GREETER));
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertValueEqual(progress(rig, player, GREETER), 1, "the first event");
                    helper.assertTrue(reset.get().isDone(), "the failed reset has not returned");
                    helper.assertTrue(reset.get().isCompletedExceptionally(), "the failed delete reported success");
                    helper.assertValueEqual(rig.service().view(player).orElseThrow().standing(GREETER),
                            Standing.ACTIVE, "standing after the failed reset");
                    failedAt.set(rig.ticks());
                    advance(helper, step, 3);
                }
                case 3 -> {
                    // The count the failed reset left unwritten reaches storage at the next flush. If the reset had dropped
                    // it, storage would stay at 0 and this step would time out.
                    helper.assertValueEqual(storedProgress(rig, player, GREETER), 1,
                            "the progress unwritten at the failed reset was dropped");
                    helper.assertTrue(rig.ticks() - failedAt.get() <= GuildService.FLUSH_TICKS + FLUSH_SLACK,
                            "the unwritten progress reached storage later than one flush period");
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
            Optional<BoardView> a = first.board(BOARD);
            Optional<BoardView> b = second.board(BOARD);
            Optional<BoardView> c = fresh.board(BOARD);
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
        rig.storage().storeBoard(new StoredBoard(LocalDate.of(2026, 11, 5), GuildContent.DEFAULT_BOARD, GuildRig.REVISION,
                0L, List.of(HORN))).join();
        helper.succeedWhen(() -> {
            rig.tick();
            Optional<BoardView> board = rig.service().board(BOARD);
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
                before.set(rig.service().board(BOARD).orElseThrow().commissions());
                helper.assertTrue(before.get().contains(HORN), "the horn is not on the board: " + before.get());
                rig.useContent(BUNDLE_NEXT);
                rig.service().contentActivated();
                changed.set(true);
            }
            List<Identifier> expected = before.get().stream().filter(id -> !id.equals(HORN)).toList();
            Optional<BoardView> today = rig.service().board(BOARD);
            helper.assertTrue(today.isPresent(), "the activation dropped the stored board");
            List<Identifier> after = today.get().commissions();
            helper.assertValueEqual(after, expected, "the board after the change");
            helper.assertTrue(!after.contains(NEW_ARRIVAL), "the new commission was added to the board mid-day");
            Optional<StoredBoard> stored = rig.storage().loadBoard(LocalDate.of(2026, 11, 5), GuildContent.DEFAULT_BOARD).join();
            helper.assertTrue(stored.isPresent(), "the stored board is missing");
            helper.assertValueEqual(stored.get().commissionIds(), before.get(), "the stored board");
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
                    Optional<BoardView> today = rig.service().board(BOARD);
                    helper.assertTrue(today.isPresent() && today.get().stored(), "today's board is not stored yet");
                    // A revision is activated mid-day, and the server restarts on it: the service has a new revision id.
                    rig.useContent(BUNDLE_NEXT);
                    restarted.set(rig.serviceOver(rig.storage(), rig::currentContent, () -> GuildRig.REVISION + 1));
                    advance(helper, step, 1);
                }
                default -> {
                    GuildService service = restarted.get();
                    service.tick();
                    Optional<BoardView> after = service.board(BOARD);
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
                    BoardView empty = rig.service().board(BOARD).orElseThrow();
                    helper.assertTrue(empty.commissions().isEmpty() && !empty.stored(),
                            "a feats-only board is not an empty, unstored board: " + empty.commissions());
                    // The pool is empty again, so the board is redrawn at once: it is present with no tick in between.
                    rig.useContent(FEATS);
                    rig.service().contentActivated();
                    helper.assertTrue(rig.service().board(BOARD).isPresent(),
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
                    BoardView drawn = rig.service().board(BOARD).orElseThrow();
                    helper.assertTrue(drawn.stored(), "the redrawn board was not stored");
                    helper.assertValueEqual(drawn.commissions().stream().sorted().toList(),
                            List.of(COURIER, HORN).stream().sorted().toList(), "the redrawn board");
                    Optional<StoredBoard> stored = rig.storage().loadBoard(LocalDate.of(2026, 11, 5), GuildContent.DEFAULT_BOARD).join();
                    helper.assertTrue(stored.isPresent(), "the redrawn board is not in storage");
                    helper.assertValueEqual(stored.get().commissionIds().size(), 2, "the stored board");
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

    @GameTest(maxTicks = 500)
    public void aWithdrawalWhoseStorageWriteFailsIsWrittenAgain(GameTestHelper helper) {
        AtomicInteger deletes = new AtomicInteger();
        GuildRig rig = GuildRig.create(helper, BUNDLE,
                delegate -> failingFirst(delegate, "abandonCommission", deletes));
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
                    helper.assertTrue(statusIs(rig, player, FEAT_ITEMS, CommissionStatus.ACTIVE), "the row before the change");
                    // The first delete of the withdrawal fails. The content no longer has the feat, so it is written again.
                    rig.useContent(BUNDLE_NEXT);
                    rig.service().contentActivated();
                    advance(helper, step, 2);
                }
                default -> {
                    helper.assertValueEqual(deletes.get(), 2, "abandonCommission calls: the failed delete and its retry");
                    helper.assertTrue(withdrawn(rig, player), "the player was not told of the withdrawal");
                    helper.assertTrue(stored(rig, player, FEAT_ITEMS).isEmpty(),
                            "the withdrawn row is still in storage without a rejoin");
                    helper.assertValueEqual(rig.service().view(player).orElseThrow().standing(FEAT_ITEMS),
                            Standing.AVAILABLE, "the feat after the withdrawal");
                }
            }
        });
    }

    /**
     * A record still loading when an activation runs takes its volume baseline from the new content. The player stands
     * in the hall at the join, when the content is empty, and the record's load is held until after the activation: the
     * player has not entered anything, so no volume is counted and no dive is armed.
     */
    @GameTest(maxTicks = 900)
    public void aRecordStillLoadingAtAnActivationTakesItsBaselineFromTheNewContent(GameTestHelper helper) {
        AtomicBoolean holding = new AtomicBoolean();
        CompletableFuture<Void> release = new CompletableFuture<>();
        GuildRig rig = GuildRig.create(helper, BUNDLE, delegate -> heldLoads(delegate, holding, release));
        ServerPlayer player = rig.newPlayer();
        move(player, OUTSIDE);
        rig.service().join(player);
        AtomicInteger entered = new AtomicInteger();
        CriterionWatchers.watch(player.getUUID(), spec(helper, "alina:volume_entered",
                "{ \"volume\": \"alina:guild_test/high_dive\" }"), event -> entered.incrementAndGet());
        AtomicInteger step = new AtomicInteger();
        AtomicLong since = new AtomicLong();
        helper.succeedWhen(() -> {
            rig.tick();
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(loaded(rig, player), "the record or today's board has not loaded");
                    helper.assertTrue(rig.service().accept(player, HALL).isEmpty(), "the hall was refused");
                    helper.assertTrue(rig.service().accept(player, DIVE).isEmpty(), "the dive was refused");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertTrue(statusIs(rig, player, HALL, CommissionStatus.ACTIVE)
                            && statusIs(rig, player, DIVE, CommissionStatus.ACTIVE), "the rows are not written yet");
                    // The player rejoins inside the hall. The content is empty while the record loads, and the load is held.
                    rig.service().leave(player);
                    move(player, IN_HIGH);
                    rig.useContent(GuildContent.EMPTY);
                    holding.set(true);
                    rig.service().join(player);
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertTrue(rig.service().view(player).isEmpty(), "the record loaded while its load was held");
                    // The activation of the new content runs while the record is still loading.
                    rig.useContent(BUNDLE);
                    rig.service().contentActivated();
                    holding.set(false);
                    release.complete(null);
                    advance(helper, step, 3);
                }
                case 3 -> {
                    helper.assertTrue(rig.service().view(player).isPresent(), "the record has not loaded after its release");
                    since.set(rig.ticks());
                    advance(helper, step, 4);
                }
                case 4 -> {
                    helper.assertTrue(rig.ticks() - since.get() >= 2 * GuildService.DESCENT_CHECK_TICKS,
                            "waiting for the descent checks after the load");
                    helper.assertValueEqual(entered.get(), 0, "volume_entered for a volume the player was standing in");
                    helper.assertValueEqual(progress(rig, player, HALL), 0, "the hall counted a volume the player did not enter");
                    // An armed dive would meet its proof here, so the basin shows that no dive was armed.
                    move(player, IN_BASIN);
                    since.set(rig.ticks());
                    advance(helper, step, 5);
                }
                default -> {
                    helper.assertTrue(rig.ticks() - since.get() >= 2 * GuildService.DESCENT_CHECK_TICKS,
                            "waiting for the checks at the basin");
                    helper.assertValueEqual(progress(rig, player, DIVE), 0, "a dive armed at the activation met its proof");
                    helper.assertValueEqual(entered.get(), 0, "volume_entered after the player moved to the basin");
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
                    // The dive is armed now. Between the volumes, Slow Falling starts.
                    helper.assertValueEqual(entered.get(), 1, "volume_entered on entering the start volume");
                    move(player, BETWEEN);
                    player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 400));
                    advance(helper, step, 3);
                }
                case 3 -> {
                    // The check at the start of this pass saw the effect, so the dive is disarmed. The effect ends now,
                    // and the basin is reached with no effect: a dive that was not disarmed meets its proof here.
                    helper.assertTrue(player.hasEffect(MobEffects.SLOW_FALLING), "Slow Falling ended before the basin");
                    player.removeEffect(MobEffects.SLOW_FALLING);
                    move(player, IN_BASIN);
                    movedAt.set(rig.ticks());
                    advance(helper, step, 4);
                }
                default -> {
                    helper.assertTrue(rig.ticks() - movedAt.get() >= 2 * GuildService.DESCENT_CHECK_TICKS,
                            "waiting for the checks at the finish");
                    helper.assertValueEqual(progress(rig, player, DIVE), 0,
                            "Slow Falling between the volumes did not disarm the dive");
                }
            }
        });
    }

    @GameTest(maxTicks = 500)
    public void glidingBetweenTheVolumesDoesNotMeetTheProof(GameTestHelper helper) {
        GuildRig rig = GuildRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        player.setPos(OUTSIDE.x, OUTSIDE.y, OUTSIDE.z);
        AtomicInteger entered = new AtomicInteger();
        CriterionWatchers.watch(player.getUUID(), spec(helper, "alina:volume_entered",
                "{ \"volume\": \"alina:guild_test/high_dive\" }"), event -> entered.incrementAndGet());
        AtomicBoolean glided = new AtomicBoolean();
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
                    // The dive is armed now. Between the volumes, an elytra starts a glide: tryToStartFallFlying needs
                    // the elytra in the chest slot and a player who can glide, which the airborne mock player is.
                    helper.assertValueEqual(entered.get(), 1, "volume_entered on entering the start volume");
                    move(player, BETWEEN);
                    player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
                    glided.set(player.tryToStartFallFlying());
                    advance(helper, step, 3);
                }
                case 3 -> {
                    // The check at the start of this pass saw the glide, so the dive is disarmed. The glide ends now, and
                    // the basin is reached while not gliding.
                    helper.assertTrue(glided.get(), "the elytra did not start a glide");
                    helper.assertTrue(player.isFallFlying(), "the player is not gliding before the basin");
                    player.stopFallFlying();
                    move(player, IN_BASIN);
                    movedAt.set(rig.ticks());
                    advance(helper, step, 4);
                }
                default -> {
                    helper.assertTrue(rig.ticks() - movedAt.get() >= 2 * GuildService.DESCENT_CHECK_TICKS,
                            "waiting for the checks at the finish");
                    helper.assertValueEqual(progress(rig, player, DIVE), 0,
                            "a glide between the volumes did not disarm the dive");
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

    @GameTest(maxTicks = 900)
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
                    // Straight from the start volume into the basin: a dive armed at the join would meet its proof here.
                    move(player, IN_BASIN);
                    since.set(rig.ticks());
                    advance(helper, step, 2);
                }
                case 2 -> {
                    helper.assertTrue(rig.ticks() - since.get() >= 2 * GuildService.DESCENT_CHECK_TICKS,
                            "waiting for the checks at the basin after the join");
                    helper.assertValueEqual(progress(rig, player, DIVE), 0, "a dive armed at the join met its proof");
                    rig.service().leave(player);
                    move(player, IN_HIGH);
                    rig.service().join(player);
                    since.set(rig.ticks());
                    advance(helper, step, 3);
                }
                case 3 -> {
                    helper.assertTrue(rig.ticks() - since.get() >= 2 * GuildService.DESCENT_CHECK_TICKS,
                            "waiting for the descent checks after the rejoin");
                    helper.assertValueEqual(entered.get(), 0, "volume_entered for the rejoin");
                    helper.assertValueEqual(progress(rig, player, HALL), 0, "the hall counted the rejoin");
                    // Straight from the start volume into the basin again, now after the rejoin.
                    move(player, IN_BASIN);
                    since.set(rig.ticks());
                    advance(helper, step, 4);
                }
                case 4 -> {
                    helper.assertTrue(rig.ticks() - since.get() >= 2 * GuildService.DESCENT_CHECK_TICKS,
                            "waiting for the checks at the basin after the rejoin");
                    helper.assertValueEqual(progress(rig, player, DIVE), 0, "a dive armed by the rejoin met its proof");
                    move(player, OUTSIDE);
                    since.set(rig.ticks());
                    advance(helper, step, 5);
                }
                case 5 -> {
                    helper.assertTrue(rig.ticks() - since.get() >= 2 * GuildService.DESCENT_CHECK_TICKS,
                            "waiting for the checks outside the volume");
                    move(player, IN_HIGH);
                    advance(helper, step, 6);
                }
                case 6 -> {
                    // A real entry still counts, and it arms the dive for the finish that follows.
                    helper.assertValueEqual(entered.get(), 1, "volume_entered for entering the volume");
                    helper.assertValueEqual(progress(rig, player, HALL), 1, "the hall did not count the entry");
                    move(player, IN_BASIN);
                    advance(helper, step, 7);
                }
                default -> {
                    helper.assertValueEqual(progress(rig, player, DIVE), 1, "the dive did not meet its proof");
                    helper.assertTrue(rig.messages(player).contains("Ready to turn in: Dive"), "no ready message");
                }
            }
        });
    }

    // Boards: each board has its own pool and its own stored draw (ALINA-PLAN §8.2).

    @GameTest(maxTicks = 300)
    public void twoBoardsDrawTheirOwnPoolsDifferentlyFromTheSameDay(GameTestHelper helper) {
        GuildRig rig = GuildRig.create(helper, BOARDS);
        helper.succeedWhen(() -> {
            rig.tick();
            BoardView hunters = loadedBoard(helper, rig.service(), HUNTERS);
            BoardView town = loadedBoard(helper, rig.service(), TOWN);
            loadedBoard(helper, rig.service(), QUARRY);
            loadedBoard(helper, rig.service(), DOCK);
            helper.assertValueEqual(rig.service().boards().stream().map(BoardView::boardId).toList(),
                    List.of(DOCK, HUNTERS, QUARRY, TOWN), "the boards, in id order");
            helper.assertValueEqual(hunters.commissions().size(), 3, "hunters draws its size");
            helper.assertValueEqual(town.commissions().size(), 3, "town draws its size");
            helper.assertTrue(hunters.stored() && town.stored(), "a board with a pool was not stored");
            helper.assertTrue(poolOf(rig, HUNTERS).containsAll(hunters.commissions()),
                    "hunters drew outside its pool: " + hunters.commissions());
            helper.assertTrue(poolOf(rig, TOWN).containsAll(town.commissions()),
                    "town drew outside its pool: " + town.commissions());
            helper.assertTrue(!numbers(hunters.commissions()).equals(numbers(town.commissions())),
                    "the two boards drew the same positions of their pools: " + hunters.commissions()
                            + " and " + town.commissions());
            Optional<StoredBoard> storedHunters = rig.storage().loadBoard(BOARD_DAY, HUNTERS).join();
            Optional<StoredBoard> storedTown = rig.storage().loadBoard(BOARD_DAY, TOWN).join();
            helper.assertTrue(storedHunters.isPresent() && storedTown.isPresent(), "a board's draw is not in storage");
            helper.assertValueEqual(storedHunters.get().commissionIds(), hunters.commissions(), "the stored hunters draw");
            helper.assertValueEqual(storedTown.get().commissionIds(), town.commissions(), "the stored town draw");
            helper.assertTrue(storedHunters.get().seed() != storedTown.get().seed(), "the two boards share a seed");
            helper.assertValueEqual(hunters.revisionId(), GuildRig.REVISION, "the revision of hunters");
        });
    }

    /**
     * A stored draw per board: the quarry's draw loses the commission its pool lost in a mid-day activation, and the other
     * boards keep theirs. A service restarted on the new content, with a new revision, serves the stored draws.
     */
    @GameTest(maxTicks = 500)
    public void aStoredDrawPerBoardSurvivesARestartAndAMidDayActivation(GameTestHelper helper) {
        GuildRig rig = GuildRig.create(helper, BOARDS);
        AtomicReference<List<Identifier>> hunterDraw = new AtomicReference<>();
        AtomicReference<List<Identifier>> townDraw = new AtomicReference<>();
        AtomicReference<GuildService> restarted = new AtomicReference<>();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            switch (step.get()) {
                case 0 -> {
                    BoardView hunters = loadedBoard(helper, rig.service(), HUNTERS);
                    BoardView town = loadedBoard(helper, rig.service(), TOWN);
                    BoardView quarry = loadedBoard(helper, rig.service(), QUARRY);
                    loadedBoard(helper, rig.service(), DOCK);
                    helper.assertValueEqual(quarry.commissions().stream().sorted().toList(),
                            List.of(QUARRY_1, QUARRY_2), "quarry draws both its repeatables");
                    hunterDraw.set(hunters.commissions());
                    townDraw.set(town.commissions());
                    // A revision is activated mid-day, and the quarry's pool loses quarry_1.
                    rig.useContent(BOARDS_NEXT);
                    rig.service().contentActivated();
                    advance(helper, step, 1);
                }
                case 1 -> {
                    helper.assertValueEqual(loadedBoard(helper, rig.service(), HUNTERS).commissions(), hunterDraw.get(),
                            "the hunters draw changed in a mid-day activation");
                    helper.assertValueEqual(loadedBoard(helper, rig.service(), TOWN).commissions(), townDraw.get(),
                            "the town draw changed in a mid-day activation");
                    BoardView quarry = loadedBoard(helper, rig.service(), QUARRY);
                    helper.assertValueEqual(quarry.commissions(), List.of(QUARRY_2),
                            "the quarry draw, less the commission its pool lost");
                    helper.assertTrue(quarry.stored(), "the quarry draw was not kept as stored");
                    // A restart on the new content, with a new revision, serves what storage holds.
                    restarted.set(rig.serviceOver(rig.storage(), rig::currentContent, () -> GuildRig.REVISION + 1));
                    advance(helper, step, 2);
                }
                default -> {
                    GuildService service = restarted.get();
                    service.tick();
                    helper.assertValueEqual(loadedBoard(helper, service, HUNTERS).commissions(), hunterDraw.get(),
                            "the hunters draw after the restart");
                    helper.assertValueEqual(loadedBoard(helper, service, TOWN).commissions(), townDraw.get(),
                            "the town draw after the restart");
                    BoardView quarry = loadedBoard(helper, service, QUARRY);
                    helper.assertValueEqual(quarry.commissions(), List.of(QUARRY_2), "the quarry draw after the restart");
                    helper.assertValueEqual(quarry.revisionId(), GuildRig.REVISION, "the stored revision after the restart");
                    helper.assertTrue(quarry.stored(), "the restarted quarry draw was redrawn");
                }
            }
        });
    }

    /**
     * A repeatable is accepted only from the draw of its own board, and a repeatable whose board is still loading is
     * refused with {@code BOARD_LOADING} whatever the other boards hold. A feat needs no draw at all.
     */
    @GameTest(maxTicks = 600)
    public void aRepeatableIsAcceptedOnlyFromItsOwnBoardsDraw(GameTestHelper helper) {
        CompletableFuture<Void> release = new CompletableFuture<>();
        GuildRig rig = GuildRig.create(helper, BOARDS, delegate -> heldBoardLoads(delegate, TOWN, release));
        ServerPlayer player = rig.joined();
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(rig.service().view(player).isPresent(), "the record has not loaded");
            switch (step.get()) {
                case 0 -> {
                    List<Identifier> drawn = loadedBoard(helper, rig.service(), HUNTERS).commissions();
                    helper.assertValueEqual(drawn.size(), 3, "hunters draw size");
                    Identifier onDraw = drawn.get(0);
                    Identifier offDraw = poolOf(rig, HUNTERS).stream().filter(id -> !drawn.contains(id)).findFirst()
                            .orElseThrow(() -> helper.assertionException("every hunters repeatable is drawn"));
                    helper.assertTrue(rig.service().accept(player, onDraw).isEmpty(),
                            "a repeatable on its board's draw was refused");
                    assertReason(helper, rig.service().checkAccept(player, offDraw), Refusal.Reason.NOT_ON_BOARD,
                            "a repeatable off its board's draw");
                    helper.assertTrue(rig.service().board(TOWN).isEmpty(), "the town's board loaded while its load is held");
                    List<Identifier> townPool = poolOf(rig, TOWN);
                    helper.assertTrue(!townPool.isEmpty(), "the town has no repeatable in its pool");
                    Identifier townRepeatable = townPool.get(0);
                    assertReason(helper, rig.service().checkAccept(player, townRepeatable), Refusal.Reason.BOARD_LOADING,
                            "a town repeatable while only the town's board is loading");
                    helper.assertTrue(rig.service().accept(player, TOWN_FEAT).isEmpty(),
                            "a feat was refused while a board loads, and a feat needs no draw");
                    advance(helper, step, 1);
                }
                case 1 -> {
                    release.complete(null);
                    advance(helper, step, 2);
                }
                case 2 -> {
                    BoardView town = loadedBoard(helper, rig.service(), TOWN);
                    helper.assertTrue(!town.commissions().isEmpty(), "the town's board drew nothing");
                    Identifier townOnDraw = town.commissions().get(0);
                    helper.assertTrue(rig.service().accept(player, townOnDraw).isEmpty(),
                            "a town repeatable on the town's draw was refused once the board loaded");
                    Identifier townOffDraw = poolOf(rig, TOWN).stream().filter(id -> !town.commissions().contains(id))
                            .findFirst().orElseThrow(() -> helper.assertionException("every town repeatable is drawn"));
                    assertReason(helper, rig.service().checkAccept(player, townOffDraw), Refusal.Reason.NOT_ON_BOARD,
                            "a town repeatable off the town's draw");
                    advance(helper, step, 3);
                }
                default -> {
                    GuildView view = rig.service().view(player).orElseThrow(() -> helper.assertionException("no record"));
                    helper.assertValueEqual(view.activeCount(), 3, "active commissions: two repeatables and a feat");
                    helper.assertValueEqual(view.standing(TOWN_FEAT), Standing.ACTIVE, "the town feat");
                }
            }
        });
    }

    /** {@code boardEntries} lists each board's own draw, then the feats of that board's pool, and no other board's feats. */
    @GameTest(maxTicks = 300)
    public void boardEntriesListsEachBoardsDrawAndItsOwnFeats(GameTestHelper helper) {
        GuildRig rig = GuildRig.create(helper, BOARDS);
        ServerPlayer player = rig.joined();
        helper.succeedWhen(() -> {
            rig.tick();
            helper.assertTrue(rig.service().view(player).isPresent(), "the record has not loaded");
            BoardView hunters = loadedBoard(helper, rig.service(), HUNTERS);
            BoardView town = loadedBoard(helper, rig.service(), TOWN);
            BoardView dock = loadedBoard(helper, rig.service(), DOCK);
            helper.assertValueEqual(entriesOf(helper, rig, player, HUNTERS), withFeat(hunters.commissions(), HUNT_FEAT),
                    "hunters entries");
            helper.assertValueEqual(entriesOf(helper, rig, player, TOWN), withFeat(town.commissions(), TOWN_FEAT),
                    "town entries");
            helper.assertValueEqual(entriesOf(helper, rig, player, DOCK), withFeat(dock.commissions(), DOCK_FEAT),
                    "dock entries");
        });
    }

    /**
     * A board whose pool has no repeatable is an empty board that is not stored, and the other boards store theirs. An
     * activation draws it again at once, and leaves the stored draw of another board as it is.
     */
    @GameTest(maxTicks = 300)
    public void aBoardWithAnEmptyPoolIsUnstoredAndEmptyWhileTheOthersStore(GameTestHelper helper) {
        GuildRig rig = GuildRig.create(helper, BOARDS);
        AtomicInteger step = new AtomicInteger();
        helper.succeedWhen(() -> {
            rig.tick();
            switch (step.get()) {
                case 0 -> {
                    BoardView dock = loadedBoard(helper, rig.service(), DOCK);
                    helper.assertTrue(dock.commissions().isEmpty() && !dock.stored(),
                            "the dock is not an empty, unstored board: " + dock.commissions());
                    helper.assertTrue(rig.storage().loadBoard(BOARD_DAY, DOCK).join().isEmpty(),
                            "the dock's empty draw was stored");
                    helper.assertTrue(loadedBoard(helper, rig.service(), HUNTERS).stored(), "hunters was not stored");
                    // The same content is activated again: the dock is present at once, drawn again, and still empty.
                    rig.useContent(BOARDS);
                    rig.service().contentActivated();
                    helper.assertTrue(rig.service().board(DOCK).isPresent(), "the dock is missing right after an activation");
                    advance(helper, step, 1);
                }
                default -> {
                    BoardView dock = loadedBoard(helper, rig.service(), DOCK);
                    helper.assertTrue(dock.commissions().isEmpty() && !dock.stored(),
                            "the redrawn dock is not an empty, unstored board");
                    BoardView hunters = loadedBoard(helper, rig.service(), HUNTERS);
                    Optional<StoredBoard> stored = rig.storage().loadBoard(BOARD_DAY, HUNTERS).join();
                    helper.assertTrue(stored.isPresent(), "the hunters draw left storage");
                    helper.assertValueEqual(hunters.commissions(), stored.get().commissionIds(),
                            "the hunters draw after the activation");
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
        return rig.service().view(player).isPresent() && rig.service().board(BOARD).isPresent();
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

    /**
     * A storage whose {@code loadCharacter} calls, made while {@code holding} is set, complete with the delegate's result
     * only after {@code release} completes. Other calls reach {@code delegate} at once.
     */
    @SuppressWarnings("unchecked")
    private static AlinaStorage heldLoads(AlinaStorage delegate, AtomicBoolean holding, CompletableFuture<Void> release) {
        return (AlinaStorage) Proxy.newProxyInstance(AlinaStorage.class.getClassLoader(),
                new Class<?>[] {AlinaStorage.class},
                (proxy, called, args) -> {
                    boolean hold = called.getName().equals("loadCharacter") && holding.get();
                    Object result;
                    try {
                        result = called.invoke(delegate, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                    if (!hold) {
                        return result;
                    }
                    return ((CompletableFuture<Object>) result).thenCompose(state -> release.thenApply(ignored -> state));
                });
    }

    /**
     * A storage whose calls to {@code method} return {@code answers} in order, one answer for each call, and whose later
     * calls reach {@code delegate}. {@code calls} counts the calls to {@code method}.
     */
    private static AlinaStorage answeredInOrder(AlinaStorage delegate, String method, AtomicInteger calls,
            List<CompletableFuture<Object>> answers) {
        return (AlinaStorage) Proxy.newProxyInstance(AlinaStorage.class.getClassLoader(),
                new Class<?>[] {AlinaStorage.class},
                (proxy, called, args) -> {
                    if (called.getName().equals(method)) {
                        int call = calls.incrementAndGet();
                        if (call <= answers.size()) {
                            return answers.get(call - 1);
                        }
                    }
                    try {
                        return called.invoke(delegate, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    /** The board's draw, which the test asserts has loaded before it is read. */
    private static BoardView loadedBoard(GameTestHelper helper, GuildService service, Identifier board) {
        Optional<BoardView> view = service.board(board);
        helper.assertTrue(view.isPresent(), "the board " + board + " has not loaded");
        return view.get();
    }

    /** The board's entries for the player, which the test asserts are present before they are read. */
    private static List<Identifier> entriesOf(GameTestHelper helper, GuildRig rig, ServerPlayer player, Identifier board) {
        Optional<List<Identifier>> entries = rig.service().boardEntries(player, board);
        helper.assertTrue(entries.isPresent(), "the entries of " + board + " have not loaded");
        return entries.get();
    }

    /** The draw followed by one feat, which is how the board lists its feats. */
    private static List<Identifier> withFeat(List<Identifier> draw, Identifier feat) {
        return Stream.concat(draw.stream(), Stream.of(feat)).toList();
    }

    /** The repeatables of the board's pool in the content the rig reads now, in id order. */
    private static List<Identifier> poolOf(GuildRig rig, Identifier board) {
        GuildContent content = rig.currentContent();
        return content.commissions().values().stream()
                .filter(commission -> commission.kind() == Commission.Kind.REPEATABLE)
                .filter(commission -> content.boardOf(commission.id()).map(Board::id).filter(board::equals).isPresent())
                .map(Commission::id)
                .sorted(Comparator.comparing(Identifier::toString))
                .toList();
    }

    /** The number at the end of each id's path, such as 3 for {@code guild_test/hunt_3}. */
    private static Set<Integer> numbers(List<Identifier> ids) {
        return ids.stream()
                .map(id -> Integer.parseInt(id.getPath().substring(id.getPath().lastIndexOf('_') + 1)))
                .collect(Collectors.toSet());
    }

    /**
     * A storage whose {@code loadBoard} calls for {@code board} complete with the delegate's result only after {@code release}
     * completes. Other calls, and loads of other boards, reach {@code delegate} at once.
     */
    @SuppressWarnings("unchecked")
    private static AlinaStorage heldBoardLoads(AlinaStorage delegate, Identifier board, CompletableFuture<Void> release) {
        return (AlinaStorage) Proxy.newProxyInstance(AlinaStorage.class.getClassLoader(),
                new Class<?>[] {AlinaStorage.class},
                (proxy, called, args) -> {
                    Object result;
                    try {
                        result = called.invoke(delegate, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                    if (!called.getName().equals("loadBoard") || !board.equals(args[1])) {
                        return result;
                    }
                    return ((CompletableFuture<Object>) result).thenCompose(stored -> release.thenApply(ignored -> stored));
                });
    }

    /** The number of commissions storage holds ACTIVE for the player. */
    private static int activeInStorage(GuildRig rig, ServerPlayer player) {
        return (int) rig.storage().loadCharacter(player.getUUID()).join().commissions().values().stream()
                .filter(row -> row.status() == CommissionStatus.ACTIVE).count();
    }
}
