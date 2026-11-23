package town.lampas.alina.trade.pricing;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.advancements.predicates.ItemPredicate;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import town.lampas.alina.TestBootstrap;
import town.lampas.alina.content.TradeContent.Denomination;
import town.lampas.alina.content.TradeContent.Price;
import town.lampas.alina.content.TradeContent.SellRule;
import town.lampas.alina.content.TradeContent.Selling;
import town.lampas.alina.content.TradeContent.Shop;

/**
 * Shared setup for the pricing tests. {@code ContentFixtures} is package-private to the content tests, so this repeats its
 * bootstrap: Minecraft booted, item components bound (a stack cannot be built before that), and the builders for the
 * currency, the shops and their sell rules. The shops are built from the records, not parsed from JSON, because the
 * reader is package-private too.
 */
final class PricingFixtures {
    private static boolean booted;

    private PricingFixtures() {}

    /** Boots Minecraft and binds item components. Later calls do nothing. */
    static synchronized void boot() {
        if (booted) {
            return;
        }
        TestBootstrap.boot();
        HolderLookup.Provider lookup = lookup();
        for (DataComponentInitializers.PendingComponents<?> pending
                : BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(lookup)) {
            pending.apply();
        }
        booted = true;
    }

    /**
     * The currency as {@code TradeContent} holds it, largest value first: ingots (9) and nuggets (1). A method, not a field,
     * because the game's item class cannot be loaded before Minecraft is booted.
     */
    static List<Denomination> currency() {
        boot();
        return List.of(ingot(), nugget());
    }

    /** The nugget, worth 1 Aur. */
    static Denomination nugget() {
        boot();
        return new Denomination(Items.GOLD_NUGGET, 1);
    }

    /** The ingot, worth 9 Aur. */
    static Denomination ingot() {
        boot();
        return new Denomination(Items.GOLD_INGOT, 9);
    }

    /** The built-in registries plus the vanilla datapack registries, as the server's registry access holds them. */
    private static HolderLookup.Provider lookup() {
        HolderLookup.Provider datapack = VanillaRegistries.createLookup();
        List<HolderLookup.RegistryLookup<?>> lookups = new ArrayList<>();
        BuiltInRegistries.REGISTRY.forEach(lookups::add);
        datapack.listRegistryKeys()
                .filter(key -> !BuiltInRegistries.REGISTRY.containsKey(key.identifier()))
                .forEach(key -> lookups.add(datapack.lookupOrThrow(key)));
        return HolderLookup.Provider.create(lookups.stream());
    }

    /** An empty main inventory: the game's inventory class, with no player and no armour. */
    static Inventory inventory() {
        boot();
        return new Inventory(null, null);
    }

    /** A stack from vanilla JSON, such as an enchanted sword. */
    static ItemStack stack(String json) {
        boot();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, lookup());
        return ItemStack.CODEC.parse(ops, JsonParser.parseString(json)).getOrThrow();
    }

    /** A shop that buys with the given rules and offers nothing. */
    static Shop shop(SellRule... rules) {
        return new Shop(Identifier.parse("lampas:s"), Component.literal("S"),
                Optional.of(new Selling(100, List.of(rules))), List.of(), Optional.empty(), Optional.empty(), Map.of());
    }

    /** A shop that does not buy from players. */
    static Shop shopThatBuysNothing() {
        return new Shop(Identifier.parse("lampas:s"), Component.literal("S"), Optional.empty(), List.of(),
                Optional.empty(), Optional.empty(), Map.of());
    }

    /** A rule for one item: {@code aur} Aur for every {@code per} items, and whether it is junk. */
    static SellRule itemRule(Item item, int aur, int per, boolean junk) {
        return new SellRule(Optional.of(itemPredicate(item)), Optional.empty(), Optional.of(new Price(aur, per)), junk,
                false, false);
    }

    /** A rule for one item that also takes stacks with component changes. */
    static SellRule modifiedItemRule(Item item, int aur, int per) {
        return new SellRule(Optional.of(itemPredicate(item)), Optional.empty(), Optional.of(new Price(aur, per)), false,
                false, true);
    }

    /** A rule for every unmodified item of a namespace. */
    static SellRule namespaceRule(String namespace, int aur, int per, boolean junk) {
        return new SellRule(Optional.empty(), Optional.of(namespace), Optional.of(new Price(aur, per)), junk, false,
                false);
    }

    /** A rule that refuses one item. */
    static SellRule refuseRule(Item item) {
        return new SellRule(Optional.of(itemPredicate(item)), Optional.empty(), Optional.empty(), false, true, false);
    }

    private static ItemPredicate itemPredicate(Item item) {
        return ItemPredicate.Builder.item().of(BuiltInRegistries.ITEM, item).build();
    }
}
