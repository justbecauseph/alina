package town.lampas.alina.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import net.minecraft.advancements.predicates.ItemPredicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import town.lampas.alina.content.TradeContent.Denomination;
import town.lampas.alina.content.TradeContent.ExperienceFees;
import town.lampas.alina.content.TradeContent.ExperienceMarket;
import town.lampas.alina.content.TradeContent.ExperienceRates;
import town.lampas.alina.content.TradeContent.Limit;
import town.lampas.alina.content.TradeContent.Offer;
import town.lampas.alina.content.TradeContent.Price;
import town.lampas.alina.content.TradeContent.PriceList;
import town.lampas.alina.content.TradeContent.Rate;
import town.lampas.alina.content.TradeContent.Requires;
import town.lampas.alina.content.TradeContent.SellRule;
import town.lampas.alina.content.TradeContent.Selling;
import town.lampas.alina.content.TradeContent.Shop;
import town.lampas.haruhi.content.api.ExtensionContext;

/**
 * Reads and validates the {@code alina:trade} extension: the currency, the price lists and the shops. The checks that
 * need the running server (a namespace that has items) run only while a server runs, as {@link ServerLookups} does.
 *
 * <p>The mint checks run last, on the parsed shops: no shop's experience buy may be cheaper per point than another shop's
 * experience sell, and no offer may be bought back for more per item than it costs. An offer is refused outright when its
 * item is a currency item or holds other items, so {@link #readOffer} checks those.
 */
final class TradeReader {
    static final Set<String> TRADE_KEYS = Set.of("currency", "experience_market", "price_lists", "shops");
    static final Set<String> DENOMINATION_KEYS = Set.of("item", "value");
    static final Set<String> EXPERIENCE_MARKET_KEYS = Set.of("reserve", "restock_percent", "max_trade_percent");
    static final Set<String> RESERVE_KEYS = Set.of("points", "aur");
    static final Set<String> PRICE_LIST_KEYS = Set.of("rules");
    static final Set<String> SELL_RULE_KEYS = Set.of("match", "namespace", "price", "junk", "refuse");
    static final Set<String> PRICE_KEYS = Set.of("aur", "per");
    static final Set<String> SHOP_KEYS = Set.of("name", "sell", "buy", "experience", "lines");
    static final Set<String> SELL_KEYS = Set.of("daily_budget", "lists", "rules");
    static final Set<String> OFFER_KEYS = Set.of("id", "item", "price", "limit", "requires");
    static final Set<String> LIMIT_KEYS = Set.of("count", "per");
    static final Set<String> REQUIRES_KEYS = Set.of("reputation", "achievement");
    static final Set<String> EXPERIENCE_KEYS = Set.of("sell", "buy");
    static final Set<String> RATE_KEYS = Set.of("points", "aur");
    static final Set<String> FEE_KEYS = Set.of("fee_percent");
    static final Set<String> LINE_KEYS = Set.of("greeting", "sold", "bought", "cannot_afford", "budget_spent", "no_room",
            "nothing_to_sell", "limit_reached", "locked", "busy");
    static final int MAX_PER = 64;
    /** The fewest points or Aur either seed reserve may hold. */
    static final int MIN_RESERVE = 1000;
    /** The most points or Aur either seed reserve may hold. */
    static final int MAX_RESERVE = 1_000_000_000;
    static final int MAX_RESTOCK_PERCENT = 100;
    static final int MIN_TRADE_PERCENT = 1;
    static final int MAX_TRADE_PERCENT = 50;
    static final int DEFAULT_TRADE_PERCENT = 10;
    static final int MAX_FEE_PERCENT = 50;
    /** The longest offer id. A unit test asserts it equals {@code OfferRow.MAX_ID_LENGTH}, the limit the shop's payloads carry. */
    static final int MAX_ID_LENGTH = 64;

    private static final Pattern OFFER_ID = Pattern.compile("[a-z0-9_]+");
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");

    private TradeReader() {}

    static Optional<TradeContent> read(JsonElement value, ExtensionContext haruhiContext) {
        CountingContext context = new CountingContext(haruhiContext);
        String where = "extension " + context.id();
        Optional<Section> root = Section.of(value, where, context);
        if (root.isEmpty()) {
            return Optional.empty();
        }
        Section section = root.get();
        section.allowOnly(TRADE_KEYS);

        List<Denomination> currency = readCurrency(section, context);
        // Declared means present, even when it fails to parse: the shops then take the form of a market.
        boolean marketDeclared = section.has("experience_market");
        Optional<ExperienceMarket> market = marketDeclared
                ? readMarket(section.raw("experience_market"), where, context)
                : Optional.empty();
        Map<String, JsonElement> priceListEntries = section.entries("price_lists");
        Map<Identifier, PriceList> priceLists = readPriceLists(section, priceListEntries, where, context);
        Map<String, JsonElement> shopEntries = section.entries("shops");
        Map<Identifier, Shop> shops = readShops(section, shopEntries, where, context, priceLists,
                declared(priceListEntries), currency, marketDeclared);
        checkMinting(shops, where, context);
        return Optional.of(new TradeContent(currency, priceLists, shops, market));
    }

