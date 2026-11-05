package town.lampas.alina.settlement;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import town.lampas.alina.Alina;
import town.lampas.alina.storage.AlinaStorage;
import town.lampas.alina.storage.ApplyResult;
import town.lampas.alina.storage.Operation;
import town.lampas.alina.storage.OperationStep;

/**
 * Settles turn-ins and achievement rewards (ALINA-PLAN §7). The steps, and what each leaves in the player's escrow:
 *
 * <ol>
 *   <li>{@link #begin}, on the server thread: rolls the reward, runs the proof removal, appends the operation to
 *       the escrow and saves it verified. A failed save keeps the operation in the escrow, and it is saved again
 *       before the storage is called.
 *   <li>{@link #tick}, every server tick: calls {@link AlinaStorage#applyOperation} for the first operation that
 *       is due, off the server thread.
 *   <li>Back on the server thread, when the storage call completes: delivers the reward stacks that fit, records
 *       the storage result in the escrow and saves verified. A failed save puts the inventory and the escrow back,
 *       so the next attempt starts from the saved state. Once the reward is delivered, the operation leaves the
 *       escrow, its row is marked settled, and the settled listeners run.
 * </ol>
 *
 * <p>A player has at most one storage call in flight. After a failed step an operation waits {@link #RETRY_TICKS}
 * before its next attempt. Everything that touches a player runs on the server thread; storage completions hop back
 * with {@code server.execute}.
 */
public final class SettlementService {
    /** Server ticks an operation waits after a failed attempt: 5 seconds. */
    static final long RETRY_TICKS = 100;

    private final MinecraftServer server;
    private final AlinaStorage storage;
    private final UUID worldId;
    private final Supplier<? extends Collection<ServerPlayer>> online;
    private final SaveProof save;
    private final List<Consumer<SettledOperation>> listeners = new CopyOnWriteArrayList<>();
    private final Map<UUID, Reconciliation> reconciliation = new LinkedHashMap<>();
    /** Players with a storage call in flight. */
    private final Set<UUID> busy = new HashSet<>();
    /** Operations whose escrow no verified save has written yet. They are saved before the storage is called. */
    private final Set<UUID> unverified = new HashSet<>();
    /** The tick each operation may next be attempted at. */
    private final Map<UUID, Long> retryAt = new HashMap<>();
    /** The tick each player may next be served at, after an escrow that could not be read. */
    private final Map<UUID, Long> pausedUntil = new HashMap<>();
    private long ticks;

    /**
     * A service for the running server. Its players are the server's player list, and its saves are verified through
     * {@link PlayerDataSaves#saveAndVerify}.
     */
    public SettlementService(MinecraftServer server, AlinaStorage storage, UUID worldId) {
        this(server, storage, worldId, () -> server.getPlayerList().getPlayers(),
                (player, expected) -> PlayerDataSaves.saveAndVerify(server, player, expected));
    }

    /**
     * @param online the players this service serves. A settlement whose player has left stays unfinished until the
     *     player is served again.
     * @param save the save step, verified as {@link PlayerDataSaves#saveAndVerify} verifies it
     */
    public SettlementService(MinecraftServer server, AlinaStorage storage, UUID worldId,
            Supplier<? extends Collection<ServerPlayer>> online, SaveProof save) {
        this.server = Objects.requireNonNull(server, "server");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        this.online = Objects.requireNonNull(online, "online");
        this.save = Objects.requireNonNull(save, "save");
    }

    /** Saves the player's data with the escrow it should hold, and reports whether the saved file holds it. */
    @FunctionalInterface
    public interface SaveProof {
        /** {@code expected} is the escrow text, or null when the escrow should be absent. */
        boolean save(ServerPlayer player, String expected);
    }

    /** The world id operations of this service carry. */
    public UUID worldId() {
        return worldId;
    }

