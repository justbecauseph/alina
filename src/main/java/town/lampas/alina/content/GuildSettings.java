package town.lampas.alina.content;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import net.minecraft.resources.Identifier;

/**
 * The Guild's board and cap settings (ALINA-PLAN §6.1). The board size and reach are the defaults of every board. A
 * board's graph is its own, or, in a section without boards, the implicit board's graph (see {@link GuildContent}).
 *
 * @param boardSize the commissions drawn per day, unless a board sets its own size
 * @param activeCap the most commissions one character may have active, across all boards
 * @param resetTime the time of day the board day starts, in {@code zone}
 * @param salt mixed into the board seed, so two servers with the same content draw different boards
 * @param boardReach how far, in blocks, a player may move from a Notice Board before its conversation ends, unless a
 *     board sets its own reach
 */
public record GuildSettings(
        int boardSize,
        int activeCap,
        LocalTime resetTime,
        ZoneId zone,
        Cooldown defaultCooldown,
        String salt,
        double boardReach) {

    /** The defaults for each setting the content leaves out. */
    public static final int DEFAULT_BOARD_SIZE = 5;
    public static final int DEFAULT_ACTIVE_CAP = 3;
    public static final LocalTime DEFAULT_RESET_TIME = LocalTime.of(4, 0);
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Manila");
    public static final Cooldown DEFAULT_COOLDOWN = new Cooldown.Fixed(Duration.ofHours(20));
    public static final double DEFAULT_BOARD_REACH = 6.0;
    /** The graph of the implicit board when a section without boards gives no {@code board_graph} (EMPTY only). */
    public static final Identifier DEFAULT_BOARD_GRAPH = Identifier.fromNamespaceAndPath("alina", "guild/board");

    /** The settings of a bundle without an {@code alina:guild} section. */
    public static final GuildSettings DEFAULTS = new GuildSettings(DEFAULT_BOARD_SIZE, DEFAULT_ACTIVE_CAP,
            DEFAULT_RESET_TIME, DEFAULT_ZONE, DEFAULT_COOLDOWN, "", DEFAULT_BOARD_REACH);
}
