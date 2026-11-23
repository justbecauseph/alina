package town.lampas.alina.client.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The stepper's quantity rules: sales move in multiples of the row's per, purchases and experience never go below 1. */
class QuantityTest {

    @Test
    void aSaleStartsAtPerAndMovesInStepsOfPer() {
        assertEquals(3, Quantity.sell(0, 0, 3, 12));
        assertEquals(6, Quantity.sell(3, 1, 3, 12));
        assertEquals(3, Quantity.sell(6, -1, 3, 12));
    }

    @Test
    void aSaleNeverGoesBelowPerOrAboveMaxCount() {
        assertEquals(3, Quantity.sell(3, -10, 3, 12));
        assertEquals(12, Quantity.sell(3, 10, 3, 12));
        assertEquals(12, Quantity.sell(12, 1, 3, 12));
        assertEquals(12, Quantity.sell(0, Quantity.ALL, 3, 12));
    }

    @Test
    void aSaleRoundsACurrentQuantityDownToAMultipleOfPer() {
        assertEquals(3, Quantity.sell(4, 0, 3, 12));
        assertEquals(6, Quantity.sell(8, 0, 3, 12));
    }

    @Test
    void aRowWithNothingToSellHasNoQuantity() {
        assertEquals(0, Quantity.sell(0, 0, 1, 0));
        assertEquals(0, Quantity.sell(5, 10, 4, 3));
    }

    @Test
    void aPurchaseMovesOneAtATimeFromOneUpToTheMaximum() {
        assertEquals(1, Quantity.count(1, -1, 5));
        assertEquals(2, Quantity.count(1, 1, 5));
        assertEquals(5, Quantity.count(1, 10, 5));
        assertEquals(5, Quantity.count(1, Quantity.ALL, 5));
    }

    @Test
    void aPurchaseWithNoAllowedCountStaysAtOne() {
        assertEquals(1, Quantity.count(1, 10, 0));
        assertEquals(1, Quantity.count(7, 0, 0));
    }

    @Test
    void aPurchaseResetsToOne() {
        assertEquals(1, Quantity.resetCount(5));
        assertEquals(1, Quantity.resetCount(0));
    }

    @Test
    void aSaleResetsToOnePerOrToZeroForARowThatCannotBeSold() {
        assertEquals(4, Quantity.resetSell(4, 64));
        assertEquals(0, Quantity.resetSell(4, 3));
    }

    @Test
    void anExperienceAmountResetsToOneStepOrToZeroWhenTheMaxIsBelowIt() {
        assertEquals(1, Quantity.resetSell(1, 26));
        assertEquals(0, Quantity.resetSell(2, 1));
    }
}
