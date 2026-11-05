package town.lampas.alina.settlement;

import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import town.lampas.alina.storage.ApplyResult;
import town.lampas.alina.storage.Operation;

/**
 * An operation as the settlement listeners receive it. {@code result} is the storage result the operation was recorded
 * with. Its {@code fresh} is false when the database had the operation already, which happens when an earlier storage
 * call applied it but its delivery was not saved. {@code commissionCategory} and {@code commissionKind} are the ones the
 * turn-in's request named, and are empty otherwise.
 *
 * <p>Settled listeners receive this once the reward has reached the player. Applied listeners receive it once storage
 * has recorded the result, before the reward is delivered, so the reward may still be pending for them.
 */
public record SettledOperation(
        ServerPlayer player,
        Operation operation,
        ApplyResult result,
        Optional<Character> commissionCategory,
        Optional<String> commissionKind) {}
