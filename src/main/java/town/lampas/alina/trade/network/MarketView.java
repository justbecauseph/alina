package town.lampas.alina.trade.network;

import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * The experience market as a shop that trades against it shows it: the pool's live reserves, its seed, the trade cap, the
 * fees of the shop, and the rate after each of the last changes of the pool. The wire order is the components in declaration order: points, aur,
 * seed points, seed aur (variable-length longs), then sell fee, buy fee and max trade percent (variable-length ints), then
 * the history as a count and its rates (variable-length ints).
 *
 * @param points the live points reserve
 * @param aur the live Aur reserve
 * @param seedPoints the points of the seed reserve, where the pool heads
 * @param seedAur the Aur of the seed reserve
 * @param sellFee the shop's fee in percent when a player sells experience; -1 when the shop does not buy experience
 * @param buyFee the shop's fee in percent when a player buys experience; -1 when the shop does not sell experience
 * @param maxTradePercent the most of the live reserve one trade may take, in percent
 * @param history the rate in hundredths of a point per Aur, {@code ReserveCurve.rateHundredths}, after each of the last
 *     changes of the pool, oldest first; at most {@link #MAX_HISTORY}
 */
public record MarketView(
        long points,
        long aur,
        long seedPoints,
        long seedAur,
        int sellFee,
        int buyFee,
        int maxTradePercent,
        List<Integer> history) {

    /** The most changes of the pool a view's rate history carries. */
    public static final int MAX_HISTORY = 32;

    public static final StreamCodec<RegistryFriendlyByteBuf, MarketView> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, MarketView::points,
            ByteBufCodecs.VAR_LONG, MarketView::aur,
            ByteBufCodecs.VAR_LONG, MarketView::seedPoints,
            ByteBufCodecs.VAR_LONG, MarketView::seedAur,
            ByteBufCodecs.VAR_INT, MarketView::sellFee,
            ByteBufCodecs.VAR_INT, MarketView::buyFee,
            ByteBufCodecs.VAR_INT, MarketView::maxTradePercent,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX_HISTORY)), MarketView::history,
            MarketView::new);

    /** Refuses a history of more than {@link #MAX_HISTORY} rates. */
    public MarketView {
        history = List.copyOf(history);
        if (history.size() > MAX_HISTORY) {
            throw new IllegalArgumentException("a market view keeps at most " + MAX_HISTORY + " rates, had " + history.size());
        }
    }
}
