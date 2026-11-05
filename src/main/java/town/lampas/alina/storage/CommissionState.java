package town.lampas.alina.storage;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/** One commission a character has accepted, is cooling down from, or has completed. */
public record CommissionState(
        Identifier commissionId,
        CommissionStatus status,
        int progress,
        Instant acceptedAt,
        Optional<Instant> completedAt,
        Optional<Instant> cooldownUntil,
        long revisionId) {

    public CommissionState {
        Objects.requireNonNull(commissionId, "commissionId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(cooldownUntil, "cooldownUntil");
        if (progress < 0) {
            throw new IllegalArgumentException("progress must not be negative, was " + progress);
        }
    }
}
