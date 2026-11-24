package town.lampas.alina.gametest;

import com.mojang.serialization.DataResult;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import town.lampas.alina.Alina;
import town.lampas.alina.settlement.SettlementRequest;
import town.lampas.alina.storage.ApplyResult;
import town.lampas.alina.storage.MarketTick;
import town.lampas.alina.storage.Operation;
import town.lampas.alina.storage.OperationEffect;
import town.lampas.alina.storage.OperationKind;
import town.lampas.alina.trade.TradeOutcome;
import town.lampas.alina.trade.TradeRefusal;
import town.lampas.alina.trade.TradeRefusal.Reason;
import town.lampas.alina.trade.TradeService;
import town.lampas.alina.trade.pricing.ReserveCurve;
import town.lampas.alina.trade.pricing.ReserveCurve.Reserves;
import town.lampas.alina.trade.network.MarketView;
import town.lampas.alina.trade.network.TradeClosePayload;
import town.lampas.alina.trade.network.TradeOpenPayload;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;

/**
 * Server GameTests for the NPC shop trade. Each test builds a {@link TradeRig}: a trade service over its own in-memory
 * storage and settlement, a clock the test moves, and mock players outside the server's list. Settlement completes on
 * storage threads and returns on a later tick, so a step that waits uses {@code thenWaitUntil}, which runs its check
 * every tick until the check passes.
 */
public final class TradeGameTests {
    private static final Identifier MIRA = TradeRig.MIRA;
    private static final Identifier TIGHT = TradeRig.TIGHT;
    private static final Identifier LUMBER = TradeRig.LUMBER;
    private static final Identifier BROKER = TradeRig.BROKER;
    private static final Identifier TOLL = TradeRig.TOLL;
    private static final Identifier ONEWAY = TradeRig.ONEWAY;
    private static final String BUNDLE = "bundle.json";
    private static final String BUNDLE_NEXT = "bundle_next.json";
    private static final String BUNDLE_MARKET = "bundle_market.json";
    /** {@link #BUNDLE_MARKET} with a quote tolerance of 2 percent on its experience market. */
    private static final String BUNDLE_MARKET_TOLERANCE = "bundle_market_tolerance.json";
    private static final String OFFER_PAPER = "paper";
    private static final String OFFER_SNOWBALL = "snowball";
    private static final String OFFER_CHARM = "charm";
    private static final String CHARACTER = "character";

    // Selling and buying.

