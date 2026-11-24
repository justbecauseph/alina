package town.lampas.alina.storage;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Cursor for keyset pagination over operations ordered by {@code createdAt DESC, operationId DESC}. */
public record OperationCursor(Instant createdAt, UUID operationId) {

    public OperationCursor {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(operationId, "operationId");
    }
}
