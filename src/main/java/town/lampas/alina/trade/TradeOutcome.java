package town.lampas.alina.trade;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.network.chat.Component;

/**
 * What a trade request came to: accepted, or refused with why. {@link #line} is the line the shop says either way.
 *
 * @param refusal the refusal, empty when the request was carried out
 * @param line the shop's line for the outcome
 */
public record TradeOutcome(Optional<TradeRefusal> refusal, Component line) {

    public TradeOutcome {
        Objects.requireNonNull(refusal, "refusal");
        Objects.requireNonNull(line, "line");
    }

    public boolean accepted() {
        return refusal.isEmpty();
    }

    /** An accepted request, with the shop's line for it. */
    public static TradeOutcome ok(Component line) {
        return new TradeOutcome(Optional.empty(), line);
    }

    /** A refused request. Its line is the refusal's message. */
    public static TradeOutcome refused(TradeRefusal refusal) {
        return new TradeOutcome(Optional.of(refusal), refusal.message());
    }
}
