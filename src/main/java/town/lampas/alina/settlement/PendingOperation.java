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
 * @param rolled the reward the loot table rolled, one entry per stack, stored once. Each entry is at most its max stack size.
 * @param reward parallel to {@code rolled}: the part of each rolled stack still to deliver. An empty stack once that
 *     stack is delivered. Before the storage result is recorded, it equals {@code rolled}.
 * @param removed the proof stacks the removal took, kept for audit
 * @param applied the storage result, once the database step has finished, and empty until then. The storage call is
 *     not made again once it is present, so the reward is delivered from the escrow alone.
 * @param commissionCategory the turn-in's commission category, A to F, when the request named it
 * @param commissionKind the turn-in's commission kind, {@code repeatable} or {@code feat}, when the request named it
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
        Optional<ApplyResult> applied,
        Optional<Character> commissionCategory,
        Optional<String> commissionKind) {

    public PendingOperation {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(applied, "applied");
        Objects.requireNonNull(commissionCategory, "commissionCategory");
        Objects.requireNonNull(commissionKind, "commissionKind");
        rolled = List.copyOf(rolled);
        reward = List.copyOf(reward);
        removed = List.copyOf(removed);
        if (reward.size() != rolled.size()) {
            throw new IllegalArgumentException("reward has " + reward.size() + " entries for " + rolled.size()
                    + " rolled stacks");
        }
        for (int i = 0; i < rolled.size(); i++) {
            ItemStack left = reward.get(i);
            if (!left.isEmpty() && (left.getCount() > rolled.get(i).getCount()
                    || !ItemStack.isSameItemSameComponents(left, rolled.get(i)))) {
                throw new IllegalArgumentException("reward entry " + i + " is not part of the rolled stack");
            }
        }
        if (commissionCategory.isPresent() != commissionKind.isPresent()) {
            throw new IllegalArgumentException("the commission category and kind come together");
        }
    }

    /** This operation with {@code remaining} as the part of each rolled stack still to deliver. */
    PendingOperation withReward(List<ItemStack> remaining) {
        return new PendingOperation(operationId, kind, subjectId, tier, revisionId, worldId, reputationDelta, effect,
                createdAt, rolled, remaining, removed, applied, commissionCategory, commissionKind);
    }

    /** This operation with the storage result recorded. */
    PendingOperation withApplied(ApplyResult result) {
        return new PendingOperation(operationId, kind, subjectId, tier, revisionId, worldId, reputationDelta, effect,
                createdAt, rolled, reward, removed, Optional.of(Objects.requireNonNull(result, "result")),
                commissionCategory, commissionKind);
    }
}
