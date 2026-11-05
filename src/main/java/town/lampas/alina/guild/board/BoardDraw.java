package town.lampas.alina.guild.board;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import net.minecraft.resources.Identifier;
import town.lampas.alina.content.Commission;

/**
 * The daily board draw (ALINA-PLAN §8.2): deterministic for a salt, date and revision, so every server run draws the
 * same board for the same day and content.
 */
public final class BoardDraw {

    private BoardDraw() {}

    /**
     * The first 8 bytes, big-endian, of SHA-256 over: the UTF-8 bytes of {@code salt}, a 0 byte, the UTF-8 bytes of the
     * date in ISO form ({@code 2026-11-05}), a 0 byte, and the UTF-8 bytes of the revision id in decimal.
     */
    public static long seed(String salt, LocalDate date, long revisionId) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Draws up to {@code size} commissions by weight without replacement, in draw order. The pool is sorted by id text
     * first, so its iteration order does not matter. A {@code java.util.Random} seeded with {@code seed} picks each
     * one: {@code nextLong(total)} over the sum of the remaining weights, and the pick is the first remaining
     * commission, in id order, whose running weight sum exceeds the roll. The caller filters the pool (repeatables in
     * rotation).
     *
     * @return {@code min(size, pool size)} ids, or an empty list for an empty pool or a size below 1
     */
    public static List<Identifier> draw(Collection<Commission> pool, int size, long seed) {
        throw new UnsupportedOperationException("not built yet");
    }
}
