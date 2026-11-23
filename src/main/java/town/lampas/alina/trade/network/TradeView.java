package town.lampas.alina.trade.network;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * The whole trade screen as the server sees it for one player, sent on open and again after each change. The wire order
 * is the components in declaration order.
 *
 * @param aur the player's Aur
 * @param selling whether the shop buys from players at all
 * @param sellsItems whether the shop has at least one sell rule, so the Sell tab is worth showing
 * @param budgetLeft the Aur the shop has left to pay out today
 * @param dailyBudget the shop's budget for a day, for the screen to show
 * @param sellRows the main inventory stacks the shop buys; at most {@link #MAX_SELL_ROWS}
 * @param junkItems the items "Sell all junk" would sell now
 * @param junkAur the Aur "Sell all junk" would pay now
 * @param offers what the shop sells; at most {@link #MAX_OFFERS}
 * @param experience the experience panel, present when the shop trades experience
 * @param busy whether a trade of this player is being settled
 */
public record TradeView(
        long aur,
        boolean selling,
        boolean sellsItems,
        int budgetLeft,
        int dailyBudget,
        List<SellRow> sellRows,
        int junkItems,
        int junkAur,
        List<OfferRow> offers,
        Optional<ExperienceView> experience,
        boolean busy) {

    /** The main inventory has 36 slots, so no sell list is longer. */
    public static final int MAX_SELL_ROWS = 36;
    /** The most offers a payload carries. A shop with more offers than this cannot be sent. */
    public static final int MAX_OFFERS = 256;

    public static final StreamCodec<RegistryFriendlyByteBuf, TradeView> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, TradeView::aur,
            ByteBufCodecs.BOOL, TradeView::selling,
            ByteBufCodecs.BOOL, TradeView::sellsItems,
            ByteBufCodecs.VAR_INT, TradeView::budgetLeft,
            ByteBufCodecs.VAR_INT, TradeView::dailyBudget,
            SellRow.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_SELL_ROWS)), TradeView::sellRows,
            ByteBufCodecs.VAR_INT, TradeView::junkItems,
            ByteBufCodecs.VAR_INT, TradeView::junkAur,
            OfferRow.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_OFFERS)), TradeView::offers,
            ByteBufCodecs.optional(ExperienceView.STREAM_CODEC), TradeView::experience,
            ByteBufCodecs.BOOL, TradeView::busy,
            TradeView::new);

    public TradeView {
        sellRows = List.copyOf(sellRows);
        offers = List.copyOf(offers);
        Objects.requireNonNull(experience, "experience");
    }
}
