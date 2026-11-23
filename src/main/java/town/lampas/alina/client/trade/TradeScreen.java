package town.lampas.alina.client.trade;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.IntBinaryOperator;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import town.lampas.alina.client.trade.TradeLayout.Box;
import town.lampas.alina.client.trade.TradeLayout.ExperienceSection;
import town.lampas.alina.client.trade.TradeLayout.ExperienceMarketStatus;
import town.lampas.alina.client.trade.TradeLayout.ExperienceStatus;
import town.lampas.alina.client.trade.TradeLayout.ItemDetail;
import town.lampas.alina.client.trade.TradeLayout.Stepper;
import town.lampas.alina.trade.network.ExperienceView;
import town.lampas.alina.trade.network.MarketView;
import town.lampas.alina.trade.network.OfferRow;
import town.lampas.alina.trade.network.SellRow;
import town.lampas.alina.trade.network.TradeBuyPayload;
import town.lampas.alina.trade.network.TradeDismissPayload;
import town.lampas.alina.trade.network.TradeExperiencePayload;
import town.lampas.alina.trade.network.TradeOpenPayload;
import town.lampas.alina.trade.network.TradeSellJunkPayload;
import town.lampas.alina.trade.network.TradeSellPayload;
import town.lampas.alina.trade.network.TradeView;
import town.lampas.alina.trade.pricing.ExperiencePoints;
import town.lampas.haruhi.client.api.DialogueStyle;
import town.lampas.haruhi.client.api.DialogueStyleScreen;

/**
 * The shopkeeper's menu in the visual-novel style. A text box runs along the bottom with the shop's line, a budget line and
 * the player's Aur total; above it, a tab strip, a list and a detail panel. The screen draws no background, so the world shows behind it.
 *
 * <p>The server is the only authority. The screen shows the last {@link TradeView} it was sent, asks for the trade the
 * player picks, and never predicts a result. While a request waits for the server's result, or the view says a settlement
 * is under way, the action buttons are dim and ignore clicks. Escape sends one dismiss and closes.
 */
public final class TradeScreen extends Screen implements DialogueStyleScreen {
    /** A list row's fill, and the fill of a selected row. The values are the screen's own, not the dialogue's. */
    private static final int ROW_FILL = 0x50000000;
    private static final int SELECTED_FILL = 0xB0304060;
    private static final int HOVER_FILL = 0x40FFFFFF;
    private static final int DISABLED_FILL = 0x80000000;
    /** The second line of the text box, which is dimmer than the shop's line. */
    private static final int NOTE_TEXT = 0xFFB8B09E;
    /** The experience bar's track, and the part of the track that is done. */
    private static final int BAR_TRACK = 0xFF202020;
    private static final int BAR_FILL = 0xFF7EFC20;
    /** The triangle and the text of a level or a price that went up, and of one that went down. */
    private static final int TREND_UP = 0xFF7EFC20;
    private static final int TREND_DOWN = 0xFFE06C5A;
    /** The market chart: the usual price's line, the price's line, the fill under the price's line, and the dot on the last value. */
    private static final int CHART_USUAL = 0x80B8B09E;
    private static final int CHART_LINE = 0xFFFFD27F;
    private static final int CHART_FILL = 0x30FFD27F;
    private static final int CHART_DOT = 0xFFFFFFFF;
    /** The space between two text lines, in addition to the font's height. */
    private static final int LINE_GAP = 2;
    /** The space between a row's count, its limit and its price. */
    private static final int ROW_TEXT_GAP = 6;

    private final int sessionId;
    private final Component shopName;
    private final OptionalInt speakerEntityId;
    private Component line;
    private TradeView view;
    private TradeTab tab;
    private TradeLayout layout;
    /** Set by a request sent, cleared by the next result. */
    private boolean awaitingResult;
    private boolean dismissed;
    private boolean junkArmed;
    /** The first list row on screen. */
    private int listScroll;
    /** The selected sell row's slot, or null. */
    private Integer sellSlot;
    private int sellQuantity;
    /** The selected offer's id, or null. */
    private String buyOfferId;
    private int buyQuantity = 1;
    private int experienceSellAur = 1;
    private int experienceBuyAur = 1;
    /** What the last frame drew that a click can hit, in the order it was drawn. */
    private final List<Control> controls = new ArrayList<>();

    /** What a left click does; {@code shift} says whether Shift was held, which steps a quantity by ten. */
    @FunctionalInterface
    private interface ClickAction {
        void run(boolean shift);
    }

    /** A clickable area from the last frame: its place, whether a click acts, and what a click does. */
    private record Control(Box box, boolean enabled, ClickAction action) {}

    /**
     * How a stepper reads its quantity, moves it by a step count (clamped by the quantity's rules), stores it, and the value
     * its reset button sets. The steps are negative to go down, and {@link Quantity#ALL} for the max button.
     */
    private record Stepping(IntSupplier get, IntBinaryOperator move, IntConsumer set, int reset) {}

    public TradeScreen(TradeOpenPayload open) {
        super(open.shopName());
        this.sessionId = open.sessionId();
        this.shopName = open.shopName();
        this.speakerEntityId = open.speakerEntityId();
        this.line = open.line();
        this.view = open.view();
        this.tab = TradeTab.available(view).stream().findFirst().orElse(TradeTab.SELL);
        reconcile();
    }

