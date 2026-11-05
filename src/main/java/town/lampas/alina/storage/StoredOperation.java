package town.lampas.alina.storage;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** An operation with its current step, the time of that step, and a note staff or recovery left on it. */
public record StoredOperation(Operation operation, OperationStep step, Instant updatedAt, Optional<String> note) {

    public StoredOperation {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(note, "note");
    }
}
