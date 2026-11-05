package town.lampas.alina.settlement;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;

/**
 * Stack helpers for the settlement escrow: cutting stacks into pieces a save can hold, and measuring the escrow text the
 * way vanilla's player-data save writes it.
 */
final class Stacks {
    /** The largest count {@link ItemStack#CODEC} accepts. */
    static final int CODEC_MAX_COUNT = 99;

    private Stacks() {}

    /**
     * The non-empty stacks in order, each cut into pieces of at most its max stack size and at most
     * {@link #CODEC_MAX_COUNT}. A loot table can roll a count above what the item stacks to, and the codec refuses counts
     * above 99.
     */
    static List<ItemStack> split(List<ItemStack> stacks) {
        List<ItemStack> pieces = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            int limit = Math.clamp(stack.getMaxStackSize(), 1, CODEC_MAX_COUNT);
            int left = stack.getCount();
            while (left > 0) {
                int piece = Math.min(limit, left);
                pieces.add(stack.copyWithCount(piece));
                left -= piece;
            }
        }
        return List.copyOf(pieces);
    }

    /**
     * The bytes {@link java.io.DataOutput#writeUTF} writes for the text, without its two-byte length. NUL takes two bytes,
     * characters from U+0080 to U+07FF take two, and every other UTF-16 unit takes three, so each half of a surrogate
     * pair takes three.
     */
    static int modifiedUtf8Length(String text) {
        int bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x0001 && c <= 0x007F) {
                bytes += 1;
            } else if (c <= 0x07FF) {
                bytes += 2;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }
}
