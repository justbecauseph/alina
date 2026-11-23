package town.lampas.alina.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static town.lampas.alina.content.ContentFixtures.trade;

import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import town.lampas.alina.content.TradeContent.Denomination;
import town.lampas.alina.content.TradeContent.ExperienceFees;
import town.lampas.alina.content.TradeContent.ExperienceMarket;
import town.lampas.alina.content.TradeContent.ExperienceRates;
import town.lampas.alina.content.TradeContent.Limit;
import town.lampas.alina.content.TradeContent.Offer;
import town.lampas.alina.content.TradeContent.Price;
import town.lampas.alina.content.TradeContent.Rate;
import town.lampas.alina.content.TradeContent.SellRule;
import town.lampas.alina.content.TradeContent.Shop;
import town.lampas.alina.trade.network.OfferRow;

/**
 * The {@code alina:trade} extension. Expected values come from the brief for the section: the JSON shape, the validation
 * rules, the two mint checks and the matching rules of sell lists. Unit tests have no server, so the namespace check
 * that needs registered items is not run here.
 */
class TradeReaderTest {

    @BeforeAll
    static void boot() {
        ContentFixtures.boot();
    }

    /**
   * The section of the brief's example, with its ids in the lampas namespace. Unit tests have no server, so the tag
   * {@code #minecraft:planks} cannot resolve here ("Missing tag"); the rule names the same planks as an explicit list.
   * The tag form is in {@code docs/content-guide.md}, where a server resolves it.
   */
    static final String EXAMPLE = """
            {
              "currency": [
                { "item": "minecraft:gold_nugget", "value": 1 },
                { "item": "minecraft:gold_ingot", "value": 9 }
              ],
              "price_lists": {
                "lampas:common_drops": {
                  "rules": [
                    { "match": { "items": "minecraft:rotten_flesh" }, "price": { "aur": 1, "per": 4 }, "junk": true },
                    { "match": { "items": ["minecraft:oak_planks", "minecraft:spruce_planks"] }, "price": { "aur": 1, "per": 8 }, "junk": true },
                    { "namespace": "farmersdelight", "price": { "aur": 1, "per": 2 }, "junk": true },
                    { "match": { "items": "minecraft:nether_star" }, "refuse": true }
                  ]
                }
              },
              "shops": {
                "lampas:mira": {
                  "name": "Mira's stall",
                  "sell": { "daily_budget": 200, "lists": ["lampas:common_drops"], "rules": [] },
                  "buy": [
                    { "id": "xp_flask", "item": { "id": "minecraft:experience_bottle" }, "price": 10 },
                    { "id": "hat", "item": { "id": "minecraft:leather_helmet" }, "price": 250,
                      "limit": { "count": 1, "per": "character" }, "requires": { "reputation": 50 } }
                  ],
                  "experience": { "sell": { "points": 50, "aur": 1 }, "buy": { "points": 50, "aur": 2 } },
                  "lines": { "greeting": "Welcome.", "sold": "A fair price.", "bought": "Use it well." }
                }
              }
            }
            """;

    /**
     * The experience market of the guide's example: one pool with seed reserves of 500000 points and 10000 Aur, restocked at
     * 10 percent a day, one trade taking at most 10 percent. Mira trades experience as fees, and has no fixed rates.
     */
    static final String MARKET_EXAMPLE = """
            {
              "currency": [
                { "item": "minecraft:gold_nugget", "value": 1 },
                { "item": "minecraft:gold_ingot", "value": 9 }
              ],
              "experience_market": {
                "reserve": { "points": 500000, "aur": 10000 },
                "restock_percent": 10,
                "max_trade_percent": 10
              },
              "shops": {
                "lampas:mira": {
                  "name": "Mira's stall",
                  "sell": { "daily_budget": 200 },
                  "experience": { "sell": { "fee_percent": 5 }, "buy": { "fee_percent": 5 } }
                }
              }
            }
            """;

    /** The experience market object of {@link #MARKET_EXAMPLE}, as the experience market cases use it. */
    private static final String MARKET = "{ \"reserve\": { \"points\": 500000, \"aur\": 10000 }, \"restock_percent\": 10,"
            + " \"max_trade_percent\": 10 }";

    /** The reserve object of {@link #MARKET_EXAMPLE}. */
    private static final String RESERVE = "{ \"points\": 500000, \"aur\": 10000 }";

    private static final String PLAIN_RULE ="{ \"match\": { \"items\": \"minecraft:paper\" }, \"price\": { \"aur\": 1, \"per\": 1 } }";

    private static Identifier id(String text) {
        return Identifier.parse(text);
    }

    /** A stack from vanilla JSON, read with the registry ops the tests use. */
    private static ItemStack stack(String json) {
        DataResult<ItemStack> parsed = ItemStack.CODEC.parse(ContentFixtures.boot(), JsonParser.parseString(json));
        return parsed.result().orElseThrow(() -> new AssertionError(json + ": "
                + parsed.error().map(error -> error.message()).orElse("no error")));
    }

    /** A section with the one currency every case needs, and the given price lists and shops. */
    private static String section(String priceLists, String shops) {
        return "{ \"currency\": [ { \"item\": \"minecraft:gold_nugget\", \"value\": 1 } ], \"price_lists\": " + priceLists
                + ", \"shops\": " + shops + " }";
    }

    /** The problems of one rule, placed in the price list lampas:l. */
    private static List<String> ruleProblems(String rule) {
        return trade(section("{ \"lampas:l\": { \"rules\": [ " + rule + " ] } }", "{}")).problems();
    }

    /** The problems of one shop, named lampas:s. */
    private static List<String> shopProblems(String shop) {
        return trade(section("{}", "{ \"lampas:s\": " + shop + " }")).problems();
    }

    /** A shop named S with one offer: its id, its item as JSON, and its price. */
    private static String shopWithOffer(String id, String item, int price) {
        return "{ \"name\": \"S\", \"buy\": [ { \"id\": \"" + id + "\", \"item\": " + item + ", \"price\": " + price + " } ] }";
    }

    /** An enchanted iron sword, as an offer's item: the enchantment is the component change. */
    private static final String ENCHANTED_SWORD = "{ \"id\": \"minecraft:iron_sword\", \"components\": "
            + "{ \"minecraft:enchantments\": { \"minecraft:sharpness\": 1 } } }";

    /** The first sell rule of the shop lampas:s, whose only sell rule is {@code rule}. */
    private static SellRule sellRule(String rule) {
        return trade(section("{}", "{ \"lampas:s\": { \"name\": \"S\", \"sell\": { \"daily_budget\": 1, \"rules\": [ "
                + rule + " ] } } }")).orThrow().shops().get(id("lampas:s")).selling().orElseThrow().rules().get(0);
    }

    /** A section with the currency every case needs, the given experience market and shops. */
    private static String marketSection(String experienceMarket, String shops) {
        return "{ \"currency\": [ { \"item\": \"minecraft:gold_nugget\", \"value\": 1 } ], \"experience_market\": "
                + experienceMarket + ", \"shops\": " + shops + " }";
    }

    /** The problems of a section with the experience market {@code experienceMarket} and no shops. */
    private static List<String> marketProblems(String experienceMarket) {
        return trade(marketSection(experienceMarket, "{}")).problems();
    }

    /** An experience market with its reserve and restock given as JSON text, and max_trade_percent left out. */
    private static String market(String reserve, String restock) {
        return "{ \"reserve\": " + reserve + ", \"restock_percent\": " + restock + " }";
    }

