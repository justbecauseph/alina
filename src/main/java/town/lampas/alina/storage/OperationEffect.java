package town.lampas.alina.storage;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/** What an {@link Operation} does to the character's commissions or achievements. Reputation is the operation's own delta. */
public sealed interface OperationEffect permits OperationEffect.CommissionSettled, OperationEffect.AchievementEarned {

    /**
     * A turn-in. The commission becomes {@link CommissionStatus#COOLDOWN} or {@link CommissionStatus#COMPLETED}.
     * {@code cooldownUntil} is present for {@code COOLDOWN} and absent for {@code COMPLETED}.
     */
    record CommissionSettled(Identifier commissionId, CommissionStatus status, Optional<Instant> cooldownUntil)
            implements OperationEffect {

        public CommissionSettled {
            Objects.requireNonNull(commissionId, "commissionId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(cooldownUntil, "cooldownUntil");
            if (status != CommissionStatus.COOLDOWN && status != CommissionStatus.COMPLETED) {
                throw new IllegalArgumentException("a turn-in settles to COOLDOWN or COMPLETED, not " + status);
            }
            if (status == CommissionStatus.COOLDOWN && cooldownUntil.isEmpty()) {
                throw new IllegalArgumentException("a turn-in to COOLDOWN needs the end of its cooldown");
            }
            if (status == CommissionStatus.COMPLETED && cooldownUntil.isPresent()) {
                throw new IllegalArgumentException("a turn-in to COMPLETED has no cooldown to end, but has "
                        + cooldownUntil.get());
            }
        }
    }

    /**
     * One earn of an achievement or tier. The achievement keeps the higher of its old tier and {@code tier}, and
     * {@code availableAt} is when this earn makes it available again.
     */
    record AchievementEarned(Identifier achievementId, int tier, Optional<Instant> availableAt)
            implements OperationEffect {

        public AchievementEarned {
            Objects.requireNonNull(achievementId, "achievementId");
            Objects.requireNonNull(availableAt, "availableAt");
            if (tier < 0) {
                throw new IllegalArgumentException("tier must not be negative, was " + tier);
            }
        }
    }
}
