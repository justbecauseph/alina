package town.lampas.alina.storage;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Rules both {@link AlinaStorage} implementations apply, so they return the same results. Instants keep only the
 * microsecond, which is the precision of PostgreSQL's {@code timestamptz}. Text may not contain a NUL character, which
 * PostgreSQL's {@code text} and {@code jsonb} cannot store. Schema names are checked because the PostgreSQL backend puts
 * them into SQL text.
 */
final class StorageSupport {
    private static final Pattern SCHEMA_NAME = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    /** How long a closing storage lets its queued work run before it drops the rest. */
    static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(10);

    private StorageSupport() {}

    static Instant micros(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    static Optional<Instant> micros(Optional<Instant> instant) {
        return instant.map(StorageSupport::micros);
    }

    static AchievementState micros(AchievementState achievement) {
        return new AchievementState(achievement.achievementId(), achievement.tier(), achievement.earnCount(),
                micros(achievement.lastEarnedAt()), micros(achievement.availableAt()));
    }

    static void requireNoNul(String text, String what) {
        if (text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(what + " contains a NUL character, which storage cannot hold");
        }
    }

    static void requireNonNegative(int value, String what) {
        if (value < 0) {
            throw new IllegalArgumentException(what + " must not be negative, was " + value);
        }
    }

    static boolean isSchemaName(String name) {
        return SCHEMA_NAME.matcher(name).matches();
    }

    /** The text a column holds for an enum constant: its name in lowercase, as {@code active} or {@code turn_in}. */
    static String stored(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /** The enum constant a column's text names. Fails for text that names none. */
    static <E extends Enum<E>> E fromStored(Class<E> type, String text) {
        return Enum.valueOf(type, text.toUpperCase(Locale.ROOT));
    }

    /**
     * The operation as storage keeps it: reward JSON in canonical form, instants truncated to the microsecond, and no
     * NUL character in its text.
     *
     * @throws IllegalArgumentException when the reward is not JSON, or the text holds a NUL character
     */
    static Operation normalised(Operation operation) {
        requireNoNul(operation.worldId(), "worldId");
        return new Operation(
                operation.operationId(),
                operation.characterId(),
                operation.kind(),
                operation.subjectId(),
                operation.tier(),
                operation.revisionId(),
                operation.worldId(),
                CanonicalJson.of(operation.rewardJson()),
                operation.reputationDelta(),
                normalised(operation.effect()),
                micros(operation.createdAt()));
    }

    static OperationEffect normalised(OperationEffect effect) {
        return switch (effect) {
            case OperationEffect.CommissionSettled settled -> new OperationEffect.CommissionSettled(
                    settled.commissionId(), settled.status(), micros(settled.cooldownUntil()));
            case OperationEffect.AchievementEarned earned -> new OperationEffect.AchievementEarned(
                    earned.achievementId(), earned.tier(), micros(earned.availableAt()));
        };
    }
}