    /** The ids of a section's keys that parse, including entries whose value failed to parse. */
    private static Set<Identifier> declared(Map<String, JsonElement> entries) {
        Set<Identifier> ids = new HashSet<>();
        for (String key : entries.keySet()) {
            Identifier id = Json.keyId(key);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * {@code currency}: a non-empty list of denominations. Each item is a registered item, each value at least 1, and no
     * item or value is listed twice. One denomination must have value 1, so any amount can be paid out.
     */
    private static List<Denomination> readCurrency(Section root, CountingContext context) {
        Optional<JsonArray> entries = root.required("currency", Json::array);
        if (entries.isEmpty()) {
            return List.of();
        }
        if (entries.get().isEmpty()) {
            root.add("currency must list at least one denomination");
            return List.of();
        }
        List<Denomination> denominations = new ArrayList<>();
        Map<Identifier, Integer> itemEntries = new HashMap<>();
        Map<Integer, Integer> valueEntries = new HashMap<>();
        boolean hasValueOne = false;
        for (int i = 0; i < entries.get().size(); i++) {
            int position = i + 1;
            Optional<Section> parsed = Section.of(entries.get().get(i), root.where() + ", currency entry " + position,
                    context);
            if (parsed.isEmpty()) {
                continue;
            }
            Section entry = parsed.get();
            entry.allowOnly(DENOMINATION_KEYS);
            Optional<Identifier> itemId = entry.required("item", Json::identifier);
            Optional<Item> item = itemId.flatMap(id -> BuiltInRegistries.ITEM.getOptional(id));
            if (itemId.isPresent() && item.isEmpty()) {
                entry.add("item '" + itemId.get() + "' is not a registered item");
            }
            Optional<Integer> value = entry.required("value", Json.integerAtLeast(1));
            hasValueOne |= value.filter(number -> number == 1).isPresent();

            boolean fresh = true;
            if (itemId.isPresent()) {
                Integer first = itemEntries.putIfAbsent(itemId.get(), position);
                if (first != null) {
                    entry.add("item '" + itemId.get() + "' is already the item of entry " + first);
                    fresh = false;
                }
            }
            if (value.isPresent()) {
                Integer first = valueEntries.putIfAbsent(value.get(), position);
                if (first != null) {
                    entry.add("value " + value.get() + " is already the value of entry " + first);
                    fresh = false;
                }
            }
            if (fresh && item.isPresent() && value.isPresent()) {
                denominations.add(new Denomination(item.get(), value.get()));
            }
        }
        if (!hasValueOne) {
            root.add("currency needs a denomination of value 1, so any amount can be paid out");
        }
        return denominations;
    }

    /**
     * {@code experience_market}: exactly {@code reserve} and {@code restock_percent}, which are required, and
     * {@code max_trade_percent}, which is {@value #DEFAULT_TRADE_PERCENT} when left out.
     */
    private static Optional<ExperienceMarket> readMarket(JsonElement value, String where, CountingContext context) {
        Optional<Section> parsed = Section.of(value, where + ", experience_market", context);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Section market = parsed.get();
        market.allowOnly(EXPERIENCE_MARKET_KEYS);
        int mark = context.count();
        if (!market.has("reserve")) {
            market.add("reserve is required");
        }
        Optional<Rate> reserve = market.has("reserve")
                ? readReserve(market.raw("reserve"), market.where(), context)
                : Optional.empty();
        Optional<Integer> restock = market.required("restock_percent", integerBetween(0, MAX_RESTOCK_PERCENT));
        Optional<Integer> maxTrade = market.optional("max_trade_percent",
                integerBetween(MIN_TRADE_PERCENT, MAX_TRADE_PERCENT));
        if (reserve.isEmpty() || restock.isEmpty() || context.count() > mark) {
            return Optional.empty();
        }
        return Optional.of(new ExperienceMarket(reserve.get().points(), reserve.get().aur(), restock.get(),
                maxTrade.orElse(DEFAULT_TRADE_PERCENT)));
    }

    /**
     * {@code reserve}: exactly {@code points} and {@code aur}, each a whole number from {@value #MIN_RESERVE} to
     * {@value #MAX_RESERVE}. Read as a {@link Rate}, the shape the two share.
     */
    private static Optional<Rate> readReserve(JsonElement value, String where, CountingContext context) {
        Optional<Section> parsed = Section.of(value, where + ", reserve", context);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Section reserve = parsed.get();
        reserve.allowOnly(RESERVE_KEYS);
        int mark = context.count();
        Optional<Integer> points = reserve.required("points", integerBetween(MIN_RESERVE, MAX_RESERVE));
        Optional<Integer> aur = reserve.required("aur", integerBetween(MIN_RESERVE, MAX_RESERVE));
        if (points.isEmpty() || aur.isEmpty() || context.count() > mark) {
            return Optional.empty();
        }
        return Optional.of(new Rate(points.get(), aur.get()));
    }

    /** A whole number from {@code minimum} to {@code maximum}, both included. */
    private static Function<JsonElement, DataResult<Integer>> integerBetween(int minimum, int maximum) {
        return value -> Json.integer(value).flatMap(number -> {
            if (number < minimum) {
                return DataResult.error(() -> "must be at least " + minimum);
            }
            if (number > maximum) {
                return DataResult.error(() -> "must be at most " + maximum);
            }
            return DataResult.success(number);
        });
    }

    /** {@code price_lists}: each key an id, each id once. A list whose rules fail is left out of the result. */
    private static Map<Identifier, PriceList> readPriceLists(
            Section root, Map<String, JsonElement> entries, String where, CountingContext context) {
        Map<Identifier, PriceList> lists = new LinkedHashMap<>();
        Map<Identifier, String> claimed = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
            Identifier id = Json.keyId(entry.getKey());
            if (id == null) {
                root.add("price_lists key '" + entry.getKey() + "' is not a valid id");
                continue;
            }
            if (GuildReader.claim(claimed, id, entry.getKey(), "price_lists", root)) {
                readPriceList(entry.getValue(), where + ", price list " + id, context)
                        .ifPresent(list -> lists.put(id, list));
            }
        }
        return lists;
    }

    private static Optional<PriceList> readPriceList(JsonElement value, String where, CountingContext context) {
        Optional<Section> parsed = Section.of(value, where, context);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Section list = parsed.get();
        list.allowOnly(PRICE_LIST_KEYS);
        int mark = context.count();
        Optional<List<SellRule>> rules = list.required("rules", Json::array)
                .flatMap(array -> readRules(elements(array), where, context));
        if (rules.isEmpty() || context.count() > mark) {
            return Optional.empty();
        }
        return Optional.of(new PriceList(rules.get()));
    }

    /**
     * The rules of one list, each at {@code <where>, rule <n>}. None when a rule failed, so a list with a broken rule is
     * left out whole.
     */
    private static Optional<List<SellRule>> readRules(List<JsonElement> elements, String where, CountingContext context) {
        List<SellRule> rules = new ArrayList<>();
        boolean valid = true;
        for (int i = 0; i < elements.size(); i++) {
            Optional<SellRule> rule = readRule(elements.get(i), where + ", rule " + (i + 1), context);
            if (rule.isPresent()) {
                rules.add(rule.get());
            } else {
                valid = false;
            }
        }
        return valid ? Optional.of(List.copyOf(rules)) : Optional.empty();
    }

    /**
     * One rule: exactly one of {@code match} and {@code namespace}; a {@code price} unless it refuses, and a refuse rule
     * has no {@code price} or {@code junk}.
     */
    private static Optional<SellRule> readRule(JsonElement value, String where, CountingContext context) {
        Optional<Section> parsed = Section.of(value, where, context);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Section rule = parsed.get();
        rule.allowOnly(SELL_RULE_KEYS);
        int mark = context.count();

        boolean refuse = rule.optional("refuse", Json::bool).orElse(false);
        boolean hasMatch = rule.has("match");
        boolean hasNamespace = rule.has("namespace");
        if (hasMatch == hasNamespace) {
            rule.add("needs exactly one of match and namespace");
        }
        Optional<ItemPredicate> match = Optional.empty();
        boolean acceptsModified = false;
        if (hasMatch) {
            JsonElement raw = rule.raw("match");
            if (raw.isJsonObject()) {
                JsonObject object = raw.getAsJsonObject();
                if (object.has("count")) {
                    rule.add("match must not have count");
                }
                acceptsModified = object.has("components") || object.has("predicates");
                match = rule.optional("match", Json.strict(ItemPredicate.CODEC, context.ops()));
            } else {
                rule.add("match must be an object");
            }
        }
        Optional<String> namespace = hasNamespace
                ? rule.optional("namespace", Json::string).filter(text -> validNamespace(text, rule))
                : Optional.empty();

        boolean junk = rule.optional("junk", Json::bool).orElse(false);
        Optional<Price> price = Optional.empty();
        if (refuse) {
            if (rule.has("price")) {
                rule.add("a refuse rule must not have price");
            }
            if (rule.has("junk")) {
                rule.add("a refuse rule must not have junk");
            }
        } else if (!rule.has("price")) {
            rule.add("price is required unless refuse is true");
        } else {
            price = readPrice(rule.raw("price"), where + ", price", context);
        }

        if (context.count() > mark) {
            return Optional.empty();
        }
        return Optional.of(new SellRule(match, namespace, price, junk, refuse, acceptsModified));
    }

    /** A namespace is a valid id namespace, and, while a server runs, some registered item has it. */
    private static boolean validNamespace(String text, Section rule) {
        if (!NAMESPACE.matcher(text).matches()) {
            rule.add("namespace '" + text + "' is not a valid id namespace");
            return false;
        }
        if (ServerLookups.available()
                && BuiltInRegistries.ITEM.keySet().stream().noneMatch(id -> id.getNamespace().equals(text))) {
            rule.add("namespace '" + text + "' has no registered items on this server");
            return false;
        }
        return true;
    }

    private static Optional<Price> readPrice(JsonElement value, String where, CountingContext context) {
        Optional<Section> parsed = Section.of(value, where, context);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Section price = parsed.get();
        price.allowOnly(PRICE_KEYS);
        int mark = context.count();
        Optional<Integer> aur = price.required("aur", Json.integerAtLeast(1));
        Optional<Integer> per = price.required("per", TradeReader::perUnit);
        if (aur.isEmpty() || per.isEmpty() || context.count() > mark) {
            return Optional.empty();
        }
        return Optional.of(new Price(aur.get(), per.get()));
    }

    /** {@code per}: a whole number from 1 to {@value #MAX_PER}. */
    private static DataResult<Integer> perUnit(JsonElement value) {
        return Json.integer(value).flatMap(number -> {
            if (number < 1) {
                return DataResult.error(() -> "must be at least 1");
            }
            if (number > MAX_PER) {
                return DataResult.error(() -> "must be at most " + MAX_PER);
            }
            return DataResult.success(number);
        });
    }

    /** Whether a shop's {@code experience} has a {@code sell} entry, in either form: a rate, or a fee in a market section. */
    private static boolean sellsExperience(JsonElement experience) {
        return experience.isJsonObject() && experience.getAsJsonObject().has("sell");
    }

    /**
     * A shop's {@code sell} resolves to its own rules followed by the rules of each price list it names, in order. The
     * rules must not be empty after that, unless a list it names failed to parse, which is already reported, or the shop's
     * {@code experience} sells, which needs a daily budget and no rules: {@code sellsExperience} says it does.
     */
    private static Optional<Selling> readSell(
            JsonElement value,
            String shopWhere,
            CountingContext context,
            Map<Identifier, PriceList> priceLists,
            Set<Identifier> declaredLists,
            boolean sellsExperience) {
        Optional<Section> parsed = Section.of(value, shopWhere + ", sell", context);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Section sell = parsed.get();
        sell.allowOnly(SELL_KEYS);
        int mark = context.count();
        Optional<Integer> budget = sell.required("daily_budget", Json.integerAtLeast(1));

        boolean unresolved = false;
        Optional<List<SellRule>> own = readRules(sell.elements("rules"), shopWhere, context);
        unresolved |= own.isEmpty();
        List<SellRule> resolved = new ArrayList<>(own.orElse(List.of()));
        List<JsonElement> names = sell.elements("lists");
        for (int i = 0; i < names.size(); i++) {
            DataResult<Identifier> name = Json.identifier(names.get(i));
            Optional<Identifier> listId = name.result();
            if (listId.isEmpty()) {
                sell.add("lists entry " + (i + 1) + " " + name.error().map(error -> error.message()).orElse("is invalid"));
                unresolved = true;
            } else if (!declaredLists.contains(listId.get())) {
                sell.add("lists entry " + (i + 1) + " names price list '" + listId.get()
                        + "', which is not a price list in this extension");
            } else if (priceLists.containsKey(listId.get())) {
                resolved.addAll(priceLists.get(listId.get()).rules());
            } else {
                unresolved = true;
            }
        }
        if (resolved.isEmpty() && !unresolved && context.count() == mark && !sellsExperience) {
            sell.add("has no rules: give rules, or name a price list that has rules");
        }
        if (budget.isEmpty() || context.count() > mark) {
            return Optional.empty();
        }
        return Optional.of(new Selling(budget.get(), resolved));
    }

    /**
     * {@code shops}: each key an id, each id once. A shop with a broken part is left out whole. {@code currency} is the
     * denominations, which no offer may sell. {@code marketDeclared} says whether the section has an experience market, which
     * decides the form of every shop's experience.
     */
    private static Map<Identifier, Shop> readShops(
            Section root,
            Map<String, JsonElement> entries,
            String where,
            CountingContext context,
            Map<Identifier, PriceList> priceLists,
            Set<Identifier> declaredLists,
            List<Denomination> currency,
            boolean marketDeclared) {
        Map<Identifier, Shop> shops = new LinkedHashMap<>();
        Map<Identifier, String> claimed = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
            Identifier id = Json.keyId(entry.getKey());
            if (id == null) {
                root.add("shops key '" + entry.getKey() + "' is not a valid id");
                continue;
            }
            if (GuildReader.claim(claimed, id, entry.getKey(), "shops", root)) {
                readShop(id, entry.getValue(), where + ", shop " + id, context, priceLists, declaredLists, currency,
                        marketDeclared).ifPresent(shop -> shops.put(id, shop));
            }
        }
        return shops;
    }

