package town.lampas.alina.content;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import net.minecraft.resources.Identifier;

/**
 * The Guild's board and cap settings (ALINA-PLAN §6.1).
 *
 * @param resetTime the time of day the board day starts, in {@code zone}
 * @param salt mixed into the board seed, so two servers with the same content draw different boards
 * @param boardGraph the dialogue graph the Notice Board opens
 * @param boardReach how far, in blocks, a player may move from the Notice Board before its conversation ends
 */
public record GuildSettings(
        int boardSize,
        int activeCap,
        LocalTime resetTime,
        ZoneId zone,
        Cooldown defaultCooldown,
        String salt,
        Identifier boardGraph,
        double boardReach) {

    /** The defaults for each setting the content leaves out. */
    public static final int DEFAULT_BOARD_SIZE = 5;
    public static final int DEFAULT_ACTIVE_CAP = 3;
    public static final LocalTime DEFAULT_RESET_TIME = LocalTime.of(4, 0);
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Manila");
    public static final Cooldown DEFAULT_COOLDOWN = new Cooldown.Fixed(Duration.ofHours(20));
    public static final double DEFAULT_BOARD_REACH = 6.0;

    /** The settings of a bundle without an {@code alina:guild} section. Its board graph is not checked. */
    public static final GuildSettings DEFAULTS = new GuildSettings(DEFAULT_BOARD_SIZE, DEFAULT_ACTIVE_CAP,
            DEFAULT_RESET_TIME, DEFAULT_ZONE, DEFAULT_COOLDOWN, "", Identifier.fromNamespaceAndPath("alina", "guild/board"),
            DEFAULT_BOARD_REACH);
}