    /** A reserve with its points and aur given as JSON text. */
    private static String reserve(String points, String aur) {
        return "{ \"points\": " + points + ", \"aur\": " + aur + " }";
    }

    /** The shops of a section: one shop named S, lampas:s, which sells experience at {@code fee} percent and buys none. */
    private static String feeShop(String fee) {
        return "{ \"lampas:s\": { \"name\": \"S\", \"sell\": { \"daily_budget\": 1 }, \"experience\": { \"sell\": "
                + "{ \"fee_percent\": " + fee + " } } } }";
    }

    @Test
    void theExampleParses() {
        TradeContent content = trade(EXAMPLE).orThrow();

        // Largest value first, whatever the order in the file.
        assertEquals(List.of(9, 1), content.currency().stream().map(Denomination::value).toList());
        assertEquals(List.of(Items.GOLD_INGOT, Items.GOLD_NUGGET),
                content.currency().stream().map(Denomination::item).toList());

        assertEquals(List.of(id("lampas:common_drops")), List.copyOf(content.priceLists().keySet()));
        assertEquals(4, content.priceLists().get(id("lampas:common_drops")).rules().size());

        Shop mira = content.shops().get(id("lampas:mira"));
        assertEquals("Mira's stall", mira.name().getString());
        assertEquals(200, mira.selling().orElseThrow().dailyBudget());
        assertEquals(4, mira.selling().orElseThrow().rules().size());
        assertEquals(List.of("xp_flask", "hat"), mira.offers().stream().map(Offer::id).toList());
        Offer hat = mira.offers().get(1);
        assertEquals(Items.LEATHER_HELMET, hat.item().getItem());
        assertEquals(1, hat.item().getCount());
        assertEquals(250, hat.price());
        assertEquals(Optional.of(new Limit(1, Limit.Period.CHARACTER)), hat.limit());
        assertEquals(OptionalInt.of(50), hat.requires().orElseThrow().reputation());
        assertEquals(Optional.empty(), hat.requires().orElseThrow().achievement());
        assertEquals(new ExperienceRates(Optional.of(new Rate(50, 1)), Optional.of(new Rate(50, 2))),
                mira.experience().orElseThrow());
        assertEquals("Welcome.", mira.lines().get("greeting").getString());
        assertEquals(3, mira.lines().size());

        SellRule flesh = mira.ruleFor(new ItemStack(Items.ROTTEN_FLESH)).orElseThrow();
        assertEquals(Optional.of(new Price(1, 4)), flesh.price());
        assertTrue(flesh.junk());
        assertFalse(flesh.refuse());
        assertTrue(mira.ruleFor(new ItemStack(Items.NETHER_STAR)).orElseThrow().refuse());
        assertEquals(Optional.empty(), mira.ruleFor(new ItemStack(Items.LEATHER_HELMET)));
    }

    @Test
    void theExampleHasNoProblems() {
        assertEquals(List.of(), trade(EXAMPLE).problems());
    }

    @Test
    void aSectionWithOnlyCurrencyIsValid() {
        assertEquals(List.of(), trade("{ \"currency\": [ { \"item\": \"minecraft:gold_nugget\", \"value\": 1 } ] }").problems());
    }

    @Test
    void unknownRootKeysAreProblems() {
        assertEquals(List.of("extension alina:trade: unknown key 'sell_all'"),
                trade("{ \"currency\": [ { \"item\": \"minecraft:gold_nugget\", \"value\": 1 } ], \"sell_all\": true }")
                        .problems());
    }

    @Test
    void currencyIsRequired() {
        assertEquals(List.of("extension alina:trade: currency is required"), trade("{}").problems());
    }

    @Test
    void currencyNeedsAtLeastOneDenomination() {
        assertEquals(List.of("extension alina:trade: currency must list at least one denomination"),
                trade("{ \"currency\": [] }").problems());
    }

    @Test
    void currencyNeedsADenominationOfValueOne() {
        assertEquals(List.of("extension alina:trade: currency needs a denomination of value 1, so any amount can be paid out"),
                trade("{ \"currency\": [ { \"item\": \"minecraft:gold_ingot\", \"value\": 9 } ] }").problems());
    }

    @Test
    void aCurrencyItemIsARegisteredItem() {
        assertEquals(List.of("extension alina:trade, currency entry 1: item 'minecraft:nugget_of_nothing' is not a registered item"),
                trade("{ \"currency\": [ { \"item\": \"minecraft:nugget_of_nothing\", \"value\": 1 } ] }").problems());
    }

    @Test
    void aCurrencyValueIsAtLeastOne() {
        assertEquals(List.of("extension alina:trade, currency entry 1: value must be at least 1"),
                trade("{ \"currency\": [ { \"item\": \"minecraft:gold_nugget\", \"value\": 0 },"
                        + " { \"item\": \"minecraft:gold_ingot\", \"value\": 1 } ] }").problems());
    }

    @Test
    void aCurrencyItemIsListedOnce() {
        assertEquals(List.of("extension alina:trade, currency entry 2: item 'minecraft:gold_nugget' is already the item of entry 1"),
                trade("{ \"currency\": [ { \"item\": \"minecraft:gold_nugget\", \"value\": 1 },"
                        + " { \"item\": \"minecraft:gold_nugget\", \"value\": 9 } ] }").problems());
    }

    @Test
    void aCurrencyValueIsListedOnce() {
        assertEquals(List.of("extension alina:trade, currency entry 2: value 1 is already the value of entry 1"),
                trade("{ \"currency\": [ { \"item\": \"minecraft:gold_nugget\", \"value\": 1 },"
                        + " { \"item\": \"minecraft:gold_ingot\", \"value\": 1 } ] }").problems());
    }

    @Test
    void aCurrencyEntryHasOnlyItsKeys() {
        assertEquals(List.of("extension alina:trade, currency entry 1: unknown key 'only_if'"),
                trade("{ \"currency\": [ { \"item\": \"minecraft:gold_nugget\", \"value\": 1, \"only_if\": [] } ] }")
                        .problems());
    }

    @Test
    void aPriceListIsAnObjectWithRules() {
        assertEquals(List.of("extension alina:trade, price list lampas:l: rules is required"),
                trade(section("{ \"lampas:l\": {} }", "{}")).problems());
        assertEquals(List.of("extension alina:trade, price list lampas:l: rules must be an array"),
                trade(section("{ \"lampas:l\": { \"rules\": {} } }", "{}")).problems());
        assertEquals(List.of("extension alina:trade, price list lampas:l: unknown key 'extra'"),
                trade(section("{ \"lampas:l\": { \"rules\": [], \"extra\": 1 } }", "{}")).problems());
    }

    @Test
    void aPriceListKeyIsAnId() {
        assertEquals(List.of("extension alina:trade: price_lists key 'Bad Key' is not a valid id"),
                trade(section("{ \"Bad Key\": { \"rules\": [] } }", "{}")).problems());
    }

    @Test
    void aPriceListIdIsOnlyOnce() {
        assertEquals(List.of("extension alina:trade: price_lists keys 'minecraft:l' and 'l' both name minecraft:l"),
                trade(section("{ \"minecraft:l\": { \"rules\": [] }, \"l\": { \"rules\": [] } }", "{}")).problems());
    }

