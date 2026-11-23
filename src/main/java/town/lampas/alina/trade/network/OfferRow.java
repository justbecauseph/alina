package town.lampas.alina.trade.network;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * One offer the shop sells, as the buy list shows it.
 *
 * @param id the offer's id in the shop; the client sends it back to buy
 * @param stack the item and the count one purchase gives
 * @param price the Aur one purchase costs
 * @param limitLeft the purchases the player has left in the limit's period, or -1 when the offer has no limit
 * @param maxCount the most the player can buy now, in items
 * @param locked why the player may not buy the offer at all, or empty when it is open
 */
public record OfferRow(
        String id,
        ItemStack stack,
        int price,
        int limitLeft,
        int maxCount,
        Optional<Component> locked) {

    /** The longest offer id a payload carries: {@link TradeBuyPayload} refuses a longer one. */
    public static final int MAX_ID_LENGTH = 64;

    public static final StreamCodec<RegistryFriendlyByteBuf, OfferRow> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_ID_LENGTH), OfferRow::id,
            ItemStack.STREAM_CODEC, OfferRow::stack,
            ByteBufCodecs.VAR_INT, OfferRow::price,
            ByteBufCodecs.VAR_INT, OfferRow::limitLeft,
            ByteBufCodecs.VAR_INT, OfferRow::maxCount,
            ComponentSerialization.OPTIONAL_STREAM_CODEC, OfferRow::locked,
            OfferRow::new);

    public OfferRow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(locked, "locked");
        stack = stack.copy();
    }
}
