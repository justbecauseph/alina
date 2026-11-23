package town.lampas.alina.trade;

import java.util.Optional;
import net.minecraft.network.chat.Component;
import town.lampas.alina.content.TradeContent.Offer;

/**
 * One offer as the player stands with it now.
 *
 * @param offer the offer
 * @param period the period its limit counts under, {@code character} or the day's ISO date; empty without a limit
 * @param limitLeft the purchases left in the limit's period, or -1 when the offer has no limit
 * @param locked why the player may not buy the offer now, empty when they may
 */
record OfferState(Offer offer, Optional<String> period, int limitLeft, Optional<Component> locked) {}