    @Test
    void aRuleHasExactlyOneOfMatchAndNamespace() {
        String both = "{ \"match\": { \"items\": \"minecraft:paper\" }, \"namespace\": \"minecraft\", \"price\": { \"aur\": 1, \"per\": 1 } }";
        String neither = "{ \"price\": { \"aur\": 1, \"per\": 1 } }";
        assertEquals(List.of("extension alina:trade, price list lampas:l, rule 1: needs exactly one of match and namespace"),
                ruleProblems(both));
        assertEquals(List.of("extension alina:trade, price list lampas:l, rule 1: needs exactly one of match and namespace"),
                ruleProblems(neither));
    }

    @Test
    void aMatchHasNoCount() {
        assertEquals(List.of("extension alina:trade, price list lampas:l, rule 1: match must not have count"),
                ruleProblems("{ \"match\": { \"items\": \"minecraft:paper\", \"count\": 2 }, \"price\": { \"aur\": 1, \"per\": 1 } }"));
    }

    @Test
    void aMatchIsAnObject() {
        assertEquals(List.of("extension alina:trade, price list lampas:l, rule 1: match must be an object"),
                ruleProblems("{ \"match\": \"minecraft:paper\", \"price\": { \"aur\": 1, \"per\": 1 } }"));
    }

    @Test
    void aNamespaceIsAValidIdNamespace() {
        assertEquals(List.of("extension alina:trade, price list lampas:l, rule 1: namespace 'Minecraft' is not a valid id namespace"),
                ruleProblems("{ \"namespace\": \"Minecraft\", \"price\": { \"aur\": 1, \"per\": 1 } }"));
    }

    @Test
    void aRuleWithoutRefuseNeedsAPrice() {
        assertEquals(List.of("extension alina:trade, price list lampas:l, rule 1: price is required unless refuse is true"),
                ruleProblems("{ \"match\": { \"items\": \"minecraft:paper\" } }"));
    }

    @Test
    void aRefuseRuleHasNoPriceOrJunk() {
        assertEquals(List.of("extension alina:trade, price list lampas:l, rule 1: a refuse rule must not have price"),
                ruleProblems("{ \"match\": { \"items\": \"minecraft:paper\" }, \"refuse\": true,"
                        + " \"price\": { \"aur\": 1, \"per\": 1 } }"));
        assertEquals(List.of("extension alina:trade, price list lampas:l, rule 1: a refuse rule must not have junk"),
                ruleProblems("{ \"match\": { \"items\": \"minecraft:paper\" }, \"refuse\": true, \"junk\": true }"));
    }

    @Test
    void aRefuseRuleNeedsNoPrice() {
        assertEquals(List.of(), ruleProblems("{ \"match\": { \"items\": \"minecraft:paper\" }, \"refuse\": true }"));
    }

    @Test
    void aPriceHasAAurAndAPerOfAtLeastOneAndAtMost64() {
        String rule = "{ \"match\": { \"items\": \"minecraft:paper\" }, \"price\": { \"aur\": %s, \"per\": %s } }";
        assertEquals(List.of("extension alina:trade, price list lampas:l, rule 1, price: aur must be at least 1"),
                ruleProblems(rule.formatted("0", "1")));
        assertEquals(List.of("extension alina:trade, price list lampas:l, rule 1, price: per must be at least 1"),
                ruleProblems(rule.formatted("1", "0")));
        assertEquals(List.of("extension alina:trade, price list lampas:l, rule 1, price: per must be at most 64"),
                ruleProblems(rule.formatted("1", "65")));
        assertEquals(List.of(), ruleProblems(rule.formatted("1", "64")));
    }

    @Test
    void junkIsABoolean() {
        assertEquals(List.of("extension alina:trade, price list lampas:l, rule 1: junk must be true or false"),
                ruleProblems("{ \"match\": { \"items\": \"minecraft:paper\" }, \"price\": { \"aur\": 1, \"per\": 1 },"
                        + " \"junk\": \"yes\" }"));
    }

    @Test
    void aRuleHasOnlyItsKeys() {
        assertEquals(List.of("extension alina:trade, price list lampas:l, rule 1: unknown key 'only_if'"),
                ruleProblems("{ \"match\": { \"items\": \"minecraft:paper\" }, \"price\": { \"aur\": 1, \"per\": 1 },"
                        + " \"only_if\": [] }"));
    }

    @Test
    void aPriceListWithABrokenRuleIsLeftOutWithoutAnotherProblemInTheShop() {
        // The shop's sell names the broken list. Only the rule is reported: the shop does not also say it has no rules.
        String priceLists = "{ \"lampas:l\": { \"rules\": [ { \"namespace\": \"Minecraft\", \"price\": { \"aur\": 1, \"per\": 1 } } ] } }";
        String shops = "{ \"lampas:s\": { \"name\": \"S\", \"sell\": { \"daily_budget\": 1, \"lists\": [\"lampas:l\"] } } }";
        assertEquals(List.of("extension alina:trade, price list lampas:l, rule 1: namespace 'Minecraft' is not a valid id namespace"),
                trade(section(priceLists, shops)).problems());
    }

    @Test
    void aShopNeedsAName() {
        assertEquals(List.of("extension alina:trade, shop lampas:s: name is required"),
                shopProblems("{ \"sell\": { \"daily_budget\": 1 }, \"experience\": { \"sell\": { \"points\": 1, \"aur\": 1 } } }"));
    }

    @Test
    void aShopNeedsSellBuyOrExperience() {
        String needs = "extension alina:trade, shop lampas:s: needs at least one of sell, buy (with an offer) and experience";
        assertEquals(List.of(needs), shopProblems("{ \"name\": \"S\" }"));
        assertEquals(List.of(needs), shopProblems("{ \"name\": \"S\", \"buy\": [] }"));
    }

    @Test
    void aShopHasOnlyItsKeys() {
        assertEquals(List.of("extension alina:trade, shop lampas:s: unknown key 'only_if'"),
                shopProblems("{ \"name\": \"S\", \"only_if\": [], \"sell\": { \"daily_budget\": 1 }, \"experience\": { \"sell\": { \"points\": 1, \"aur\": 1 } } }"));
    }

    @Test
    void aShopIdIsAnId() {
        assertEquals(List.of("extension alina:trade: shops key 'Bad Key' is not a valid id"),
                trade(section("{}", "{ \"Bad Key\": { \"name\": \"S\", \"sell\": { \"daily_budget\": 1 }, \"experience\": { \"sell\": { \"points\": 1, \"aur\": 1 } } } }"))
                        .problems());
    }

    @Test
    void aShopIdIsOnlyOnce() {
        String shop = "{ \"name\": \"S\", \"sell\": { \"daily_budget\": 1 }, \"experience\": { \"sell\": { \"points\": 1, \"aur\": 1 } } }";
        assertEquals(List.of("extension alina:trade: shops keys 'minecraft:s' and 's' both name minecraft:s"),
                trade(section("{}", "{ \"minecraft:s\": " + shop + ", \"s\": " + shop + " }")).problems());
    }

    @Test
    void aSellHasADailyBudgetOfAtLeastOne() {
        assertEquals(List.of("extension alina:trade, shop lampas:s, sell: daily_budget is required"),
                shopProblems("{ \"name\": \"S\", \"sell\": { \"rules\": [ " + PLAIN_RULE + " ] } }"));
        assertEquals(List.of("extension alina:trade, shop lampas:s, sell: daily_budget must be at least 1"),
                shopProblems("{ \"name\": \"S\", \"sell\": { \"daily_budget\": 0, \"rules\": [ " + PLAIN_RULE + " ] } }"));
    }

