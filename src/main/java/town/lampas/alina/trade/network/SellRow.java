package town.lampas.alina.trade.network;

import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * One main inventory stack the shop buys, as the sell list shows it.
 *
 * @param slot the main inventory slot the stack is in
 * @param stack the stack as the player has it; the record keeps a copy
 * @param aur the Aur the shop pays for {@code per} items
 * @param per the number of items {@code aur} pays for
 * @param maxCount the most the player can sell now: a multiple of {@code per}, possibly 0
 * @param junk whether "Sell all junk" takes the stack
 */
public record SellRow(int slot, ItemStack stack, int aur, int per, int maxCount, boolean junk) {
    public static final StreamCodec<RegistryFriendlyByteBuf, SellRow> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SellRow::slot,
            ItemStack.STREAM_CODEC, SellRow::stack,
            ByteBufCodecs.VAR_INT, SellRow::aur,
            ByteBufCodecs.VAR_INT, SellRow::per,
            ByteBufCodecs.VAR_INT, SellRow::maxCount,
            ByteBufCodecs.BOOL, SellRow::junk,
            SellRow::new);

    public SellRow {
        Objects.requireNonNull(stack, "stack");
        stack = stack.copy();
    }
}