    @GameTest(maxTicks = 400)
    public void sellingOneStackPaysTheLargestCoins(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        player.getInventory().setItem(0, new ItemStack(Items.BONE, 8));
        LocalDate day = rig.day();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player), "the trade record has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, player, MIRA, Optional.empty());
                    assertAccepted(helper, rig.service().sell(player, rig.sessionId(player), 0, 8, 24), "sell 8 bones");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(player), "the sale is still settling");
                })
                .thenExecute(() -> {
                    // The coins arrive in the first empty slot, which is the one the bones left, so count the items.
                    helper.assertValueEqual(count(player, Items.BONE), 0, "the bones left the inventory");
                    // 24 Aur in the largest denominations: one ingot of 9 twice, then six nuggets.
                    helper.assertValueEqual(count(player, Items.GOLD_INGOT), 2, "gold ingots paid");
                    helper.assertValueEqual(count(player, Items.GOLD_NUGGET), 6, "gold nuggets paid");
                    helper.assertValueEqual(rig.service().budget(player, MIRA).orElseThrow().paid(), 24,
                            "the record's Aur paid today");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertValueEqual(rig.storedAur(player, MIRA, day), 24, "the storage budget row");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 400)
    public void sellingAllJunkTakesBundlesAcrossStacksAndLeavesTheRemainder(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        // Nine rotten flesh over two stacks: junk sells in bundles of four, so eight go and one stays.
        player.getInventory().setItem(0, new ItemStack(Items.ROTTEN_FLESH, 3));
        player.getInventory().setItem(1, new ItemStack(Items.ROTTEN_FLESH, 6));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player), "the trade record has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, player, MIRA, Optional.empty());
                    helper.assertValueEqual(rig.messenger().lastView(player).orElseThrow().junkAur(), 2,
                            "the junk plan pays two bundles of one Aur");
                    assertAccepted(helper, rig.service().sellJunk(player, rig.sessionId(player), 2), "sell all junk");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(player), "the junk sale is still settling");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(count(player, Items.ROTTEN_FLESH), 1, "one flesh remains");
                    helper.assertValueEqual(player.getInventory().getItem(1).getCount(), 1, "the remainder is in slot 1");
                    helper.assertValueEqual(count(player, Items.GOLD_NUGGET), 2, "two nuggets paid");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 400)
    public void buyingWithChangeTakesTheCoinsAndReturnsTheChange(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        // 2 ones and 2 nines: 13 Aur takes both ones and both nines, which is 20, so 7 comes back.
        player.getInventory().setItem(0, new ItemStack(Items.GOLD_NUGGET, 2));
        player.getInventory().setItem(1, new ItemStack(Items.GOLD_INGOT, 2));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player), "the trade record has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, player, MIRA, Optional.empty());
                    assertAccepted(helper, rig.service().buy(player, rig.sessionId(player), OFFER_PAPER, 1, 13),
                            "buy paper for 13");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(player), "the purchase is still settling");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(count(player, Items.PAPER), 1, "the paper arrived");
                    helper.assertValueEqual(count(player, Items.GOLD_INGOT), 0, "the ingots were paid");
                    helper.assertValueEqual(count(player, Items.GOLD_NUGGET), 7, "7 Aur came back as nuggets");
                })
                .thenSucceed();
    }

    // Experience.

    @GameTest(maxTicks = 500)
    public void experienceMovesInWholeStepsOfTheRateAndOddAmountsAreRefused(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        // Level 10 with no progress holds 160 points. The rate is 50 points for every 2 Aur, in both directions.
        player.experienceLevel = 10;
        player.experienceProgress = 0f;
        player.totalExperience = 160;
        player.getInventory().setItem(2, new ItemStack(Items.GOLD_INGOT, 1));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player), "the trade record has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, player, MIRA, Optional.empty());
                    helper.assertValueEqual(rig.messenger().lastView(player).orElseThrow().experience().orElseThrow()
                            .maxSellAur(), 6, "160 points cover three steps of 2 Aur");
                    assertRefused(helper, rig.service().experience(player, rig.sessionId(player), true, 3, 75),
                            Reason.INVALID, "an odd Aur amount of experience");
                    // The ingot is 9 Aur: 2 Aur for 50 points takes it, and 7 comes back as nuggets. Nothing else is held.
                    assertAccepted(helper, rig.service().experience(player, rig.sessionId(player), false, 2, 50),
                            "buy experience for 2 Aur");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(player), "the experience purchase is still settling");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(player.totalExperience, 210, "50 points came to the player");
                    helper.assertValueEqual(count(player, Items.GOLD_INGOT), 0, "the ingot paid for it");
                    helper.assertValueEqual(count(player, Items.GOLD_NUGGET), 7, "the change came back as nuggets");
                    // The player now holds 210 points, so 100 of them sell for 4 Aur.
                    assertAccepted(helper, rig.service().experience(player, rig.sessionId(player), true, 4, 100),
                            "sell experience for 4 Aur");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(player), "the experience sale is still settling");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(player.totalExperience, 110, "100 points left the player");
                    helper.assertValueEqual(count(player, Items.GOLD_NUGGET), 11, "4 Aur arrived as nuggets");
                })
                .thenSucceed();
    }

    // Budgets and limits.

    @GameTest(maxTicks = 600)
    public void theBudgetStopsASaleAndANewTradeDayAllowsIt(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        player.getInventory().setItem(0, new ItemStack(Items.BONE, 8));
        LocalDate firstDay = rig.day();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player), "the trade record has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, player, TIGHT, Optional.empty());
                    // The shop pays 10 Aur a day: three bones are 9, and two more would be 6.
                    assertAccepted(helper, rig.service().sell(player, rig.sessionId(player), 0, 3, 9), "sell 3 bones");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(player), "the first sale is still settling");
                })
                .thenExecute(() -> {
                    assertRefused(helper, rig.service().sell(player, rig.sessionId(player), 0, 2, 6), Reason.BUDGET_SPENT,
                            "two more bones over the budget");
                    helper.assertValueEqual(rig.service().budget(player, TIGHT).orElseThrow().left(), 1,
                            "1 Aur of the budget is left");
                    rig.clock().advance(Duration.ofHours(24));
                })
                .thenExecute(() -> {
                    assertAccepted(helper, rig.service().sell(player, rig.sessionId(player), 0, 2, 6),
                            "the same sale on the next trade day");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(player), "the sale on the next day is still settling");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    LocalDate nextDay = rig.day();
                    helper.assertValueEqual(rig.storedAur(player, TIGHT, firstDay), 9, "the first day's row");
                    helper.assertValueEqual(rig.storedAur(player, TIGHT, nextDay), 6, "the next day's row");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 400)
    public void aPerCharacterLimitStopsASecondPurchase(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        player.getInventory().setItem(0, new ItemStack(Items.GOLD_NUGGET, 3));
        LocalDate day = rig.day();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player), "the trade record has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, player, MIRA, Optional.empty());
                    assertAccepted(helper, rig.service().buy(player, rig.sessionId(player), OFFER_CHARM, 1, 1),
                            "the first charm");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(player), "the first charm is still settling");
                })
                .thenExecute(() -> {
                    assertRefused(helper, rig.service().buy(player, rig.sessionId(player), OFFER_CHARM, 1, 1),
                            Reason.LIMIT_REACHED, "a second charm for the same character");
                    helper.assertValueEqual(rig.messenger().lastView(player).orElseThrow().offers().get(2).limitLeft(), 0,
                            "no charm is left in the limit");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertValueEqual(rig.storedPurchases(player, MIRA, OFFER_CHARM, CHARACTER, day), 1,
                            "the purchase row in storage");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 400)
    public void aBudgetResetIsRefusedWhileASaleSettlesAndTheRecordKeepsTheAur(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        player.getInventory().setItem(0, new ItemStack(Items.BONE, 8));
        LocalDate day = rig.day();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player), "the trade record has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, player, MIRA, Optional.empty());
                    assertAccepted(helper, rig.service().sell(player, rig.sessionId(player), 0, 8, 24), "sell 8 bones");
                    // Settlement has not ticked, so the sale is still in flight and the reset must wait for it.
                    Optional<TradeRefusal> refusal = rig.service().resetBudget(player, Optional.empty()).join();
                    helper.assertTrue(refusal.map(TradeRefusal::reason).filter(Reason.BUSY::equals).isPresent(),
                            "the reset was not refused while the sale settles: " + refusal.map(found -> found.reason().name())
                                    .orElse("accepted"));
                    helper.assertTrue(refusal.get().message().getString().contains("still being settled"),
                            "the refusal says the trade is settling: " + refusal.get().message().getString());
                    helper.assertValueEqual(rig.service().budget(player, MIRA).orElseThrow().paid(), 24,
                            "the record still holds the sale's Aur");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(player), "the sale is still settling");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertValueEqual(rig.storedAur(player, MIRA, day), 24, "storage keeps the sale's Aur");
                    helper.assertValueEqual(rig.service().budget(player, MIRA).orElseThrow().paid(), 24,
                            "the record keeps the sale's Aur once it settled");
                })
                .thenSucceed();
    }

    // Refusals that change nothing.

    @GameTest(maxTicks = 300)
    public void aDeadPlayersExperienceSaleIsRefusedAndNothingMoves(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        // Level 10 with no progress holds 160 points, and four bones show that the inventory stays as it is.
        player.experienceLevel = 10;
        player.experienceProgress = 0f;
        player.totalExperience = 160;
        player.getInventory().setItem(0, new ItemStack(Items.BONE, 4));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player), "the trade record has not loaded");
                })
                .thenExecute(() -> open(helper, rig, player, MIRA, Optional.empty()))
                .thenExecute(() -> {
                    // The lethal hit lands between ticks, so the request comes before the tick that would close the session.
                    player.setHealth(0.0F);
                    assertRefused(helper, rig.service().experience(player, rig.sessionId(player), true, 2, 50), Reason.CLOSED,
                            "a sale of experience by a dead player");
                    helper.assertValueEqual(player.totalExperience, 160, "the experience stayed");
                    helper.assertValueEqual(count(player, Items.BONE), 4, "the bones stayed");
                    helper.assertValueEqual(rig.service().budget(player, MIRA).orElseThrow().paid(), 0, "no Aur was paid");
                    helper.assertTrue(rig.settlement().pendingFor(player).isEmpty(), "a trade was begun for a dead player");
                    helper.assertTrue(rig.messenger().last(player, TradeClosePayload.class).isPresent(),
                            "the session did not close");
                    Optional<TradeRefusal> opened = rig.service().open(player, MIRA, Optional.empty(), Optional.empty(),
                            Optional.empty());
                    helper.assertTrue(opened.map(TradeRefusal::reason).filter(Reason.CLOSED::equals).isPresent(),
                            "a dead player could open the shop: " + opened.map(found -> found.reason().name()).orElse("accepted"));
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 300)
    public void aFullInventoryRefusesAPurchaseWithNoRoom(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        // Every main slot but the first is full, and the first holds 17 nuggets. A snowball of 16 costs 16 Aur, which takes 16
        // nuggets and leaves one behind in the first slot, so no empty slot is left for the snowballs.
        for (int slot = 1; slot < 36; slot++) {
            player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        player.getInventory().setItem(0, new ItemStack(Items.GOLD_NUGGET, 17));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player), "the trade record has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, player, MIRA, Optional.empty());
                    assertRefused(helper, rig.service().buy(player, rig.sessionId(player), OFFER_SNOWBALL, 1, 16),
                            Reason.NO_ROOM, "a snowball with no room");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(player), "the refusal left a trade in flight");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(player.getInventory().getItem(0).getCount(), 17, "the nuggets were not taken");
                    helper.assertValueEqual(count(player, Items.SNOWBALL), 0, "no snowball arrived");
                    helper.assertValueEqual(rig.service().budget(player, MIRA).orElseThrow().paid(), 0,
                            "no budget was used");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 300)
    public void aStaleExpectedValueIsRefusedAndChangesNothing(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        player.getInventory().setItem(0, new ItemStack(Items.BONE, 4));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player), "the trade record has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, player, MIRA, Optional.empty());
                    // Four bones are 12 Aur; the client believes 999.
                    assertRefused(helper, rig.service().sell(player, rig.sessionId(player), 0, 4, 999), Reason.STALE,
                            "a sale priced at 999");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertValueEqual(player.getInventory().getItem(0).getCount(), 4, "the bones stayed");
                    helper.assertValueEqual(rig.service().budget(player, MIRA).orElseThrow().paid(), 0,
                            "no Aur was paid");
                    helper.assertValueEqual(rig.messenger().lastView(player).orElseThrow().sellRows().get(0).maxCount(), 4,
                            "the refusal sent a fresh view with the bones");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 400)
    public void aRequestWhileATradeSettlesIsBusyAndWorksAfterward(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        player.getInventory().setItem(0, new ItemStack(Items.BONE, 2));
        player.getInventory().setItem(1, new ItemStack(Items.BONE, 2));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player), "the trade record has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, player, MIRA, Optional.empty());
                    assertAccepted(helper, rig.service().sell(player, rig.sessionId(player), 0, 2, 6), "the first sale");
                    assertRefused(helper, rig.service().sell(player, rig.sessionId(player), 1, 2, 6), Reason.BUSY,
                            "a second sale while the first settles");
                    helper.assertValueEqual(player.getInventory().getItem(1).getCount(), 2,
                            "the refused sale took nothing");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(player), "the first sale is still settling");
                })
                .thenExecute(() -> {
                    assertAccepted(helper, rig.service().sell(player, rig.sessionId(player), 1, 2, 6),
                            "the second sale once the first settled");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(player), "the second sale is still settling");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(rig.service().budget(player, MIRA).orElseThrow().paid(), 12,
                            "both sales are on the budget");
                })
                .thenSucceed();
    }

    // Sessions.

    @GameTest(maxTicks = 300)
    public void aSessionClosesWhenThePlayerIsMoreThanEightBlocksFromTheInterlocutor(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        Villager villager = helper.spawnWithNoFreeWill(EntityTypes.VILLAGER, new BlockPos(2, 2, 2));
        player.setPos(villager.getX(), villager.getY(), villager.getZ());
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player), "the trade record has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, player, MIRA, Optional.of(villager));
                })
                .thenExecuteFor(5, rig::tick)
                .thenExecute(() -> {
                    helper.assertTrue(rig.messenger().last(player, TradeClosePayload.class).isEmpty(),
                            "the session closed next to the villager");
                    player.setPos(villager.getX() + 20, villager.getY(), villager.getZ());
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    TradeClosePayload close = rig.messenger().last(player, TradeClosePayload.class)
                            .orElseThrow(() -> helper.assertionException(Component.literal("no close was sent")));
                    helper.assertValueEqual(close.sessionId(), rig.sessionId(player), "the closed session");
                })
                .thenExecute(() -> assertRefused(helper, rig.service().sell(player, rig.sessionId(player), 0, 1, 1),
                        Reason.NO_SESSION, "a request in the closed session"))
                .thenSucceed();
    }

    @GameTest(maxTicks = 300)
    public void aSessionClosesWhenTheContentRevisionChanges(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player), "the trade record has not loaded");
                })
                .thenExecute(() -> open(helper, rig, player, MIRA, Optional.empty()))
                .thenExecuteFor(3, rig::tick)
                .thenExecute(() -> {
                    helper.assertTrue(rig.messenger().last(player, TradeClosePayload.class).isEmpty(),
                            "the session closed before the revision changed");
                    rig.setRevision(TradeRig.REVISION + 1);
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.messenger().last(player, TradeClosePayload.class).isPresent(),
                            "the session is still open after the revision changed");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 300)
    public void aSessionClosesWhenItsShopIsGone(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player), "the trade record has not loaded");
                })
                .thenExecute(() -> open(helper, rig, player, TIGHT, Optional.empty()))
                .thenExecuteFor(3, rig::tick)
                .thenExecute(() -> {
                    helper.assertTrue(rig.messenger().last(player, TradeClosePayload.class).isEmpty(),
                            "the session closed while its shop existed");
                    // The next content has the other shops, but not Tight Purse.
                    rig.useContent(BUNDLE_NEXT);
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.messenger().last(player, TradeClosePayload.class).isPresent(),
                            "the session is still open after its shop left the content");
                })
                .thenSucceed();
    }

    // Rules on a server.

    @GameTest(maxTicks = 400)
    public void tagAndNamespaceRulesResolveOnTheServer(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        player.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
        player.getInventory().setItem(1, new ItemStack(Items.STONE_SWORD, 1));
        ItemStack named = new ItemStack(Items.STONE_SWORD, 1);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Named"));
        player.getInventory().setItem(2, named);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player), "the trade record has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, player, LUMBER, Optional.empty());
                    // The #minecraft:planks tag: four oak planks are one Aur.
                    assertAccepted(helper, rig.service().sell(player, rig.sessionId(player), 0, 4, 1), "sell oak planks");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(player), "the planks sale is still settling");
                })
                .thenExecute(() -> {
                    // The namespace rule takes the plain sword, and never a sword with a component change.
                    assertAccepted(helper, rig.service().sell(player, rig.sessionId(player), 1, 1, 1), "sell a plain sword");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(player), "the sword sale is still settling");
                })
                .thenExecute(() -> {
                    assertRefused(helper, rig.service().sell(player, rig.sessionId(player), 2, 1, 1), Reason.STALE,
                            "a named sword, which no rule takes");
                    helper.assertValueEqual(count(player, Items.OAK_PLANKS), 0, "the planks left");
                    // Only the named sword is left; the plain one was sold.
                    helper.assertValueEqual(count(player, Items.STONE_SWORD), 1, "the plain sword left");
                    helper.assertValueEqual(player.getInventory().getItem(2).getCount(), 1, "the named sword stayed");
                    helper.assertValueEqual(count(player, Items.GOLD_NUGGET), 2, "one Aur from each sale");
                })
                .thenSucceed();
    }

    // The dialogue action, at validation.

    @GameTest(maxTicks = 20)
    public void theOpenActionParsesInAGraphAndRefusesAnUnknownShop(GameTestHelper helper) {
        String fixture = TradeRig.readFixture(helper, BUNDLE);
        DataResult<ContentBundle> parsed = HaruhiServices.content().parseAndValidate(fixture);
        ContentBundle bundle = parsed.result().orElseThrow(() -> helper.assertionException(Component.literal(
                "the bundle was refused: " + parsed.error().map(error -> error.message()).orElse(""))));
        helper.assertTrue(bundle.dialogues().containsKey(Alina.id("trade_test/counter")),
                "the counter graph with alina:trade/open parsed");

        String unknownShop = fixture.replace("\"shop\": \"alina:trade_test/mira\"", "\"shop\": \"alina:trade_test/nowhere\"");
        helper.assertTrue(!unknownShop.equals(fixture), "the unknown-shop variant changed the fixture");
        DataResult<ContentBundle> refused = HaruhiServices.content().parseAndValidate(unknownShop);
        helper.assertTrue(refused.result().isEmpty(), "a graph naming an unknown shop parsed");
        String message = refused.error().map(error -> error.message()).orElse("");
        helper.assertTrue(message.contains("alina:trade_test/nowhere"),
                "the refusal names the shop: " + message);

        String unknownGraph = fixture.replace("\"then\": \"alina:trade_test/bye\"", "\"then\": \"alina:trade_test/nowhere\"");
        helper.assertTrue(!unknownGraph.equals(fixture), "the unknown-graph variant changed the fixture");
        String graphMessage = HaruhiServices.content().parseAndValidate(unknownGraph).error()
                .map(error -> error.message()).orElse("");
        helper.assertTrue(graphMessage.contains("alina:trade_test/nowhere"),
                "the refusal names the then graph: " + graphMessage);
        helper.succeed();
    }

    // The experience market.

    @GameTest(maxTicks = 400)
    public void aSaleAtTheBrokerTakesPointsAndMovesThePool(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE_MARKET);
        ServerPlayer player = rig.joined();
        holdFiveThousandPoints(player);
        LocalDate day = rig.day();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player) && rig.service().market().isPresent(),
                            "the trade record or the market has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, player, BROKER, Optional.empty());
                    // 100 Aur at no fee is 5051 points: 5050.5 rounded up.
                    assertAccepted(helper, rig.service().experience(player, rig.sessionId(player), true, 100, 5051),
                            "sell 100 Aur of experience");
                    helper.assertValueEqual(rig.service().market().orElseThrow().live(), new Reserves(505_051, 9_900),
                            "the pool moves when the sale is accepted");
                    helper.assertValueEqual(rig.service().budget(player, BROKER).orElseThrow().paid(), 100,
                            "the sale counts against the broker's budget");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(player), "the sale is still settling");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertValueEqual(rig.storedMarket().orElseThrow().points(), 505_051L, "the stored points");
                    helper.assertValueEqual(rig.storedMarket().orElseThrow().aur(), 9_900L, "the stored Aur");
                    helper.assertValueEqual(rig.storedAur(player, BROKER, day), 100, "the broker's budget row");
                    // 100 Aur in the largest coins: 11 ingots of 9 and one nugget.
                    helper.assertValueEqual(count(player, Items.GOLD_INGOT), 11, "the ingots paid");
                    helper.assertValueEqual(count(player, Items.GOLD_NUGGET), 1, "the nugget paid");
                    helper.assertValueEqual(player.totalExperience, 294, "5051 points left the player");
                    // The trade began here, so its move was made once, at the sale; storage applying it moved nothing more.
                    helper.assertValueEqual(rig.service().market().orElseThrow().live(), new Reserves(505_051, 9_900),
                            "the pool moved once, for the sale");
                })
                .thenExecute(() -> {
                    // 505051 * 100 / 9900 is 5101.5, which floors to 5101 hundredths of a point per Aur.
                    open(helper, rig, player, BROKER, Optional.empty());
                    helper.assertValueEqual(rig.messenger().lastView(player).orElseThrow().experience().orElseThrow()
                            .market().orElseThrow().history(), List.of(5_101), "the rate after the sale");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 400)
    public void theTollShopSeesThePoolTheBrokerMoved(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE_MARKET);
        ServerPlayer player = rig.joined();
        holdFiveThousandPoints(player);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player) && rig.service().market().isPresent(),
                            "the trade record or the market has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, player, BROKER, Optional.empty());
                    assertAccepted(helper, rig.service().experience(player, rig.sessionId(player), true, 100, 5051),
                            "sell 100 Aur of experience");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(player), "the sale is still settling");
                })
                .thenExecute(() -> {
                    open(helper, rig, player, TOLL, Optional.empty());
                    MarketView market = rig.messenger().lastView(player).orElseThrow().experience().orElseThrow()
                            .market().orElseThrow();
                    helper.assertValueEqual(market.points(), 505_051L, "the toll shop shows the moved points");
                    helper.assertValueEqual(market.aur(), 9_900L, "the toll shop shows the moved Aur");
                    helper.assertValueEqual(market.seedPoints(), 500_000L, "the seed points");
                    helper.assertValueEqual(market.seedAur(), 10_000L, "the seed Aur");
                    helper.assertValueEqual(market.sellFee(), 5, "the toll's sell fee");
                    helper.assertValueEqual(market.buyFee(), 5, "the toll's buy fee");
                    helper.assertValueEqual(market.maxTradePercent(), 10, "the trade cap");
                    helper.assertValueEqual(market.history(), List.of(5_101), "the rate after the sale");
                    open(helper, rig, player, BROKER, Optional.empty());
                    helper.assertValueEqual(rig.messenger().lastView(player).orElseThrow().experience().orElseThrow()
                            .market().orElseThrow().history(), market.history(), "the broker's chart is the toll's");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 400)
    public void anOpenShopIsSentThePoolAnotherPlayerMoved(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE_MARKET);
        ServerPlayer watcher = rig.joined();
        ServerPlayer seller = rig.joined();
        holdFiveThousandPoints(seller);
        int[] ticksSinceSale = {0};
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(watcher) && rig.loaded(seller) && rig.service().market().isPresent(),
                            "a trade record or the market has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, watcher, TOLL, Optional.empty());
                    MarketView before = rig.messenger().lastView(watcher).orElseThrow().experience().orElseThrow()
                            .market().orElseThrow();
                    helper.assertValueEqual(before.points(), 500_000L, "the watcher opens on the seed points");
                    helper.assertValueEqual(before.aur(), 10_000L, "the watcher opens on the seed Aur");
                    open(helper, rig, seller, BROKER, Optional.empty());
                    assertAccepted(helper, rig.service().experience(seller, rig.sessionId(seller), true, 100, 5051),
                            "the other player sells 100 Aur of experience");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    ticksSinceSale[0]++;
                    MarketView market = rig.messenger().lastView(watcher).orElseThrow().experience().orElseThrow()
                            .market().orElseThrow();
                    helper.assertValueEqual(market.points(), 505_051L, "the watcher's open shop shows the moved points");
                    helper.assertValueEqual(market.aur(), 9_900L, "the watcher's open shop shows the moved Aur");
                    helper.assertValueEqual(market.history(), List.of(5_101), "the watcher's chart has the sale");
                })
                .thenExecute(() -> {
                    helper.assertTrue(ticksSinceSale[0] <= TradeService.VIEW_TICKS,
                            "the view took " + ticksSinceSale[0] + " ticks, more than " + TradeService.VIEW_TICKS);
                    helper.assertValueEqual(rig.messenger().all(watcher, TradeOpenPayload.class).size(), 1,
                            "the watcher's shop was opened once and never reopened");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 600)
    public void aMarketTradeWithinTheToleranceGoesThroughAtTheLivePoints(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE_MARKET_TOLERANCE);
        ServerPlayer quoter = rig.joined();
        ServerPlayer mover = rig.joined();
        holdFiveThousandPoints(quoter);
        holdFiveThousandPoints(mover);
        int[] quoted = new int[1];
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(quoter) && rig.loaded(mover) && rig.service().market().isPresent(),
                            "the trade record or the market has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, quoter, BROKER, Optional.empty());
                    open(helper, rig, mover, BROKER, Optional.empty());
                    helper.assertValueEqual(rig.messenger().lastView(quoter).orElseThrow().experience().orElseThrow()
                            .market().orElseThrow().quoteTolerancePercent(), 2, "the view carries the quote tolerance");
                    // The quote is the curve at the pool as it stands, with the broker's fee of 0: 100 Aur is 5051 points.
                    Reserves before = live(rig);
                    quoted[0] = ReserveCurve.sellPoints(before, 100, 0).getAsInt();
                    helper.assertValueEqual(quoted[0], 5051, "the quote for 100 Aur at the seed pool");
                    int moverQuote = ReserveCurve.sellPoints(before, 10, 0).getAsInt();
                    helper.assertValueEqual(moverQuote, 501, "the quote for 10 Aur at the seed pool");
                    assertAccepted(helper, rig.service().experience(mover, rig.sessionId(mover), true, 10, moverQuote),
                            "the other player sells 10 Aur of experience");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(mover), "the other player's sale is still settling");
                })
                .thenExecute(() -> {
                    // The pool is now 500501 points and 9990 Aur. 100 Aur costs 5061 points there: 10 more than the quote,
                    // and 2 percent of the quote is 102 points.
                    helper.assertValueEqual(live(rig), new Reserves(500_501, 9_990), "the other sale moved the pool");
                    TradeOutcome outcome = rig.service().experience(quoter, rig.sessionId(quoter), true, 100, quoted[0]);
                    assertAccepted(helper, outcome, "the quoter sells 100 Aur of experience at the old quote");
                    helper.assertValueEqual(outcome.line().getString(), "Sold 5061 points for 100 Aur (quoted 5051).",
                            "the line names the live points and the quote");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(quoter), "the quoter's sale is still settling");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(quoter.totalExperience, 284, "the live 5061 points left the quoter, not 5051");
                    // 100 Aur in the largest coins: 11 ingots of 9 and one nugget.
                    helper.assertValueEqual(count(quoter, Items.GOLD_INGOT), 11, "the ingots paid");
                    helper.assertValueEqual(count(quoter, Items.GOLD_NUGGET), 1, "the nugget paid");
                    helper.assertValueEqual(rig.service().market().orElseThrow().requests(), 2L,
                            "the two experience requests at the market");
                    helper.assertValueEqual(rig.service().market().orElseThrow().movedAccepted(), 1L,
                            "the quoter's trade was accepted at a moved price");
                    helper.assertValueEqual(rig.service().market().orElseThrow().staleRefused(), 0L,
                            "no request was refused as stale");
                    helper.assertValueEqual(rig.service().market().orElseThrow().quoteTolerancePercent(), 2,
                            "the market's quote tolerance");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 600)
    public void aMarketTradeBeyondTheToleranceIsRefusedAsStale(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE_MARKET_TOLERANCE);
        ServerPlayer quoter = rig.joined();
        ServerPlayer seller = rig.joined();
        holdFiveThousandPoints(quoter);
        holdFiveThousandPoints(seller);
        int[] quoted = new int[1];
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(quoter) && rig.loaded(seller) && rig.service().market().isPresent(),
                            "the trade record or the market has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, quoter, BROKER, Optional.empty());
                    open(helper, rig, seller, BROKER, Optional.empty());
                    quoted[0] = ReserveCurve.sellPoints(live(rig), 100, 0).getAsInt();
                    helper.assertValueEqual(quoted[0], 5051, "the quote for 100 Aur at the seed pool");
                    assertAccepted(helper, rig.service().experience(seller, rig.sessionId(seller), true, 100, quoted[0]),
                            "the other player sells 100 Aur of experience");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(seller), "the other player's sale is still settling");
                })
                .thenExecute(() -> {
                    // The pool is now 505051 points and 9900 Aur. 100 Aur costs 5154 points there: 103 more than the quote,
                    // and the allowance is 102 points.
                    helper.assertValueEqual(live(rig), new Reserves(505_051, 9_900), "the other sale moved the pool");
                    helper.assertValueEqual(ReserveCurve.sellPoints(live(rig), 100, 0).getAsInt(), 5154,
                            "the live cost of 100 Aur");
                    assertRefused(helper, rig.service().experience(quoter, rig.sessionId(quoter), true, 100, quoted[0]),
                            Reason.STALE, "100 Aur quoted at 5051 when the live cost is 5154");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertValueEqual(quoter.totalExperience, 5_345, "no points left the quoter");
                    helper.assertValueEqual(count(quoter, Items.GOLD_INGOT), 0, "no ingots were paid");
                    helper.assertValueEqual(count(quoter, Items.GOLD_NUGGET), 0, "no nugget was paid");
                    helper.assertValueEqual(rig.service().budget(quoter, BROKER).orElseThrow().paid(), 0,
                            "no Aur was paid against the broker's budget");
                    helper.assertValueEqual(rig.service().market().orElseThrow().staleRefused(), 1L,
                            "the refused request counts as stale");
                    helper.assertValueEqual(rig.service().market().orElseThrow().movedAccepted(), 0L,
                            "no accepted request moved");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 600)
    public void withoutAToleranceAMovedMarketQuoteIsRefused(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE_MARKET);
        ServerPlayer quoter = rig.joined();
        ServerPlayer seller = rig.joined();
        holdFiveThousandPoints(quoter);
        holdFiveThousandPoints(seller);
        int[] quoted = new int[1];
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(quoter) && rig.loaded(seller) && rig.service().market().isPresent(),
                            "the trade record or the market has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, quoter, BROKER, Optional.empty());
                    open(helper, rig, seller, BROKER, Optional.empty());
                    quoted[0] = ReserveCurve.sellPoints(live(rig), 100, 0).getAsInt();
                    helper.assertValueEqual(quoted[0], 5051, "the quote for 100 Aur at the seed pool");
                    int sellerQuote = ReserveCurve.sellPoints(live(rig), 1, 0).getAsInt();
                    assertAccepted(helper, rig.service().experience(seller, rig.sessionId(seller), true, 1, sellerQuote),
                            "the other player sells 1 Aur of experience");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(seller), "the other player's sale is still settling");
                })
                .thenExecute(() -> {
                    // The pool is now 500051 points and 9999 Aur. 100 Aur costs 5052 points there: one more than the quote.
                    helper.assertValueEqual(live(rig), new Reserves(500_051, 9_999), "the other sale moved the pool");
                    helper.assertValueEqual(ReserveCurve.sellPoints(live(rig), 100, 0).getAsInt(), 5052,
                            "the live cost of 100 Aur");
                    assertRefused(helper, rig.service().experience(quoter, rig.sessionId(quoter), true, 100, quoted[0]),
                            Reason.STALE, "100 Aur quoted at 5051 with no tolerance when the live cost is 5052");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertValueEqual(quoter.totalExperience, 5_345, "no points left the quoter");
                    helper.assertValueEqual(rig.service().market().orElseThrow().staleRefused(), 1L,
                            "the refused request counts as stale");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 600)
    public void aMarketPurchaseWithinTheToleranceAtAFeeGoesThroughAtTheLivePoints(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE_MARKET_TOLERANCE);
        ServerPlayer quoter = rig.joined();
        ServerPlayer buyer = rig.joined();
        holdFiveThousandPoints(quoter);
        holdFiveThousandPoints(buyer);
        // 100 Aur in the largest coins for the quoter: 11 ingots of 9 and one nugget. The other buyer has 50 Aur: 5 and 5.
        quoter.getInventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 11));
        quoter.getInventory().setItem(1, new ItemStack(Items.GOLD_NUGGET, 1));
        buyer.getInventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 5));
        buyer.getInventory().setItem(1, new ItemStack(Items.GOLD_NUGGET, 5));
        int[] quoted = new int[1];
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(quoter) && rig.loaded(buyer) && rig.service().market().isPresent(),
                            "the trade record or the market has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, quoter, TOLL, Optional.empty());
                    open(helper, rig, buyer, TOLL, Optional.empty());
                    // The quote: the curve gives floor(500000 * 100 / 10100) = 4950 points, and the toll's 5 percent fee takes
                    // floor(4950 * 95 / 100) = 4702, leaving 4702 points for the quoter.
                    quoted[0] = ReserveCurve.buyPoints(live(rig), 100, 5).getAsInt();
                    helper.assertValueEqual(quoted[0], 4_702, "the quote for 100 Aur at the seed pool");
                    // The other buyer's figure for 50 Aur: floor(500000 * 50 / 10050) = 2487, and floor(2487 * 95 / 100) = 2362.
                    int buyerPoints = ReserveCurve.buyPoints(live(rig), 50, 5).getAsInt();
                    helper.assertValueEqual(buyerPoints, 2_362, "the other buyer's figure for 50 Aur");
                    assertAccepted(helper, rig.service().experience(buyer, rig.sessionId(buyer), false, 50, buyerPoints),
                            "the other player buys 50 Aur of experience");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(buyer), "the other player's purchase is still settling");
                })
                .thenExecute(() -> {
                    // The pool is now 500000 - 2362 = 497638 points and 10050 Aur. There 100 Aur gives floor(497638 * 100 /
                    // 10150) = 4902 before the fee, and floor(4902 * 95 / 100) = 4656 after it. That is 46 fewer than the quote,
                    // and 2 percent of the quote, 4702, is 94.04 points, rounded up to 95.
                    helper.assertValueEqual(live(rig), new Reserves(497_638, 10_050), "the other purchase moved the pool");
                    TradeOutcome outcome = rig.service().experience(quoter, rig.sessionId(quoter), false, 100, quoted[0]);
                    assertAccepted(helper, outcome, "the quoter buys 100 Aur of experience at the old quote");
                    helper.assertValueEqual(outcome.line().getString(), "Bought 4656 points for 100 Aur (quoted 4702).",
                            "the line names the live points and the quote");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(quoter), "the quoter's purchase is still settling");
                })
                .thenExecute(() -> {
                    // 5345 + 4656 = 10001: the live points came to the quoter, not the 4702 quoted.
                    helper.assertValueEqual(quoter.totalExperience, 10_001, "the live 4656 points came to the quoter");
                    helper.assertValueEqual(count(quoter, Items.GOLD_INGOT), 0, "the ingots paid");
                    helper.assertValueEqual(count(quoter, Items.GOLD_NUGGET), 0, "the nugget paid");
                    helper.assertValueEqual(live(rig), new Reserves(492_982, 10_150), "the pool after both purchases");
                    helper.assertValueEqual(rig.service().market().orElseThrow().requests(), 2L,
                            "the two experience requests at the market");
                    helper.assertValueEqual(rig.service().market().orElseThrow().movedAccepted(), 1L,
                            "the quoter's purchase was accepted at a moved price");
                    helper.assertValueEqual(rig.service().market().orElseThrow().staleRefused(), 0L,
                            "no request was refused as stale");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertValueEqual(rig.storedMarket().orElseThrow().points(), 492_982L, "the stored points");
                    helper.assertValueEqual(rig.storedMarket().orElseThrow().aur(), 10_150L, "the stored Aur");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 600)
    public void aFavourableMarketMoveGoesThroughAtTheLivePoints(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE_MARKET_TOLERANCE);
        ServerPlayer seller = rig.joined();
        ServerPlayer buyer = rig.joined();
        holdFiveThousandPoints(seller);
        holdFiveThousandPoints(buyer);
        // The other buyer has 10 Aur: one ingot of 9 and one nugget.
        buyer.getInventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 1));
        buyer.getInventory().setItem(1, new ItemStack(Items.GOLD_NUGGET, 1));
        int[] quoted = new int[1];
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(seller) && rig.loaded(buyer) && rig.service().market().isPresent(),
                            "the trade record or the market has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, seller, BROKER, Optional.empty());
                    open(helper, rig, buyer, BROKER, Optional.empty());
                    // The quote: 100 Aur at the seed pool with the broker's fee of 0 is ceil(500000 * 100 / 9900) = ceil(5050.5)
                    // = 5051 points.
                    quoted[0] = ReserveCurve.sellPoints(live(rig), 100, 0).getAsInt();
                    helper.assertValueEqual(quoted[0], 5_051, "the quote for 100 Aur at the seed pool");
                    // The other buyer's figure for 10 Aur: floor(500000 * 10 / 10010) = floor(499.5) = 499 points.
                    int buyerPoints = ReserveCurve.buyPoints(live(rig), 10, 0).getAsInt();
                    helper.assertValueEqual(buyerPoints, 499, "the other buyer's figure for 10 Aur");
                    assertAccepted(helper, rig.service().experience(buyer, rig.sessionId(buyer), false, 10, buyerPoints),
                            "the other player buys 10 Aur of experience");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(buyer), "the other player's purchase is still settling");
                })
                .thenExecute(() -> {
                    // The pool is now 500000 - 499 = 499501 points and 10010 Aur. There 100 Aur costs ceil(499501 * 100 / 9910)
                    // = ceil(5040.37) = 5041 points: 10 fewer than the quote, so the move is in the seller's favour.
                    helper.assertValueEqual(live(rig), new Reserves(499_501, 10_010), "the other purchase moved the pool");
                    TradeOutcome outcome = rig.service().experience(seller, rig.sessionId(seller), true, 100, quoted[0]);
                    assertAccepted(helper, outcome, "the seller sells 100 Aur of experience at the old quote");
                    helper.assertValueEqual(outcome.line().getString(), "Sold 5041 points for 100 Aur (quoted 5051).",
                            "the line names the live points and the quote");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(seller), "the seller's sale is still settling");
                })
                .thenExecute(() -> {
                    // 5345 - 5041 = 304: the live 5041 points left the seller, fewer than the quote.
                    helper.assertValueEqual(seller.totalExperience, 304, "the live 5041 points left the seller");
                    // 100 Aur in the largest coins: 11 ingots of 9 and one nugget.
                    helper.assertValueEqual(count(seller, Items.GOLD_INGOT), 11, "the ingots paid");
                    helper.assertValueEqual(count(seller, Items.GOLD_NUGGET), 1, "the nugget paid");
                    helper.assertValueEqual(live(rig), new Reserves(504_542, 9_910), "the pool after both trades");
                    helper.assertValueEqual(rig.service().market().orElseThrow().requests(), 2L,
                            "the two experience requests at the market");
                    helper.assertValueEqual(rig.service().market().orElseThrow().movedAccepted(), 1L,
                            "the seller's sale was accepted at a moved price");
                    helper.assertValueEqual(rig.service().market().orElseThrow().staleRefused(), 0L,
                            "no request was refused as stale");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertValueEqual(rig.storedMarket().orElseThrow().points(), 504_542L, "the stored points");
                    helper.assertValueEqual(rig.storedMarket().orElseThrow().aur(), 9_910L, "the stored Aur");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 500)
    public void aBuyBackAtTheBrokerGivesFewerPointsThanTheSaleTook(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE_MARKET);
        ServerPlayer player = rig.joined();
        holdFiveThousandPoints(player);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player) && rig.service().market().isPresent(),
                            "the trade record or the market has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, player, BROKER, Optional.empty());
                    assertAccepted(helper, rig.service().experience(player, rig.sessionId(player), true, 100, 5051),
                            "sell 100 Aur of experience");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(player), "the sale is still settling");
                })
                .thenExecute(() -> {
                    // 100 Aur at no fee buys 5050 points: 5050.5 rounded down. The pool gets back 5051 and gives 5050.
                    assertAccepted(helper, rig.service().experience(player, rig.sessionId(player), false, 100, 5050),
                            "buy 100 Aur of experience back");
                    helper.assertValueEqual(rig.service().market().orElseThrow().live(), new Reserves(500_001, 10_000),
                            "the pool after the buy");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(player), "the buy is still settling");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertValueEqual(rig.storedMarket().orElseThrow().points(), 500_001L, "the stored points");
                    helper.assertValueEqual(rig.storedMarket().orElseThrow().aur(), 10_000L, "the stored Aur");
                    // 294 points were left after the sale, and the buy gave 5050 more.
                    helper.assertValueEqual(player.totalExperience, 5_344, "the buy gave 5050 points");
                    helper.assertValueEqual(count(player, Items.GOLD_INGOT), 0, "the ingots paid for the buy");
                    helper.assertValueEqual(count(player, Items.GOLD_NUGGET), 0, "the nugget paid for the buy");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 400)
    public void aTollSaleOfOneAurTakesFiftyFourPoints(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE_MARKET);
        ServerPlayer player = rig.joined();
        holdFiveThousandPoints(player);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player) && rig.service().market().isPresent(),
                            "the trade record or the market has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, player, TOLL, Optional.empty());
                    // One Aur at a 5 percent fee on the seed: the curve gives 51 points, rounded up, and the fee makes 54.
                    assertAccepted(helper, rig.service().experience(player, rig.sessionId(player), true, 1, 54),
                            "sell 1 Aur of experience at the toll");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(player), "the toll sale is still settling");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertValueEqual(player.totalExperience, 5_291, "54 points left the player");
                    helper.assertValueEqual(count(player, Items.GOLD_NUGGET), 1, "the Aur paid as a nugget");
                    helper.assertValueEqual(rig.storedMarket().orElseThrow().points(), 500_054L, "the stored points");
                    helper.assertValueEqual(rig.storedMarket().orElseThrow().aur(), 9_999L, "the stored Aur");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 400)
    public void aTollPurchaseOfTwoAurGivesNinetyFourPoints(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE_MARKET);
        ServerPlayer player = rig.joined();
        holdFiveThousandPoints(player);
        player.getInventory().setItem(0, new ItemStack(Items.GOLD_NUGGET, 2));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player) && rig.service().market().isPresent(),
                            "the trade record or the market has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, player, TOLL, Optional.empty());
                    // Two Aur at a 5 percent fee on the seed: the curve gives 99 points, and the fee leaves 94 of them.
                    assertAccepted(helper, rig.service().experience(player, rig.sessionId(player), false, 2, 94),
                            "buy experience for 2 Aur at the toll");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(player), "the toll purchase is still settling");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertValueEqual(player.totalExperience, 5_439, "94 points came to the player");
                    helper.assertValueEqual(count(player, Items.GOLD_NUGGET), 0, "the two nuggets paid");
                    helper.assertValueEqual(rig.storedMarket().orElseThrow().points(), 499_906L, "the stored points");
                    helper.assertValueEqual(rig.storedMarket().orElseThrow().aur(), 10_002L, "the stored Aur");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 400)
    public void aStaleFigureAndAnAmountOverTheCapAreRefusedAndNothingMoves(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE_MARKET);
        ServerPlayer player = rig.joined();
        holdFiveThousandPoints(player);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player) && rig.service().market().isPresent(),
                            "the trade record or the market has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, player, BROKER, Optional.empty());
                    assertRefused(helper, rig.service().experience(player, rig.sessionId(player), true, 100, 5000),
                            Reason.STALE, "100 Aur priced at 5000 points");
                    // The cap is 10 percent of 10000 Aur, so 1001 is over it, whatever the figure.
                    assertRefused(helper, rig.service().experience(player, rig.sessionId(player), true, 1_001, 0),
                            Reason.INVALID, "1001 Aur, over the cap");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertValueEqual(rig.service().market().orElseThrow().live(), new Reserves(500_000, 10_000),
                            "the pool stayed");
                    helper.assertValueEqual(rig.storedMarket().orElseThrow().points(), 500_000L, "the stored pool stayed");
                    helper.assertValueEqual(player.totalExperience, 5_345, "the experience stayed");
                    helper.assertValueEqual(rig.service().budget(player, BROKER).orElseThrow().paid(), 0,
                            "no Aur was paid");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 300)
    public void aShopThatOnlyBuysExperienceRefusesASale(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE_MARKET);
        ServerPlayer player = rig.joined();
        holdFiveThousandPoints(player);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player) && rig.service().market().isPresent(),
                            "the trade record or the market has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, player, ONEWAY, Optional.empty());
                    assertRefused(helper, rig.service().experience(player, rig.sessionId(player), true, 100, 5051),
                            Reason.NOT_OFFERED, "a sale at a shop with no sell fee");
                    helper.assertValueEqual(rig.service().market().orElseThrow().live(), new Reserves(500_000, 10_000),
                            "the pool stayed");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 500)
    public void aSecondSaleAppendsASecondRateToTheChart(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE_MARKET);
        ServerPlayer player = rig.joined();
        holdFiveThousandPoints(player);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player) && rig.service().market().isPresent(),
                            "the trade record or the market has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, player, BROKER, Optional.empty());
                    assertAccepted(helper, rig.service().experience(player, rig.sessionId(player), true, 100, 5051),
                            "sell 100 Aur of experience");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(player), "the sale is still settling");
                })
                .thenExecute(() -> {
                    // Level 51 with no progress holds 5637 points: 5345 for level 50, and 292 more to reach 51.
                    player.experienceLevel = 51;
                    player.experienceProgress = 0f;
                    player.totalExperience = 5_637;
                    // 100 Aur at the pool of (505051, 9900) costs the curve 5153.6 points, which rounds up to 5154.
                    assertAccepted(helper, rig.service().experience(player, rig.sessionId(player), true, 100, 5154),
                            "a second sale of 100 Aur");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(player), "the second sale is still settling");
                })
                .thenExecute(() -> {
                    // The pool is (510205, 9800), and 510205 * 100 / 9800 is 5206.2, which floors to 5206.
                    helper.assertValueEqual(rig.service().market().orElseThrow().live(), new Reserves(510_205, 9_800),
                            "the pool after the second sale");
                    open(helper, rig, player, BROKER, Optional.empty());
                    helper.assertValueEqual(rig.messenger().lastView(player).orElseThrow().experience().orElseThrow()
                            .market().orElseThrow().history(), List.of(5_101, 5_206), "one rate per sale, oldest first");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 500)
    public void aNewBoardDayRestocksThePoolAndRecordsTheTick(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE_MARKET);
        ServerPlayer player = rig.joined();
        holdFiveThousandPoints(player);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player) && rig.service().market().isPresent(),
                            "the trade record or the market has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, player, BROKER, Optional.empty());
                    assertAccepted(helper, rig.service().experience(player, rig.sessionId(player), true, 100, 5051),
                            "sell 100 Aur of experience");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(player), "the sale is still settling");
                })
                .thenExecute(() -> rig.clock().advance(Duration.ofHours(24)))
                .thenWaitUntil(() -> {
                    rig.tick();
                    // The gaps are 5051 and 100, and 10 percent of each rounds up to 506 and 10.
                    helper.assertValueEqual(rig.service().market().orElseThrow().live(), new Reserves(504_545, 9_910),
                            "the restocked pool");
                    helper.assertValueEqual(rig.service().market().orElseThrow().restockedDay(), rig.day(),
                            "the restock day is the new day");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertValueEqual(rig.storedMarket().orElseThrow().points(), 504_545L, "the stored points");
                    helper.assertValueEqual(rig.storedMarket().orElseThrow().aur(), 9_910L, "the stored Aur");
                    helper.assertValueEqual(rig.storedMarket().orElseThrow().restockedDay(), rig.day(),
                            "the stored restock day");
                    // Newest first: the restock's tick, then the sale's.
                    helper.assertValueEqual(ticksOf(rig), List.of(List.of(504_545L, 9_910L), List.of(505_051L, 9_900L)),
                            "the ticks after the restock");
                })
                .thenExecute(() -> {
                    open(helper, rig, player, BROKER, Optional.empty());
                    // 504545 * 100 / 9910 is 5091.3, which floors to 5091: the rate after the restock follows the sale's 5101.
                    helper.assertValueEqual(rig.messenger().lastView(player).orElseThrow().experience().orElseThrow()
                            .market().orElseThrow().history(), List.of(5_101, 5_091), "the chart after the restock");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 500)
    public void aStaffSetIsNotRefusedForATradeInFlightAndStorageEndsEqualToMemory(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE_MARKET);
        ServerPlayer player = rig.joined();
        holdFiveThousandPoints(player);
        AtomicReference<CompletableFuture<Optional<TradeRefusal>>> pending = new AtomicReference<>();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player) && rig.service().market().isPresent(),
                            "the trade record or the market has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, player, BROKER, Optional.empty());
                    assertAccepted(helper, rig.service().experience(player, rig.sessionId(player), true, 100, 5051),
                            "sell 100 Aur of experience");
                    // The sale is in flight. The set is the difference from the live pool, which already holds the sale's move,
                    // and storage adds both deltas whichever lands first, so nothing is refused.
                    pending.set(rig.service().setMarket(1_000, 1_000));
                    helper.assertValueEqual(rig.service().market().orElseThrow().live(), new Reserves(1_000, 1_000),
                            "the set moved the pool at once");
                    boolean refused = false;
                    try {
                        rig.service().setMarket(0, 10);
                    } catch (IllegalArgumentException expected) {
                        refused = true;
                    }
                    helper.assertTrue(refused, "a point reserve of 0 was not refused");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(pending.get().isDone(), "the set has not completed");
                    helper.assertTrue(pending.get().join().isEmpty(), "the set was refused");
                    helper.assertTrue(rig.idle(player), "the sale is still settling");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertValueEqual(rig.storedMarket().orElseThrow().points(), 1_000L, "the stored points");
                    helper.assertValueEqual(rig.storedMarket().orElseThrow().aur(), 1_000L, "the stored Aur");
                    helper.assertValueEqual(rig.service().market().orElseThrow().live(), new Reserves(1_000, 1_000),
                            "the memory pool, which storage now equals");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 400)
    public void aTradeThisRunDidNotBeginMovesThePoolWhenStorageAppliesItFresh(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE_MARKET);
        ServerPlayer player = rig.joined();
        holdFiveThousandPoints(player);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player) && rig.service().market().isPresent(),
                            "the trade record or the market has not loaded");
                })
                .thenExecute(() -> {
                    // Settlement takes the trade with no service behind it, as one begun in an earlier run would be.
                    helper.assertTrue(rig.settlement().begin(player, foreignSale(rig, 5_051, -100)).isPresent(),
                            "settlement refused the trade");
                    helper.assertValueEqual(rig.service().market().orElseThrow().live(), new Reserves(500_000, 10_000),
                            "the pool before storage applies the trade");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertValueEqual(rig.service().market().orElseThrow().live(), new Reserves(505_051, 9_900),
                            "the pool learned of the trade storage applied");
                    helper.assertValueEqual(rig.storedMarket().orElseThrow().points(), 505_051L, "the stored points");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 400)
    public void aTradeReportedNotFreshLeavesThePoolAsItIs(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE_MARKET);
        ServerPlayer player = rig.joined();
        holdFiveThousandPoints(player);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player) && rig.service().market().isPresent(),
                            "the trade record or the market has not loaded");
                })
                .thenExecute(() -> {
                    UUID operationId = rig.settlement().begin(player, foreignSale(rig, 5_051, -100)).orElseThrow();
                    // Storage applies the operation first, as it does when a delivery was not saved, so settlement's apply is
                    // not fresh. The pool is not told: the trade was not begun here, and storage already holds it.
                    ApplyResult first = rig.storage().applyOperation(new Operation(operationId, player.getUUID(),
                            OperationKind.TRADE, BROKER, 0, TradeRig.REVISION, rig.settlement().worldId().toString(), "{}", 0,
                            saleEffect(rig, 5_051, -100), rig.clock().instant())).join();
                    helper.assertTrue(first.fresh(), "the direct apply was not fresh");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.settlement().pendingFor(player).isEmpty(), "the trade is still settling");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(rig.service().market().orElseThrow().live(), new Reserves(500_000, 10_000),
                            "a trade reported not fresh left the pool alone");
                    helper.assertValueEqual(rig.storedMarket().orElseThrow().points(), 505_051L,
                            "storage applied the trade once");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 900)
    public void aRestockThatStorageFailsIsReconciledFromTheRowAndRestockedAgain(GameTestHelper helper) {
        restockRecovers(helper, TradeRig.Refusal.FAILURE);
    }

    @GameTest(maxTicks = 900)
    public void aRestockThatStorageDoesNotApplyIsReconciledFromTheRowAndRestockedAgain(GameTestHelper helper) {
        restockRecovers(helper, TradeRig.Refusal.NOT_APPLIED);
    }

    @GameTest(maxTicks = 300)
    public void anExperienceRequestIsLoadingUntilTheMarketLoads(GameTestHelper helper) {
        TradeRig rig = TradeRig.create(helper, BUNDLE);
        ServerPlayer player = rig.joined();
        holdFiveThousandPoints(player);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player), "the trade record has not loaded");
                })
                .thenExecute(() -> {
                    // The content gains the market with no tick after it, so the market has not loaded when the request comes.
                    rig.useContent(BUNDLE_MARKET);
                    open(helper, rig, player, BROKER, Optional.empty());
                    helper.assertTrue(rig.messenger().lastView(player).orElseThrow().experience().isEmpty(),
                            "the view shows an experience panel before the market loaded");
                    assertRefused(helper, rig.service().experience(player, rig.sessionId(player), true, 100, 5051),
                            Reason.LOADING, "a sale before the market loaded");
                    helper.assertTrue(rig.service().market().isEmpty(), "the market reports before it loaded");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.service().market().isPresent(), "the market has not loaded");
                    helper.assertValueEqual(rig.service().market().orElseThrow().live(), new Reserves(500_000, 10_000),
                            "the seed loaded");
                })
                .thenSucceed();
    }

    // Helpers.

    /** Opens the shop for the player and fails the test when it is refused. */
    private static void open(GameTestHelper helper, TradeRig rig, ServerPlayer player, Identifier shop,
            Optional<Entity> interlocutor) {
        Optional<TradeRefusal> refusal = rig.service().open(player, shop, interlocutor, Optional.empty(),
                Optional.empty());
        helper.assertTrue(refusal.isEmpty(), "the shop " + shop + " did not open: "
                + refusal.map(found -> found.reason().name()).orElse(""));
    }

    private static void assertAccepted(GameTestHelper helper, TradeOutcome outcome, String message) {
        helper.assertTrue(outcome.accepted(), message + ": refused with " + reasonOf(outcome));
    }

    private static void assertRefused(GameTestHelper helper, TradeOutcome outcome, Reason reason, String message) {
        helper.assertTrue(outcome.refusal().map(TradeRefusal::reason).filter(reason::equals).isPresent(),
                message + ": expected " + reason + ", got " + reasonOf(outcome));
    }

    private static String reasonOf(TradeOutcome outcome) {
        return outcome.refusal().map(refusal -> refusal.reason().name()).orElse("accepted");
    }

    /**
     * A sale of experience at the broker that settlement takes as given: the service begins nothing, so the pool moves only
     * when storage applies it. The effect is {@link #saleEffect}.
     */
    private static SettlementRequest foreignSale(TradeRig rig, long points, long aur) {
        return new SettlementRequest(OperationKind.TRADE, BROKER, 0, TradeRig.REVISION, Optional.empty(), 0,
                saleEffect(rig, points, aur), player -> List.of(), Optional.empty(), Optional.empty(), List.of(), 0);
    }

    private static OperationEffect.TradeSettled saleEffect(TradeRig rig, long points, long aur) {
        return new OperationEffect.TradeSettled(BROKER, rig.day(), 0, List.of(), "{}", points, aur);
    }

    /**
     * A sale of 100 Aur at the broker, then a day that restocks the pool, with the restock's write answered by {@code how}. The
     * failed write makes the reconcile due, so memory returns to the row, which still has the old restock day; the next restock
     * then applies the same move once, and the closed day is kept once.
     */
    private static void restockRecovers(GameTestHelper helper, TradeRig.Refusal how) {
        TradeRig rig = TradeRig.create(helper, BUNDLE_MARKET);
        ServerPlayer player = rig.joined();
        holdFiveThousandPoints(player);
        LocalDate closed = rig.day();
        LocalDate next = closed.plusDays(1);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.loaded(player) && rig.service().market().isPresent(),
                            "the trade record or the market has not loaded");
                })
                .thenExecute(() -> {
                    open(helper, rig, player, BROKER, Optional.empty());
                    assertAccepted(helper, rig.service().experience(player, rig.sessionId(player), true, 100, 5051),
                            "sell 100 Aur of experience");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertTrue(rig.idle(player), "the sale is still settling");
                })
                .thenExecute(() -> {
                    rig.failNext("restockMarket", how);
                    rig.clock().advance(Duration.ofHours(24));
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    helper.assertValueEqual(rig.service().market().orElseThrow().restockedDay(), next,
                            "the restock ran in memory");
                })
                .thenWaitUntil(() -> {
                    rig.tick();
                    // The reconcile puts memory back to the row, which is still at the sale's pool and the closed day. The next
                    // tick then restocks from that row: 505051 and 9900 move 10 percent toward the seed, 506 points and 10 Aur.
                    // A memory that was not reconciled would never restock again, and storage would never reach these values.
                    helper.assertValueEqual(rig.service().market().orElseThrow().live(), new Reserves(504_545, 9_910),
                            "the second restock applied, in memory");
                    helper.assertValueEqual(rig.service().market().orElseThrow().restockedDay(), next,
                            "the second restock's day, in memory");
                    helper.assertValueEqual(rig.storedMarket().orElseThrow().points(), 504_545L, "the stored points");
                    helper.assertValueEqual(rig.storedMarket().orElseThrow().aur(), 9_910L, "the stored Aur");
                    helper.assertValueEqual(rig.storedMarket().orElseThrow().restockedDay(), next, "the stored restock day");
                    helper.assertValueEqual(ticksOf(rig), List.of(List.of(504_545L, 9_910L), List.of(505_051L, 9_900L)),
                            "the ticks once the second restock applied: the failed one recorded none");
                })
                .thenExecute(() -> {
                    open(helper, rig, player, BROKER, Optional.empty());
                    helper.assertValueEqual(rig.messenger().lastView(player).orElseThrow().experience().orElseThrow()
                            .market().orElseThrow().history(), List.of(5_101, 5_091), "the chart, each rate once");
                })
                .thenSucceed();
    }

    /** The stored ticks as {@code [points, aur]} pairs, newest first. */
    private static List<List<Long>> ticksOf(TradeRig rig) {
        return rig.storedMarketTicks().stream().map(tick -> List.of(tick.points(), tick.aur())).toList();
    }

    /** The experience market's live reserves, which the test's service must have loaded. */
    private static Reserves live(TradeRig rig) {
        return rig.service().market().orElseThrow().live();
    }

    /** Gives the player level 50 with no progress, which holds 5345 points: the total the game needs to reach level 50. */
    private static void holdFiveThousandPoints(ServerPlayer player) {
        player.experienceLevel = 50;
        player.experienceProgress = 0f;
        player.totalExperience = 5_345;
    }

    /** The items of the type in the player's main inventory. */
    private static int count(ServerPlayer player, Item item) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