    @Test
    void aSellNeedsARuleAfterResolving() {
        assertEquals(List.of("extension alina:trade, shop lampas:s, sell: has no rules: give rules, or name a price list that has rules"),
                shopProblems("{ \"name\": \"S\", \"sell\": { \"daily_budget\": 1 } }"));
    }

    @Test
    void anExperienceSellNeedsASellBlockInEitherForm() {
        String message = "extension alina:trade, shop lampas:s: experience sell needs sell with a daily_budget: "
                + "the most Aur the shop pays a player a day";
        assertEquals(List.of(message), shopProblems("{ \"name\": \"S\", \"experience\": { \"sell\": { \"points\": 50, "
                + "\"aur\": 1 } } }"));
        String shops = "{ \"lampas:s\": { \"name\": \"S\", \"experience\": { \"sell\": { \"fee_percent\": 5 } } } }";
        assertEquals(List.of(message), trade(marketSection(market(reserve("500000", "10000"), "10"), shops)).problems());
    }

    @Test
    void anExperienceBuyNeedsNoSellBlockInEitherForm() {
        assertEquals(List.of(), shopProblems("{ \"name\": \"S\", \"experience\": { \"buy\": { \"points\": 50, \"aur\": 2 } } }"));
        String shops = "{ \"lampas:s\": { \"name\": \"S\", \"experience\": { \"buy\": { \"fee_percent\": 5 } } } }";
        assertEquals(List.of(), trade(marketSection(market(reserve("500000", "10000"), "10"), shops)).problems());
    }

    @Test
    void aSellWithNoRulesIsFineWhenTheShopSellsExperienceAtAFixedRate() {
        // A broker that buys experience pays out from its daily budget, and has no items to take.
        assertEquals(List.of(), shopProblems("{ \"name\": \"S\", \"sell\": { \"daily_budget\": 1 }, "
                + "\"experience\": { \"sell\": { \"points\": 50, \"aur\": 1 } } }"));
    }

    @Test
    void aSellWithNoRulesIsFineWhenTheShopSellsExperienceAtAFee() {
        String shops = "{ \"lampas:s\": { \"name\": \"S\", \"sell\": { \"daily_budget\": 1 }, "
                + "\"experience\": { \"sell\": { \"fee_percent\": 5 } } } }";

        assertEquals(List.of(), trade(marketSection(market(reserve("500000", "10000"), "10"), shops)).problems());
    }

    @Test
    void aSellWithNoRulesIsStillRefusedWhenTheShopOnlyBuysExperience() {
        String rule = "extension alina:trade, shop lampas:s, sell: has no rules: give rules, or name a price list that has rules";
        assertEquals(List.of(rule), shopProblems("{ \"name\": \"S\", \"sell\": { \"daily_budget\": 1 }, "
                + "\"experience\": { \"buy\": { \"points\": 50, \"aur\": 2 } } }"));
        String shops = "{ \"lampas:s\": { \"name\": \"S\", \"sell\": { \"daily_budget\": 1 }, "
                + "\"experience\": { \"buy\": { \"fee_percent\": 5 } } } }";
        assertEquals(List.of(rule), trade(marketSection(market(reserve("500000", "10000"), "10"), shops)).problems());
    }

    @Test
    void aSellListMustBeAPriceListOfTheSection() {
        assertEquals(List.of("extension alina:trade, shop lampas:s, sell: lists entry 1 names price list 'lampas:nowhere', "
                + "which is not a price list in this extension"),
                shopProblems("{ \"name\": \"S\", \"sell\": { \"daily_budget\": 1, \"lists\": [\"lampas:nowhere\"] } }"));
    }

    @Test
    void aSellListIsAnId() {
        assertEquals(List.of("extension alina:trade, shop lampas:s, sell: lists entry 1 'Bad Id' is not a valid id"),
                shopProblems("{ \"name\": \"S\", \"sell\": { \"daily_budget\": 1, \"lists\": [\"Bad Id\"] } }"));
    }

    @Test
    void aSellRuleIsLocatedInTheShop() {
        assertEquals(List.of("extension alina:trade, shop lampas:s, rule 1: price is required unless refuse is true"),
                shopProblems("{ \"name\": \"S\", \"sell\": { \"daily_budget\": 1, \"rules\": [ "
                        + "{ \"match\": { \"items\": \"minecraft:paper\" } } ] } }"));
    }

    @Test
    void experienceNeedsSellBuyOrBoth() {
        assertEquals(List.of("extension alina:trade, shop lampas:s, experience: needs sell, buy or both"),
                shopProblems("{ \"name\": \"S\", \"experience\": {} }"));
        assertEquals(List.of("extension alina:trade, shop lampas:s, experience, sell: points must be at least 1"),
                shopProblems("{ \"name\": \"S\", \"sell\": { \"daily_budget\": 1 }, \"experience\": { \"sell\": { \"points\": 0, \"aur\": 1 } } }"));
    }

    @Test
    void linesAreTheKnownKeysAndText() {
        assertEquals(List.of("extension alina:trade, shop lampas:s, lines: unknown key 'hello'"),
                shopProblems("{ \"name\": \"S\", \"sell\": { \"daily_budget\": 1 }, \"experience\": { \"sell\": { \"points\": 1, \"aur\": 1 } },"
                        + " \"lines\": { \"hello\": \"x\" } }"));
        assertEquals(List.of(), shopProblems("{ \"name\": \"S\", \"sell\": { \"daily_budget\": 1 }, \"experience\": { \"sell\": { \"points\": 1, \"aur\": 1 } },"
                + " \"lines\": { \"busy\": \"One moment.\", \"no_room\": { \"text\": \"Clear a slot.\" } } }"));
    }

    @Test
    void anOfferIdMatchesLowerCaseLettersDigitsAndUnderscores() {
        assertEquals(List.of("extension alina:trade, shop lampas:s, buy entry 1: id 'Hat' must use only a-z, 0-9 and _"),
                shopProblems("{ \"name\": \"S\", \"buy\": [ { \"id\": \"Hat\", \"item\": { \"id\": \"minecraft:paper\" },"
                        + " \"price\": 1 } ] }"));
    }

    @Test
    void anOfferIdIsUniqueInItsShop() {
        assertEquals(List.of("extension alina:trade, shop lampas:s, buy entry 2: id 'hat' is already the id of buy entry 1"),
                shopProblems("{ \"name\": \"S\", \"buy\": [ { \"id\": \"hat\", \"item\": { \"id\": \"minecraft:paper\" },"
                        + " \"price\": 1 }, { \"id\": \"hat\", \"item\": { \"id\": \"minecraft:paper\" }, \"price\": 2 } ] }"));
    }

    @Test
    void anOfferIsLocatedByItsIdOnceItHasOne() {
        assertEquals(List.of("extension alina:trade, shop lampas:s, buy entry 1: id is required"),
                shopProblems("{ \"name\": \"S\", \"buy\": [ { \"item\": { \"id\": \"minecraft:paper\" }, \"price\": 1 } ] }"));
        assertEquals(List.of("extension alina:trade, shop lampas:s, offer hat: item is required"),
                shopProblems("{ \"name\": \"S\", \"buy\": [ { \"id\": \"hat\", \"price\": 1 } ] }"));
    }