    /** The server's id for the trade on this screen. */
    public int sessionId() {
        return sessionId;
    }

    /** Replaces the view, keeping the selection where the same row is still there. Client thread only. */
    void showView(TradeView next) {
        view = next;
        junkArmed = false;
        reconcile();
    }

    /** Puts the server's line in the text box, and clears the wait for a result. Client thread only. */
    void showResult(Component text) {
        line = text;
        awaitingResult = false;
        relayout();
    }

    @Override
    protected void init() {
        relayout();
    }

    @Override
    public OptionalInt hudBoxTop() {
        return layout == null ? OptionalInt.empty() : OptionalInt.of(layout.top());
    }

    private void relayout() {
        if (width <= 0 || height <= 0) {
            return;
        }
        layout = TradeLayout.compute(width, height, lineHeight(), textWidth -> font.split(line, textWidth).size());
    }

    private int lineHeight() {
        return font.lineHeight + LINE_GAP;
    }

    /** Sets the selection for the view: the selected row when it is still there, else the first row; quantities clamped. */
    private void reconcile() {
        tab = Selection.keep(tab, TradeTab.available(view)).orElse(TradeTab.SELL);

        Integer previousSlot = sellSlot;
        sellSlot = Selection.keep(previousSlot, view.sellRows().stream().map(SellRow::slot).toList()).orElse(null);
        boolean sameSlot = Objects.equals(previousSlot, sellSlot);
        sellQuantity = selectedSell()
                .map(row -> Quantity.sell(sameSlot ? sellQuantity : 0, 0, row.per(), row.maxCount()))
                .orElse(0);

        String previousId = buyOfferId;
        buyOfferId = Selection.keep(previousId, view.offers().stream().map(OfferRow::id).toList()).orElse(null);
        boolean sameId = Objects.equals(previousId, buyOfferId);
        buyQuantity = selectedOffer()
                .map(offer -> Quantity.count(sameId ? buyQuantity : 1, 0, offer.maxCount()))
                .orElse(1);

        view.experience().ifPresent(experience -> {
            experienceSellAur = Quantity.sell(experienceSellAur, 0, Exchange.experienceStep(experience, true),
                    experience.maxSellAur());
            experienceBuyAur = Quantity.sell(experienceBuyAur, 0, Exchange.experienceStep(experience, false),
                    experience.maxBuyAur());
        });
    }

    private Optional<SellRow> selectedSell() {
        if (sellSlot == null) {
            return Optional.empty();
        }
        return view.sellRows().stream().filter(row -> row.slot() == sellSlot).findFirst();
    }

    private Optional<OfferRow> selectedOffer() {
        return view.offers().stream().filter(offer -> offer.id().equals(buyOfferId)).findFirst();
    }

    private void selectTab(TradeTab next) {
        if (next == tab) {
            return;
        }
        tab = next;
        listScroll = 0;
        junkArmed = false;
    }

    private void selectSell(SellRow row) {
        sellSlot = row.slot();
        sellQuantity = Quantity.sell(0, 0, row.per(), row.maxCount());
    }

    private void selectOffer(OfferRow offer) {
        buyOfferId = offer.id();
        buyQuantity = Quantity.count(1, 0, offer.maxCount());
    }

    private boolean busy() {
        return awaitingResult || view.busy();
    }

    /** Sends a request and waits for its result. Nothing is sent while one is already waiting, or the client cannot send. */
    private void sendRequest(CustomPacketPayload payload) {
        if (busy() || !ClientPlayNetworking.canSend(payload.type())) {
            return;
        }
        awaitingResult = true;
        ClientPlayNetworking.send(payload);
    }

    private int rowCount() {
        return switch (tab) {
            case BUY -> view.offers().size();
            case SELL -> view.sellRows().size();
            case EXPERIENCE -> 0;
        };
    }

    private boolean listScrollable() {
        return layout != null && rowCount() > TradeLayout.visibleRows(layout.list());
    }

    private void scrollList(int rows) {
        listScroll = Selection.scroll(listScroll + rows, rowCount(), TradeLayout.visibleRows(layout.list()));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        controls.clear();
        if (layout == null) {
            return;
        }
        drawTextBox(graphics);
        drawStrip(graphics, mouseX, mouseY);
        switch (tab) {
            case BUY -> {
                drawOfferRows(graphics, mouseX, mouseY);
                drawOfferDetail(graphics, mouseX, mouseY);
            }
            case SELL -> {
                drawSellRows(graphics, mouseX, mouseY);
                drawSellDetail(graphics, mouseX, mouseY);
            }
            case EXPERIENCE -> {
                drawExperienceStatus(graphics);
                drawExperienceDetail(graphics, mouseX, mouseY);
            }
        }
    }

