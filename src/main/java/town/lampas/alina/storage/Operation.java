package town.lampas.alina.storage;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.Identifier;

/**
 * One turn-in or achievement reward, as the settlement path records it. The operation id is the idempotency key: applying
 * an operation again changes nothing.
 *
 * <p>{@code rewardJson} is JSON text. Storage keeps it in canonical form: compact, object keys sorted, numbers in plain
 * notation. Read back, it therefore can differ from the text written, but it is the same JSON.
 */
public record Operation(
        UUID operationId,
        UUID characterId,
        OperationKind kind,
        Identifier subjectId,
        int tier,
        long revisionId,
        String worldId,
        String rewardJson,
        int reputationDelta,
        OperationEffect effect,
        Instant createdAt) {

    public Operation {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(characterId, "characterId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(rewardJson, "rewardJson");
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