    private static Optional<Shop> readShop(
            Identifier id,
            JsonElement value,
            String where,
            CountingContext context,
            Map<Identifier, PriceList> priceLists,
            Set<Identifier> declaredLists,
            List<Denomination> currency,
            boolean marketDeclared) {
        Optional<Section> parsed = Section.of(value, where, context);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Section shop = parsed.get();
        shop.allowOnly(SHOP_KEYS);
        int mark = context.count();
        Optional<Component> name = shop.required("name", Json.text(context.ops()));
        boolean trades = shop.has("experience");
        Optional<Selling> selling = shop.has("sell")
                ? readSell(shop.raw("sell"), where, context, priceLists, declaredLists,
                        trades && sellsExperience(shop.raw("experience")))
                : Optional.empty();
        List<Offer> offers = shop.has("buy")
                ? readOffers(shop.optional("buy", Json::array), where, context, currency)
                : List.of();
        Optional<ExperienceRates> experience = trades && !marketDeclared
                ? readExperience(shop.raw("experience"), where, context)
                : Optional.empty();
        Optional<ExperienceFees> experienceFees = trades && marketDeclared
                ? readExperienceFees(shop.raw("experience"), where, context)
                : Optional.empty();
        Map<String, Component> lines = shop.has("lines")
                ? readLines(shop.raw("lines"), where + ", lines", context).orElse(Map.of())
                : Map.of();

        if (trades && sellsExperience(shop.raw("experience")) && !shop.has("sell")) {
            // The shop pays the experience out of its daily budget, which only a sell block gives.
            shop.add("experience sell needs sell with a daily_budget: the most Aur the shop pays a player a day");
        }
        if (context.count() == mark && !shop.has("sell") && !trades && offers.isEmpty()) {
            shop.add("needs at least one of sell, buy (with an offer) and experience");
        }
        if (name.isEmpty() || (shop.has("sell") && selling.isEmpty())
                || (trades && experience.isEmpty() && experienceFees.isEmpty()) || context.count() > mark) {
            return Optional.empty();
        }
        return Optional.of(new Shop(id, name.get(), selling, offers, experience, experienceFees, lines));
    }