    @Test
    void anOfferItemIsAStackWithinItsStackSizeAndNotAir() {
        assertEquals(List.of("extension alina:trade, shop lampas:s, offer hat: item count 17 is above its stack size, 16"),
                shopProblems("{ \"name\": \"S\", \"buy\": [ { \"id\": \"hat\","
                        + " \"item\": { \"id\": \"minecraft:ender_pearl\", \"count\": 17 }, \"price\": 1 } ] }"));
        assertEquals(List.of("extension alina:trade, shop lampas:s, offer hat: item Item must not be minecraft:air"),
                shopProblems("{ \"name\": \"S\", \"buy\": [ { \"id\": \"hat\", \"item\": { \"id\": \"minecraft:air\" },"
                        + " \"price\": 1 } ] }"));
    }

    @Test
    void anOfferItemDoesNotSayADefaultCount() {
        assertEquals(List.of("extension alina:trade, shop lampas:s, offer hat: item key 'count' has no effect: it is not a key "
                + "at that place, or only repeats its default; remove it"),
                shopProblems("{ \"name\": \"S\", \"buy\": [ { \"id\": \"hat\","
                        + " \"item\": { \"id\": \"minecraft:paper\", \"count\": 1 }, \"price\": 1 } ] }"));
    }

    @Test
    void anOfferIdIsAtMostSixtyFourCharacters() {
        assertEquals(List.of(), shopProblems(shopWithOffer("a".repeat(64), "{ \"id\": \"minecraft:paper\" }", 1)));
        assertEquals(List.of("extension alina:trade, shop lampas:s, buy entry 1: id must be at most 64 characters"),
                shopProblems(shopWithOffer("a".repeat(65), "{ \"id\": \"minecraft:paper\" }", 1)));
    }

    @Test
    void theOfferIdLimitIsTheLimitOfTheShopPayloads() {
        assertEquals(OfferRow.MAX_ID_LENGTH, TradeReader.MAX_ID_LENGTH);
    }

    @Test
    void anOfferOfACurrencyItemIsRefusedWhateverItsComponents() {
        String refused = "extension alina:trade, shop lampas:s, offer hat: item is a currency item, so the offer would sell Aur";
        assertEquals(List.of(refused), shopProblems(shopWithOffer("hat", "{ \"id\": \"minecraft:gold_nugget\" }", 1)));
        assertEquals(List.of(refused), shopProblems(shopWithOffer("hat",
                "{ \"id\": \"minecraft:gold_nugget\", \"components\": { \"minecraft:custom_name\": \"Coin\" } }", 1)));
    }

    @Test
    void anOfferOfAStackThatHoldsItemsIsRefused() {
        String refused = "extension alina:trade, shop lampas:s, offer box: item holds other items; an offer must be a single item";
        // A chest with a stone in its first slot, and a bundle with a stone in it.
        assertEquals(List.of(refused), shopProblems(shopWithOffer("box",
                "{ \"id\": \"minecraft:chest\", \"components\": { \"minecraft:container\": [ { \"slot\": 0, \"item\": "
                        + "{ \"id\": \"minecraft:stone\" } } ] } }", 5)));
        assertEquals(List.of(refused), shopProblems(shopWithOffer("box",
                "{ \"id\": \"minecraft:bundle\", \"components\": { \"minecraft:bundle_contents\": [ { \"id\": \"minecraft:stone\" } ] } }",
                5)));
    }

    @Test
    void anOfferPriceIsAtLeastOne() {
        assertEquals(List.of("extension alina:trade, shop lampas:s, offer hat: price must be at least 1"),
                shopProblems("{ \"name\": \"S\", \"buy\": [ { \"id\": \"hat\", \"item\": { \"id\": \"minecraft:paper\" },"
                        + " \"price\": 0 } ] }"));
    }

    @Test
    void anOfferHasOnlyItsKeys() {
        assertEquals(List.of("extension alina:trade, shop lampas:s, offer hat: unknown key 'only_if'"),
                shopProblems("{ \"name\": \"S\", \"buy\": [ { \"id\": \"hat\", \"item\": { \"id\": \"minecraft:paper\" },"
                        + " \"price\": 1, \"only_if\": [] } ] }"));
    }

    @Test
    void aLimitHasACountOfAtLeastOneAndADay() {
        String offer = "{ \"id\": \"hat\", \"item\": { \"id\": \"minecraft:paper\" }, \"price\": 1, \"limit\": %s }";
        assertEquals(List.of("extension alina:trade, shop lampas:s, offer hat, limit: count must be at least 1"),
                shopProblems("{ \"name\": \"S\", \"buy\": [ " + offer.formatted("{ \"count\": 0, \"per\": \"day\" }") + " ] }"));
        assertEquals(List.of("extension alina:trade, shop lampas:s, offer hat, limit: per must be day or character"),
                shopProblems("{ \"name\": \"S\", \"buy\": [ " + offer.formatted("{ \"count\": 1, \"per\": \"week\" }") + " ] }"));
    }

    @Test
    void requiresHasAReputationOrAnAchievement() {
        String offer = "{ \"id\": \"hat\", \"item\": { \"id\": \"minecraft:paper\" }, \"price\": 1, \"requires\": %s }";
        assertEquals(List.of("extension alina:trade, shop lampas:s, offer hat, requires: needs reputation, achievement or both"),
                shopProblems("{ \"name\": \"S\", \"buy\": [ " + offer.formatted("{}") + " ] }"));
        assertEquals(List.of("extension alina:trade, shop lampas:s, offer hat, requires: reputation must be a whole number"),
                shopProblems("{ \"name\": \"S\", \"buy\": [ " + offer.formatted("{ \"reputation\": 1.5 }") + " ] }"));
        assertEquals(List.of(), shopProblems("{ \"name\": \"S\", \"buy\": [ "
                + offer.formatted("{ \"achievement\": \"alina:guild/first_commission\" }") + " ] }"));
    }

    @Test
    void experienceRatesAreWholeAndAtLeastOne() {
        assertEquals(List.of(), shopProblems("{ \"name\": \"S\", \"experience\": { \"buy\": { \"points\": 50, \"aur\": 2 } } }"));
        assertEquals(List.of("extension alina:trade, shop lampas:s, experience, buy: aur must be at least 1"),
                shopProblems("{ \"name\": \"S\", \"experience\": { \"buy\": { \"points\": 50, \"aur\": 0 } } }"));
    }

    @Test
    void problemsOfSeveralPlacesAreAllReportedInOnePass() {
        assertEquals(List.of(
                        "extension alina:trade: currency needs a denomination of value 1, so any amount can be paid out",
                        "extension alina:trade, shop lampas:s: name is required",
                        "extension alina:trade, shop lampas:s, sell: daily_budget must be at least 1"),
                trade("{ \"currency\": [ { \"item\": \"minecraft:gold_ingot\", \"value\": 9 } ], \"shops\": { \"lampas:s\": "
                        + "{ \"sell\": { \"daily_budget\": 0, \"rules\": [ " + PLAIN_RULE + " ] } } } }").problems());
    }

    // The mint checks. Equal rates are allowed; a cheaper experience buy and a cheaper offer buy-back are refused.