    /** No dimmed background, so the world shows behind the screen. Only the HUD's deferred subtitles still draw. */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        minecraft.gui.hud.extractDeferredSubtitles();
    }

    /**
     * The shop's lines, then the note line under them: the player's Aur total at the right on every tab, and the budget
     * line to the left of the total when the shop buys from players.
     */
    private void drawTextBox(GuiGraphicsExtractor graphics) {
        Box text = layout.text();
        drawPanel(graphics, text);
        Box frame = layout.frame();
        DialogueStyle.drawHeadshot(graphics, speaker(), frame.x0(), frame.y0(), frame.x1(), frame.y1());
        List<FormattedCharSequence> parts = font.split(line, layout.textWidth());
        int y = layout.textY();
        for (FormattedCharSequence part : parts.subList(0, Math.min(parts.size(), layout.textLines()))) {
            graphics.text(font, part, layout.textX(), y, DialogueStyle.TEXT);
            y += layout.lineHeight();
        }
        Component total = Component.translatableWithFallback("alina.trade.screen.aur_total", "%s Aur", view.aur());
        int totalX = text.x1() - TradeLayout.PADDING - font.width(total);
        graphics.text(font, total, totalX, layout.budgetY(), DialogueStyle.NAME_TEXT);
        if (view.selling()) {
            Component budget = Component.translatableWithFallback("alina.trade.screen.budget_left",
                    "%s can pay %s more Aur today.", shopName, view.budgetLeft());
            drawLine(graphics, budget, new Box(layout.textX(), layout.budgetY(), totalX - ROW_TEXT_GAP,
                    layout.budgetY() + font.lineHeight), NOTE_TEXT);
        }
    }

    /** The speaker as a loaded living entity, or null when the speaker is not in the level. */
    private LivingEntity speaker() {
        Minecraft minecraft = Minecraft.getInstance();
        if (speakerEntityId.isEmpty() || minecraft.level == null) {
            return null;
        }
        Entity entity = minecraft.level.getEntity(speakerEntityId.getAsInt());
        return entity instanceof LivingEntity living ? living : null;
    }

    private void drawStrip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        List<TradeTab> tabs = TradeTab.available(view);
        int[] widths = tabs.stream().mapToInt(t -> font.width(tabLabel(t))).toArray();
        List<Box> boxes = TradeLayout.tabs(layout.strip(), widths);
        for (int i = 0; i < tabs.size(); i++) {
            TradeTab current = tabs.get(i);
            Box box = boxes.get(i);
            boolean selected = current == tab;
            boolean hovered = box.contains(mouseX, mouseY);
            drawPanel(graphics, box);
            if (selected) {
                fill(graphics, box, SELECTED_FILL);
            }
            if (hovered) {
                fill(graphics, box, HOVER_FILL);
            }
            int color = selected ? DialogueStyle.CHOICE_HOVER_TEXT : DialogueStyle.CHOICE_TEXT;
            drawCentered(graphics, tabLabel(current), box, color);
            controls.add(new Control(box, true, shift -> selectTab(current)));
        }
    }

    private static Component tabLabel(TradeTab tab) {
        return switch (tab) {
            case BUY -> Component.translatableWithFallback("alina.trade.screen.tab.buy", "Buy");
            case SELL -> Component.translatableWithFallback("alina.trade.screen.tab.sell", "Sell");
            case EXPERIENCE -> Component.translatableWithFallback("alina.trade.screen.tab.experience", "Experience");
        };
    }

    private static Component countLabel(int count) {
        return Component.translatableWithFallback("alina.trade.screen.count", "x%s", count);
    }

    private static Component leftLabel(int left) {
        return Component.translatableWithFallback("alina.trade.screen.left", "%s left", left);
    }

    private static Component quantityLabel() {
        return Component.translatableWithFallback("alina.trade.screen.quantity", "Quantity");
    }

    private void drawOfferRows(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Box list = layout.list();
        drawPanel(graphics, list);
        List<OfferRow> offers = view.offers();
        int visible = TradeLayout.visibleRows(list);
        listScroll = Selection.scroll(listScroll, offers.size(), visible);
        for (int i = listScroll; i < Math.min(offers.size(), listScroll + visible); i++) {
            OfferRow offer = offers.get(i);
            Box row = TradeLayout.row(list, i - listScroll);
            boolean hovered = row.contains(mouseX, mouseY);
            boolean locked = offer.locked().isPresent();
            drawRowFrame(graphics, row, offer.id().equals(buyOfferId), hovered);
            Optional<Component> count = offer.stack().getCount() > 1
                    ? Optional.of(countLabel(offer.stack().getCount()))
                    : Optional.empty();
            Optional<Component> left = offer.limitLeft() >= 0
                    ? Optional.of(leftLabel(offer.limitLeft()))
                    : Optional.empty();
            drawRow(graphics, row, offer.stack(), count, left, PriceLabel.unit(offer.price(), 1),
                    locked ? DialogueStyle.CHOICE_LOCKED_TEXT : DialogueStyle.TEXT);
            if (hovered) {
                graphics.setTooltipForNextFrame(font, offer.stack(), mouseX, mouseY);
            }
            controls.add(new Control(row, true, shift -> selectOffer(offer)));
        }
        drawScrollThumb(graphics, list, offers.size());
    }

    private void drawSellRows(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Box list = layout.list();
        drawPanel(graphics, list);
        List<SellRow> rows = view.sellRows();
        if (rows.isEmpty()) {
            Component nothing = Component.translatableWithFallback("alina.trade.screen.nothing_to_sell",
                    "Nothing to sell here.");
            drawLine(graphics, nothing, TradeLayout.row(list, 0), DialogueStyle.TEXT);
            return;
        }
        int visible = TradeLayout.visibleRows(list);
        listScroll = Selection.scroll(listScroll, rows.size(), visible);
        for (int i = listScroll; i < Math.min(rows.size(), listScroll + visible); i++) {
            SellRow row = rows.get(i);
            Box box = TradeLayout.row(list, i - listScroll);
            boolean hovered = box.contains(mouseX, mouseY);
            boolean sellable = row.maxCount() > 0;
            drawRowFrame(graphics, box, sellSlot != null && sellSlot == row.slot(), hovered);
            drawRow(graphics, box, row.stack(), Optional.of(countLabel(row.stack().getCount())), Optional.empty(),
                    PriceLabel.unit(row.aur(), row.per()), sellable ? DialogueStyle.TEXT : DialogueStyle.CHOICE_LOCKED_TEXT);
            if (hovered) {
                graphics.setTooltipForNextFrame(font, row.stack(), mouseX, mouseY);
            }
            controls.add(new Control(box, true, shift -> selectSell(row)));
        }
        drawScrollThumb(graphics, list, rows.size());
    }

    /** The list's scroll thumb at its right edge, when its {@code total} rows do not all fit in the list. */
    private void drawScrollThumb(GuiGraphicsExtractor graphics, Box list, int total) {
        TradeLayout.scrollThumb(list, listScroll, total, TradeLayout.visibleRows(list))
                .ifPresent(thumb -> fill(graphics, thumb, DialogueStyle.BOX_EDGE));
    }

    /**
     * An icon, the item's name on the first line, and on the second line the optional count and limit on the left and the
     * price on the right.
     */
    private void drawRow(GuiGraphicsExtractor graphics, Box row, ItemStack stack, Optional<Component> count,
                         Optional<Component> limit, Component price, int color) {
        graphics.item(stack, row.x0() + TradeLayout.ICON_OFFSET, row.y0() + TradeLayout.ICON_OFFSET);
        int textX = row.x0() + TradeLayout.ICON_OFFSET + TradeLayout.ICON_SIZE + TradeLayout.ICON_GAP;
        int textRight = row.x1() - TradeLayout.ICON_OFFSET;
        int secondY = row.y0() + font.lineHeight + 1;
        Box name = new Box(textX, row.y0() + 1, textRight, row.y0() + 1 + font.lineHeight);
        drawLine(graphics, stack.getHoverName(), name, color);
        int priceX = textRight - font.width(price);
        graphics.text(font, price, priceX, secondY, color);
        int x = textX;
        if (count.isPresent()) {
            drawLine(graphics, count.get(), new Box(x, secondY, priceX - ROW_TEXT_GAP, secondY + font.lineHeight), color);
            x += font.width(count.get()) + ROW_TEXT_GAP;
        }
        if (limit.isPresent()) {
            drawLine(graphics, limit.get(), new Box(x, secondY, priceX - ROW_TEXT_GAP, secondY + font.lineHeight), color);
        }
    }

    private void drawSellDetail(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Box detail = layout.detail();
        drawPanel(graphics, detail);
        ItemDetail parts = TradeLayout.itemDetail(detail, lineHeight(), true);
        Optional<SellRow> selected = selectedSell();
        if (selected.isPresent()) {
            SellRow row = selected.get();
            boolean enabled = row.maxCount() > 0 && !busy();
            int quantity = sellQuantity;
            int payout = Exchange.payout(quantity, row.per(), row.aur());
            drawLine(graphics, row.stack().getHoverName(), parts.name(), DialogueStyle.NAME_TEXT);
            drawTooltip(graphics, row.stack(), parts.tooltip());
            Stepping stepping = new Stepping(() -> sellQuantity,
                    (current, steps) -> Quantity.sell(current, steps, row.per(), row.maxCount()),
                    value -> sellQuantity = value,
                    Quantity.resetSell(row.per(), row.maxCount()));
            drawStepper(graphics, mouseX, mouseY, parts.stepper(), quantityLabel(), quantity, enabled, stepping);
            Component receive = Component.translatableWithFallback("alina.trade.screen.you_receive",
                    "You receive %s Aur", payout);
            drawLine(graphics, receive, parts.receive(), DialogueStyle.TEXT);
            drawButton(graphics, mouseX, mouseY, parts.action(),
                    Component.translatableWithFallback("alina.trade.screen.sell", "Sell"), enabled,
                    shift -> sendRequest(new TradeSellPayload(sessionId, row.slot(), quantity, payout)));
        }
        int junkItems = view.junkItems();
        int junkAur = view.junkAur();
        Component junkLabel = junkArmed
                ? Component.translatableWithFallback("alina.trade.screen.sell_junk_confirm",
                        "Confirm: %s for %s Aur", junkItems, junkAur)
                : Component.translatableWithFallback("alina.trade.screen.sell_junk",
                        "Sell junk: %s for %s Aur", junkItems, junkAur);
        drawButton(graphics, mouseX, mouseY, parts.junk(), junkLabel, junkItems > 0 && !busy(), shift -> {
            if (!junkArmed) {
                junkArmed = true;
                return;
            }
            junkArmed = false;
            sendRequest(new TradeSellJunkPayload(sessionId, junkAur));
        });
    }

    private void drawOfferDetail(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Box detail = layout.detail();
        drawPanel(graphics, detail);
        ItemDetail parts = TradeLayout.itemDetail(detail, lineHeight(), false);
        Optional<OfferRow> selected = selectedOffer();
        if (selected.isEmpty()) {
            return;
        }
        OfferRow offer = selected.get();
        boolean locked = offer.locked().isPresent();
        boolean enabled = !locked && !busy();
        int quantity = buyQuantity;
        int cost = Exchange.cost(quantity, offer.price());
        drawLine(graphics, offer.stack().getHoverName(), parts.name(), DialogueStyle.NAME_TEXT);
        if (locked) {
            drawWrapped(graphics, offer.locked().get(), parts.tooltip(), parts.tooltip().y0(),
                    DialogueStyle.CHOICE_LOCKED_TEXT);
        } else {
            drawTooltip(graphics, offer.stack(), parts.tooltip());
        }
        Stepping stepping = new Stepping(() -> buyQuantity,
                (current, steps) -> Quantity.count(current, steps, offer.maxCount()),
                value -> buyQuantity = value,
                Quantity.resetCount(offer.maxCount()));
        drawStepper(graphics, mouseX, mouseY, parts.stepper(), quantityLabel(), quantity, enabled, stepping);
        Component pay = Component.translatableWithFallback("alina.trade.screen.you_pay", "You pay %s Aur", cost);
        drawLine(graphics, pay, parts.receive(), DialogueStyle.TEXT);
        drawButton(graphics, mouseX, mouseY, parts.action(),
                Component.translatableWithFallback("alina.trade.screen.buy", "Buy"), enabled,
                shift -> sendRequest(new TradeBuyPayload(sessionId, offer.id(), quantity, cost)));
    }

    /**
     * The left panel of the experience tab, top to bottom: the level and the point total, the progress bar, then the preview
     * row, which gives the level after each amount the steppers would trade. Then the rate lines of a fixed-rate shop, or the
     * market group of a shop that trades against the market. The level, the progress and the previews all come from the
     * point total, so they agree. A box that does not fit inside the panel is not drawn, so a short window shows the top
     * items only. The panel takes no clicks.
     */
    private void drawExperienceStatus(GuiGraphicsExtractor graphics) {
        Box list = layout.list();
        drawPanel(graphics, list);
        Optional<ExperienceView> optional = view.experience();
        if (optional.isEmpty()) {
            return;
        }
        ExperienceView experience = optional.get();
        // The server never sends a negative total; the floor keeps a bad packet from throwing while drawing.
        int points = Math.max(0, experience.points());
        if (experience.market().isPresent()) {
            ExperienceMarketStatus status = TradeLayout.experienceMarketStatus(list, lineHeight());
            drawProgress(graphics, list, status.summary(), status.bar(), points);
            drawPreviews(graphics, list, status.preview(), experience, points);
            drawMarket(graphics, list, status, experience.market().get());
        } else {
            ExperienceStatus status = TradeLayout.experienceStatus(list, lineHeight());
            drawProgress(graphics, list, status.summary(), status.bar(), points);
            drawPreviews(graphics, list, status.preview(), experience, points);
            drawFixedRates(graphics, list, status.rates(), experience);
        }
    }

    /** The level and point total, and the progress bar for {@code points}, each drawn when it fits. */
    private void drawProgress(GuiGraphicsExtractor graphics, Box list, Box summary, Box bar, int points) {
        ExperiencePoints.Standing standing = ExperiencePoints.standing(points);
        if (TradeLayout.fitsIn(list, summary)) {
            drawLine(graphics, Component.translatableWithFallback("alina.trade.screen.level", "Level %s", standing.level()),
                    summary, DialogueStyle.NAME_TEXT);
            Component total = Component.translatableWithFallback("alina.trade.screen.experience_points", "%s points",
                    String.format(Locale.ROOT, "%,d", points));
            graphics.text(font, total, summary.x1() - font.width(total), summary.y0(), DialogueStyle.TEXT);
        }

        if (TradeLayout.fitsIn(list, bar)) {
            fill(graphics, bar, BAR_TRACK);
            int filled = Exchange.barFill(bar.width(), standing.into(), standing.needed());
            fill(graphics, new Box(bar.x0(), bar.y0(), bar.x0() + filled, bar.y1()), BAR_FILL);
        }
    }

    /** The sell rate line, then the buy rate line, each for the direction the fixed-rate shop offers. */
    private void drawFixedRates(GuiGraphicsExtractor graphics, Box list, List<Box> rates, ExperienceView experience) {
        int rate = 0;
        if (Exchange.experienceSellOffered(experience)) {
            drawIfFits(graphics, list, rates.get(rate++),
                    PriceLabel.sellRate(experience.sellPoints(), experience.sellAur()), DialogueStyle.TEXT);
        }
        if (Exchange.experienceBuyOffered(experience)) {
            drawIfFits(graphics, list, rates.get(rate),
                    PriceLabel.buyRate(experience.buyPoints(), experience.buyAur()), DialogueStyle.TEXT);
        }
    }

    /**
     * The market group: the price of 100 points, and the chart of its recent changes, with the usual price's line across it;
     * then the fee of each direction the shop trades. The usual price is the price of the seed reserves.
     */
    private void drawMarket(GuiGraphicsExtractor graphics, Box list, ExperienceMarketStatus status, MarketView market) {
        int price = Exchange.priceHundredths(market.points(), market.aur());
        int usual = Exchange.priceHundredths(market.seedPoints(), market.seedAur());
        drawPrice(graphics, list, status.price(), price, Exchange.changePercent(price, usual));
        List<Integer> values = Exchange.chartValues(market.history(), Exchange.marketRate(market));
        drawChart(graphics, list, status.chart(), values, usual);
        if (market.sellFee() >= 0 || market.buyFee() >= 0) {
            drawIfFits(graphics, list, status.fee(), PriceLabel.fee(market.sellFee(), market.buyFee()), NOTE_TEXT);
        }
    }

    /**
     * The price line: the price of 100 points at the left, and the change against the usual price at the right edge, an up or
     * down triangle with the percent, or the bare {@code 0%} when the change rounds to 0. The change is left out when it would
     * overlap the price.
     */
    private void drawPrice(GuiGraphicsExtractor graphics, Box list, Box line, int price, int change) {
        if (!TradeLayout.fitsIn(list, line)) {
            return;
        }
        Component text = PriceLabel.price(price);
        drawLine(graphics, text, line, DialogueStyle.TEXT);
        Component percent = PriceLabel.priceChange(change);
        int width = change == 0 ? font.width(percent) : trendWidth(percent);
        if (font.width(text) + ROW_TEXT_GAP + width > line.width()) {
            return;
        }
        int x = line.x1() - width;
        if (change == 0) {
            graphics.text(font, percent, x, line.y0(), NOTE_TEXT);
        } else {
            drawTrend(graphics, x, line.y0(), change > 0, percent, change > 0 ? TREND_UP : TREND_DOWN);
        }
    }

    /**
     * The chart of recent prices: the fill under the price line, the usual price's line across the chart, the price line
     * itself, and the dot on its last value. Nothing is drawn when there are no values or the chart does not fit.
     */
    private void drawChart(GuiGraphicsExtractor graphics, Box list, Box chart, List<Integer> values, int usual) {
        int[] rows = Exchange.chartYs(values, usual, chart.width(), chart.height());
        if (rows.length == 0 || !TradeLayout.fitsIn(list, chart)) {
            return;
        }
        for (Box column : TradeLayout.chartFill(chart, rows)) {
            fill(graphics, column, CHART_FILL);
        }
        fill(graphics, TradeLayout.chartRow(chart, Exchange.chartBaseY(values, usual, chart.height())), CHART_USUAL);
        for (Box step : TradeLayout.chartLine(chart, rows)) {
            fill(graphics, step, CHART_LINE);
        }
        fill(graphics, TradeLayout.chartDot(chart, rows[rows.length - 1]), CHART_DOT);
    }

    /**
     * A trend mark: a triangle with its top-left corner at ({@code x}, {@code y}), then {@code text} after it, both in
     * {@code color}. The triangle is vertically centred on the text's line.
     */
    private void drawTrend(GuiGraphicsExtractor graphics, int x, int y, boolean up, Component text, int color) {
        int triangleY = y + (font.lineHeight - TradeLayout.TRIANGLE_HEIGHT + 1) / 2;
        for (Box row : TradeLayout.triangle(x, triangleY, up)) {
            fill(graphics, row, color);
        }
        graphics.text(font, text, x + TradeLayout.TRIANGLE_WIDTH + TradeLayout.TRIANGLE_TEXT_GAP, y, color);
    }

    /** The width of the trend mark {@link #drawTrend} draws with {@code text}: the triangle, its gap, and the text. */
    private int trendWidth(Component text) {
        return TradeLayout.TRIANGLE_WIDTH + TradeLayout.TRIANGLE_TEXT_GAP + font.width(text);
    }

    /**
     * The preview row: at the left, the level after selling the sell stepper's Aur, when the player holds the points it gives;
     * at the right edge, the level after buying the buy stepper's Aur. Each is a triangle with {@code Lv N}. The purchase is
     * left out when it would reach the sale's text. The row is drawn only when it fits.
     */
    private void drawPreviews(GuiGraphicsExtractor graphics, Box list, Box row, ExperienceView experience, int points) {
        if (!TradeLayout.fitsIn(list, row)) {
            return;
        }
        OptionalInt sold = OptionalInt.empty();
        if (Exchange.experienceSellOffered(experience)) {
            int giving = Exchange.experienceSellPoints(experience, experienceSellAur);
            if (giving > 0 && giving <= points) {
                sold = OptionalInt.of(ExperiencePoints.standing(points - giving).level());
            }
        }
        OptionalInt bought = OptionalInt.empty();
        if (Exchange.experienceBuyOffered(experience)) {
            int getting = Exchange.experienceBuyPoints(experience, experienceBuyAur);
            if (getting > 0) {
                int after = (int) Math.min((long) points + getting, Integer.MAX_VALUE);
                bought = OptionalInt.of(ExperiencePoints.standing(after).level());
            }
        }
        int taken = row.x0();
        if (sold.isPresent()) {
            Component text = PriceLabel.previewLevel(sold.getAsInt());
            drawTrend(graphics, row.x0(), row.y0(), false, text, TREND_DOWN);
            taken = row.x0() + trendWidth(text) + ROW_TEXT_GAP;
        }
        if (bought.isPresent()) {
            Component text = PriceLabel.previewLevel(bought.getAsInt());
            int x = row.x1() - trendWidth(text);
            if (x >= taken) {
                drawTrend(graphics, x, row.y0(), true, text, TREND_UP);
            }
        }
    }

    /** Draws the first line of {@code text} in {@code box}, when {@code box} fits inside {@code list}. */
    private void drawIfFits(GuiGraphicsExtractor graphics, Box list, Box box, Component text, int color) {
        if (TradeLayout.fitsIn(list, box)) {
            drawLine(graphics, text, box, color);
        }
    }

    private void drawExperienceDetail(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Box detail = layout.detail();
        drawPanel(graphics, detail);
        Optional<ExperienceView> optional = view.experience();
        if (optional.isEmpty()) {
            return;
        }
        ExperienceView experience = optional.get();
        boolean sell = Exchange.experienceSellOffered(experience);
        boolean buy = Exchange.experienceBuyOffered(experience);
        int sectionCount = (sell ? 1 : 0) + (buy ? 1 : 0);
        List<ExperienceSection> sections = TradeLayout.experienceSections(detail, sectionCount);
        int index = 0;
        if (sell) {
            drawExperienceSection(graphics, mouseX, mouseY, sections.get(index++), experience, true);
        }
        if (buy) {
            drawExperienceSection(graphics, mouseX, mouseY, sections.get(index), experience, false);
        }
    }

    private void drawExperienceSection(GuiGraphicsExtractor graphics, int mouseX, int mouseY, ExperienceSection section,
                                       ExperienceView experience, boolean selling) {
        int max = selling ? experience.maxSellAur() : experience.maxBuyAur();
        int aur = selling ? experienceSellAur : experienceBuyAur;
        int step = Exchange.experienceStep(experience, selling);
        int points = selling
                ? Exchange.experienceSellPoints(experience, aur)
                : Exchange.experienceBuyPoints(experience, aur);
        // The stepper moves while the Aur is within the most allowed; the action also needs points to trade.
        boolean ready = aur > 0 && aur <= max && !busy();
        Stepping stepping = selling
                ? new Stepping(() -> experienceSellAur,
                        (current, steps) -> Quantity.sell(current, steps, step, experience.maxSellAur()),
                        value -> experienceSellAur = value,
                        Quantity.resetSell(step, experience.maxSellAur()))
                : new Stepping(() -> experienceBuyAur,
                        (current, steps) -> Quantity.sell(current, steps, step, experience.maxBuyAur()),
                        value -> experienceBuyAur = value,
                        Quantity.resetSell(step, experience.maxBuyAur()));
        Component label = selling
                ? Component.translatableWithFallback("alina.trade.screen.aur_to_get", "Aur to get")
                : Component.translatableWithFallback("alina.trade.screen.aur_to_pay", "Aur to pay");
        drawStepper(graphics, mouseX, mouseY, section.stepper(), label, aur, ready, stepping);
        drawButton(graphics, mouseX, mouseY, section.action(), PriceLabel.experienceAction(selling, points, aur),
                ready && points > 0, shift -> sendRequest(new TradeExperiencePayload(sessionId, selling, aur, points)));
    }

    /**
     * The minus, the value, the plus, the max and the reset buttons of a stepper, and its label. The reset button is enabled
     * when the stepper is and the value is not the stepping's reset value already.
     */
    private void drawStepper(GuiGraphicsExtractor graphics, int mouseX, int mouseY, Stepper stepper, Component label,
                             int value, boolean enabled, Stepping stepping) {
        // A narrow window leaves the label less room than its text needs; it is left out there, not cut short.
        if (font.width(label) <= stepper.label().width()) {
            drawLine(graphics, label, stepper.label(), DialogueStyle.TEXT);
        }
        String digits = Integer.toString(value);
        Box valueBox = stepper.value();
        graphics.text(font, digits, valueBox.x0() + (valueBox.width() - font.width(digits)) / 2,
                valueBox.y0() + (valueBox.height() - font.lineHeight + 1) / 2, DialogueStyle.TEXT);
        drawButton(graphics, mouseX, mouseY, stepper.minus(),
                Component.translatableWithFallback("alina.trade.screen.decrease", "-"), enabled,
                shift -> step(stepping, shift ? -Quantity.SHIFT_STEPS : -1));
        drawButton(graphics, mouseX, mouseY, stepper.plus(),
                Component.translatableWithFallback("alina.trade.screen.increase", "+"), enabled,
                shift -> step(stepping, shift ? Quantity.SHIFT_STEPS : 1));
        drawButton(graphics, mouseX, mouseY, stepper.max(),
                Component.translatableWithFallback("alina.trade.screen.max", "max"), enabled,
                shift -> step(stepping, Quantity.ALL));
        drawButton(graphics, mouseX, mouseY, stepper.reset(),
                Component.translatableWithFallback("alina.trade.screen.reset", "reset"),
                enabled && value != stepping.reset(), shift -> stepping.set().accept(stepping.reset()));
    }

    private static void step(Stepping stepping, int steps) {
        stepping.set().accept(stepping.move().applyAsInt(stepping.get().getAsInt(), steps));
    }

    /** A button: a panel with its label centred, dimmed and inert when {@code enabled} is false. */
    private void drawButton(GuiGraphicsExtractor graphics, int mouseX, int mouseY, Box box, Component label, boolean enabled,
                            ClickAction action) {
        boolean hovered = enabled && box.contains(mouseX, mouseY);
        drawPanel(graphics, box);
        if (hovered) {
            fill(graphics, box, HOVER_FILL);
        }
        if (!enabled) {
            fill(graphics, box, DISABLED_FILL);
        }
        int color = !enabled ? DialogueStyle.CHOICE_LOCKED_TEXT
                : hovered ? DialogueStyle.CHOICE_HOVER_TEXT : DialogueStyle.CHOICE_TEXT;
        drawCentered(graphics, label, box, color);
        controls.add(new Control(box, enabled, action));
    }

    /** The item's tooltip lines below its name, as many as fit. The first line is the name, shown already. */
    private void drawTooltip(GuiGraphicsExtractor graphics, ItemStack stack, Box box) {
        List<Component> lines = getTooltipFromItem(Minecraft.getInstance(), stack);
        int y = box.y0();
        for (Component component : lines.subList(Math.min(1, lines.size()), lines.size())) {
            y = drawWrapped(graphics, component, box, y, DialogueStyle.TEXT);
        }
    }

    /** Draws {@code text} wrapped to the width of {@code box} from height {@code y} down, as many lines as fit. */
    private int drawWrapped(GuiGraphicsExtractor graphics, Component text, Box box, int y, int color) {
        int next = y;
        for (FormattedCharSequence part : font.split(text, Math.max(1, box.width()))) {
            if (next + font.lineHeight > box.y1()) {
                return next;
            }
            graphics.text(font, part, box.x0(), next, color);
            next += lineHeight();
        }
        return next;
    }

    /** The first line of {@code text} wrapped to {@code box}'s width, at the top of the box. */
    private void drawLine(GuiGraphicsExtractor graphics, Component text, Box box, int color) {
        List<FormattedCharSequence> parts = font.split(text, Math.max(1, box.width()));
        if (!parts.isEmpty()) {
            graphics.text(font, parts.getFirst(), box.x0(), box.y0(), color);
        }
    }

    private void drawCentered(GuiGraphicsExtractor graphics, Component text, Box box, int color) {
        List<FormattedCharSequence> parts = font.split(text, Math.max(1, box.width()));
        if (parts.isEmpty()) {
            return;
        }
        FormattedCharSequence part = parts.getFirst();
        graphics.text(font, part, box.x0() + (box.width() - font.width(part)) / 2,
                box.y0() + (box.height() - font.lineHeight + 1) / 2, color);
    }

    private void drawRowFrame(GuiGraphicsExtractor graphics, Box row, boolean selected, boolean hovered) {
        fill(graphics, row, selected ? SELECTED_FILL : ROW_FILL);
        if (hovered) {
            fill(graphics, row, HOVER_FILL);
        }
    }

    private void drawPanel(GuiGraphicsExtractor graphics, Box box) {
        DialogueStyle.drawBox(graphics, box.x0(), box.y0(), box.x1(), box.y1());
    }

    private static void fill(GuiGraphicsExtractor graphics, Box box, int color) {
        graphics.fill(box.x0(), box.y0(), box.x1(), box.y1(), color);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == GLFW.GLFW_KEY_TAB) {
            selectTab(TradeTab.cycle(TradeTab.available(view), tab, event.hasShiftDown() ? -1 : 1));
            return true;
        }
        if ((key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) && listScrollable()) {
            scrollList(key == GLFW.GLFW_KEY_UP ? -1 : 1);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return super.mouseClicked(event, doubleClick);
        }
        for (Control control : controls) {
            if (control.box().contains(event.x(), event.y())) {
                if (control.enabled()) {
                    control.action().run(event.hasShiftDown());
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** The wheel scrolls the list when its rows do not all fit. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0.0 && listScrollable()) {
            scrollList(scrollY > 0.0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Escape: tell the server the player dismissed the trade, once, then close. */
    @Override
    public void onClose() {
        if (!dismissed) {
            dismissed = true;
            if (ClientPlayNetworking.canSend(TradeDismissPayload.TYPE)) {
                ClientPlayNetworking.send(new TradeDismissPayload(sessionId));
            }
        }
        Minecraft.getInstance().gui.setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
