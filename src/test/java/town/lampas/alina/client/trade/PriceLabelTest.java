package town.lampas.alina.client.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import town.lampas.alina.TestBootstrap;

/** The price texts, as the English fallbacks read. */
class PriceLabelTest {

    @BeforeAll
    static void boot() {
        TestBootstrap.boot();
    }

    @Test
    void aSingleItemIsPricedInAur() {
        assertEquals("40 Aur", PriceLabel.unit(40, 1).getString());
    }

    @Test
    void aGroupOfItemsIsPricedPerGroup() {
        assertEquals("12 Aur/3", PriceLabel.unit(12, 3).getString());
    }

    @Test
    void experienceRatesNameTheirPointsAndAur() {
        assertEquals("Sell: 7 points for 2 Aur", PriceLabel.sellRate(7, 2).getString());
        assertEquals("Buy: 10 points for 5 Aur", PriceLabel.buyRate(10, 5).getString());
    }

    @Test
    void thePriceLineNamesTheAurOfAHundredPoints() {
        assertEquals("100 points = 2.00 Aur", PriceLabel.price(200).getString());
    }

    @Test
    void aPriceChangeIsItsPercentWithoutASign() {
        assertEquals("2%", PriceLabel.priceChange(2).getString());
        assertEquals("2%", PriceLabel.priceChange(-2).getString());
        assertEquals("0%", PriceLabel.priceChange(0).getString());
    }

    @Test
    void aFeeNamesOneRateWhenTheDirectionsMatchOrOnlyOneIsTraded() {
        assertEquals("Fee 5%", PriceLabel.fee(5, 5).getString());
        assertEquals("Fee 5%", PriceLabel.fee(5, -1).getString());
        assertEquals("Fee 3%", PriceLabel.fee(-1, 3).getString());
    }

    @Test
    void aFeeNamesEachDirectionWhenTheyDiffer() {
        assertEquals("Fee 5% selling, 3% buying", PriceLabel.fee(5, 3).getString());
    }

    @Test
    void theQuoteToleranceIsItsOwnLineUnderTheFee() {
        assertEquals("Quote tolerance 2%", PriceLabel.tolerance(2).getString());
        assertEquals("Quote tolerance 10%", PriceLabel.tolerance(10).getString());
    }

    @Test
    void aPreviewNamesTheLevelAfterTheTrade() {
        assertEquals("Lv 24", PriceLabel.previewLevel(24).getString());
    }

    @Test
    void anExperienceActionNamesThePointsAndTheAurWhenBothArePositive() {
        assertEquals("Sell 1,370 points for 26 Aur", PriceLabel.experienceAction(true, 1370, 26).getString());
        assertEquals("Buy 124 points for 2 Aur", PriceLabel.experienceAction(false, 124, 2).getString());
    }

    @Test
    void anExperienceActionIsThePlainVerbWhenNothingTrades() {
        assertEquals("Sell experience", PriceLabel.experienceAction(true, 1370, 0).getString());
        assertEquals("Buy experience", PriceLabel.experienceAction(false, 0, 2).getString());
    }
}
