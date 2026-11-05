package town.lampas.alina.guild.board;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
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
        MessageDigest digest = sha256();
        digest.update(salt.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(date.toString().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(Long.toString(revisionId).getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.wrap(digest.digest()).getLong();
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
        if (size < 1 || pool.isEmpty()) {
            return List.of();
        }
        List<Commission> remaining = new ArrayList<>(pool);
        remaining.sort(Comparator.comparing(commission -> commission.id().toString()));
        for (Commission commission : remaining) {
            if (commission.weight() < 1) {
                throw new IllegalArgumentException("commission " + commission.id() + " has weight "
                        + commission.weight() + "; a weight is at least 1");
            }
        }
        Random random = new Random(seed);
        List<Identifier> drawn = new ArrayList<>();
        while (drawn.size() < size && !remaining.isEmpty()) {
            long total = 0;
            for (Commission commission : remaining) {
                total += commission.weight();
            }
            long roll = random.nextLong(total);
            long running = 0;
            int picked = 0;
            for (int i = 0; i < remaining.size(); i++) {
                running += remaining.get(i).weight();
                if (running > roll) {
                    picked = i;
                    break;
                }
            }
            drawn.add(remaining.remove(picked).id());
        }
        return List.copyOf(drawn);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("every Java runtime has SHA-256", failure);
        }
    }
}