    /**
     * Begins an operation for the player: rolls the reward with a seed taken from the new operation id, runs the
     * removal, and saves the escrow. Server thread only.
     *
     * @return the operation id, or empty when the player already has a pending operation of the same kind and
     *     subject. Then nothing changes.
     * @throws IllegalArgumentException when the reward's loot table is not on this server
     */
    public Optional<UUID> begin(ServerPlayer player, SettlementRequest request) {
        requireServerThread();
        List<PendingOperation> pending = read(player);
        for (PendingOperation operation : pending) {
            if (operation.kind() == request.kind() && operation.subjectId().equals(request.subjectId())) {
                return Optional.empty();
            }
        }
        UUID operationId = UUID.randomUUID();
        List<ItemStack> rolled = request.reward().map(table -> roll(table, player, operationId)).orElse(List.of());
        List<ItemStack> removed = copies(request.removal().remove(player));
        PendingOperation operation = new PendingOperation(operationId, request.kind(), request.subjectId(),
                request.tier(), request.revisionId(), worldId, request.reputationDelta(), request.effect(),
                Instant.now(), rolled, rolled, removed, Optional.empty());
        List<PendingOperation> next = new ArrayList<>(pending);
        next.add(operation);
        String json = EscrowJson.encode(next, registries());
        write(player, json);
        player.getInventory().setChanged();
        if (save.save(player, json)) {
            markVerified(next);
        } else {
            unverified.add(operationId);
            retryAt.put(operationId, ticks + RETRY_TICKS);
            Alina.LOGGER.warn("Alina could not verify the player data save of {}; operation {} stays in the escrow "
                    + "and is saved again", player.getUUID(), operationId);
        }
        return Optional.of(operationId);
    }

    /** Advances each online player's escrow by at most one step. Called every server tick. */
    public void tick() {
        requireServerThread();
        ticks++;
        for (ServerPlayer player : List.copyOf(online.get())) {
            UUID character = player.getUUID();
            if (busy.contains(character) || ticks < pausedUntil.getOrDefault(character, 0L)) {
                continue;
            }
            try {
                serve(player);
            } catch (RuntimeException failure) {
                pausedUntil.put(character, ticks + RETRY_TICKS);
                Alina.LOGGER.error("Alina could not read the escrow of {}; it is paused for 5 seconds", character,
                        failure);
            }
        }
    }

