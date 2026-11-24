package town.lampas.alina.client.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import town.lampas.alina.client.trade.TradeLayout.Box;
import town.lampas.alina.client.trade.TradeLayout.ExperienceSection;
import town.lampas.alina.client.trade.TradeLayout.ExperienceMarketStatus;
import town.lampas.alina.client.trade.TradeLayout.ExperienceStatus;
import town.lampas.alina.client.trade.TradeLayout.ItemDetail;
import town.lampas.alina.client.trade.TradeLayout.Stepper;

/**
 * The trade screen's geometry: that the parts fit the window, stack in order and do not overlap, and that the text box
 * and the panel stay on screen at the smallest GUI (320 by 240) and on larger windows.
 */
class TradeLayoutTest {
    private static final int LINE = 11;
    /** A shop list of eight rows: 168 pixels, less the list's inset on both ends, in rows of 20 pixels. */
    private static final Box SHOP_LIST = new Box(0, 0, 100, 168);
    private static final int SHOP_ROWS = 8;
    /** The detail panel the experience sections are laid out in: 216 by 170, as a wide window gives. */
    private static final Box EXPERIENCE_DETAIL = new Box(0, 0, 216, 170);
    /** A market's chart four pixels wide, from x 10 to x 14, and 28 high, from y 100 to y 128. */
    private static final Box SMALL_CHART = new Box(10, 100, 14, 128);
    private static final int[] CHART_ROWS = {0, 5, 5, 27};

    private static TradeLayout layout(int width, int height, int lines) {
        return TradeLayout.compute(width, height, LINE, textWidth -> lines);
    }

    /** The scroll thumb of {@link #SHOP_LIST} for {@code total} rows, scrolled to {@code scroll}. */
    private static Optional<Box> thumb(int scroll, int total) {
        return TradeLayout.scrollThumb(SHOP_LIST, scroll, total, SHOP_ROWS);
    }

    private static boolean inside(Box outer, Box inner) {
        return inner.x0() >= outer.x0() && inner.x1() <= outer.x1() && inner.y0() >= outer.y0() && inner.y1() <= outer.y1();
    }

    @Test
    void thePanelAndTheTextBoxFitTheSmallestGui() {
        TradeLayout layout = layout(320, 240, 1);

        assertTrue(layout.strip().y0() >= TradeLayout.MARGIN);
        assertTrue(layout.text().y1() <= 240 - TradeLayout.MARGIN);
        assertTrue(layout.text().x0() >= 0 && layout.text().x1() <= 320);
        assertTrue(inside(new Box(0, 0, 320, 240), layout.list()));
        assertTrue(inside(new Box(0, 0, 320, 240), layout.detail()));
        assertEquals(layout.strip().y0(), layout.top());
    }

    @Test
    void theShopPanelStacksAboveTheTextBoxInOrder() {
        TradeLayout layout = layout(320, 240, 2);

        assertTrue(layout.strip().y1() <= layout.list().y0());
        assertTrue(layout.list().y1() + TradeLayout.BODY_TEXT_GAP <= layout.text().y0());
        assertEquals(layout.list().y0(), layout.detail().y0());
        assertEquals(layout.list().y1(), layout.detail().y1());
    }

    @Test
    void theListAndTheDetailSitSideBySideWithAGap() {
        TradeLayout layout = layout(320, 240, 1);

        assertEquals(layout.list().x0(), layout.strip().x0());
        assertTrue(layout.list().x1() + TradeLayout.PANEL_GAP <= layout.detail().x0());
        assertEquals(layout.strip().x1(), layout.detail().x1());
        assertTrue(layout.list().width() < layout.detail().width());
    }

    @Test
    void thePanelIsCentredAndCapped() {
        TradeLayout small = layout(320, 240, 1);
        assertEquals(304, small.strip().width());
        assertEquals(8, small.strip().x0());

        TradeLayout wide = layout(960, 540, 1);
        assertEquals(TradeLayout.MAX_PANEL_WIDTH, wide.strip().width());
        assertEquals(wide.strip().x0(), 960 - wide.strip().x1());
        assertEquals(TradeLayout.MAX_BODY_HEIGHT, wide.list().height());
    }