    /** {@code buy}: the offers of a shop. An offer that fails is left out; the rest are kept. */
    private static List<Offer> readOffers(
            Optional<JsonArray> entries, String where, CountingContext context, List<Denomination> currency) {
        if (entries.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> ids = new HashMap<>();
        List<Offer> offers = new ArrayList<>();
        for (int i = 0; i < entries.get().size(); i++) {
            readOffer(entries.get().get(i), i + 1, where, ids, context, currency).ifPresent(offers::add);
        }
        return offers;
    }

    /**
     * One offer. Until its id parses, its problems are located at {@code buy entry <n>}; after that, at
     * {@code offer <id>}.
     */
    private static Optional<Offer> readOffer(
            JsonElement value,
            int position,
            String shopWhere,
            Map<String, Integer> ids,
            CountingContext context,
            List<Denomination> currency) {
        String entryWhere = shopWhere + ", buy entry " + position;
        Optional<Section> parsed = Section.of(value, entryWhere, context);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Section entry = parsed.get();
        int mark = context.count();

        Optional<String> id = entry.required("id", Json::string);
        if (id.isPresent()) {
            if (!OFFER_ID.matcher(id.get()).matches()) {
                entry.add("id '" + id.get() + "' must use only a-z, 0-9 and _");
                id = Optional.empty();
            } else if (id.get().length() > MAX_ID_LENGTH) {
                entry.add("id must be at most " + MAX_ID_LENGTH + " characters");
                id = Optional.empty();
            } else if (ids.containsKey(id.get())) {
                entry.add("id '" + id.get() + "' is already the id of buy entry " + ids.get(id.get()));
                id = Optional.empty();
            } else {
                ids.put(id.get(), position);
            }
        }
        Section offer = id.isPresent()
                ? new Section(entry.json(), shopWhere + ", offer " + id.get(), context)
                : entry;
        offer.allowOnly(OFFER_KEYS);

        Optional<ItemStack> item = offer.required("item", Json.strict(ItemStack.CODEC, context.ops()));
        if (item.isPresent() && !sellable(item.get(), offer, currency)) {
            item = Optional.empty();
        }
        Optional<Integer> price = offer.required("price", Json.integerAtLeast(1));
        Optional<Limit> limit = offer.has("limit")
                ? readLimit(offer.raw("limit"), offer.where() + ", limit", context)
                : Optional.empty();
        Optional<Requires> requires = offer.has("requires")
                ? readRequires(offer.raw("requires"), offer.where() + ", requires", context)
                : Optional.empty();

        if (id.isEmpty() || item.isEmpty() || price.isEmpty() || context.count() > mark
                || (offer.has("limit") && limit.isEmpty()) || (offer.has("requires") && requires.isEmpty())) {
            return Optional.empty();
        }
        return Optional.of(new Offer(id.get(), item.get(), price.get(), limit, requires));
    }

    /**
     * An offer's stack is one item and not money. Its item is not a denomination of the currency, whatever its components,
     * since a player who holds the item holds Aur. It holds no other items, since a container's contents would be sold with
     * it. Its count is within the item's stack size. Every problem is reported. ItemStack.CODEC already refuses air.
     */
    private static boolean sellable(ItemStack stack, Section offer, List<Denomination> currency) {
        boolean valid = true;
        if (currency.stream().anyMatch(denomination -> denomination.item() == stack.getItem())) {
            offer.add("item is a currency item, so the offer would sell Aur");
            valid = false;
        }
        if (holdsItems(stack)) {
            offer.add("item holds other items; an offer must be a single item");
            valid = false;
        }
        if (stack.getCount() > stack.getMaxStackSize()) {
            offer.add("item count " + stack.getCount() + " is above its stack size, " + stack.getMaxStackSize());
            valid = false;
        }
        return valid;
    }

    /**
     * Whether the stack holds items: a container or bundle component with something in it. The same test as
     * {@code SellPlanner}'s, which this reader cannot call without a package cycle.
     */
    private static boolean holdsItems(ItemStack stack) {
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container != null && container.nonEmptyItemCopyStream().findAny().isPresent()) {
            return true;
        }
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        return bundle != null && !bundle.isEmpty();
    }

