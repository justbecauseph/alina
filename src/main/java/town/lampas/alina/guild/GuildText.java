package town.lampas.alina.guild;

import java.time.Duration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The text the Guild shows players: chat messages, grayed-out reasons and status rows. Each is a translatable component
 * whose key is in {@code assets/alina/lang/en_us.json}, with the same English text as its fallback. Commission names
 * are passed as components and stay as the content wrote them.
 */
public final class GuildText {

    private GuildText() {}

    private static Component text(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("alina.guild." + key, fallback, args);
    }

    public static Component loading() {
        return text("loading", "Your Guild record is still loading.");
    }

    public static Component boardLoading() {
        return text("board_loading", "Today's board is still being posted.");
    }

    public static Component unknown(Identifier commission) {
        return text("unknown", "The Guild has no commission %s.", commission.toString());
    }

    public static Component notOnBoard(Component name) {
        return text("not_on_board", "%s is not on today's board.", name);
    }

    public static Component active(Component name) {
        return text("active", "%s is already active.", name);
    }

    public static Component notActive(Component name) {
        return text("not_active", "%s is not active.", name);
    }

    public static Component settling(Component name) {
        return text("settling", "%s is being settled.", name);
    }

    public static Component cooldown(Component name, Duration left) {
        return text("cooldown", "%s is available again in %s.", name, duration(left));
    }

    public static Component completed(Component name) {
        return text("completed", "You have already completed %s.", name);
    }

    public static Component capReached(int cap) {
        return text("cap_reached", "You already hold %s commissions, the most the Guild allows.", cap);
    }

    public static Component busy(Component name) {
        return text("busy", "The Guild is still recording %s.", name);
    }

    /** A turn-in action refused because the proof is not met: the name and the commission's hint. */
    public static Component proofNotMet(Component name, Component hint) {
        return text("proof_not_met", "%s: %s", name, hint);
    }

    public static Component settlementRefused(Component name) {
        return text("settlement_refused", "The Guild could not take %s right now. Try again in a moment.", name);
    }

    public static Component accepted(Component name) {
        return text("accepted", "Commission accepted: %s", name);
    }

    public static Component acceptFailed(Component name) {
        return text("accept_failed", "The Guild could not record %s. Try again.", name);
    }

    public static Component abandoned(Component name) {
        return text("abandoned", "Commission abandoned: %s", name);
    }

    public static Component abandonFailed(Component name) {
        return text("abandon_failed", "The Guild could not abandon %s. Try again.", name);
    }

    /** A criterion or descent proof has just been met. */
    public static Component ready(Component name) {
        return text("ready", "Ready to turn in: %s", name);
    }

    /** Settlement has begun a turn-in: the proof is taken. */
    public static Component turnedIn(Component name) {
        return text("turned_in", "Turned in: %s. Your reward is on its way.", name);
    }

    /** Settlement has delivered a turn-in's whole reward. */
    public static Component commissionComplete(Component name) {
        return text("commission_complete", "Commission complete: %s", name);
    }

    /** A new content revision removed a commission the player held active. */
    public static Component withdrawn(Component name) {
        return text("withdrawn", "The Guild withdrew %s, so it is no longer active.", name);
    }

    /** The player used the Notice Board from farther than its reach. */
    public static Component tooFar() {
        return text("too_far", "Step closer to the notice board to read it.");
    }

    /** A Notice Board with no board assigned, while the content has several boards. */
    public static Component boardUnassigned() {
        return text("board_unassigned", "This notice board has nothing pinned to it yet.");
    }

    /** The Notice Board's graph is not in the active content. */
    public static Component boardMissing() {
        return text("board_missing", "Nothing is posted on the notice board.");
    }

    public static Component statusReputation(int reputation) {
        return text("status.reputation", "Reputation: %s", reputation);
    }

    public static Component statusActive(Component name) {
        return text("status.active", "%s: active", name);
    }

    /** An active counted criterion proof: progress out of the count. */
    public static Component statusProgress(Component name, int progress, int count) {
        return text("status.progress", "%s: %s of %s", name, progress, count);
    }

    public static Component statusSettling(Component name) {
        return text("status.settling", "%s: being settled", name);
    }

    public static Component statusCooldown(Component name, Duration left) {
        return text("status.cooldown", "%s: available in %s", name, duration(left));
    }

    public static Component statusCompleted(Component name) {
        return text("status.completed", "%s: completed", name);
    }

    /**
     * A time left, rounded up to the minute: {@code 3d 4h}, {@code 5h 12m}, {@code 12m}. Under a minute, and zero or
     * negative, is {@code 1m}.
     */
    public static String duration(Duration left) {
        long minutes = Math.max(1, (left.toSeconds() + 59) / 60);
        long days = minutes / (24 * 60);
        long hours = minutes / 60 % 24;
        long rest = minutes % 60;
        if (days > 0) {
            return hours > 0 ? days + "d " + hours + "h" : days + "d";
        }
        if (hours > 0) {
            return rest > 0 ? hours + "h " + rest + "m" : hours + "h";
        }
        return rest + "m";
    }
}
