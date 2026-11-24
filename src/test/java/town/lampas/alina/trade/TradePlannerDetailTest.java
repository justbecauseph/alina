package town.lampas.alina.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.advancements.predicates.ItemPredicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import town.lampas.alina.content.TradeContent.Denomination;
import town.lampas.alina.content.TradeContent.Offer;
import town.lampas.alina.content.TradeContent.Price;
import town.lampas.alina.content.TradeContent.SellRule;
import town.lampas.alina.content.TradeContent.Selling;
import town.lampas.alina.content.TradeContent.Shop;

/** Verifies the {@code detail} JSON emitted by {@link TradePlanner} for junk sales and offer buys. */
class TradePlannerDetailTest {
    private static final Identifier SHOP_ID = Identifier.parse("alina:trade_test/detail");

    @BeforeAll
    static void boot() {
        TradePlannerExperienceTest.boot();
    }

    @Test
    void junkDetailIncludesPerSlotAurSummingToRuleAur() {
        List<Denomination> currency = List.of(new Denomination(Items.GOLD_NUGGET, 1));
        SellRule fleshRule = new SellRule(
                Optional.of(ItemPredicate.Builder.item().of(BuiltInRegistries.ITEM, Items.ROTTEN_FLESH).build()),
                Optional.empty(), Optional.of(new Price(5, 4)), true, false, false);
        Shop shop = new Shop(SHOP_ID, Component.literal("Shop"),
                Optional.of(new Selling(100, List.of(fleshRule))),
                List.of(), Optional.empty(), Optional.empty(), Map.of());
        Inventory inventory = new Inventory(null, null);
        inventory.setItem(0, new ItemStack(Items.ROTTEN_FLESH, 3));
        inventory.setItem(1, new ItemStack(Items.ROTTEN_FLESH, 5));

        TradePlan plan = TradePlanner.junk(shop, currency, inventory, 100, 10);

        assertTrue(plan.refusal().isEmpty());
        JsonObject detail = JsonParser.parseString(plan.detail()).getAsJsonObject();
        JsonArray sold = detail.getAsJsonArray("sold");
        assertEquals(2, sold.size());
        JsonObject first = sold.get(0).getAsJsonObject();
        JsonObject second = sold.get(1).getAsJsonObject();
        assertEquals("minecraft:rotten_flesh", first.get("item").getAsString());
        assertEquals(3, first.get("count").getAsInt());
        assertEquals(3, first.get("aur").getAsInt());
        assertEquals("minecraft:rotten_flesh", second.get("item").getAsString());
        assertEquals(5, second.get("count").getAsInt());
        assertEquals(7, second.get("aur").getAsInt());
        assertEquals(10, first.get("aur").getAsInt() + second.get("aur").getAsInt());
    }

    @Test
    void buyDetailIncludesOfferId() {
        List<Denomination> currency = List.of(new Denomination(Items.GOLD_NUGGET, 1));
        Offer offer = new Offer("scroll_pack", new ItemStack(Items.PAPER, 2), 7, Optional.empty(), Optional.empty());
        Shop shop = new Shop(SHOP_ID, Component.literal("Shop"), Optional.empty(), List.of(offer),
                Optional.empty(), Optional.empty(), Map.of());
        Inventory inventory = new Inventory(null, null);
        inventory.setItem(0, new ItemStack(Items.GOLD_NUGGET, 20));
        OfferState state = new OfferState(offer, Optional.empty(), -1, Optional.empty());

        TradePlan plan = TradePlanner.buy(shop, currency, inventory, state, 2, 14);

        assertTrue(plan.refusal().isEmpty());
        JsonObject detail = JsonParser.parseString(plan.detail()).getAsJsonObject();
        JsonArray bought = detail.getAsJsonArray("bought");
        assertEquals(1, bought.size());
        JsonObject entry = bought.get(0).getAsJsonObject();
        assertEquals("scroll_pack", entry.get("offer").getAsString());
        assertEquals("minecraft:paper", entry.get("item").getAsString());
        assertEquals(4, entry.get("count").getAsInt());
        assertEquals(14, entry.get("aur").getAsInt());
        assertEquals(14, detail.get("aur_spent").getAsLong());
    }
}