    @Test
    void theShopLineHoldsAtLeastOneLineAndNoMoreThanTheCap() {
        assertEquals(1, layout(320, 240, 0).textLines());
        assertEquals(TradeLayout.MAX_TEXT_LINES, layout(320, 240, 9).textLines());
    }

    @Test
    void theTextBoxHoldsTheShopLineAndTheBudgetLine() {
        TradeLayout layout = layout(320, 240, 2);
        Box text = layout.text();

        assertTrue(layout.textY() >= text.y0());
        assertTrue(layout.budgetY() >= layout.textY() + 2 * LINE);
        assertTrue(layout.budgetY() + LINE <= text.y1());
        assertTrue(inside(text, layout.frame()));
        assertTrue(layout.textX() >= layout.frame().x1());
    }

    @Test
    void theListShowsAtLeastThreeRowsAtTheSmallestGui() {
        TradeLayout layout = layout(320, 240, 1);
        int rows = TradeLayout.visibleRows(layout.list());

        assertTrue(rows >= 3, "rows " + rows);
        Box last = TradeLayout.row(layout.list(), rows - 1);
        assertTrue(inside(layout.list(), last));
    }

    @Test
    void noRowIsDrawnWhenTheListHasNoRoom() {
        assertEquals(0, TradeLayout.visibleRows(new Box(0, 0, 100, 5)));
    }

    @Test
    void aTinyWindowGivesNonNegativeBoxes() {
        TradeLayout layout = layout(100, 60, 4);
        for (Box box : List.of(layout.strip(), layout.list(), layout.detail(), layout.text(), layout.frame())) {
            assertTrue(box.width() >= 0 && box.height() >= 0, box.toString());
        }
    }

    @Test
    void theItemDetailStacksFromTheBottomAndStaysInside() {
        Box detail = layout(320, 240, 1).detail();
        ItemDetail parts = TradeLayout.itemDetail(detail, LINE, true);

        assertTrue(inside(detail, parts.name()));
        assertTrue(parts.name().y1() <= parts.tooltip().y0());
        assertTrue(parts.tooltip().y1() <= parts.stepper().label().y0());
        assertTrue(parts.stepper().label().y1() <= parts.receive().y0());
        assertTrue(parts.receive().y1() <= parts.action().y0());
        assertTrue(parts.action().y1() <= parts.junk().y0());
        assertTrue(parts.junk().y1() <= detail.y1() - TradeLayout.PADDING);
        assertTrue(inside(detail, parts.junk()));
    }

    @Test
    void theItemDetailWithoutAJunkRowGivesItsSpaceToTheAction() {
        Box detail = layout(320, 240, 1).detail();
        ItemDetail withJunk = TradeLayout.itemDetail(detail, LINE, true);
        ItemDetail withoutJunk = TradeLayout.itemDetail(detail, LINE, false);

        assertTrue(withoutJunk.action().y1() > withJunk.action().y1());
        assertTrue(withoutJunk.action().y1() <= detail.y1() - TradeLayout.PADDING);
    }

    @Test
    void theStepperLaysOutLeftToRightWithTheResetButtonLast() {
        Box row = new Box(0, 0, 200, 14);
        Stepper stepper = TradeLayout.stepper(row);
        List<Box> parts = List.of(stepper.label(), stepper.minus(), stepper.value(), stepper.plus(), stepper.max(),
                stepper.reset());

        for (int i = 1; i < parts.size(); i++) {
            assertTrue(parts.get(i - 1).x1() <= parts.get(i).x0(), parts.get(i - 1) + " then " + parts.get(i));
        }
        assertEquals(TradeLayout.STEP_GAP, stepper.reset().x0() - stepper.max().x1());
        assertEquals(row.x1(), stepper.reset().x1());
        assertEquals(TradeLayout.RESET_BUTTON_WIDTH, stepper.reset().width());
        assertEquals(row.x0(), stepper.label().x0());
        assertTrue(inside(row, stepper.minus()));
        assertTrue(inside(row, stepper.max()));
        assertTrue(inside(row, stepper.reset()));
    }

