package town.lampas.alina.trade;

import java.util.Objects;
import net.minecraft.network.chat.Component;

/**
 * Why a trade or an open was refused, with the line the player sees.
 *
 * @param reason why it was refused
 * @param message the line to show: the shop's own line for the reason when the shop has one, otherwise Alina's default
 */
public record TradeRefusal(Reason reason, Component message) {

    public TradeRefusal {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(message, "message");
    }

    /** Why a trade or an open was refused. */
    public enum Reason {
        /** The player's trade record has not loaded yet. */
        LOADING,
        /** The shop is not in the active content. */
        UNKNOWN_SHOP,
        /** The client has no Alina mod, so it cannot show the trade screen. */
        CANNOT_OPEN,
        /** The request names a session that is not the player's current one. */
        NO_SESSION,
        /** The session's shop is gone, or the content revision changed; the session is closed. */
        CLOSED,
        /** A trade of the player is still being settled, or settlement refused to begin one; a budget reset waits too. */
        BUSY,
        /** The content or the inventory changed since the client showed the trade. */
        STALE,
        /** A number is out of range, or the request names something the shop does not have. */
        INVALID,
        /** The shop has no Aur left to pay out today. */
        BUDGET_SPENT,
        /** The player does not hold enough Aur for the purchase. */
        CANNOT_AFFORD,
        /** The offer's limit for the player is used up. */
        LIMIT_REACHED,
        /** The player does not meet the offer's requirements. */
        LOCKED,
        /** What the player would receive does not fit in the main inventory. */
        NO_ROOM,
        /** The inventory holds nothing the shop would buy as junk. */
        NOTHING_TO_SELL,
        /** The player does not hold the experience points the trade moves. */
        NOT_ENOUGH_EXPERIENCE,
        /** The shop does not trade experience in the direction asked. */
        NOT_OFFERED
    }
}
