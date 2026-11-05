package town.lampas.alina.settlement;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import town.lampas.alina.storage.ApplyResult;
import town.lampas.alina.storage.OperationEffect;
import town.lampas.alina.storage.OperationKind;

/**
 * One operation in a player's escrow: what the player's data holds until the reward is delivered.
 *
 * @param rolled the reward the loot table rolled, as the operation records it
 * @param reward the stacks still to deliver
 * @param removed the proof stacks the removal took, kept for audit
 * @param applied the storage result, once the database step has finished, and empty until then. The storage call is
 *     not made again once it is present, so the reward is delivered from the escrow alone.
 */
public record PendingOperation(
        UUID operationId,
        OperationKind kind,
        Identifier subjectId,
        int tier,
        long revisionId,
        UUID worldId,
        int reputationDelta,
        OperationEffect effect,
        Instant createdAt,
        List<ItemStack> rolled,
        List<ItemStack> reward,
        List<ItemStack> removed,
        Optional<ApplyResult> applied) {

    public PendingOperation {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(applied, "applied");
        rolled = List.copyOf(rolled);
        reward = List.copyOf(reward);
        removed = List.copyOf(removed);
    }

    /** This operation with {@code remaining} as the stacks still to deliver. */
    PendingOperation withReward(List<ItemStack> remaining) {
        return new PendingOperation(operationId, kind, subjectId, tier, revisionId, worldId, reputationDelta, effect,
                createdAt, rolled, remaining, removed, applied);
    }

    /** This operation with the storage result recorded. */
    PendingOperation withApplied(ApplyResult result) {
        return new PendingOperation(operationId, kind, subjectId, tier, revisionId, worldId, reputationDelta, effect,
                createdAt, rolled, reward, removed, Optional.of(Objects.requireNonNull(result, "result")));
    }
}
