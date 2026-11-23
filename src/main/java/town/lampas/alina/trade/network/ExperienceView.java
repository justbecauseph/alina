package town.lampas.alina.trade.network;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * The player's experience and the shop's experience trade, as the experience panel shows it. The sell rate is
 * {@code sellPoints} points for every {@code sellAur} Aur the shop pays, and the buy rate is {@code buyPoints} points for
 * every {@code buyAur} Aur the player pays. A shop that trades against the experience market has no rates: its points and
 * Aur are 0, and {@code market} says what the pool is.
 *
 * @param points the points the player holds
 * @param level the player's experience level
 * @param sellPoints the points of the sell rate; 0 when the shop does not buy experience
 * @param sellAur the Aur of the sell rate; 0 when the shop does not buy experience
 * @param maxSellAur the most Aur the player can earn by selling experience now
 * @param buyPoints the points of the buy rate; 0 when the shop does not sell experience
 * @param buyAur the Aur of the buy rate; 0 when the shop does not sell experience
 * @param maxBuyAur the most Aur the player can spend on experience now
 * @param market the experience market, present when the shop trades against it
 */
public record ExperienceView(
        int points,
        int level,
        int sellPoints,
        int sellAur,
        int maxSellAur,
        int buyPoints,
        int buyAur,
        int maxBuyAur,
        Optional<MarketView> market) {

    public static final StreamCodec<RegistryFriendlyByteBuf, ExperienceView> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ExperienceView::points,
            ByteBufCodecs.VAR_INT, ExperienceView::level,
            ByteBufCodecs.VAR_INT, ExperienceView::sellPoints,
            ByteBufCodecs.VAR_INT, ExperienceView::sellAur,
            ByteBufCodecs.VAR_INT, ExperienceView::maxSellAur,
            ByteBufCodecs.VAR_INT, ExperienceView::buyPoints,
            ByteBufCodecs.VAR_INT, ExperienceView::buyAur,
            ByteBufCodecs.VAR_INT, ExperienceView::maxBuyAur,
            ByteBufCodecs.optional(MarketView.STREAM_CODEC), ExperienceView::market,
            ExperienceView::new);

    public ExperienceView {
        Objects.requireNonNull(market, "market");
    }
}
