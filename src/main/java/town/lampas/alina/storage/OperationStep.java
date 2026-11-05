package town.lampas.alina.storage;

/**
 * How far an operation has got. {@code RECORDED}: the database applied it once. {@code SETTLED}: its reward reached the
 * player. {@code RECONCILE}: something needs staff attention, such as a world that changed under it.
 */
public enum OperationStep {
    RECORDED,
    SETTLED,
    RECONCILE
}
