package town.lampas.alina.storage;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/** What an {@link Operation} does to the character's commissions, achievements or trade budgets. Reputation is the operation's own delta. */
public sealed interface OperationEffect
        permits OperationEffect.CommissionSettled, OperationEffect.AchievementEarned, OperationEffect.TradeSettled {

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
     * One earn of an achievement or tier. The achievement keeps the higher of its old tier and {@code tier}, and the earn
     * counts once. {@code tier} is 1 for an achievement without tiers, as {@link AchievementState} stores it. {@code availableAt}
     * is when this earn makes it available again. When the stored tier is above {@code tier}, the stored availability stays,
     * so a lower tier applied late does not clear the availability of a higher one; otherwise the earn's {@code availableAt} is
     * stored, even when it is empty.
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

    /**
     * An NPC shop trade. {@code aurPaid} is the Aur the shop paid the character in the trade, and it adds to the shop's
     * budget for {@code tradeDay}. {@code purchases} are the offers bought, and may be empty. {@code detailJson} is a JSON
     * object that describes what was sold and bought, for the trade log. Storage keeps it and does not interpret it. It is
     * {@code "{}"} when there is nothing to say. {@code marketPoints} and {@code marketAur} are the change the trade makes to
     * each reserve of the experience market, and are 0 and 0 for a trade that is not an experience trade against the market.
     * Storage adds them to the market in the trade's transaction, and drops them when there is no market.
     */
    record TradeSettled(Identifier shopId, LocalDate tradeDay, int aurPaid, List<Purchase> purchases, String detailJson,
            long marketPoints, long marketAur) implements OperationEffect {

        /** A trade that makes no change to the experience market. */
        public TradeSettled(Identifier shopId, LocalDate tradeDay, int aurPaid, List<Purchase> purchases,
                String detailJson) {
            this(shopId, tradeDay, aurPaid, purchases, detailJson, 0, 0);
        }

        public TradeSettled {
            Objects.requireNonNull(shopId, "shopId");
            Objects.requireNonNull(tradeDay, "tradeDay");
            Objects.requireNonNull(purchases, "purchases");
            Objects.requireNonNull(detailJson, "detailJson");
            if (aurPaid < 0) {
                throw new IllegalArgumentException("aurPaid must not be negative, was " + aurPaid);
            }
            purchases = List.copyOf(purchases);
            requireJsonObject(detailJson);
        }

        /**
         * One offer bought in a trade. {@code period} is an ISO local date, such as {@code 2026-11-23}, for an offer whose
         * limit is per day, or {@code character} for an offer whose limit is per character. {@code count} is above zero.
         */
        public record Purchase(String offerId, String period, int count) {

            public Purchase {
                Objects.requireNonNull(offerId, "offerId");
                Objects.requireNonNull(period, "period");
                if (offerId.isBlank()) {
                    throw new IllegalArgumentException("offerId must not be blank");
                }
                if (!period.equals(StorageSupport.CHARACTER_PERIOD) && !isIsoDate(period)) {
                    throw new IllegalArgumentException("period must be an ISO date or character, was " + period);
                }
                if (count <= 0) {
                    throw new IllegalArgumentException("count must be positive, was " + count);
                }
            }

            private static boolean isIsoDate(String text) {
                try {
                    return LocalDate.parse(text).toString().equals(text);
                } catch (DateTimeException notADate) {
                    return false;
                }
            }
        }

        private static void requireJsonObject(String text) {
            // CanonicalJson refuses text that is not strict JSON, and returns the object's compact form.
            if (!CanonicalJson.of(text).startsWith("{")) {
                throw new IllegalArgumentException("detailJson must be a JSON object, was " + text);
            }
        }
    }
}
