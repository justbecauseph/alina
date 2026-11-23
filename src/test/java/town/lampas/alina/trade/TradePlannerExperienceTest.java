package town.lampas.alina.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import town.lampas.alina.TestBootstrap;
import town.lampas.alina.content.TradeContent.Denomination;
import town.lampas.alina.content.TradeContent.ExperienceFees;
import town.lampas.alina.content.TradeContent.Selling;
import town.lampas.alina.content.TradeContent.Shop;
import town.lampas.alina.trade.TradeRefusal.Reason;
import town.lampas.alina.trade.pricing.ReserveCurve.Reserves;

/**
 * The refusal order and the moves of a trade at a shop that trades against the experience market. The pool is
 * {@code Reserves(500000, 10000)} at a cap of 10 percent, and the figures follow {@code ReserveCurve}'s rules. At no fee,
 * selling 100 Aur takes 5051 points: the curve gives 5050.5, rounded up. Buying 100 Aur gives 4950 points:
 * floor(500000 * 100 / 10100) is 4950. A player at level 50 with no progress holds 5345 points (the total to reach level 50).
 */
class TradePlannerExperienceTest {
    private static final Identifier SHOP = Identifier.parse("alina:trade_test/broker");
    private static final Reserves POOL = new Reserves(500_000, 10_000);
    private static final ExperienceMarket.Snapshot MARKET = new ExperienceMarket.Snapshot(POOL, POOL, 10, List.of());
    private static final long HELD = 5_345;
    private static final int BUDGET = 5_000;
    private static final int SALE_POINTS = 5_051;
    private static final int PURCHASE_POINTS = 4_950;
    private static boolean booted;

    @BeforeAll
    static synchronized void boot() {
        if (booted) {
            return;
        }
        TestBootstrap.boot();
        HolderLookup.Provider datapack = VanillaRegistries.createLookup();
        List<HolderLookup.RegistryLookup<?>> lookups = new ArrayList<>();
        BuiltInRegistries.REGISTRY.forEach(lookups::add);
        datapack.listRegistryKeys()
                .filter(key -> !BuiltInRegistries.REGISTRY.containsKey(key.identifier()))
                .forEach(key -> lookups.add(datapack.lookupOrThrow(key)));
        for (DataComponentInitializers.PendingComponents<?> pending
                : BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(HolderLookup.Provider.create(lookups.stream()))) {
            pending.apply();
        }
        booted = true;
    }

    @Test
    void aFeeShopWithNoMarketIsLoadingBeforeAnyOtherCheck() {
        TradePlan plan = experience(feeShop(OptionalInt.of(0), OptionalInt.of(0)), true, 1_001, 0, BUDGET, HELD,
                Optional.empty());

        assertEquals(Optional.of(Reason.LOADING), plan.refusal());
    }

    @Test
    void aSaleAtAShopWithNoSellFeeIsNotOfferedBeforeTheAmountIsChecked() {
        // 1001 Aur is over the cap, but the direction is checked first.
        TradePlan plan = experience(feeShop(OptionalInt.empty(), OptionalInt.of(0)), true, 1_001, 0, BUDGET, HELD,
                Optional.of(MARKET));

        assertEquals(Optional.of(Reason.NOT_OFFERED), plan.refusal());
    }

    @Test
    void aBuyAtAShopWithNoBuyFeeIsNotOffered() {
        TradePlan plan = experience(feeShop(OptionalInt.of(0), OptionalInt.empty()), false, 100, PURCHASE_POINTS, BUDGET,
                HELD, Optional.of(MARKET));

        assertEquals(Optional.of(Reason.NOT_OFFERED), plan.refusal());
    }

    @Test
    void aSaleAboveTheCapIsInvalid() {
        // The cap is 10 percent of 10000 Aur, so 1001 Aur is one over it.
        TradePlan plan = experience(feeShop(OptionalInt.of(0), OptionalInt.of(0)), true, 1_001, 0, BUDGET, HELD,
                Optional.of(MARKET));

        assertEquals(Optional.of(Reason.INVALID), plan.refusal());
    }

    @Test
    void aSaleAtAFigureTheCurveDoesNotGiveIsStale() {
        TradePlan plan = experience(feeShop(OptionalInt.of(0), OptionalInt.of(0)), true, 100, 5_000, BUDGET, HELD,
                Optional.of(MARKET));

        assertEquals(Optional.of(Reason.STALE), plan.refusal());
    }

