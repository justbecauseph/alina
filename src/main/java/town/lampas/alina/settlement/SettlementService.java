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
 * Settles turn-ins and achievement rewards (ALINA-PLAN section 7). The steps, and what each leaves in the player's escrow:
 *
 * <ol>
 *   <li>{@link #begin}, on the server thread: rolls the reward, takes the proof, appends the operation to the escrow and
 *       saves it verified. A failed save keeps the operation in the escrow, and it is saved again before the storage is
 *       called. A refused begin changes nothing.
 *   <li>{@link #tick}, every server tick: calls {@link AlinaStorage#applyOperation} for the first operation that is due,
 *       off the server thread. A player who is dead, or whose entity was removed, is not served.
 *   <li>Back on the server thread, when the storage call completes: delivers the reward stacks that fit, records the
 *       storage result in the escrow and saves verified. The applied listeners run once that save has verified. A failed
 *       save puts the inventory and the escrow back, so the next attempt starts from the saved state. Once the reward is
 *       delivered, the operation leaves the escrow, its row is marked settled, and the settled listeners run.
 * </ol>
 *
 * <p>A player has at most one storage call in flight. A completion is applied only to the entity that started the call,
 * and only while that entity is alive and is still the player's online entity. Otherwise the completion changes nothing,
 * and the entity that is online is served from its own escrow: storage is called again and answers with fresh false.
 *
 * <p>After a failed step an operation waits {@link #RETRY_TICKS} before its next attempt. Everything that touches a player
 * runs on the server thread; storage completions hop back with {@code server.execute}.
 */
public final class SettlementService {
    /** Server ticks an operation waits after a failed attempt: 5 seconds. */
    static final long RETRY_TICKS = 100;
    /**
     * The most modified-UTF-8 bytes the escrow text may hold when {@link #begin} writes it. Vanilla writes the escrow as
     * one string in the player data, and a string above 65535 bytes fails the whole save, so every save of the player
     * would fail. The margin covers the storage results that later commits add to the escrow.
     */
    static final int ESCROW_LIMIT_BYTES = 60_000;

    private final MinecraftServer server;
    private final AlinaStorage storage;
    private final UUID worldId;
    private final Supplier<? extends Collection<ServerPlayer>> online;
    private final SaveProof save;
    private final List<Consumer<SettledOperation>> settledListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<SettledOperation>> appliedListeners = new CopyOnWriteArrayList<>();
    private final Map<UUID, Reconciliation> reconciliation = new LinkedHashMap<>();
    /** Players with a storage call in flight. */
    private final Set<UUID> busy = new HashSet<>();
    /** Operations whose escrow no verified save has written yet. They are saved before the storage is called. */
    private final Set<UUID> unverified = new HashSet<>();
    /** The tick each operation may next be attempted at. */
    private final Map<UUID, Long> retryAt = new HashMap<>();
    /** The tick each player may next be served at, after an escrow that could not be read. */
    private final Map<UUID, Long> pausedUntil = new HashMap<>();
    /** Operations whose applied listeners have run in this server run. */
    private final Set<UUID> announced = new HashSet<>();
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
     * @param online the players this service serves. The entity it serves for a player is the one in this collection.
     *     A settlement whose player has left stays unfinished until the player is served again.
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
     * Begins an operation for the player: rolls the reward with a seed taken from the new operation id, takes the proof
     * with the request's removal, and saves the escrow. Server thread only.
     *
     * <p>A refused begin changes nothing: the proof is back where it was, the escrow is not written, and nothing is
     * saved. The caller must keep its own state, such as a commission's board slot, and try again later. A begin is
     * refused when the player already has an operation of this world with the same kind, subject and tier; when the escrow with the new
     * operation would be over {@link #ESCROW_LIMIT_BYTES}; and when the removal or the encoding throws (the failure is
     * logged).
     *
     * @return the operation id, or empty when the operation was refused
     * @throws IllegalArgumentException when the reward's loot table is not on this server, before anything changes
     */
    public Optional<UUID> begin(ServerPlayer player, SettlementRequest request) {
        requireServerThread();
        List<PendingOperation> pending = read(player);
        for (PendingOperation operation : pending) {
            // An operation of another world is never applied here, and stays in the escrow for reconciliation: it does not refuse.
            if (operation.worldId().equals(worldId) && operation.kind() == request.kind()
                    && operation.subjectId().equals(request.subjectId()) && operation.tier() == request.tier()) {
                return Optional.empty();
            }
        }
        UUID operationId = UUID.randomUUID();
        List<ItemStack> rolled = request.reward().map(table -> roll(table, player, operationId)).orElse(List.of());
        Inventory inventory = player.getInventory();
        ItemStack[] before = snapshot(inventory, inventory.getContainerSize());
        List<PendingOperation> next;
        String json;
        try {
            List<ItemStack> removed = Stacks.split(copies(request.removal().remove(player)));
            PendingOperation operation = new PendingOperation(operationId, request.kind(), request.subjectId(),
                    request.tier(), request.revisionId(), worldId, request.reputationDelta(), request.effect(),
                    Instant.now(), rolled, rolled.stream().map(ItemStack::copy).toList(), removed, Optional.empty(),
                    request.commissionCategory(), request.commissionKind());
            next = new ArrayList<>(pending);
            next.add(operation);
            json = EscrowJson.encode(next, registries());
        } catch (RuntimeException failure) {
            restore(inventory, before);
            Alina.LOGGER.error("Alina could not prepare operation {} for {}; the proof is back in the inventory",
                    operationId, player.getUUID(), failure);
            return Optional.empty();
        }
        int bytes = Stacks.modifiedUtf8Length(json);
        if (bytes > ESCROW_LIMIT_BYTES) {
            restore(inventory, before);
            Alina.LOGGER.warn("Alina refused operation {} for {}: its escrow would be {} bytes, over the {} byte limit; "
                    + "the proof is back in the inventory", operationId, player.getUUID(), bytes, ESCROW_LIMIT_BYTES);
            return Optional.empty();
        }
        write(player, json);
        inventory.setChanged();
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

    /**
     * Advances each online player's escrow by at most one step. Called every server tick. A player who is dead, or whose
     * entity was removed, is skipped: the escrow stays as it is until the player's live entity is served.
     */
    public void tick() {
        requireServerThread();
        ticks++;
        for (ServerPlayer player : List.copyOf(online.get())) {
            UUID character = player.getUUID();
            if (!player.isAlive() || busy.contains(character) || ticks < pausedUntil.getOrDefault(character, 0L)) {
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

    /**
     * Adds a listener that runs on the server thread each time an operation's reward reaches its player. It gets the
     * operation once, after the delivery is saved.
     */
    public void addSettledListener(Consumer<SettledOperation> listener) {
        settledListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Adds a listener that runs on the server thread once per operation in this server run, when a verified save first
     * records the storage result. The reward may still be pending then. The listener gets a {@link SettledOperation}, as
     * the settled listeners do, with the result it was recorded with.
     */
    public void addAppliedListener(Consumer<SettledOperation> listener) {
        appliedListeners.add(Objects.requireNonNull(listener, "listener"));
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
        call.whenComplete((result, failure) -> server.execute(() -> {
            if (server.isSameThread()) {
                applied(player, op.operationId(), result, failure);
            } else {
                // The server stopped before storage answered. The escrow keeps the operation without its result, so
                // the next start calls storage again, and storage answers fresh false.
                Alina.LOGGER.warn("Alina dropped the storage answer for settlement operation {} of {}: it finished after "
                        + "the server stopped, and the next start applies the operation again", op.operationId(),
                        player.getUUID());
            }
        }));
    }

    /**
     * Runs when storage has answered for {@code attempted}. The answer is applied only to that entity, while it is alive
     * and is still the player's online entity. Entities are compared with {@code ==}: {@code Entity.equals} compares
     * ids, and a respawn gives the new entity the old id.
     */
    private void applied(ServerPlayer attempted, UUID operationId, ApplyResult result, Throwable failure) {
        busy.remove(attempted.getUUID());
        if (failure != null) {
            Alina.LOGGER.warn("Alina could not apply settlement operation {} for {}; it is tried again in 5 seconds",
                    operationId, attempted.getUUID(), failure);
            return;
        }
        if (liveEntity(attempted.getUUID()) != attempted || !attempted.isAlive()) {
            // The entity that made the call is dead or replaced, so the result is not recorded in the escrow the
            // live entity holds. That entity is served at its next tick, calls storage again, and gets fresh false.
            retryAt.remove(operationId);
            return;
        }
        try {
            List<PendingOperation> ops = read(attempted);
            Optional<PendingOperation> op = ops.stream()
                    .filter(candidate -> candidate.operationId().equals(operationId))
                    .findFirst();
            if (op.isEmpty()) {
                retryAt.remove(operationId);
                return;
            }
            commit(attempted, ops, op.get(), result);
        } catch (RuntimeException failed) {
            pausedUntil.put(attempted.getUUID(), ticks + RETRY_TICKS);
            Alina.LOGGER.error("Alina could not deliver settlement operation {} for {}; paused for 5 seconds",
                    operationId, attempted.getUUID(), failed);
        }
    }

    /**
     * Delivers the stacks that fit, then saves the escrow with the storage result recorded. A save that does not verify
     * puts the inventory and the escrow back as they were, so the operation is attempted again from its saved state. A
     * step with a recorded result that moves no stack saves nothing.
     */
    private void commit(ServerPlayer player, List<PendingOperation> ops, PendingOperation op, ApplyResult result) {
        Inventory inventory = player.getInventory();
        String previous = player.getAttached(SettlementModule.ESCROW);
        boolean recorded = op.applied().isPresent();
        ItemStack[] main = snapshot(inventory, inventory.getNonEquipmentItems().size());
        List<ItemStack> remaining = insert(inventory, op.reward());
        if (recorded && !moved(op.reward(), remaining)) {
            // The escrow already holds the result and nothing went into the inventory: there is nothing to save.
            retryAt.put(op.operationId(), ticks + RETRY_TICKS);
            return;
        }
        boolean finished = remaining.stream().allMatch(ItemStack::isEmpty);
        PendingOperation updated = op.withApplied(result).withReward(remaining);
        List<PendingOperation> next = new ArrayList<>();
        for (PendingOperation candidate : ops) {
            if (!candidate.operationId().equals(op.operationId())) {
                next.add(candidate);
            } else if (!finished) {
                next.add(updated);
            }
        }
        String json;
        try {
            json = next.isEmpty() ? null : EscrowJson.encode(next, registries());
        } catch (RuntimeException failure) {
            restore(inventory, main);
            retryAt.put(op.operationId(), ticks + RETRY_TICKS);
            Alina.LOGGER.error("Alina could not encode the escrow of {} for operation {}; the delivery is tried again "
                    + "in 5 seconds", player.getUUID(), op.operationId(), failure);
            return;
        }
        write(player, json);
        inventory.setChanged();
        if (!save.save(player, json)) {
            restore(inventory, main);
            write(player, previous);
            inventory.setChanged();
            retryAt.put(op.operationId(), ticks + RETRY_TICKS);
            Alina.LOGGER.warn("Alina could not verify the player data save of {} while delivering operation {}; the "
                    + "delivery is tried again in 5 seconds", player.getUUID(), op.operationId());
            return;
        }
        markVerified(next);
        unverified.remove(op.operationId());
        if (!recorded) {
            announce(player, updated, result);
        }
        if (finished) {
            retryAt.remove(op.operationId());
            settle(player, updated, result);
        } else {
            retryAt.put(op.operationId(), ticks + RETRY_TICKS);
        }
    }

    /** Runs the applied listeners, once per operation in this server run. */
    private void announce(ServerPlayer player, PendingOperation op, ApplyResult result) {
        if (!announced.add(op.operationId())) {
            return;
        }
        SettledOperation applied = settledOf(player, op, result);
        for (Consumer<SettledOperation> listener : appliedListeners) {
            try {
                listener.accept(applied);
            } catch (RuntimeException failure) {
                Alina.LOGGER.error("An applied listener failed for operation {}", op.operationId(), failure);
            }
        }
    }

    /** Marks the operation settled in storage, write-behind, and tells the listeners. */
    private void settle(ServerPlayer player, PendingOperation op, ApplyResult result) {
        announced.remove(op.operationId());
        storage.markOperation(op.operationId(), OperationStep.SETTLED, Optional.empty(), Instant.now())
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        Alina.LOGGER.warn("Alina could not mark operation {} settled; it is audit data only",
                                op.operationId(), failure);
                    }
                });
        SettledOperation settled = settledOf(player, op, result);
        for (Consumer<SettledOperation> listener : settledListeners) {
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
        return Stacks.split(copies(server.reloadableRegistries().getLootTable(key).getRandomItems(params, seed)));
    }

    /**
     * Moves each stack into the main inventory, and returns, in the same order, what did not fit (an empty stack when all
     * of it did). A stack first fills the main stacks of the same item and components that have room, then takes empty
     * main slots. The offhand and the armour are never touched, and nothing is discarded.
     */
    private static List<ItemStack> insert(Inventory inventory, List<ItemStack> reward) {
        List<ItemStack> remaining = new ArrayList<>(reward.size());
        for (ItemStack stack : reward) {
            ItemStack rest = stack.isEmpty() ? ItemStack.EMPTY : place(inventory, stack);
            remaining.add(rest.isEmpty() ? ItemStack.EMPTY : rest);
        }
        return List.copyOf(remaining);
    }

    /** Places as much of {@code stack} as the main inventory takes, and returns a copy of what is left. */
    private static ItemStack place(Inventory inventory, ItemStack stack) {
        ItemStack work = stack.copy();
        int mainSlots = inventory.getNonEquipmentItems().size();
        for (int slot = 0; slot < mainSlots && !work.isEmpty(); slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, work)) {
                int moved = Math.min(existing.getMaxStackSize() - existing.getCount(), work.getCount());
                if (moved > 0) {
                    existing.grow(moved);
                    work.setCount(work.getCount() - moved);
                }
            }
        }
        for (int slot = 0; slot < mainSlots && !work.isEmpty(); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                int moved = Math.min(work.getMaxStackSize(), work.getCount());
                if (moved > 0) {
                    inventory.setItem(slot, work.copyWithCount(moved));
                    work.setCount(work.getCount() - moved);
                }
            }
        }
        return work;
    }

    /** Whether any stack's count went down between the two parallel lists. */
    private static boolean moved(List<ItemStack> before, List<ItemStack> after) {
        for (int i = 0; i < before.size(); i++) {
            if (before.get(i).getCount() != after.get(i).getCount()) {
                return true;
            }
        }
        return false;
    }

    /** Copies of the first {@code slots} inventory slots. */
    private static ItemStack[] snapshot(Inventory inventory, int slots) {
        ItemStack[] copies = new ItemStack[slots];
        for (int slot = 0; slot < slots; slot++) {
            copies[slot] = inventory.getItem(slot).copy();
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

    private SettledOperation settledOf(ServerPlayer player, PendingOperation op, ApplyResult result) {
        return new SettledOperation(player, operation(op, player.getUUID()), result, op.commissionCategory(),
                op.commissionKind());
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

    /**
     * The entity the service serves for the player's id now, or null. Entities are compared by identity elsewhere, never
     * with {@code Entity.equals}.
     */
    private ServerPlayer liveEntity(UUID character) {
        for (ServerPlayer candidate : online.get()) {
            if (candidate.getUUID().equals(character)) {
                return candidate;
            }
        }
        return null;
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