    private static Optional<Limit> readLimit(JsonElement value, String where, CountingContext context) {
        Optional<Section> parsed = Section.of(value, where, context);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Section limit = parsed.get();
        limit.allowOnly(LIMIT_KEYS);
        int mark = context.count();
        Optional<Integer> count = limit.required("count", Json.integerAtLeast(1));
        Optional<Limit.Period> per = limit.required("per", TradeReader::period);
        if (count.isEmpty() || per.isEmpty() || context.count() > mark) {
            return Optional.empty();
        }
        return Optional.of(new Limit(count.get(), per.get()));
    }

    private static DataResult<Limit.Period> period(JsonElement value) {
        return Json.string(value).flatMap(text -> switch (text) {
            case "day" -> DataResult.success(Limit.Period.DAY);
            case "character" -> DataResult.success(Limit.Period.CHARACTER);
            default -> DataResult.error(() -> "must be day or character");
        });
    }

    private static Optional<Requires> readRequires(JsonElement value, String where, CountingContext context) {
        Optional<Section> parsed = Section.of(value, where, context);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Section requires = parsed.get();
        requires.allowOnly(REQUIRES_KEYS);
        int mark = context.count();
        if (!requires.has("reputation") && !requires.has("achievement")) {
            requires.add("needs reputation, achievement or both");
        }
        Optional<Integer> reputation = requires.optional("reputation", Json::integer);
        Optional<Identifier> achievement = requires.optional("achievement", Json::identifier);
        if (context.count() > mark) {
            return Optional.empty();
        }
        OptionalInt points = reputation.isPresent() ? OptionalInt.of(reputation.get()) : OptionalInt.empty();
        return Optional.of(new Requires(points, achievement));
    }