    @Test
    void anExperienceSectionsActionSitsDirectlyUnderItsStepperRow() {
        List<ExperienceSection> sections = TradeLayout.experienceSections(EXPERIENCE_DETAIL, 2);

        assertEquals(2, sections.size());
        for (ExperienceSection section : sections) {
            Stepper stepper = section.stepper();
            assertEquals(stepper.label().y1() + TradeLayout.STEP_GAP, section.action().y0());
            assertEquals(stepper.label().x0(), section.action().x0());
            assertEquals(stepper.reset().x1(), section.action().x1());
            assertEquals(TradeLayout.CONTROL_HEIGHT, section.action().height());
        }
    }

    @Test
    void theSecondExperienceSectionStartsASectionGapBelowTheFirst() {
        List<ExperienceSection> sections = TradeLayout.experienceSections(EXPERIENCE_DETAIL, 2);

        assertEquals(sections.get(0).action().y1() + TradeLayout.SECTION_GAP, sections.get(1).stepper().label().y0());
    }

    @Test
    void theExperienceSectionsStayInsideTheDetailsPadding() {
        Box inner = new Box(TradeLayout.PADDING, TradeLayout.PADDING, EXPERIENCE_DETAIL.x1() - TradeLayout.PADDING,
                EXPERIENCE_DETAIL.y1() - TradeLayout.PADDING);
        List<ExperienceSection> sections = TradeLayout.experienceSections(EXPERIENCE_DETAIL, 2);

        for (ExperienceSection section : sections) {
            Stepper stepper = section.stepper();
            for (Box box : List.of(stepper.label(), stepper.minus(), stepper.value(), stepper.plus(), stepper.max(),
                    stepper.reset(), section.action())) {
                assertTrue(inside(inner, box), box.toString());
            }
        }
    }

    @Test
    void noExperienceSectionsForACountOfZero() {
        assertTrue(TradeLayout.experienceSections(EXPERIENCE_DETAIL, 0).isEmpty());
    }

    @Test
    void theTabsFollowEachOtherAlongTheStrip() {
        Box strip = new Box(8, 20, 312, 36);
        List<Box> tabs = TradeLayout.tabs(strip, new int[] {16, 20});

        assertEquals(strip.x0(), tabs.get(0).x0());
        assertEquals(16 + 2 * TradeLayout.TAB_PADDING, tabs.get(0).width());
        assertEquals(tabs.get(0).x1() + TradeLayout.TAB_GAP, tabs.get(1).x0());
        assertEquals(20 + 2 * TradeLayout.TAB_PADDING, tabs.get(1).width());
    }

    @Test
    void theScrollThumbStartsAtTheTopOfTheTrackAtTheFirstRow() {
        assertEquals(Optional.of(new Box(97, 4, 99, 84)), thumb(0, 16));
    }

    @Test
    void theScrollThumbEndsAtTheBottomOfTheTrackAtTheLastRow() {
        assertEquals(Optional.of(new Box(97, 84, 99, 164)), thumb(8, 16));
    }

    @Test
    void theScrollThumbMovesHalfwayWhenTheListIsHalfScrolled() {
        assertEquals(Optional.of(new Box(97, 44, 99, 124)), thumb(4, 16));
    }

    @Test
    void noScrollThumbIsDrawnWhenEveryRowFits() {
        assertEquals(Optional.empty(), thumb(0, 8));
        assertEquals(Optional.empty(), thumb(0, 3));
        assertEquals(Optional.empty(), TradeLayout.scrollThumb(SHOP_LIST, 0, 16, 0));
    }

    @Test
    void theScrollThumbKeepsAtLeastTheMinimumHeightForALongList() {
        assertEquals(Optional.of(new Box(97, 4, 99, 4 + TradeLayout.MIN_SCROLL_THUMB)), thumb(0, 1000));
        assertEquals(Optional.of(new Box(97, 160, 99, 160 + TradeLayout.MIN_SCROLL_THUMB)), thumb(992, 1000));
    }

    @Test
    void aScrollPastTheEndsStopsAtTheTopOrTheBottomOfTheTrack() {
        assertEquals(Optional.of(new Box(97, 84, 99, 164)), thumb(50, 16));
        assertEquals(Optional.of(new Box(97, 4, 99, 84)), thumb(-3, 16));
    }

    /** The experience status's five boxes, top to bottom: summary, bar, preview row, then both rates. */
    private static List<Box> statusBoxes(ExperienceStatus status) {
        List<Box> boxes = new ArrayList<>();
        boxes.add(status.summary());
        boxes.add(status.bar());
        boxes.add(status.preview());
        boxes.addAll(status.rates());
        return boxes;
    }

