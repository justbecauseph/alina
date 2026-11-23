package town.lampas.alina.client.trade;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntUnaryOperator;

/**
 * Where the parts of the {@link TradeScreen} go, in GUI pixels. The text box runs along the bottom of the screen, as the
 * dialogue box does: a square headshot frame on the left, then the shop's line and, under it, the budget line. Above it
 * sits the shop panel: a tab strip, then a list on the left and a detail panel on the right. The panel is as wide as the
 * screen allows, up to {@link #MAX_PANEL_WIDTH}, and centred. Pure arithmetic with no client classes, so it is unit tested.
 *
 * @param strip the tab strip; its top is the top of the whole panel
 * @param list the list panel: the sell rows, the offers, or the experience status
 * @param detail the detail panel: the selected row, its quantity stepper and its actions
 * @param text the text box
 * @param frame the headshot square, inside the text box
 * @param textX the left edge of the shop's lines
 * @param textY the top of the shop's first line
 * @param textWidth the width the shop's line wraps to
 * @param textLines how many lines of the shop's line the box holds, at least 1
 * @param budgetY the top of the budget line, under the shop's lines
 * @param lineHeight the distance from one text line to the next
 */
record TradeLayout(
        Box strip,
        Box list,
        Box detail,
        Box text,
        Box frame,
        int textX,
        int textY,
        int textWidth,
        int textLines,
        int budgetY,
        int lineHeight) {

    /** The space between the screen's edges and the panel and text box. */
    static final int MARGIN = 8;
    /** The widest the panel and the text box get, so a large window does not stretch them. */
    static final int MAX_PANEL_WIDTH = 400;
    /** The tallest the list and detail panels get, so a tall window does not stretch them. */
    static final int MAX_BODY_HEIGHT = 170;
    /** The height of the tab strip. */
    static final int STRIP_HEIGHT = 16;
    /** The space between the tab strip and the panels under it. */
    static final int STRIP_BODY_GAP = 4;
    /** The space between the panels and the text box. */
    static final int BODY_TEXT_GAP = 6;
    /** The share of the panel's width the list takes, in percent. */
    static final int LIST_PERCENT = 45;
    /** The space between the list and the detail panel. */
    static final int PANEL_GAP = 4;
    /** The space between a panel's edge and what is inside it. */
    static final int PADDING = 6;
    /** The space between a list panel's edge and its rows. */
    static final int LIST_INSET = 4;
    /** The height of one list row: an item icon, and two lines of text beside it. */
    static final int ROW_HEIGHT = 20;
    /** The width of the list's scroll thumb. */
    static final int SCROLL_THUMB_WIDTH = 2;
    /** The least height of the list's scroll thumb. */
    static final int MIN_SCROLL_THUMB = 4;
    /** The side of an item icon in a list row. */
    static final int ICON_SIZE = 16;
    /** How far an item icon sits in from its row's corner. */
    static final int ICON_OFFSET = 2;
    /** The space between an item icon and the row's text. */
    static final int ICON_GAP = 4;
    /** The height of a button, a stepper's buttons and a stepper's row. */
    static final int CONTROL_HEIGHT = 16;
    /** The width of a stepper's minus and plus buttons. */
    static final int CONTROL_WIDTH = 16;
    /** The width of a stepper's max button. */
    static final int MAX_BUTTON_WIDTH = 30;
    /** The width of a stepper's reset button: the word {@code reset} in the game font, with 4 pixels on each side. */
    static final int RESET_BUTTON_WIDTH = 36;
    /** The width of a stepper's value. */
    static final int VALUE_WIDTH = 36;
    /** The space between the parts of a stepper row. */
    static final int STEP_GAP = 2;
    /** The space between the parts of the detail panel. */
    static final int GAP = 4;
    /** The space between the sections of the experience detail. */
    static final int SECTION_GAP = 8;
    /** The height of the experience status's progress bar. */
    static final int EXPERIENCE_BAR_HEIGHT = 5;
    /** The space between the experience status's progress bar and the lines above and below it. */
    static final int EXPERIENCE_BAR_GAP = 3;
    /** The space between the groups of the experience status: the progress, and the rates or the market. */
    static final int EXPERIENCE_GROUP_GAP = 6;
    /** The space between a market's chart and the price and fee lines above and below it. */
    static final int EXPERIENCE_CHART_GAP = 3;
    /** The height of a market's chart, in GUI pixels. */
    static final int MARKET_CHART_HEIGHT = 28;
    /** The side of the dot on a market's chart's last value. */
    static final int CHART_DOT_SIZE = 3;
    /** The width of a trend triangle: the widest of its rows. */
    static final int TRIANGLE_WIDTH = 7;
    /** The height of a trend triangle, in rows of one pixel. */
    static final int TRIANGLE_HEIGHT = 4;
    /** The space between a trend triangle and the text after it. */
    static final int TRIANGLE_TEXT_GAP = 2;
    /** The space between a tab's label and its frame, on each side. */
    static final int TAB_PADDING = 6;
    /** The space between two tabs. */
    static final int TAB_GAP = 4;
    /** The space between the headshot frame and the shop's lines. */
    static final int FRAME_GAP = 8;
    /** The space between the shop's lines and the budget line, in addition to a line's height. */
    static final int BUDGET_GAP = 2;
    /** The largest headshot square, in GUI pixels. */
    static final int HEADSHOT_SIZE = 48;
    /** The smallest headshot square, on a short window. */
    static final int MIN_HEADSHOT_SIZE = 24;
    /** The most lines the shop's line shows; any beyond them are not drawn. */
    static final int MAX_TEXT_LINES = 4;

    /** A rectangle in GUI pixels, from its left and top edges to its right and bottom edges, which are exclusive. */
    record Box(int x0, int y0, int x1, int y1) {
        int width() {
            return x1 - x0;
        }

        int height() {
            return y1 - y0;
        }

        boolean contains(double x, double y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    /**
     * The quantity stepper's parts: the label, then the minus button, the value, the plus button, the max button and the
     * reset button, in that order from left to right.
     */
    record Stepper(Box label, Box minus, Box value, Box plus, Box max, Box reset) {}

    /**
     * The item detail's parts, top to bottom: the name line, the tooltip area, the stepper, the line that says what the
     * selection costs or pays, the action button, and the sell-junk button when the panel has one (an empty box otherwise).
     */
    record ItemDetail(Box name, Box tooltip, Stepper stepper, Box receive, Box action, Box junk) {}

    /** One experience section: a stepper in Aur, and under it the action button that trades the amount. */
    record ExperienceSection(Stepper stepper, Box action) {}

    /**
     * The boxes of the experience status panel, top to bottom: the summary line, the progress bar, the preview row, then the
     * two rate lines. A box that reaches past the panel's inner bottom is not drawn.
     */
    record ExperienceStatus(Box summary, Box bar, Box preview, List<Box> rates) {}

    /**
     * The boxes of the experience status panel of a shop that trades against the market, top to bottom: the summary line, the
     * progress bar and the preview row; the price line, the chart and the fee line. The chart is {@link #MARKET_CHART_HEIGHT}
     * high. A box that reaches past the panel's inner bottom is not drawn.
     */
    record ExperienceMarketStatus(Box summary, Box bar, Box preview, Box price, Box chart, Box fee) {}

    /** The top of the whole panel, which the HUD keeps the chat above. */
    int top() {
        return strip.y0();
    }

    /**
     * The layout for a window of {@code screenWidth} by {@code screenHeight} GUI pixels.
     *
     * @param lineHeight the distance from one text line to the next
     * @param linesForWidth how many lines the shop's line wraps to at a width
     */
    static TradeLayout compute(int screenWidth, int screenHeight, int lineHeight, IntUnaryOperator linesForWidth) {
        int panelWidth = Math.max(1, Math.min(screenWidth - 2 * MARGIN, MAX_PANEL_WIDTH));
        int left = (screenWidth - panelWidth) / 2;
        int right = left + panelWidth;
        int frameSize = Math.clamp(screenHeight / 4, MIN_HEADSHOT_SIZE, HEADSHOT_SIZE);
        int textX = left + PADDING + frameSize + FRAME_GAP;
        int textWidth = Math.max(1, right - PADDING - textX);
        int lines = Math.clamp(linesForWidth.applyAsInt(textWidth), 1, MAX_TEXT_LINES);
        int textBlock = lines * lineHeight + BUDGET_GAP + lineHeight;
        int textHeight = 2 * PADDING + Math.max(frameSize, textBlock);
        int textBottom = screenHeight - MARGIN;
        int textTop = textBottom - textHeight;
        int bodyBottom = textTop - BODY_TEXT_GAP;
        int bodyTop = Math.max(bodyBottom - MAX_BODY_HEIGHT, MARGIN + STRIP_HEIGHT + STRIP_BODY_GAP);
        int bodyHeight = bodyBottom - bodyTop;
        int listWidth = panelWidth * LIST_PERCENT / 100;
        Box strip = sized(left, bodyTop - STRIP_BODY_GAP - STRIP_HEIGHT, panelWidth, STRIP_HEIGHT);
        Box list = sized(left, bodyTop, listWidth, bodyHeight);
        Box detail = sized(left + listWidth + PANEL_GAP, bodyTop, panelWidth - listWidth - PANEL_GAP, bodyHeight);
        Box text = new Box(left, textTop, right, textBottom);
        Box frame = sized(left + PADDING, textTop + PADDING, frameSize, frameSize);
        int textY = textTop + PADDING;
        int budgetY = textY + lines * lineHeight + BUDGET_GAP;
        return new TradeLayout(strip, list, detail, text, frame, textX, textY, textWidth, lines, budgetY, lineHeight);
    }

    /** The tab frames along {@code strip}, one per label width, left to right. */
    static List<Box> tabs(Box strip, int[] labelWidths) {
        List<Box> boxes = new ArrayList<>(labelWidths.length);
        int x = strip.x0();
        for (int labelWidth : labelWidths) {
            int width = labelWidth + 2 * TAB_PADDING;
            boxes.add(sized(x, strip.y0(), width, strip.height()));
            x += width + TAB_GAP;
        }
        return List.copyOf(boxes);
    }

    /** How many list rows fit in {@code list}, whole rows only. */
    static int visibleRows(Box list) {
        return Math.max(0, (list.height() - 2 * LIST_INSET) / ROW_HEIGHT);
    }

    /**
     * The scroll thumb of a list that shows {@code visible} of {@code total} rows from row {@code scroll}, in the list's
     * right inset. Empty when every row fits.
     */
    static Optional<Box> scrollThumb(Box list, int scroll, int total, int visible) {
        if (visible <= 0 || total <= visible) {
            return Optional.empty();
        }
        int trackTop = list.y0() + LIST_INSET;
        int trackHeight = visible * ROW_HEIGHT;
        int thumbHeight = Math.clamp(trackHeight * visible / total, MIN_SCROLL_THUMB, trackHeight);
        int maxScroll = total - visible;
        int thumbTop = trackTop + (trackHeight - thumbHeight) * Math.clamp(scroll, 0, maxScroll) / maxScroll;
        int x1 = list.x1() - 1;
        return Optional.of(new Box(x1 - SCROLL_THUMB_WIDTH, thumbTop, x1, thumbTop + thumbHeight));
    }

    /** The box of list row {@code index}, counted from the top of the list's visible rows. */
    static Box row(Box list, int index) {
        return sized(list.x0() + LIST_INSET, list.y0() + LIST_INSET + index * ROW_HEIGHT,
                list.width() - 2 * LIST_INSET, ROW_HEIGHT);
    }

    /**
     * The item detail in {@code detail}, stacked from the bottom: the action (and, when {@code junkRow} is set, the junk
     * button under the action), the line above it, and the stepper above that. The tooltip takes what is left under the
     * name.
     */
    static ItemDetail itemDetail(Box detail, int lineHeight, boolean junkRow) {
        Box inner = inset(detail, PADDING);
        Box junk = junkRow
                ? sized(inner.x0(), inner.y1() - CONTROL_HEIGHT, inner.width(), CONTROL_HEIGHT)
                : sized(inner.x0(), inner.y1(), inner.width(), 0);
        int actionBottom = junkRow ? junk.y0() - GAP : inner.y1();
        Box action = sized(inner.x0(), actionBottom - CONTROL_HEIGHT, inner.width(), CONTROL_HEIGHT);
        Box receive = sized(inner.x0(), action.y0() - GAP - lineHeight, inner.width(), lineHeight);
        Box stepperRow = sized(inner.x0(), receive.y0() - GAP - CONTROL_HEIGHT, inner.width(), CONTROL_HEIGHT);
        Box name = sized(inner.x0(), inner.y0(), inner.width(), lineHeight);
        Box tooltip = new Box(inner.x0(), name.y1() + GAP, inner.x1(), Math.max(name.y1() + GAP, stepperRow.y0() - GAP));
        return new ItemDetail(name, tooltip, stepper(stepperRow), receive, action, junk);
    }

    /**
     * The stepper in {@code row}: the reset, max, plus, value and minus buttons on the right, in that order from the right
     * edge, and the label in what is left on the left.
     */
    static Stepper stepper(Box row) {
        Box reset = sized(row.x1() - RESET_BUTTON_WIDTH, row.y0(), RESET_BUTTON_WIDTH, row.height());
        Box max = sized(reset.x0() - STEP_GAP - MAX_BUTTON_WIDTH, row.y0(), MAX_BUTTON_WIDTH, row.height());
        Box plus = sized(max.x0() - STEP_GAP - CONTROL_WIDTH, row.y0(), CONTROL_WIDTH, row.height());
        Box value = sized(plus.x0() - STEP_GAP - VALUE_WIDTH, row.y0(), VALUE_WIDTH, row.height());
        Box minus = sized(value.x0() - STEP_GAP - CONTROL_WIDTH, row.y0(), CONTROL_WIDTH, row.height());
        Box label = new Box(row.x0(), row.y0(), Math.max(row.x0(), minus.x0() - GAP), row.y1());
        return new Stepper(label, minus, value, plus, max, reset);
    }

    /**
     * The first {@code count} experience sections in {@code detail}, stacked from the top: a stepper row, then the action
     * button {@link #STEP_GAP} under it, the full width of the row. The next section starts {@link #SECTION_GAP} under the
     * button.
     */
    static List<ExperienceSection> experienceSections(Box detail, int count) {
        Box inner = inset(detail, PADDING);
        List<ExperienceSection> sections = new ArrayList<>(count);
        int y = inner.y0();
        for (int i = 0; i < count; i++) {
            Box stepperRow = sized(inner.x0(), y, inner.width(), CONTROL_HEIGHT);
            Box action = sized(inner.x0(), stepperRow.y1() + STEP_GAP, inner.width(), CONTROL_HEIGHT);
            sections.add(new ExperienceSection(stepper(stepperRow), action));
            y = action.y1() + SECTION_GAP;
        }
        return List.copyOf(sections);
    }

    /**
     * The experience status in {@code list}, stacked from the top of its inner area: the summary line, the progress bar, the
     * preview row, then the two rate lines. Each text line is {@code lineHeight} high, and the bar is
     * {@link #EXPERIENCE_BAR_HEIGHT}. The boxes are not checked against the list; see {@link #fitsIn}.
     */
    static ExperienceStatus experienceStatus(Box list, int lineHeight) {
        int x0 = list.x0() + LIST_INSET + ICON_OFFSET;
        int width = list.x1() - LIST_INSET - ICON_OFFSET - x0;
        int y = list.y0() + LIST_INSET + 1;
        Box summary = sized(x0, y, width, lineHeight);
        Box bar = sized(x0, summary.y1() + EXPERIENCE_BAR_GAP, width, EXPERIENCE_BAR_HEIGHT);
        Box preview = sized(x0, bar.y1() + EXPERIENCE_BAR_GAP, width, lineHeight);
        Box rateSell = sized(x0, preview.y1() + EXPERIENCE_GROUP_GAP, width, lineHeight);
        Box rateBuy = sized(x0, rateSell.y1(), width, lineHeight);
        return new ExperienceStatus(summary, bar, preview, List.of(rateSell, rateBuy));
    }

    /**
     * The experience status of a shop that trades against the market, in {@code list}: the progress group as
     * {@link #experienceStatus} lays it out, then the price line {@link #EXPERIENCE_GROUP_GAP} under the preview row, the
     * chart with {@link #EXPERIENCE_CHART_GAP} above and below it, and the fee line. The boxes are not checked against the
     * list; see {@link #fitsIn}.
     */
    static ExperienceMarketStatus experienceMarketStatus(Box list, int lineHeight) {
        ExperienceStatus progress = experienceStatus(list, lineHeight);
        int x0 = progress.summary().x0();
        int width = progress.summary().width();
        Box price = sized(x0, progress.preview().y1() + EXPERIENCE_GROUP_GAP, width, lineHeight);
        Box chart = sized(x0, price.y1() + EXPERIENCE_CHART_GAP, width, MARKET_CHART_HEIGHT);
        Box fee = sized(x0, chart.y1() + EXPERIENCE_CHART_GAP, width, lineHeight);
        return new ExperienceMarketStatus(progress.summary(), progress.bar(), progress.preview(), price, chart, fee);
    }

    /**
     * The line of a market's chart: one box per column of {@code rows}, each one pixel wide, from the row of the column before
     * to the row of its own column, so the line has no gaps. The first column is one pixel. {@code rows} count from the top of
     * {@code chart}, as {@link Exchange#chartYs} gives them.
     */
    static List<Box> chartLine(Box chart, int[] rows) {
        List<Box> boxes = new ArrayList<>(rows.length);
        for (int column = 0; column < rows.length; column++) {
            int from = column == 0 ? rows[0] : rows[column - 1];
            int top = Math.min(from, rows[column]);
            int bottom = Math.max(from, rows[column]);
            int x = chart.x0() + column;
            boxes.add(new Box(x, chart.y0() + top, x + 1, chart.y0() + bottom + 1));
        }
        return List.copyOf(boxes);
    }

    /**
     * The fill under a market's chart's line: one box per column of {@code rows}, one pixel wide, from the column's row down
     * to the chart's bottom edge.
     */
    static List<Box> chartFill(Box chart, int[] rows) {
        List<Box> boxes = new ArrayList<>(rows.length);
        for (int column = 0; column < rows.length; column++) {
            int x = chart.x0() + column;
            boxes.add(new Box(x, chart.y0() + rows[column], x + 1, chart.y1()));
        }
        return List.copyOf(boxes);
    }

    /** The usual price's line across a market's chart: a box across the chart's width, one pixel high at {@code row}. */
    static Box chartRow(Box chart, int row) {
        return sized(chart.x0(), chart.y0() + row, chart.width(), 1);
    }

    /**
     * The dot on a market's chart's last value: a {@link #CHART_DOT_SIZE} square in the chart's rightmost
     * {@link #CHART_DOT_SIZE} columns, centred on {@code row} as far as the chart's height allows.
     */
    static Box chartDot(Box chart, int row) {
        int top = Math.clamp(row - CHART_DOT_SIZE / 2, 0, Math.max(0, chart.height() - CHART_DOT_SIZE));
        return sized(chart.x1() - CHART_DOT_SIZE, chart.y0() + top, CHART_DOT_SIZE, CHART_DOT_SIZE);
    }

    /**
     * The rows of a trend triangle {@link #TRIANGLE_WIDTH} wide and {@link #TRIANGLE_HEIGHT} tall, with its top-left corner at
     * ({@code x}, {@code y}). Each row is one pixel high, centred on the middle column. An up triangle has its tip at the top,
     * with rows of 1, 3, 5 and 7 pixels; a down triangle has its tip at the bottom, with rows of 7, 5, 3 and 1.
     */
    static List<Box> triangle(int x, int y, boolean up) {
        List<Box> rows = new ArrayList<>(TRIANGLE_HEIGHT);
        for (int row = 0; row < TRIANGLE_HEIGHT; row++) {
            int width = up ? 2 * row + 1 : TRIANGLE_WIDTH - 2 * row;
            rows.add(sized(x + (TRIANGLE_WIDTH - width) / 2, y + row, width, 1));
        }
        return List.copyOf(rows);
    }

    /**
     * Whether {@code box} lies wholly inside {@code list}'s inner area, which is {@code list} inset by {@link #LIST_INSET} on
     * every side. A box that fits is drawn.
     */
    static boolean fitsIn(Box list, Box box) {
        return box.x0() >= list.x0() + LIST_INSET && box.x1() <= list.x1() - LIST_INSET
                && box.y0() >= list.y0() + LIST_INSET && box.y1() <= list.y1() - LIST_INSET;
    }

    private static Box inset(Box box, int by) {
        return sized(box.x0() + by, box.y0() + by, box.width() - 2 * by, box.height() - 2 * by);
    }

    private static Box sized(int x, int y, int width, int height) {
        return new Box(x, y, x + Math.max(0, width), y + Math.max(0, height));
    }
}