    /** Adds a listener that runs on the server thread each time an operation's reward reaches its player. */
    public void addSettledListener(Consumer<SettledOperation> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** The player's pending operations, as the escrow holds them. Server thread only. */
    public List<PendingOperation> pendingFor(ServerPlayer player) {
        requireServerThread();
        return read(player);
    }

    /** The operations found in an escrow of another world, for staff. Listed in memory only. */
    public List<Reconciliation> reconciliation() {
        return List.copyOf(reconciliation.values());
    }

    private void serve(ServerPlayer player) {
        UUID character = player.getUUID();
        String escrow = player.getAttached(SettlementModule.ESCROW);
        List<PendingOperation> ops = escrow == null ? List.of() : EscrowJson.decode(escrow, registries());
        reconciliation.values().removeIf(entry -> entry.player().equals(character)
                && ops.stream().noneMatch(op -> op.operationId().equals(entry.operation().operationId())));
        for (PendingOperation op : ops) {
            if (!op.worldId().equals(worldId)) {
                reconcile(character, op);
                continue;
            }
            if (ticks < retryAt.getOrDefault(op.operationId(), 0L)) {
                continue;
            }
            if (op.applied().isPresent()) {
                commit(player, ops, op, op.applied().get());
                return;
            }
            if (unverified.contains(op.operationId())) {
                if (!save.save(player, escrow)) {
                    retryAt.put(op.operationId(), ticks + RETRY_TICKS);
                    Alina.LOGGER.warn("Alina could not verify the player data save of {}; operation {} is saved again "
                            + "in 5 seconds", character, op.operationId());
                    return;
                }
                markVerified(ops);
            }
            apply(player, op);
            return;
        }
    }

    /** Calls storage for the operation. The completion returns to the server thread through server.execute. */
    private void apply(ServerPlayer player, PendingOperation op) {
        UUID character = player.getUUID();
        busy.add(character);
        retryAt.put(op.operationId(), ticks + RETRY_TICKS);
        CompletableFuture<ApplyResult> call;
        try {
            call = storage.applyOperation(operation(op, character));
        } catch (RuntimeException failure) {
            call = CompletableFuture.failedFuture(failure);
        }
        call.whenComplete((result, failure) ->
                server.execute(() -> applied(player, op.operationId(), result, failure)));
    }

    private void applied(ServerPlayer player, UUID operationId, ApplyResult result, Throwable failure) {
        busy.remove(player.getUUID());
        if (failure != null) {
            Alina.LOGGER.warn("Alina could not apply settlement operation {} for {}; it is tried again in 5 seconds",
                    operationId, player.getUUID(), failure);
            return;
        }
        if (!isOnline(player)) {
            // The escrow still lacks the result. The next join calls storage again, and gets the same answer.
            retryAt.remove(operationId);
            return;
        }
        try {
            List<PendingOperation> ops = read(player);
            Optional<PendingOperation> op = ops.stream()
                    .filter(candidate -> candidate.operationId().equals(operationId))
                    .findFirst();
            if (op.isEmpty()) {
                retryAt.remove(operationId);
                return;
            }
            commit(player, ops, op.get(), result);
        } catch (RuntimeException failed) {
            pausedUntil.put(player.getUUID(), ticks + RETRY_TICKS);
            Alina.LOGGER.error("Alina could not deliver settlement operation {} for {}; paused for 5 seconds",
                    operationId, player.getUUID(), failed);
        }
    }

    /**
     * Delivers the stacks that fit, then saves the escrow with the storage result recorded. A save that does not
     * verify puts the inventory and the escrow back as they were, so the operation is attempted again from its saved
     * state.
     */
    private void commit(ServerPlayer player, List<PendingOperation> ops, PendingOperation op, ApplyResult result) {
        Inventory inventory = player.getInventory();
        String previous = player.getAttached(SettlementModule.ESCROW);
        ItemStack[] slots = snapshot(inventory);
        List<ItemStack> remaining = insert(inventory, op.reward());
        boolean finished = remaining.isEmpty();
        PendingOperation updated = op.withApplied(result).withReward(remaining);
        List<PendingOperation> next = new ArrayList<>();
        for (PendingOperation candidate : ops) {
            if (!candidate.operationId().equals(op.operationId())) {
                next.add(candidate);
            } else if (!finished) {
                next.add(updated);
            }
        }
        String json = next.isEmpty() ? null : EscrowJson.encode(next, registries());
        write(player, json);
        inventory.setChanged();
        if (!save.save(player, json)) {
            restore(inventory, slots);
            write(player, previous);
            inventory.setChanged();
            retryAt.put(op.operationId(), ticks + RETRY_TICKS);
            Alina.LOGGER.warn("Alina could not verify the player data save of {} while delivering operation {}; the "
                    + "delivery is tried again in 5 seconds", player.getUUID(), op.operationId());
            return;
        }
        markVerified(next);
        unverified.remove(op.operationId());
        if (finished) {
            retryAt.remove(op.operationId());
            settle(player, op, result);
        } else {
            retryAt.put(op.operationId(), ticks + RETRY_TICKS);
        }
    }

    /** Marks the operation settled in storage, write-behind, and tells the listeners. */
    private void settle(ServerPlayer player, PendingOperation op, ApplyResult result) {
        storage.markOperation(op.operationId(), OperationStep.SETTLED, Optional.empty(), Instant.now())
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        Alina.LOGGER.warn("Alina could not mark operation {} settled; it is audit data only",
                                op.operationId(), failure);
                    }
                });
        SettledOperation settled = new SettledOperation(player, operation(op, player.getUUID()), result);
        for (Consumer<SettledOperation> listener : listeners) {
            try {
                listener.accept(settled);
            } catch (RuntimeException failure) {
                Alina.LOGGER.error("A settled listener failed for operation {}", op.operationId(), failure);
            }
        }
    }

    private void reconcile(UUID character, PendingOperation op) {
        if (reconciliation.putIfAbsent(op.operationId(), new Reconciliation(character, op)) == null) {
            Alina.LOGGER.warn("Alina operation {} for {} was made in world {}, not {}; it is not applied and is listed "
                    + "for reconciliation", op.operationId(), character, op.worldId(), worldId);
        }
    }

    private List<ItemStack> roll(Identifier table, ServerPlayer player, UUID operationId) {
        ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, table);
        boolean exists = server.reloadableRegistries().lookup().lookup(Registries.LOOT_TABLE)
                .flatMap(tables -> tables.get(key))
                .isPresent();
        if (!exists) {
            throw new IllegalArgumentException("loot table " + table + " is not on this server");
        }
        LootParams params = new LootParams.Builder(player.level())
                .withParameter(LootContextParams.THIS_ENTITY, player)
                .withParameter(LootContextParams.ORIGIN, player.position())
                .create(LootContextParamSets.GIFT);
        long seed = operationId.getMostSignificantBits() ^ operationId.getLeastSignificantBits();
        return copies(server.reloadableRegistries().getLootTable(key).getRandomItems(params, seed));
    }

    /** Inserts each stack that fits, and returns copies of what does not fit, in order. */
    private static List<ItemStack> insert(Inventory inventory, List<ItemStack> stacks) {
        List<ItemStack> remaining = new ArrayList<>();
        for (ItemStack stack : stacks) {
            ItemStack work = stack.copy();
            // Vanilla's add discards a stack that finds no room when the player is in creative mode. Checking for room
            // first keeps the stack in the escrow instead.
            if (inventory.getFreeSlot() >= 0 || inventory.getSlotWithRemainingSpace(work) >= 0) {
                inventory.add(work);
            }
            if (!work.isEmpty()) {
                remaining.add(work);
            }
        }
        return remaining;
    }

    private static ItemStack[] snapshot(Inventory inventory) {
        NonNullList<ItemStack> items = inventory.getNonEquipmentItems();
        ItemStack[] copies = new ItemStack[items.size()];
        for (int slot = 0; slot < copies.length; slot++) {
            copies[slot] = items.get(slot).copy();
        }
        return copies;
    }

    private static void restore(Inventory inventory, ItemStack[] slots) {
        for (int slot = 0; slot < slots.length; slot++) {
            if (!ItemStack.matches(inventory.getItem(slot), slots[slot])) {
                inventory.setItem(slot, slots[slot]);
            }
        }
    }

    private static List<ItemStack> copies(List<ItemStack> stacks) {
        return Objects.requireNonNull(stacks, "stacks").stream()
                .filter(stack -> !stack.isEmpty())
                .map(ItemStack::copy)
                .toList();
    }

    private Operation operation(PendingOperation op, UUID character) {
        return new Operation(op.operationId(), character, op.kind(), op.subjectId(), op.tier(), op.revisionId(),
                op.worldId().toString(), EscrowJson.rewardJson(op.rolled(), registries()), op.reputationDelta(),
                op.effect(), op.createdAt());
    }

    private List<PendingOperation> read(ServerPlayer player) {
        String escrow = player.getAttached(SettlementModule.ESCROW);
        return escrow == null ? List.of() : EscrowJson.decode(escrow, registries());
    }

    private static void write(ServerPlayer player, String json) {
        if (json == null) {
            player.removeAttached(SettlementModule.ESCROW);
        } else {
            player.setAttached(SettlementModule.ESCROW, json);
        }
    }

    private void markVerified(List<PendingOperation> ops) {
        for (PendingOperation op : ops) {
            unverified.remove(op.operationId());
        }
    }

    private boolean isOnline(ServerPlayer player) {
        return online.get().contains(player);
    }

    private HolderLookup.Provider registries() {
        return server.registryAccess();
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Alina settlement runs on the server thread");
        }
    }
}
