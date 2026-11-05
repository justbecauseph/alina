package town.lampas.alina.storage;

/**
 * Where a character's commission stands. {@code ACTIVE} is accepted and in progress, {@code COOLDOWN} was turned in and
 * may be taken again later, and {@code COMPLETED} was turned in for good.
 */
public enum CommissionStatus {
    ACTIVE,
    COOLDOWN,
    COMPLETED
}
