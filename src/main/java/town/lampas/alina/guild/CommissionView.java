package town.lampas.alina.guild;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/**
 * One commission of a character's Guild record, as the Guild service holds it.
 *
 * @param standing never {@link Standing#AVAILABLE}: an available commission has no view
 * @param progress events counted for a criterion proof, or 1 once a descent proof is met; 0 otherwise
 * @param cooldownUntil present for {@link Standing#COOLDOWN}
 * @param completedAt when it was turned in, for {@link Standing#COOLDOWN} and {@link Standing#COMPLETED}
 */
public record CommissionView(
        Identifier id,
        Standing standing,
        int progress,
        Instant acceptedAt,
        Optional<Instant> cooldownUntil,
        Optional<Instant> completedAt) {

    public CommissionView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(standing, "standing");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        Objects.requireNonNull(cooldownUntil, "cooldownUntil");
        Objects.requireNonNull(completedAt, "completedAt");
        if (standing == Standing.AVAILABLE) {
            throw new IllegalArgumentException("an available commission has no view");
        }
        if (progress < 0) {
            throw new IllegalArgumentException("progress must not be negative, was " + progress);
        }
    }
}