    @Test
    void theExperienceStatusBoxesShareOneColumnInsideTheList() {
        Box list = new Box(0, 0, 180, 170);
        List<Box> boxes = statusBoxes(TradeLayout.experienceStatus(list, LINE));
        Box first = boxes.get(0);

        for (Box box : boxes) {
            assertEquals(first.x0(), box.x0());
            assertEquals(first.x1(), box.x1());
            assertTrue(inside(list, box), box.toString());
        }
    }

    @Test
    void theExperienceStatusBoxesFollowEachOtherTopToBottomWithoutOverlap() {
        ExperienceStatus status = TradeLayout.experienceStatus(new Box(0, 0, 180, 170), LINE);
        List<Box> boxes = statusBoxes(status);

        assertEquals(5, boxes.size());
        for (int i = 1; i < boxes.size(); i++) {
            assertTrue(boxes.get(i - 1).y1() <= boxes.get(i).y0(), boxes.get(i - 1) + " then " + boxes.get(i));
        }
        assertEquals(TradeLayout.EXPERIENCE_BAR_HEIGHT, status.bar().height());
    }

    @Test
    void allFiveExperienceStatusBoxesFitAListOf170Pixels() {
        Box list = new Box(0, 0, 180, 170);

        for (Box box : statusBoxes(TradeLayout.experienceStatus(list, LINE))) {
            assertTrue(TradeLayout.fitsIn(list, box), box.toString());
        }
    }

    @Test
    void aShortListLeavesOutTheRateLinesThatReachPastItsInnerBottom() {
        // The stack starts 5 below the list's top: 11 + 3 + 5 + 3 + 11 + 6 + 11 ends at 55, and the second rate at 66.
        Box list = new Box(0, 0, 180, 60);
        ExperienceStatus status = TradeLayout.experienceStatus(list, LINE);

        assertTrue(TradeLayout.fitsIn(list, status.summary()));
        assertTrue(TradeLayout.fitsIn(list, status.bar()));
        assertTrue(TradeLayout.fitsIn(list, status.preview()));
        assertTrue(TradeLayout.fitsIn(list, status.rates().get(0)));
        assertFalse(TradeLayout.fitsIn(list, status.rates().get(1)));
    }

    @Test
    void fitsInStopsExactlyAtTheInnerBottomOfTheList() {
        Box list = new Box(0, 0, 180, 170);
        int bottom = list.y1() - TradeLayout.LIST_INSET;

        assertTrue(TradeLayout.fitsIn(list, new Box(6, bottom - LINE, 174, bottom)));
        assertFalse(TradeLayout.fitsIn(list, new Box(6, bottom - LINE + 1, 174, bottom + 1)));
    }

    /**
     * The market status's seven boxes, top to bottom: the summary, the bar and the preview row; the price, chart, fee and quote
     * tolerance lines.
     */
    private static List<Box> marketBoxes(ExperienceMarketStatus status) {
        return List.of(status.summary(), status.bar(), status.preview(), status.price(), status.chart(), status.fee(),
                status.tolerance());
    }

    @Test
    void theMarketStatusBoxesShareOneColumnInsideTheList() {
        Box list = new Box(0, 0, 180, 170);
        List<Box> boxes = marketBoxes(TradeLayout.experienceMarketStatus(list, LINE));
        Box first = boxes.get(0);

        for (Box box : boxes) {
            assertEquals(first.x0(), box.x0());
            assertEquals(first.x1(), box.x1());
            assertTrue(inside(list, box), box.toString());
        }
    }

    @Test
    void theMarketStatusBoxesFollowEachOtherTopToBottomWithoutOverlap() {
        List<Box> boxes = marketBoxes(TradeLayout.experienceMarketStatus(new Box(0, 0, 180, 170), LINE));

        assertEquals(7, boxes.size());
        for (int i = 1; i < boxes.size(); i++) {
            assertTrue(boxes.get(i - 1).y1() <= boxes.get(i).y0(), boxes.get(i - 1) + " then " + boxes.get(i));
        }
    }