    /**
     * {@code experience} in a section without an experience market: {@code sell}, {@code buy} or both, each a rate.
     */
    private static Optional<ExperienceRates> readExperience(JsonElement value, String where, CountingContext context) {
        Optional<Section> parsed = Section.of(value, where + ", experience", context);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Section experience = parsed.get();
        experience.allowOnly(EXPERIENCE_KEYS);
        int mark = context.count();
        if (!experience.has("sell") && !experience.has("buy")) {
            experience.add("needs sell, buy or both");
        }
        Optional<Rate> sell = experience.has("sell")
                ? readRate(experience.raw("sell"), experience.where() + ", sell", context)
                : Optional.empty();
        Optional<Rate> buy = experience.has("buy")
                ? readRate(experience.raw("buy"), experience.where() + ", buy", context)
                : Optional.empty();
        if (context.count() > mark) {
            return Optional.empty();
        }
        return Optional.of(new ExperienceRates(sell, buy));
    }

    /**
     * {@code experience} in a section with an experience market: {@code sell}, {@code buy} or both, each a fee. The shop is
     * refused when any entry fails.
     */
    private static Optional<ExperienceFees> readExperienceFees(JsonElement value, String where, CountingContext context) {
        Optional<Section> parsed = Section.of(value, where + ", experience", context);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Section experience = parsed.get();
        experience.allowOnly(EXPERIENCE_KEYS);
        int mark = context.count();
        if (!experience.has("sell") && !experience.has("buy")) {
            experience.add("needs sell, buy or both");
        }
        OptionalInt sell = experience.has("sell")
                ? readFee(experience.raw("sell"), experience.where() + ", sell", context)
                : OptionalInt.empty();
        OptionalInt buy = experience.has("buy")
                ? readFee(experience.raw("buy"), experience.where() + ", buy", context)
                : OptionalInt.empty();
        if (context.count() > mark) {
            return Optional.empty();
        }
        return Optional.of(new ExperienceFees(sell, buy));
    }