    @Test
    void aSaleOverTheBudgetIsRefusedBeforeThePlayersExperienceIsChecked() {
        TradePlan plan = experience(feeShop(OptionalInt.of(0), OptionalInt.of(0)), true, 100, SALE_POINTS, 99, 0,
                Optional.of(MARKET));

        assertEquals(Optional.of(Reason.BUDGET_SPENT), plan.refusal());
    }

    @Test
    void aSaleOfMoreExperienceThanThePlayerHoldsIsRefused() {
        TradePlan plan = experience(feeShop(OptionalInt.of(0), OptionalInt.of(0)), true, 100, SALE_POINTS, BUDGET,
                SALE_POINTS - 1, Optional.of(MARKET));

        assertEquals(Optional.of(Reason.NOT_ENOUGH_EXPERIENCE), plan.refusal());
    }

    @Test
    void anAcceptedSaleTakesThePointsAndAddsThemToThePool() {
        TradePlan plan = experience(feeShop(OptionalInt.of(0), OptionalInt.of(0)), true, 100, SALE_POINTS, BUDGET,
                HELD, Optional.of(MARKET));

        assertFalse(plan.refusal().isPresent());
        assertEquals("sold", plan.line());
        assertEquals(SALE_POINTS, plan.pointsTaken());
        assertEquals(0, plan.pointsGiven());
        assertEquals(100, plan.aurPaid());
        assertEquals(SALE_POINTS, plan.marketPoints());
        assertEquals(-100, plan.marketAur());
    }

    @Test
    void anAcceptedPurchaseGivesThePointsAndTakesThemFromThePool() {
        TradePlan plan = experience(feeShop(OptionalInt.of(0), OptionalInt.of(0)), false, 100, PURCHASE_POINTS, BUDGET,
                0, Optional.of(MARKET), eleven());

        assertFalse(plan.refusal().isPresent());
        assertEquals("bought", plan.line());
        assertEquals(PURCHASE_POINTS, plan.pointsGiven());
        assertEquals(0, plan.aurPaid());
        assertEquals(-PURCHASE_POINTS, plan.marketPoints());
        assertEquals(100, plan.marketAur());
    }

    @Test
    void aPurchaseAtAFigureTheCurveDoesNotGiveIsStaleBeforeTheCoinsAreChecked() {
        TradePlan plan = experience(feeShop(OptionalInt.of(0), OptionalInt.of(0)), false, 100, 999, BUDGET, 0,
                Optional.of(MARKET));

        assertEquals(Optional.of(Reason.STALE), plan.refusal());
    }

    @Test
    void aPurchaseWithoutTheCoinsCannotAfford() {
        TradePlan plan = experience(feeShop(OptionalInt.of(0), OptionalInt.of(0)), false, 100, PURCHASE_POINTS, BUDGET,
                0, Optional.of(MARKET));

        assertEquals(Optional.of(Reason.CANNOT_AFFORD), plan.refusal());
    }

    @Test
    void aPurchaseThatWouldPushThePointsPastTheIntIsInvalid() {
        TradePlan plan = experience(feeShop(OptionalInt.of(0), OptionalInt.of(0)), false, 100, PURCHASE_POINTS, BUDGET,
                TradePlanner.MAX_POINTS - 10, Optional.of(MARKET), eleven());

        assertEquals(Optional.of(Reason.INVALID), plan.refusal());
    }

    @Test
    void aPurchaseOverTheCapIsInvalid() {
        // 1111 Aur is the most at this pool, so 1112 is one over it.
        TradePlan plan = experience(feeShop(OptionalInt.of(0), OptionalInt.of(0)), false, 1_112, 0, BUDGET, 0,
                Optional.of(MARKET), eleven());

        assertEquals(Optional.of(Reason.INVALID), plan.refusal());
    }

    @Test
    void aSaleAtAFeeOfFiveGoesUpToTheCapWhenThePlayerCanAffordIt() {
        // The cap is 1000 Aur. The curve gives 1000 Aur 55556 points (500000 * 1000 / 9000, rounded up), and the fee of 5
        // percent makes that 58334 (55556 * 1.05 = 58333.8, rounded up).
        int most = ExperienceMarket.maxSellAur(POOL, OptionalInt.of(5), 1_000_000, 5_000, 10);
        assertEquals(1_000, most);

        TradePlan accepted = experience(feeShop(OptionalInt.of(5), OptionalInt.of(5)), true, most, 58_334, 5_000, 1_000_000,
                Optional.of(MARKET));
        assertFalse(accepted.refusal().isPresent());
        assertEquals(58_334, accepted.pointsTaken());

        TradePlan over = experience(feeShop(OptionalInt.of(5), OptionalInt.of(5)), true, most + 1, 0, 5_000, 1_000_000,
                Optional.of(MARKET));
        assertEquals(Optional.of(Reason.INVALID), over.refusal());
    }