    @Test
    void theQuoteToleranceLineSitsDirectlyUnderTheFeeLine() {
        ExperienceMarketStatus status = TradeLayout.experienceMarketStatus(new Box(0, 0, 180, 170), LINE);

        assertEquals(status.fee().y1(), status.tolerance().y0());
        assertEquals(LINE, status.tolerance().height());
        assertEquals(status.fee().x0(), status.tolerance().x0());
        assertEquals(status.fee().x1(), status.tolerance().x1());
        assertFalse(overlaps(status.fee(), status.tolerance()));
    }

    /** Whether two boxes share an area, with their edges touching not counted. */
    private static boolean overlaps(Box a, Box b) {
        return a.x0() < b.x1() && b.x0() < a.x1() && a.y0() < b.y1() && b.y0() < a.y1();
    }

    @Test
    void theMarketChartIs28PixelsHighWithThreePixelsAboveAndBelow() {
        ExperienceMarketStatus status = TradeLayout.experienceMarketStatus(new Box(0, 0, 180, 170), LINE);

        assertEquals(28, status.chart().height());
        assertEquals(TradeLayout.EXPERIENCE_CHART_GAP, status.chart().y0() - status.price().y1());
        assertEquals(TradeLayout.EXPERIENCE_CHART_GAP, status.fee().y0() - status.chart().y1());
    }

    @Test
    void thePriceLineSitsASixPixelGroupGapBelowThePreviewRow() {
        ExperienceMarketStatus status = TradeLayout.experienceMarketStatus(new Box(0, 0, 180, 170), LINE);

        assertEquals(TradeLayout.EXPERIENCE_GROUP_GAP, status.price().y0() - status.preview().y1());
        assertEquals(LINE, status.price().height());
        assertEquals(LINE, status.fee().height());
    }

    @Test
    void everyMarketStatusBoxFitsA170PixelList() {
        Box list = new Box(0, 0, 180, 170);

        for (Box box : marketBoxes(TradeLayout.experienceMarketStatus(list, LINE))) {
            assertTrue(TradeLayout.fitsIn(list, box), box.toString());
        }
    }

    @Test
    void anUpTriangleHasItsTipAtTheTopAndWidensDownwards() {
        assertEquals(List.of(new Box(23, 30, 24, 31), new Box(22, 31, 25, 32), new Box(21, 32, 26, 33),
                new Box(20, 33, 27, 34)), TradeLayout.triangle(20, 30, true));
    }

    @Test
    void aDownTriangleHasItsTipAtTheBottomAndNarrowsDownwards() {
        assertEquals(List.of(new Box(20, 30, 27, 31), new Box(21, 31, 26, 32), new Box(22, 32, 25, 33),
                new Box(23, 33, 24, 34)), TradeLayout.triangle(20, 30, false));
    }

    @Test
    void theChartLineJoinsEachColumnToThePreviousOne() {
        assertEquals(List.of(new Box(10, 100, 11, 101), new Box(11, 100, 12, 106), new Box(12, 105, 13, 106),
                new Box(13, 105, 14, 128)), TradeLayout.chartLine(SMALL_CHART, CHART_ROWS));
    }

    @Test
    void theChartFillRunsFromEachColumnDownToTheBottomEdge() {
        assertEquals(List.of(new Box(10, 100, 11, 128), new Box(11, 105, 12, 128), new Box(12, 105, 13, 128),
                new Box(13, 127, 14, 128)), TradeLayout.chartFill(SMALL_CHART, CHART_ROWS));
    }

    @Test
    void theUsualPriceIsAOnePixelRowAcrossTheWholeChart() {
        assertEquals(new Box(10, 109, 14, 110), TradeLayout.chartRow(SMALL_CHART, 9));
    }

    @Test
    void theChartDotCoversTheRightmostColumnsAroundTheLastValue() {
        assertEquals(new Box(11, 109, 14, 112), TradeLayout.chartDot(SMALL_CHART, 10));
    }

    @Test
    void theChartDotStaysInsideTheChartAtEitherEnd() {
        assertEquals(new Box(11, 100, 14, 103), TradeLayout.chartDot(SMALL_CHART, 0));
        assertEquals(new Box(11, 125, 14, 128), TradeLayout.chartDot(SMALL_CHART, 27));
    }
}