    @Test
    void anExperienceBuyEqualToASellIsAllowed() {
        // Sell pays 1 aur for 50 points, buy costs 2 aur for 100 points: the same price per point.
        assertEquals(List.of(), trade(section("{}", "{ \"lampas:a\": { \"name\": \"A\", \"sell\": { \"daily_budget\": 1 }, \"experience\": { \"sell\": "
                + "{ \"points\": 50, \"aur\": 1 } } }, \"lampas:b\": { \"name\": \"B\", \"experience\": { \"buy\": "
                + "{ \"points\": 100, \"aur\": 2 } } } }")).problems());
    }

    @Test
    void anExperienceBuyCheaperThanASellIsRefusedOnTheBuyingShop() {
        assertEquals(List.of("extension alina:trade, shop lampas:b: experience buy costs 1 aur for 50 points, less per point "
                + "than shop lampas:a pays for experience sold to it (2 aur for 50 points), so buying from shop lampas:b and "
                + "selling to shop lampas:a mints Aur"),
                trade(section("{}", "{ \"lampas:a\": { \"name\": \"A\", \"sell\": { \"daily_budget\": 1 }, \"experience\": { \"sell\": "
                        + "{ \"points\": 50, \"aur\": 2 } } }, \"lampas:b\": { \"name\": \"B\", \"experience\": { \"buy\": "
                        + "{ \"points\": 50, \"aur\": 1 } } } }")).problems());
    }

    @Test
    void anExperienceBuyCheaperThanItsOwnSellIsRefused() {
        assertEquals(List.of("extension alina:trade, shop lampas:a: experience buy costs 1 aur for 50 points, less per point "
                + "than shop lampas:a pays for experience sold to it (2 aur for 50 points), so buying from shop lampas:a and "
                + "selling to shop lampas:a mints Aur"),
                trade(section("{}", "{ \"lampas:a\": { \"name\": \"A\", \"sell\": { \"daily_budget\": 1 }, \"experience\": { \"sell\": "
                        + "{ \"points\": 50, \"aur\": 2 }, \"buy\": { \"points\": 50, \"aur\": 1 } } } }")).problems());
    }

    @Test
    void anOfferBoughtBackForMoreThanItCostsIsRefusedOnTheOffer() {
        assertEquals(List.of("extension alina:trade, shop lampas:a, offer paper: shop lampas:b pays 2 aur for 1 item, more per "
                + "item than this offer costs (1 aur for 1 item), so buying it here and selling it to shop lampas:b mints Aur"),
                trade(section("{}", "{ \"lampas:a\": { \"name\": \"A\", \"buy\": [ { \"id\": \"paper\", \"item\": "
                        + "{ \"id\": \"minecraft:paper\" }, \"price\": 1 } ] }, \"lampas:b\": { \"name\": \"B\", \"sell\": "
                        + "{ \"daily_budget\": 10, \"rules\": [ { \"match\": { \"items\": \"minecraft:paper\" }, \"price\": "
                        + "{ \"aur\": 2, \"per\": 1 } } ] } } }")).problems());
    }

    @Test
    void anOfferBoughtBackAtTheSamePricePerItemIsAllowed() {
        // Four papers for 4 aur is 1 aur each; the buyer pays 1 aur for 2 papers, half that.
        assertEquals(List.of(), trade(section("{}", "{ \"lampas:a\": { \"name\": \"A\", \"buy\": [ { \"id\": \"paper\", "
                + "\"item\": { \"id\": \"minecraft:paper\", \"count\": 4 }, \"price\": 4 } ] }, \"lampas:b\": { \"name\": \"B\", "
                + "\"sell\": { \"daily_budget\": 10, \"rules\": [ { \"match\": { \"items\": \"minecraft:paper\" }, \"price\": "
                + "{ \"aur\": 1, \"per\": 2 } } ] } } }")).problems());
        // Four papers for 4 aur, bought back for 1 aur per 1 paper: the same, so allowed.
        assertEquals(List.of(), trade(section("{}", "{ \"lampas:a\": { \"name\": \"A\", \"buy\": [ { \"id\": \"paper\", "
                + "\"item\": { \"id\": \"minecraft:paper\", \"count\": 4 }, \"price\": 4 } ] }, \"lampas:b\": { \"name\": \"B\", "
                + "\"sell\": { \"daily_budget\": 10, \"rules\": [ " + PLAIN_RULE + " ] } } }")).problems());
    }

    /** Shop lampas:a sells one enchanted sword for {@code price}; shop lampas:b pays 5 Aur for a plain iron sword. */
    private static List<String> swordProblems(int price) {
        return trade(section("{}", "{ \"lampas:a\": { \"name\": \"A\", \"buy\": [ { \"id\": \"sword\", \"item\": "
                + ENCHANTED_SWORD + ", \"price\": " + price + " } ] }, \"lampas:b\": { \"name\": \"B\", \"sell\": "
                + "{ \"daily_budget\": 10, \"rules\": [ { \"match\": { \"items\": \"minecraft:iron_sword\" }, \"price\": "
                + "{ \"aur\": 5, \"per\": 1 } } ] } } }")).problems();
    }

    @Test
    void aModifiedOfferBelowThePlainItemsBuyBackPriceIsRefused() {
        // A grindstone strips the enchantment, and the plain sword pays 5 Aur, more than the 4 the offer costs.
        assertEquals(List.of("extension alina:trade, shop lampas:a, offer sword: shop lampas:b pays 5 aur for 1 item, more per "
                + "item than this offer costs (4 aur for 1 item), so buying it here and selling it to shop lampas:b mints Aur"),
                swordProblems(4));
    }

    @Test
    void aModifiedOfferAtOrAboveThePlainItemsBuyBackPriceIsAllowed() {
        assertEquals(List.of(), swordProblems(5));
        assertEquals(List.of(), swordProblems(6));
    }

    @Test
    void aRefuseRuleShadowsALaterBuyBackPrice() {
        assertEquals(List.of(), trade(section("{}", "{ \"lampas:a\": { \"name\": \"A\", \"buy\": [ { \"id\": \"paper\", "
                + "\"item\": { \"id\": \"minecraft:paper\" }, \"price\": 1 } ] }, \"lampas:b\": { \"name\": \"B\", "
                + "\"sell\": { \"daily_budget\": 10, \"rules\": [ { \"match\": { \"items\": \"minecraft:paper\" }, \"refuse\": true },"
                + " { \"match\": { \"items\": \"minecraft:paper\" }, \"price\": { \"aur\": 2, \"per\": 1 } } ] } } }")).problems());
    }

    // SellRule.matches

    @Test
    void anUnmodifiedStackMatchesAPlainRule() {
        assertTrue(sellRule(PLAIN_RULE).matches(stack("{ \"id\": \"minecraft:paper\" }")));
    }

    @Test
    void anEnchantedStackDoesNotMatchAPlainRule() {
        ItemStack enchanted = stack("{ \"id\": \"minecraft:iron_sword\", \"components\": "
                + "{ \"minecraft:enchantments\": { \"minecraft:sharpness\": 1 } } }");
        SellRule plain = sellRule("{ \"match\": { \"items\": \"minecraft:iron_sword\" }, \"price\": { \"aur\": 1, \"per\": 1 } }");
        assertFalse(plain.acceptsModified());
        assertFalse(plain.matches(enchanted));
    }

    @Test
    void anEnchantedStackMatchesARuleWithComponents() {
        ItemStack enchanted = stack("{ \"id\": \"minecraft:iron_sword\", \"components\": "
                + "{ \"minecraft:enchantments\": { \"minecraft:sharpness\": 1 } } }");
        SellRule rule = sellRule("{ \"match\": { \"items\": \"minecraft:iron_sword\", \"components\": "
                + "{ \"minecraft:enchantments\": { \"minecraft:sharpness\": 1 } } }, \"price\": { \"aur\": 1, \"per\": 1 } }");
        assertTrue(rule.acceptsModified());
        assertTrue(rule.matches(enchanted));
    }