    @Test
    void aSaleAtAFeeOfFiveStopsWhereThePlayersPointsRunOut() {
        // With 20000 points the most is 366 Aur. The curve gives 366 Aur 18996 points (500000 * 366 / 9634, rounded up), and the
        // fee makes them 19946 (18996 * 1.05 = 19945.8, rounded up). 367 Aur cost 19050 by the curve and 20003 with the fee.
        int most = ExperienceMarket.maxSellAur(POOL, OptionalInt.of(5), 20_000, 5_000, 10);
        assertEquals(366, most);

        TradePlan accepted = experience(feeShop(OptionalInt.of(5), OptionalInt.of(5)), true, most, 19_946, 5_000, 20_000,
                Optional.of(MARKET));
        assertFalse(accepted.refusal().isPresent());

        TradePlan over = experience(feeShop(OptionalInt.of(5), OptionalInt.of(5)), true, most + 1, 20_003, 5_000, 20_000,
                Optional.of(MARKET));
        assertEquals(Optional.of(Reason.NOT_ENOUGH_EXPERIENCE), over.refusal());
    }

    @Test
    void aPurchaseAtAFeeOfFiveGoesUpToTheCapWhenThePlayerHoldsTheAur() {
        // The cap is 1111 Aur: 500000 * 1111 / 11111 is 49995.5 points, and 1112 Aur passes the cap's 50000. At 1111 Aur the
        // curve gives 49995 points, and the fee of 5 percent leaves 47495 (49995 * 0.95 = 47495.25, rounded down).
        int most = ExperienceMarket.maxBuyAur(POOL, OptionalInt.of(5), 5_000, 0, 10);
        assertEquals(1_111, most);
        List<ItemStack> coins = List.of(new ItemStack(Items.GOLD_INGOT, 64), new ItemStack(Items.GOLD_INGOT, 59),
                new ItemStack(Items.GOLD_NUGGET, 4));

        TradePlan accepted = experience(feeShop(OptionalInt.of(5), OptionalInt.of(5)), false, most, 47_495, 5_000, 0,
                Optional.of(MARKET), coins);
        assertFalse(accepted.refusal().isPresent());
        assertEquals(47_495, accepted.pointsGiven());

        TradePlan over = experience(feeShop(OptionalInt.of(5), OptionalInt.of(5)), false, most + 1, 0, 5_000, 0,
                Optional.of(MARKET), coins);
        assertEquals(Optional.of(Reason.INVALID), over.refusal());
    }

    /** A shop with the given experience fees, which trades nothing else. */
    private static Shop feeShop(OptionalInt sell, OptionalInt buy) {
        boot();
        Optional<Selling> selling = Optional.of(new Selling(BUDGET, List.of()));
        return new Shop(SHOP, Component.literal("Test"), selling, List.of(), Optional.empty(),
                Optional.of(new ExperienceFees(sell, buy)), Map.of());
    }

    private static TradePlan experience(Shop shop, boolean selling, int aur, int expectedPoints, int budgetLeft,
            long held, Optional<ExperienceMarket.Snapshot> market) {
        return experience(shop, selling, aur, expectedPoints, budgetLeft, held, market, List.of());
    }

    /** A plan for the request, with the player's inventory holding the given stacks in its first slots. */
    private static TradePlan experience(Shop shop, boolean selling, int aur, int expectedPoints, int budgetLeft,
            long held, Optional<ExperienceMarket.Snapshot> market, List<ItemStack> stacks) {
        boot();
        Inventory inventory = new Inventory(null, null);
        for (int slot = 0; slot < stacks.size(); slot++) {
            inventory.setItem(slot, stacks.get(slot));
        }
        return TradePlanner.experience(shop, currency(), inventory, budgetLeft, held, selling, aur, expectedPoints,
                market);
    }

    /** Eleven ingots and a nugget: 100 Aur, which is what the purchase at the pool costs. */
    private static List<ItemStack> eleven() {
        return List.of(new ItemStack(Items.GOLD_INGOT, 11), new ItemStack(Items.GOLD_NUGGET, 1));
    }

    private static List<Denomination> currency() {
        return List.of(new Denomination(Items.GOLD_INGOT, 9), new Denomination(Items.GOLD_NUGGET, 1));
    }
}
