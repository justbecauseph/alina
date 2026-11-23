package town.lampas.alina.trade.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static town.lampas.alina.trade.pricing.PricingFixtures.currency;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import town.lampas.alina.content.TradeContent.Shop;
import town.lampas.alina.trade.pricing.SellPlanner.JunkPlan;
import town.lampas.alina.trade.pricing.SellPlanner.Quote;

/**
 * What a shop pays for a stack, and "Sell all junk". Rules are built in code (see {@link PricingFixtures}). Expected values
 * are worked out by hand from the bundle rule: {@code per} items for {@code aur} Aur, across stacks, lower slots first.
 */
class SellPlannerTest {

    @BeforeAll
    static void boot() {
        PricingFixtures.boot();
    }

    @Test
    void aMatchingStackQuotesItsRulesPriceAndRule() {
        Shop shop = PricingFixtures.shop(PricingFixtures.itemRule(Items.ROTTEN_FLESH, 1, 4, true));

        Quote quote = SellPlanner.quote(shop, currency(), new ItemStack(Items.ROTTEN_FLESH, 3)).orElseThrow();

        assertEquals(1, quote.aur());
        assertEquals(4, quote.per());
        assertEquals(shop.selling().orElseThrow().rules().get(0), quote.rule());
    }

    @Test
    void aRefuseRuleQuotesNothingEvenWhenALaterRuleWouldTakeTheStack() {
        Shop shop = PricingFixtures.shop(PricingFixtures.refuseRule(Items.NETHER_STAR),
                PricingFixtures.namespaceRule("minecraft", 2, 1, false));

        assertTrue(SellPlanner.quote(shop, currency(), new ItemStack(Items.NETHER_STAR)).isEmpty());
    }

    @Test
    void aCoinQuotesNothingEvenWhenARuleWouldTakeIt() {
        Shop shop = PricingFixtures.shop(PricingFixtures.namespaceRule("minecraft", 1, 1, false));
        ItemStack nugget = new ItemStack(Items.GOLD_NUGGET);

        assertTrue(SellPlanner.quote(shop, currency(), nugget).isEmpty());
        // Without the currency, the same nugget is sold by the namespace rule.
        assertEquals(1, SellPlanner.quote(shop, List.of(PricingFixtures.ingot()), nugget).orElseThrow().aur());
    }

    @Test
    void anEnchantedStackQuotesNothingAgainstAPlainRule() {
        ItemStack sword = PricingFixtures.stack("{ \"id\": \"minecraft:iron_sword\", \"components\": "
                + "{ \"minecraft:enchantments\": { \"minecraft:sharpness\": 1 } } }");

        Shop plain = PricingFixtures.shop(PricingFixtures.itemRule(Items.IRON_SWORD, 5, 1, false));
        assertTrue(SellPlanner.quote(plain, currency(), sword).isEmpty());

        Shop modified = PricingFixtures.shop(PricingFixtures.modifiedItemRule(Items.IRON_SWORD, 9, 1));
        assertEquals(9, SellPlanner.quote(modified, currency(), sword).orElseThrow().aur());
    }