    @Test
    void aNamespaceRuleTakesOnlyUnmodifiedStacksOfItsNamespace() {
        SellRule rule = sellRule("{ \"namespace\": \"minecraft\", \"price\": { \"aur\": 1, \"per\": 1 } }");
        assertFalse(rule.acceptsModified());
        assertTrue(rule.matches(stack("{ \"id\": \"minecraft:iron_sword\" }")));
        assertFalse(rule.matches(stack("{ \"id\": \"minecraft:iron_sword\", \"components\": "
                + "{ \"minecraft:enchantments\": { \"minecraft:sharpness\": 1 } } }")));
        assertFalse(sellRule("{ \"namespace\": \"farmersdelight\", \"price\": { \"aur\": 1, \"per\": 1 } }")
                .matches(stack("{ \"id\": \"minecraft:iron_sword\" }")));
    }

    // Shop.ruleFor

    private static final String IRON_5 = "{ \"match\": { \"items\": \"minecraft:iron_sword\" }, \"price\": { \"aur\": 5, \"per\": 1 } }";
    private static final String IRON_9 = "{ \"match\": { \"items\": \"minecraft:iron_sword\" }, \"price\": { \"aur\": 9, \"per\": 1 } }";
    private static final String MINECRAFT_3 = "{ \"namespace\": \"minecraft\", \"price\": { \"aur\": 3, \"per\": 1 } }";

    @Test
    void ownRulesComeBeforeTheListsAndTheFirstMatchWins() {
        String priceLists = "{ \"lampas:first\": { \"rules\": [ " + MINECRAFT_3 + " ] }, \"lampas:second\": { \"rules\": [ "
                + IRON_9 + " ] } }";
        String shops = "{ \"lampas:s\": { \"name\": \"S\", \"sell\": { \"daily_budget\": 9, \"lists\": [\"lampas:first\", "
                + "\"lampas:second\"], \"rules\": [ " + IRON_5 + " ] } } }";
        Shop shop = trade(section(priceLists, shops)).orThrow().shops().get(id("lampas:s"));

        // The shop's own rule is tried first, even though the lists also match.
        assertEquals(Optional.of(new Price(5, 1)), shop.ruleFor(stack("{ \"id\": \"minecraft:iron_sword\" }")).orElseThrow().price());
        // The first list's rule matches the pickaxe, and no later rule is tried.
        assertEquals(Optional.of(new Price(3, 1)), shop.ruleFor(stack("{ \"id\": \"minecraft:iron_pickaxe\" }")).orElseThrow().price());
        assertEquals(List.of(new Price(5, 1), new Price(3, 1), new Price(9, 1)),
                shop.selling().orElseThrow().rules().stream().map(rule -> rule.price().orElseThrow()).toList());
    }

    @Test
    void theFirstListWinsOverALaterListThatAlsoMatches() {
        String priceLists = "{ \"lampas:first\": { \"rules\": [ " + MINECRAFT_3 + " ] }, \"lampas:second\": { \"rules\": [ "
                + IRON_9 + " ] } }";
        String shops = "{ \"lampas:s\": { \"name\": \"S\", \"sell\": { \"daily_budget\": 9, \"lists\": [\"lampas:first\", "
                + "\"lampas:second\"] } } }";
        Shop shop = trade(section(priceLists, shops)).orThrow().shops().get(id("lampas:s"));
        assertEquals(Optional.of(new Price(3, 1)), shop.ruleFor(stack("{ \"id\": \"minecraft:iron_sword\" }")).orElseThrow().price());
    }

    @Test
    void aRefuseRuleShadowsALaterPriceInALaterList() {
        String priceLists = "{ \"lampas:first\": { \"rules\": [ { \"match\": { \"items\": \"minecraft:paper\" }, \"refuse\": true } ] },"
                + " \"lampas:second\": { \"rules\": [ " + PLAIN_RULE + " ] } }";
        String shops = "{ \"lampas:s\": { \"name\": \"S\", \"sell\": { \"daily_budget\": 9, \"lists\": [\"lampas:first\", "
                + "\"lampas:second\"] } } }";
        Shop shop = trade(section(priceLists, shops)).orThrow().shops().get(id("lampas:s"));
        SellRule found = shop.ruleFor(stack("{ \"id\": \"minecraft:paper\" }")).orElseThrow();
        assertTrue(found.refuse());
        assertEquals(Optional.empty(), found.price());
    }

    @Test
    void aShopWithoutSellHasNoRuleForAnyStack() {
        Shop shop = trade(section("{}", "{ \"lampas:s\": { \"name\": \"S\", \"experience\": { \"buy\": { \"points\": 1, \"aur\": 1 } } } }"))
                .orThrow().shops().get(id("lampas:s"));
        assertEquals(Optional.empty(), shop.ruleFor(stack("{ \"id\": \"minecraft:paper\" }")));
    }

    // The experience market

    @Test
    void theMarketExampleParses() {
        TradeContent content = trade(MARKET_EXAMPLE).orThrow();

        assertEquals(new ExperienceMarket(500000, 10000, 10, 10), content.market().orElseThrow());
        Shop mira = content.shops().get(id("lampas:mira"));
        assertEquals(Optional.empty(), mira.experience());
        assertEquals(new ExperienceFees(OptionalInt.of(5), OptionalInt.of(5)), mira.experienceFees().orElseThrow());
    }

    @Test
    void theMarketExampleHasNoProblems() {
        assertEquals(List.of(), trade(MARKET_EXAMPLE).problems());
    }

    @Test
    void aSectionWithoutAMarketHasRatesAndNoFees() {
        TradeContent content = trade(EXAMPLE).orThrow();

        assertEquals(Optional.empty(), content.market());
        assertEquals(Optional.empty(), content.shops().get(id("lampas:mira")).experienceFees());
    }

    @Test
    void maxTradePercentLeftOutIsTen() {
        TradeContent content = trade(marketSection(market(RESERVE, "10"), "{}")).orThrow();

        assertEquals(new ExperienceMarket(500000, 10000, 10, 10), content.market().orElseThrow());
    }

    @Test
    void aSellFeeOfZeroTradesOnlyTheSell() {
        Shop shop = trade(marketSection(MARKET, feeShop("0"))).orThrow().shops().get(id("lampas:s"));

        assertEquals(new ExperienceFees(OptionalInt.of(0), OptionalInt.empty()), shop.experienceFees().orElseThrow());
    }

    @Test
    void aMarketNeedsNoShopThatTradesExperience() {
        assertEquals(List.of(), marketProblems(MARKET));
    }

    @Test
    void theReservePointsAreFrom1000To1000000000() {
        assertEquals(List.of("extension alina:trade, experience_market, reserve: points must be at least 1000"),
                marketProblems(market(reserve("999", "10000"), "10")));
        assertEquals(List.of("extension alina:trade, experience_market, reserve: points must be at most 1000000000"),
                marketProblems(market(reserve("1000000001", "10000"), "10")));
        assertEquals(List.of(), marketProblems(market(reserve("1000", "10000"), "10")));
        assertEquals(List.of(), marketProblems(market(reserve("1000000000", "10000"), "10")));
    }

