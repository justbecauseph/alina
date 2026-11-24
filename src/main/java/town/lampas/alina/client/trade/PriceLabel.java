package town.lampas.alina.client.trade;

import java.util.Locale;
import net.minecraft.network.chat.Component;

/**
 * The price texts of the trade screen: a unit price, an experience rate, the text of an experience action button, and the
 * texts of an experience market's price line, fees and previews. Each has an English fallback.
 */
final class PriceLabel {
    private PriceLabel() {}

    /**
     * The text of an experience action button. With {@code points} and {@code aur} both above 0 it names the trade:
     * {@code Sell 1,370 points for 26 Aur} when {@code selling}, {@code Buy 124 points for 2 Aur} otherwise. Otherwise it is
     * the plain verb, {@code Sell experience} or {@code Buy experience}. The points are grouped by thousands.
     */
    static Component experienceAction(boolean selling, int points, int aur) {
        if (points <= 0 || aur <= 0) {
            return selling
                    ? Component.translatableWithFallback("alina.trade.screen.sell_experience", "Sell experience")
                    : Component.translatableWithFallback("alina.trade.screen.buy_experience", "Buy experience");
        }
        String grouped = String.format(Locale.ROOT, "%,d", points);
        return selling
                ? Component.translatableWithFallback("alina.trade.screen.sell_experience_for",
                        "Sell %s points for %s Aur", grouped, aur)
                : Component.translatableWithFallback("alina.trade.screen.buy_experience_for",
                        "Buy %s points for %s Aur", grouped, aur);
    }

    /** The price of one item: {@code aur} when {@code per} is 1, else {@code aur/per}, for {@code per} items. */
    static Component unit(int aur, int per) {
        return per == 1
                ? Component.translatableWithFallback("alina.trade.screen.price", "%s Aur", aur)
                : Component.translatableWithFallback("alina.trade.screen.price_per", "%s Aur/%s", aur, per);
    }

    /** The shop's sell rate: the player gives {@code points} experience points for {@code aur} Aur. */
    static Component sellRate(int points, int aur) {
        return Component.translatableWithFallback("alina.trade.screen.exp_sell_rate",
                "Sell: %s points for %s Aur", points, aur);
    }

    /** The shop's buy rate: the player gets {@code points} experience points for {@code aur} Aur. */
    static Component buyRate(int points, int aur) {
        return Component.translatableWithFallback("alina.trade.screen.exp_buy_rate",
                "Buy: %s points for %s Aur", points, aur);
    }

    /**
     * The price of 100 points of experience: {@code hundredths} is the Aur per 100 points times 100, as
     * {@link Exchange#priceHundredths} gives it, so {@code 200} is {@code 100 points = 2.00 Aur}.
     */
    static Component price(int hundredths) {
        return Component.translatableWithFallback("alina.trade.screen.market_price", "100 points = %s Aur",
                Exchange.hundredths(hundredths));
    }

    /** A price's change against its usual price, in percent, without a sign: the triangle beside it gives the direction. */
    static Component priceChange(int percent) {
        return Component.translatableWithFallback("alina.trade.screen.price_change", "%s%%", Math.abs(percent));
    }

    /**
     * The fee of the directions a shop trades, in percent: {@code Fee 5%} when the two fees are the same or only one direction
     * is traded, and {@code Fee 5% selling, 3% buying} when they differ. A fee of -1 is a direction the shop does not trade,
     * so at least one of the two is 0 or more.
     */
    static Component fee(int sellFee, int buyFee) {
        if (sellFee >= 0 && buyFee >= 0 && sellFee != buyFee) {
            return Component.translatableWithFallback("alina.trade.screen.market_fees",
                    "Fee %s%% selling, %s%% buying", sellFee, buyFee);
        }
        int fee = sellFee >= 0 ? sellFee : buyFee;
        return Component.translatableWithFallback("alina.trade.screen.market_fee", "Fee %s%%", fee);
    }

    /** The quote tolerance of a market, in percent: {@code Quote tolerance 2%}, shown under the fee when it is above 0. */
    static Component tolerance(int tolerancePercent) {
        return Component.translatableWithFallback("alina.trade.screen.market_tolerance", "Quote tolerance %s%%",
                tolerancePercent);
    }

    /** The level a preview reaches: {@code Lv 24} is level 24. */
    static Component previewLevel(int level) {
        return Component.translatableWithFallback("alina.trade.screen.preview_level", "Lv %s", level);
    }
}