    /** A rate: {@code { "points", "aur" }}, both at least 1, in a section without an experience market. */
    private static Optional<Rate> readRate(JsonElement value, String where, CountingContext context) {
        Optional<Section> parsed = Section.of(value, where, context);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Section rate = parsed.get();
        if (!formFits(rate, false)) {
            return Optional.empty();
        }
        rate.allowOnly(RATE_KEYS);
        int mark = context.count();
        Optional<Integer> points = rate.required("points", Json.integerAtLeast(1));
        Optional<Integer> aur = rate.required("aur", Json.integerAtLeast(1));
        if (points.isEmpty() || aur.isEmpty() || context.count() > mark) {
            return Optional.empty();
        }
        return Optional.of(new Rate(points.get(), aur.get()));
    }

    /**
     * A fee: {@code { "fee_percent" }}, a whole number from 0 to {@value #MAX_FEE_PERCENT}, in a section with an experience
     * market.
     */
    private static OptionalInt readFee(JsonElement value, String where, CountingContext context) {
        Optional<Section> parsed = Section.of(value, where, context);
        if (parsed.isEmpty()) {
            return OptionalInt.empty();
        }
        Section fee = parsed.get();
        if (!formFits(fee, true)) {
            return OptionalInt.empty();
        }
        fee.allowOnly(FEE_KEYS);
        int mark = context.count();
        Optional<Integer> percent = fee.required("fee_percent", integerBetween(0, MAX_FEE_PERCENT));
        if (percent.isEmpty() || context.count() > mark) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(percent.get());
    }

    /**
     * Whether a sell or buy entry has the form its section takes: a rate ({@code points}, {@code aur}) without an experience
     * market, a fee ({@code fee_percent}) with one. An entry that has both forms, or the other form, is recorded and not read
     * further.
     */
    private static boolean formFits(Section entry, boolean marketDeclared) {
        boolean rate = entry.has("points") || entry.has("aur");
        boolean fee = entry.has("fee_percent");
        if (rate && fee) {
            entry.add("gives both a rate (points, aur) and a fee (fee_percent); give one");
            return false;
        }
        if (marketDeclared && rate) {
            entry.add("the bundle has an experience_market, so the entry must be { \"fee_percent\": n }");
            return false;
        }
        if (!marketDeclared && fee) {
            entry.add("fee_percent needs an experience_market section");
            return false;
        }
        return true;
    }

