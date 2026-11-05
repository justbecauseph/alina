package town.lampas.alina.achievement;

import java.time.Instant;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import town.lampas.alina.content.Reward;

/**
 * A step of an achievement's state that storage has not taken yet: an earn, or a cycle reset. The record holds it until
 * storage has the step, and {@link AchievementService} retries it. Fields are read and written on the server thread.
 */
final class AchievementEarn {
    enum Kind {
        /** An earn with a reward: its counts are written, then settlement begins the reward. */
        REWARD,
        /** An earn without a reward: its counts and its state are written. */
        DIRECT,
        /** A new cycle: the state is written with tier 0 and the counts are deleted. */
        RESET
    }

    final Kind kind;
    final Identifier achievement;
    /** The tier earned, 1-based: 1 for an achievement without tiers, 0 for a reset. */
    final int tier;
    /** The stored tier: 0 for an achievement without tiers, the tier for a tiered one. */
    final int storageTier;
    /** The time of the earn, or of the reset. */
    final Instant at;
    /** When the earn makes the achievement available again, for a repeatable whose cycle is complete. */
    final Optional<Instant> availableAt;
    /** The reward of a {@link Kind#REWARD} earn, empty otherwise. */
    final Reward reward;
    /** For a {@link Kind#REWARD} earn: its counts are in storage, so only the settlement begin is left. */
    boolean countsWritten;
    /** A storage call of this step is in flight. */
    boolean inFlight;
    /** The server tick the next attempt may run at. */
    long retryAt;

    private AchievementEarn(Kind kind, Identifier achievement, int tier, int storageTier, Instant at,
            Optional<Instant> availableAt, Reward reward) {
        this.kind = kind;
        this.achievement = achievement;
        this.tier = tier;
        this.storageTier = storageTier;
        this.at = at;
        this.availableAt = availableAt;
        this.reward = reward;
    }

    static AchievementEarn reward(Identifier achievement, int tier, int storageTier, Instant at,
            Optional<Instant> availableAt, Reward reward) {
        return new AchievementEarn(Kind.REWARD, achievement, tier, storageTier, at, availableAt, reward);
    }

    static AchievementEarn direct(Identifier achievement, int tier, int storageTier, Instant at,
            Optional<Instant> availableAt) {
        return new AchievementEarn(Kind.DIRECT, achievement, tier, storageTier, at, availableAt, Reward.NONE);
    }

    static AchievementEarn reset(Identifier achievement, Instant at) {
        return new AchievementEarn(Kind.RESET, achievement, 0, 0, at, Optional.empty(), Reward.NONE);
    }
}
