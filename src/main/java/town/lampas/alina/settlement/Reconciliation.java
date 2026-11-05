package town.lampas.alina.settlement;

import java.util.UUID;

/**
 * An operation in a player's escrow whose world id is not this world's. It is never applied, and staff must
 * reconcile it. Listed in memory only, from the last time the player was served.
 */
public record Reconciliation(UUID player, PendingOperation operation) {}