    @Test
    void theReserveAurIsFrom1000To1000000000() {
        assertEquals(List.of("extension alina:trade, experience_market, reserve: aur must be at least 1000"),
                marketProblems(market(reserve("500000", "999"), "10")));
        assertEquals(List.of("extension alina:trade, experience_market, reserve: aur must be at most 1000000000"),
                marketProblems(market(reserve("500000", "1000000001"), "10")));
        assertEquals(List.of(), marketProblems(market(reserve("500000", "1000"), "10")));
        assertEquals(List.of(), marketProblems(market(reserve("500000", "1000000000"), "10")));
    }

    @Test
    void restockPercentIsFrom0To100() {
        assertEquals(List.of("extension alina:trade, experience_market: restock_percent must be at least 0"),
                marketProblems(market(RESERVE, "-1")));
        assertEquals(List.of("extension alina:trade, experience_market: restock_percent must be at most 100"),
                marketProblems(market(RESERVE, "101")));
        assertEquals(List.of(), marketProblems(market(RESERVE, "0")));
        assertEquals(List.of(), marketProblems(market(RESERVE, "100")));
    }

    @Test
    void maxTradePercentIsFrom1To50() {
        String withMax = "{ \"reserve\": " + RESERVE + ", \"restock_percent\": 10, \"max_trade_percent\": %s }";
        assertEquals(List.of("extension alina:trade, experience_market: max_trade_percent must be at least 1"),
                marketProblems(withMax.formatted("0")));
        assertEquals(List.of("extension alina:trade, experience_market: max_trade_percent must be at most 50"),
                marketProblems(withMax.formatted("51")));
        assertEquals(List.of(), marketProblems(withMax.formatted("1")));
        assertEquals(List.of(), marketProblems(withMax.formatted("50")));
    }

    @Test
    void feePercentIsFrom0To50() {
        assertEquals(List.of("extension alina:trade, shop lampas:s, experience, sell: fee_percent must be at least 0"),
                trade(marketSection(MARKET, feeShop("-1"))).problems());
        assertEquals(List.of("extension alina:trade, shop lampas:s, experience, sell: fee_percent must be at most 50"),
                trade(marketSection(MARKET, feeShop("51"))).problems());
        assertEquals(List.of(), trade(marketSection(MARKET, feeShop("50"))).problems());
    }

    @Test
    void reserveAndRestockPercentAreRequired() {
        assertEquals(List.of("extension alina:trade, experience_market: reserve is required"),
                marketProblems("{ \"restock_percent\": 10 }"));
        assertEquals(List.of("extension alina:trade, experience_market: restock_percent is required"),
                marketProblems("{ \"reserve\": " + RESERVE + " }"));
    }

    @Test
    void theMarketAndItsReserveHaveOnlyTheirKeys() {
        assertEquals(List.of("extension alina:trade, experience_market: unknown key 'only_if'"),
                marketProblems("{ \"reserve\": " + RESERVE + ", \"restock_percent\": 10, \"only_if\": true }"));
        assertEquals(List.of("extension alina:trade, experience_market, reserve: unknown key 'only_if'"),
                marketProblems("{ \"reserve\": { \"points\": 500000, \"aur\": 10000, \"only_if\": true },"
                        + " \"restock_percent\": 10 }"));
    }

    @Test
    void withAMarketARateSellIsRefusedForItsForm() {
        assertEquals(List.of("extension alina:trade, shop lampas:s, experience, sell: the bundle has an experience_market, "
                + "so the entry must be { \"fee_percent\": n }"),
                trade(marketSection(MARKET, "{ \"lampas:s\": { \"name\": \"S\", \"sell\": { \"daily_budget\": 1 }, \"experience\": { \"sell\": "
                        + "{ \"points\": 50, \"aur\": 1 } } } }")).problems());
    }

    @Test
    void withoutAMarketAFeeBuyIsRefusedForItsForm() {
        assertEquals(List.of("extension alina:trade, shop lampas:s, experience, buy: fee_percent needs an experience_market section"),
                shopProblems("{ \"name\": \"S\", \"experience\": { \"buy\": { \"fee_percent\": 5 } } }"));
    }

    @Test
    void anEntryIsARateOrAFeeNotBoth() {
        String both = "{ \"points\": 50, \"fee_percent\": 5 }";
        String message = "extension alina:trade, shop lampas:s, experience, sell: gives both a rate (points, aur) and a fee "
                + "(fee_percent); give one";
        assertEquals(List.of(message), trade(marketSection(MARKET, "{ \"lampas:s\": { \"name\": \"S\", \"sell\": "
                + "{ \"daily_budget\": 1 }, \"experience\": { \"sell\": " + both + " } } }")).problems());
        assertEquals(List.of(message), shopProblems("{ \"name\": \"S\", \"sell\": { \"daily_budget\": 1 }, \"experience\": { \"sell\": " + both + " } }"));
    }

    @Test
    void aBrokenMarketDoesNotMakeItsFeeShopsNeedAMarket() {
        // The market fails to parse but is declared, so the fee shop takes the market form and reports nothing of its own.
        assertEquals(List.of("extension alina:trade, experience_market: restock_percent is required"),
                trade(marketSection("{ \"reserve\": " + RESERVE + " }", feeShop("5"))).problems());
    }

    @Test
    void theMarketAndTheShopsAreReportedInOnePass() {
        assertEquals(List.of(
                        "extension alina:trade, experience_market: restock_percent must be at most 100",
                        "extension alina:trade, shop lampas:s, experience, sell: the bundle has an experience_market, "
                                + "so the entry must be { \"fee_percent\": n }"),
                trade(marketSection(market(RESERVE, "101"), "{ \"lampas:s\": { \"name\": \"S\", \"sell\": { \"daily_budget\": 1 }, \"experience\": { \"sell\": "
                        + "{ \"points\": 50, \"aur\": 1 } } } }")).problems());
    }

    @Test
    void aMarketHasNoExperienceMintCheck() {
        // As fixed rates these two shops are refused: a sell of 2 aur for 50 points against a buy of 1 aur for 50 points.
        assertFalse(trade(section("{}", "{ \"lampas:a\": { \"name\": \"A\", \"sell\": { \"daily_budget\": 1 }, \"experience\": { \"sell\": "
                + "{ \"points\": 50, \"aur\": 2 } } }, \"lampas:b\": { \"name\": \"B\", \"experience\": { \"buy\": "
                + "{ \"points\": 50, \"aur\": 1 } } } }")).problems().isEmpty());
        assertEquals(List.of(), trade(marketSection(MARKET, "{ \"lampas:a\": { \"name\": \"A\", \"sell\": { \"daily_budget\": 1 }, \"experience\": { \"sell\": "
                + "{ \"fee_percent\": 5 } } }, \"lampas:b\": { \"name\": \"B\", \"experience\": { \"buy\": "
                + "{ \"fee_percent\": 5 } } } }")).problems());
    }

    @Test
    void aShopHasFixedRatesOrFeesNotBoth() {
        assertThrows(IllegalArgumentException.class, () -> new Shop(id("lampas:s"), Component.literal("S"), Optional.empty(),
                List.of(), Optional.of(new ExperienceRates(Optional.of(new Rate(50, 1)), Optional.empty())),
                Optional.of(new ExperienceFees(OptionalInt.of(5), OptionalInt.empty())), Map.of()));
    }
}
