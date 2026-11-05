package town.lampas.alina.settlement;

import net.minecraft.server.level.ServerPlayer;
import town.lampas.alina.storage.ApplyResult;
import town.lampas.alina.storage.Operation;

/**
 * An operation whose reward has reached the player, as the settled listeners receive it. {@code result} is the storage
 * result the operation was recorded with. Its {@code fresh} is false when the database had the operation already, which
 * happens when an earlier storage call applied it but its delivery was not saved.
 */
public record SettledOperation(ServerPlayer player, Operation operation, ApplyResult result) {}
