package town.lampas.alina.client.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The rule that keeps a selection across a new view, and the clamp on a list's scroll. */
class SelectionTest {

    @Test
    void aSelectionThatIsStillThereIsKept() {
        assertEquals(Optional.of(2), Selection.keep(2, List.of(1, 2, 3)));
        assertEquals(Optional.of("b"), Selection.keep("b", List.of("a", "b")));
    }

    @Test
    void aSelectionThatIsGoneFallsBackToTheFirstRow() {
        assertEquals(Optional.of(1), Selection.keep(4, List.of(1, 2, 3)));
    }

    @Test
    void anEmptyListSelectsNothing() {
        assertTrue(Selection.keep(4, List.<Integer>of()).isEmpty());
        assertTrue(Selection.keep(null, List.<String>of()).isEmpty());
    }

    @Test
    void noPreviousSelectionSelectsTheFirstRow() {
        assertEquals(Optional.of(7), Selection.keep(null, List.of(7, 8)));
    }

    @Test
    void aScrollStaysWithinTheRowsThatDoNotFit() {
        assertEquals(4, Selection.scroll(5, 10, 6));
        assertEquals(0, Selection.scroll(-1, 10, 6));
        assertEquals(4, Selection.scroll(9, 10, 6));
        assertEquals(0, Selection.scroll(3, 2, 6));
    }
}
