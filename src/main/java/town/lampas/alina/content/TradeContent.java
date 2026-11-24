package town.lampas.alina.content;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.advancements.predicates.ItemPredicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The parsed {@code alina:trade} extension: the currency, the price lists, the shops and the experience market. Immutable.
 * The format is in {@code docs/content-guide.md}.
 *
 * @param currency the denominations, largest value first
 * @param priceLists the price lists of the section, by id
 * @param shops the shops of the section, by id
 * @param market the experience market, present when the section has one; then every shop's experience is fees
 */
public record TradeContent(
        List<Denomination> currency,
        Map<Identifier, PriceList> priceLists,
        Map<Identifier, Shop> shops,
        Optional<ExperienceMarket> market) {
    public static final TradeContent EMPTY = new TradeContent(List.of(), Map.of(), Map.of(), Optional.empty());

    public TradeContent {
        currency = currency.stream().sorted(Comparator.comparingInt(Denomination::value).reversed()).toList();
        priceLists = Map.copyOf(priceLists);
        shops = Map.copyOf(shops);
    }

    /** An item that stands for {@code value} Aur when it is paid out as change. */
    public record Denomination(Item item, int value) {}

    /** {@code aur} Aur for every {@code per} items. */
    public record Price(int aur, int per) {}

    /**
     * One rule of a sell list. It matches a stack when its {@code namespace} is the item's namespace, or its
     * {@code match} predicate accepts the stack, and, unless {@code acceptsModified}, the stack has no component changes.
     *
     * @param match a vanilla item predicate without {@code count}; empty for a namespace rule
     * @param namespace the namespace of every item the rule takes; empty for a predicate rule
     * @param price what the shop pays for {@code price.per()} items; empty for a refuse rule
     * @param junk whether the shop takes the item as junk
     * @param refuse whether the shop refuses the item; callers check it, as {@link Shop#ruleFor} returns refuse rules too
     * @param acceptsModified whether {@code match} has {@code components} or {@code predicates}, so a modified stack can match
     */
    public record SellRule(
            Optional<ItemPredicate> match,
            Optional<String> namespace,
            Optional<Price> price,
            boolean junk,
            boolean refuse,
            boolean acceptsModified) {

        /** Whether this rule applies to {@code stack}. */
        public boolean matches(ItemStack stack) {
            boolean named = namespace.map(text -> text.equals(itemNamespace(stack))).orElse(false)
                    || match.map(predicate -> predicate.test(stack)).orElse(false);
            return named && (acceptsModified || stack.getComponentsPatch().isEmpty());
        }

        private static String itemNamespace(ItemStack stack) {
            return BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace();
        }
    }

    /** A price list: the sell rules a sell section names by id, in order. */
    public record PriceList(List<SellRule> rules) {
        public PriceList {
            rules = List.copyOf(rules);
        }
    }

    /**
     * One shop.
     *
     * @param selling present when the shop buys from players
     * @param offers what the shop sells to players, in the order the content gives them
     * @param experience present when the shop trades experience at fixed rates, in a section without an experience market
     * @param experienceFees present when the shop trades experience at fees, in a section with an experience market
     * @param lines the player-facing lines the content gives, by key
     * @throws IllegalArgumentException when both {@code experience} and {@code experienceFees} are present
     */
    public record Shop(
            Identifier id,
            Component name,
            Optional<Selling> selling,
            List<Offer> offers,
            Optional<ExperienceRates> experience,
            Optional<ExperienceFees> experienceFees,
            Map<String, Component> lines) {

        public Shop {
            if (experience.isPresent() && experienceFees.isPresent()) {
                throw new IllegalArgumentException("a shop has experience rates or experience fees, not both");
            }
            offers = List.copyOf(offers);
            lines = Map.copyOf(lines);
        }

        /**
         * The first rule of the selling rules that matches {@code stack}. A refuse rule is returned as it is, so callers
         * check {@link SellRule#refuse()}. Empty when the shop does not buy from players or no rule matches.
         */
        public Optional<SellRule> ruleFor(ItemStack stack) {
            return selling.flatMap(sell -> sell.rules().stream().filter(rule -> rule.matches(stack)).findFirst());
        }
    }

    /**
     * What a shop pays players: its daily budget and its sell rules in the order they are tried. The rules are the
     * shop's own rules, then the rules of each price list the shop names, in the order it names them.
     */
    public record Selling(int dailyBudget, List<SellRule> rules) {
        public Selling {
            rules = List.copyOf(rules);
        }
    }

    /**
     * One item a shop sells to players for {@code price} Aur. The id is unique within the shop.
     *
     * @param item the item and the count sold for {@code price}; the count is never 0. {@link #item()} returns a copy.
     */
    public record Offer(String id, ItemStack item, int price, Optional<Limit> limit, Optional<Requires> requires) {
        public Offer {
            item = item.copy();
        }

        /** A copy, so a caller cannot change the offer. */
        @Override
        public ItemStack item() {
            return item.copy();
        }
    }

    /** How many of an offer one player may buy in each {@link Period}. */
    public record Limit(int count, Period per) {
        public enum Period {
            DAY,
            CHARACTER
        }
    }

    /** What a player needs before an offer is open to them: a reputation, an achievement, or both. */
    public record Requires(OptionalInt reputation, Optional<Identifier> achievement) {}

    /**
     * The experience a shop trades. {@code sell} is what a player sells to the shop, {@code buy} what a player buys from
     * it.
     */
    public record ExperienceRates(Optional<Rate> sell, Optional<Rate> buy) {}

    /** {@code points} experience points for every {@code aur} Aur. */
    public record Rate(int points, int aur) {}

    /**
     * The one pool every experience broker of the section trades against. The seed reserves are where the pool heads: their
     * ratio is the base rate, and the restock moves the live reserves back toward them.
     *
     * @param seedPoints the points of the seed reserve
     * @param seedAur the Aur of the seed reserve
     * @param restockPercent how far each live reserve moves back to the seed per Guild board day, in percent
     * @param maxTradePercent the most of the reserve one trade may take from it, in percent
     * @param quoteTolerancePercent how far, in percent of the quoted points, a market trade may have moved against the player
     *     since the quote and still go through; 0 refuses any difference
     */
    public record ExperienceMarket(long seedPoints, long seedAur, int restockPercent, int maxTradePercent,
            int quoteTolerancePercent) {}

    /**
     * A shop's fee on experience in a section with an experience market: the fee in percent for each direction. A direction
     * that is empty is not traded.
     *
     * @param sell the fee when a player sells experience to the shop
     * @param buy the fee when a player buys experience from the shop
     */
    public record ExperienceFees(OptionalInt sell, OptionalInt buy) {}
}