    /** {@code lines}: text for the keys in {@link #LINE_KEYS}; every key is optional. */
    private static Optional<Map<String, Component>> readLines(JsonElement value, String where, CountingContext context) {
        Optional<Section> parsed = Section.of(value, where, context);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Section lines = parsed.get();
        lines.allowOnly(LINE_KEYS);
        int mark = context.count();
        Map<String, Component> texts = new LinkedHashMap<>();
        for (String key : lines.json().keySet()) {
            if (LINE_KEYS.contains(key)) {
                lines.optional(key, Json.text(context.ops())).ifPresent(text -> texts.put(key, text));
            }
        }
        if (context.count() > mark) {
            return Optional.empty();
        }
        return Optional.of(texts);
    }

    /**
     * Content must not mint Aur. An experience buy may not cost less per point than another shop's experience sell pays,
     * and an offer may not be bought back for more per item than it costs, as its stack or, when the stack is modified, as
     * the plain item. Both compare by cross-multiplication in {@code long}, so equal rates are allowed. Only fixed rates are
     * compared: a shop's experience fees have no price per point, so a bundle's experience market takes no such check.
     */
    private static void checkMinting(Map<Identifier, Shop> shops, String where, CountingContext context) {
        for (Shop buyer : shops.values()) {
            Optional<Rate> buyRate = buyer.experience().flatMap(ExperienceRates::buy);
            for (Shop seller : shops.values()) {
                Optional<Rate> sellRate = seller.experience().flatMap(ExperienceRates::sell);
                if (buyRate.isEmpty() || sellRate.isEmpty()) {
                    continue;
                }
                Rate buy = buyRate.get();
                Rate sell = sellRate.get();
                // The buy costs buy.aur() per buy.points(); the sell pays sell.aur() per sell.points().
                if ((long) buy.aur() * sell.points() < (long) sell.aur() * buy.points()) {
                    context.problem(where + ", shop " + buyer.id(), "experience buy costs " + buy.aur() + " aur for "
                            + buy.points() + " points, less per point than shop " + seller.id() + " pays for experience"
                            + " sold to it (" + sell.aur() + " aur for " + sell.points() + " points), so buying from shop "
                            + buyer.id() + " and selling to shop " + seller.id() + " mints Aur");
                }
            }
        }
        for (Shop seller : shops.values()) {
            for (Offer offer : seller.offers()) {
                ItemStack stack = offer.item().copyWithCount(1);
                // A player can strip a modification (a grindstone, a cauldron) and sell the plain item, so when the offer is
                // modified, the plain item is priced too, by the rule that takes it.
                ItemStack plain = new ItemStack(offer.item().getItem());
                boolean modified = !offer.item().getComponentsPatch().isEmpty();
                for (Shop buyer : shops.values()) {
                    Optional<SellRule> rule = buyer.ruleFor(stack);
                    buyBackProblem(seller, offer, buyer, rule, where, context);
                    Optional<SellRule> plainRule = buyer.ruleFor(plain);
                    if (modified && !plainRule.equals(rule)) {
                        buyBackProblem(seller, offer, buyer, plainRule, where, context);
                    }
                }
            }
        }
    }

    /** Reports the buyer's rule for an offer's item when it pays more per item than the offer costs per item. */
    private static void buyBackProblem(
            Shop seller, Offer offer, Shop buyer, Optional<SellRule> rule, String where, CountingContext context) {
        if (rule.isEmpty() || rule.get().refuse() || rule.get().price().isEmpty()) {
            return;
        }
        Price paid = rule.get().price().get();
        int count = offer.item().getCount();
        // The buyer pays paid.aur() per paid.per() items; the offer costs offer.price() per count items.
        if ((long) paid.aur() * count > (long) offer.price() * paid.per()) {
            context.problem(where + ", shop " + seller.id() + ", offer " + offer.id(), "shop " + buyer.id()
                    + " pays " + paid.aur() + " aur for " + items(paid.per()) + ", more per item than this offer"
                    + " costs (" + offer.price() + " aur for " + items(count) + "), so buying it here and"
                    + " selling it to shop " + buyer.id() + " mints Aur");
        }
    }

    private static String items(int count) {
        return count + (count == 1 ? " item" : " items");
    }

    private static List<JsonElement> elements(JsonArray array) {
        List<JsonElement> list = new ArrayList<>();
        array.forEach(list::add);
        return list;
    }
}