    @Test
    void aContainerOrBundleHoldingItemsQuotesNothing() {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIRT, 3))));
        Shop boxShop = PricingFixtures.shop(PricingFixtures.modifiedItemRule(Items.SHULKER_BOX, 5, 1));
        assertTrue(SellPlanner.quote(boxShop, currency(), box).isEmpty());

        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(new ItemStackTemplate(Items.DIRT))));
        Shop bundleShop = PricingFixtures.shop(PricingFixtures.modifiedItemRule(Items.BUNDLE, 5, 1));
        assertTrue(SellPlanner.quote(bundleShop, currency(), bundle).isEmpty());
    }

    @Test
    void anEmptyContainerDoesNotBlockASale() {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of()));
        Shop shop = PricingFixtures.shop(PricingFixtures.modifiedItemRule(Items.SHULKER_BOX, 5, 1));

        assertEquals(5, SellPlanner.quote(shop, currency(), box).orElseThrow().aur());
    }

    @Test
    void aStackNoRuleMatchesQuotesNothingAndAShopThatBuysNothingQuotesNothing() {
        Shop shop = PricingFixtures.shop(PricingFixtures.itemRule(Items.ROTTEN_FLESH, 1, 4, true));

        assertTrue(SellPlanner.quote(shop, currency(), new ItemStack(Items.PAPER)).isEmpty());
        assertTrue(SellPlanner.quote(PricingFixtures.shopThatBuysNothing(), currency(), new ItemStack(Items.ROTTEN_FLESH))
                .isEmpty());
    }

    @Test
    void bundlesOfOneRuleSpanStacks() {
        // 3 and 5 are 8 items: two bundles of 4, so 2 Aur.
        Shop shop = PricingFixtures.shop(PricingFixtures.itemRule(Items.ROTTEN_FLESH, 1, 4, true));
        Inventory inventory = PricingFixtures.inventory();
        inventory.setItem(0, new ItemStack(Items.ROTTEN_FLESH, 3));
        inventory.setItem(1, new ItemStack(Items.ROTTEN_FLESH, 5));

        assertEquals(new JunkPlan(List.of(new Take(0, 3), new Take(1, 5)), 8, 2),
                SellPlanner.junk(shop, currency(), inventory, 100));
    }

    @Test
    void aRemainderSmallerThanABundleIsLeftBehind() {
        // 3 and 2 are 5 items: one bundle of 4, taken as 3 from slot 0 and 1 from slot 1. One item stays in slot 1.
        Shop shop = PricingFixtures.shop(PricingFixtures.itemRule(Items.ROTTEN_FLESH, 1, 4, true));
        Inventory inventory = PricingFixtures.inventory();
        inventory.setItem(0, new ItemStack(Items.ROTTEN_FLESH, 3));
        inventory.setItem(1, new ItemStack(Items.ROTTEN_FLESH, 2));

        assertEquals(new JunkPlan(List.of(new Take(0, 3), new Take(1, 1)), 4, 1),
                SellPlanner.junk(shop, currency(), inventory, 100));
    }

    @Test
    void theBudgetCutsTheBundles() {
        // Two bundles would pay 2 Aur, but only 1 is left in the budget.
        Shop shop = PricingFixtures.shop(PricingFixtures.itemRule(Items.ROTTEN_FLESH, 1, 4, true));
        Inventory inventory = PricingFixtures.inventory();
        inventory.setItem(0, new ItemStack(Items.ROTTEN_FLESH, 3));
        inventory.setItem(1, new ItemStack(Items.ROTTEN_FLESH, 5));

        assertEquals(new JunkPlan(List.of(new Take(0, 3), new Take(1, 1)), 4, 1),
                SellPlanner.junk(shop, currency(), inventory, 1));
    }

    @Test
    void theBudgetIsSharedByTheJunkRulesInOrder() {
        // The first rule pays 2 of the 3 Aur left, so the second rule has 1 Aur for one paper.
        Shop shop = PricingFixtures.shop(PricingFixtures.itemRule(Items.ROTTEN_FLESH, 1, 1, true),
                PricingFixtures.itemRule(Items.PAPER, 1, 1, true));
        Inventory inventory = PricingFixtures.inventory();
        inventory.setItem(0, new ItemStack(Items.ROTTEN_FLESH, 2));
        inventory.setItem(1, new ItemStack(Items.PAPER, 2));

        assertEquals(new JunkPlan(List.of(new Take(0, 2), new Take(1, 1)), 3, 3),
                SellPlanner.junk(shop, currency(), inventory, 3));
    }

    @Test
    void aNonJunkRuleBeforeAJunkRuleKeepsItsItemOut() {
        // Rotten flesh belongs to the first rule, which is not junk, so the junk rule sells only the paper.
        Shop shop = PricingFixtures.shop(PricingFixtures.itemRule(Items.ROTTEN_FLESH, 2, 1, false),
                PricingFixtures.namespaceRule("minecraft", 1, 1, true));
        Inventory inventory = PricingFixtures.inventory();
        inventory.setItem(0, new ItemStack(Items.ROTTEN_FLESH, 5));
        inventory.setItem(1, new ItemStack(Items.PAPER, 3));

        assertEquals(new JunkPlan(List.of(new Take(1, 3)), 3, 3), SellPlanner.junk(shop, currency(), inventory, 100));
    }

    @Test
    void coinsAreNotJunk() {
        Shop shop = PricingFixtures.shop(PricingFixtures.namespaceRule("minecraft", 1, 1, true));
        Inventory inventory = PricingFixtures.inventory();
        inventory.setItem(0, new ItemStack(Items.GOLD_NUGGET, 2));
        inventory.setItem(1, new ItemStack(Items.PAPER, 1));

        assertEquals(new JunkPlan(List.of(new Take(1, 1)), 1, 1), SellPlanner.junk(shop, currency(), inventory, 100));
    }

    @Test
    void aShopWithoutJunkRulesSellsNoJunk() {
        Shop shop = PricingFixtures.shop(PricingFixtures.itemRule(Items.ROTTEN_FLESH, 1, 4, false));
        Inventory inventory = PricingFixtures.inventory();
        inventory.setItem(0, new ItemStack(Items.ROTTEN_FLESH, 8));

        assertEquals(SellPlanner.JunkPlan.EMPTY, SellPlanner.junk(shop, currency(), inventory, 100));
        assertEquals(SellPlanner.JunkPlan.EMPTY,
                SellPlanner.junk(PricingFixtures.shopThatBuysNothing(), currency(), inventory, 100));
    }
}
