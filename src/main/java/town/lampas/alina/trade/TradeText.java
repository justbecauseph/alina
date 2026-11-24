package town.lampas.alina.trade;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import town.lampas.alina.content.TradeContent.Shop;

/**
 * The text the trade shows players: the shop's default lines, refusals and the requirement rows. Each is a translatable
 * component whose key is in {@code assets/alina/lang/en_us.json}, with the English text as its fallback. A shop's own line
 * for a key takes the place of the default, when the content gives one.
 */
public final class TradeText {

    private TradeText() {}

    private static Component text(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("alina.trade." + key, fallback, args);
    }

    public static Component loading() {
        return text("loading", "Your trade record is still loading.");
    }

    public static Component unknownShop() {
        return text("unknown_shop", "This shop is not open.");
    }

    public static Component cannotOpen() {
        return text("cannot_open", "The shop screen needs the Alina mod on your client.");
    }

    public static Component closed() {
        return text("closed", "The shop is no longer open to you.");
    }

    public static Component stale() {
        return text("stale", "Prices or your inventory changed. Look again before you trade.");
    }

    public static Component invalid() {
        return text("invalid", "That trade cannot be made.");
    }

    public static Component notOffered() {
        return text("not_offered", "This shop does not trade that.");
    }

    public static Component notEnoughExperience() {
        return text("not_enough_experience", "You do not have that much experience.");
    }

    /** The staff refusal of a budget reset while a trade of the player settles. */
    public static Component resetBusy() {
        return text("reset_busy", "a trade of this player is still being settled; try again in a moment");
    }

    /** The staff refusal of a change to the experience market while the market has not loaded. */
    public static Component marketLoading() {
        return text("market_loading", "The experience market is still loading.");
    }

    /** The line of a sale at the market whose points moved from the quote: the points given, the Aur, and the points quoted. */
    public static Component soldMoved(int points, int aur, int quoted) {
        return text("sold_moved", "Sold %s points for %s Aur (quoted %s).", points, aur, quoted);
    }

    /** The line of a purchase at the market whose points moved from the quote: the points got, the Aur, and the points quoted. */
    public static Component boughtMoved(int points, int aur, int quoted) {
        return text("bought_moved", "Bought %s points for %s Aur (quoted %s).", points, aur, quoted);
    }

    public static Component needsReputation(int reputation) {
        return text("needs_reputation", "You need reputation %s.", reputation);
    }

    public static Component needsAchievement(Identifier achievement) {
        return text("needs_achievement", "You need the %s achievement.", achievement.toString());
    }

    /** The shop's own line for {@code key} when its content has one, else Alina's default for the key. */
    public static Component line(Shop shop, String key) {
        Component own = shop.lines().get(key);
        return own != null ? own : defaultLine(key);
    }

    /** The line a refusal shows: the shop's line for it when the shop has one, and Alina's text otherwise. */
    public static Component refusal(TradeRefusal.Reason reason, @Nullable Shop shop) {
        return switch (reason) {
            case LOADING -> loading();
            case UNKNOWN_SHOP -> unknownShop();
            case CANNOT_OPEN -> cannotOpen();
            case NO_SESSION, CLOSED -> closed();
            case STALE -> stale();
            case INVALID -> invalid();
            case NOT_OFFERED -> notOffered();
            case NOT_ENOUGH_EXPERIENCE -> notEnoughExperience();
            case BUSY -> lineOf(shop, "busy");
            case BUDGET_SPENT -> lineOf(shop, "budget_spent");
            case CANNOT_AFFORD -> lineOf(shop, "cannot_afford");
            case LIMIT_REACHED -> lineOf(shop, "limit_reached");
            case LOCKED -> lineOf(shop, "locked");
            case NO_ROOM -> lineOf(shop, "no_room");
            case NOTHING_TO_SELL -> lineOf(shop, "nothing_to_sell");
        };
    }

    private static Component lineOf(@Nullable Shop shop, String key) {
        return shop == null ? defaultLine(key) : line(shop, key);
    }

    private static Component defaultLine(String key) {
        return switch (key) {
            case "greeting" -> text("greeting", "What will you have?");
            case "sold" -> text("sold", "Sold.");
            case "bought" -> text("bought", "Bought.");
            case "cannot_afford" -> text("cannot_afford", "You cannot afford that.");
            case "budget_spent" -> text("budget_spent", "The shop has no more Aur to pay out today.");
            case "no_room" -> text("no_room", "You have no room for that.");
            case "nothing_to_sell" -> text("nothing_to_sell", "There is nothing here the shop will buy.");
            case "limit_reached" -> text("limit_reached", "You have reached the limit for that.");
            case "locked" -> text("locked", "You do not meet the requirements for that yet.");
            case "busy" -> text("busy", "Give me a moment to write down your last trade.");
            default -> throw new IllegalArgumentException("no default line for " + key);
        };
    }
}
