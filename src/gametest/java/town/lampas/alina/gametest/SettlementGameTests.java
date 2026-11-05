package town.lampas.alina.gametest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import town.lampas.alina.Alina;
import town.lampas.alina.settlement.PendingOperation;
import town.lampas.alina.settlement.PlayerDataSaves;
import town.lampas.alina.settlement.SettledOperation;
import town.lampas.alina.settlement.SettlementModule;
import town.lampas.alina.settlement.SettlementRequest;
import town.lampas.alina.settlement.SettlementService;
import town.lampas.alina.storage.AlinaStorage;
import town.lampas.alina.storage.CommissionStatus;
import town.lampas.alina.storage.InMemoryStorage;
import town.lampas.alina.storage.OperationEffect;
import town.lampas.alina.storage.OperationKind;
import town.lampas.alina.storage.OperationStep;
import town.lampas.alina.storage.StoredOperation;

/**
 * Server GameTests owned by the settlement component (ALINA-PLAN §7 and §12, spike S3). Each test makes its own
 * {@link SettlementService} over an in-memory storage and ticks it by hand, so no test races the server's service. Save
 * and storage failures are injected by wrapping the save step and the storage.
 */
public final class SettlementGameTests {
    private static final int REPUTATION = 2;
    private static final int REVISION = 7;
    private static final int S3_RUNS = 20;
    private static final Identifier COURIER = Alina.id("settlement_test/courier");
    private static final Identifier PLACEHOLDER = Alina.id("rewards/placeholder");
    private static final Identifier TWO_ITEMS = Alina.id("test/two_items");

    @GameTest(maxTicks = 400)
    public void aTurnInIsSettledOnce(GameTestHelper helper) {
        Rig rig = rig(helper, new InMemoryStorage());
        rig.player().getInventory().setItem(0, new ItemStack(Items.PAPER));
        SettlementService service = serviceFor(rig);
        List<SettledOperation> settled = new ArrayList<>();
        service.addSettledListener(settled::add);
        UUID operation = begin(helper, service, rig, turnIn(takeOnePaper(), PLACEHOLDER));
        helper.assertValueEqual(count(rig.player(), Items.PAPER), 0,
                "the proof is not removed when the turn-in begins");

        helper.succeedWhen(() -> {
            service.tick();
            checkSettled(helper, rig, operation, REPUTATION);
            helper.assertValueEqual(count(rig.player(), Items.EMERALD), 1, "emeralds in the inventory");
            helper.assertValueEqual(count(rig.player(), Items.PAPER), 0, "the proof came back");
            helper.assertValueEqual(settled.size(), 1, "settled listener calls");
            helper.assertTrue(settled.get(0).result().fresh(), "the first storage call did not apply the operation");
        });
    }

    @GameTest(maxTicks = 400)
    public void aFailedFirstSaveKeepsTheOperationAndSavesAgainBeforeApplying(GameTestHelper helper) {
        Rig rig = rig(helper, new InMemoryStorage());
        rig.player().getInventory().setItem(0, new ItemStack(Items.PAPER));
        SettlementService service = serviceFor(rig);
        List<SettledOperation> settled = new ArrayList<>();
        service.addSettledListener(settled::add);
        rig.saves().failNext(1);
        UUID operation = begin(helper, service, rig, turnIn(takeOnePaper(), PLACEHOLDER));
        helper.assertValueEqual(count(rig.player(), Items.PAPER), 0, "the proof is not removed when the save fails");
        helper.assertTrue(rig.player().getAttached(SettlementModule.ESCROW) != null, "the operation left the escrow");
        helper.assertTrue(rig.storage().loadOperation(operation).join().isEmpty(),
                "storage applied the operation before its escrow was saved");

        helper.runAfterDelay(20, () -> {
            helper.assertTrue(rig.storage().loadOperation(operation).join().isEmpty(),
                    "storage applied the operation during the wait for the retry");
            helper.assertValueEqual(count(rig.player(), Items.EMERALD), 0,
                    "the reward arrived before the save verified");
        });
        helper.succeedWhen(() -> {
            service.tick();
            checkSettled(helper, rig, operation, REPUTATION);
            helper.assertValueEqual(count(rig.player(), Items.EMERALD), 1, "emeralds in the inventory");
            helper.assertValueEqual(settled.size(), 1, "settled listener calls");
            helper.assertTrue(rig.saves().attempts() >= 2, "the escrow was not saved again");
        });
    }

    @GameTest(maxTicks = 400)
    public void aFailedStorageCallIsRetriedAndAppliedOnce(GameTestHelper helper) {
        AtomicInteger failures = new AtomicInteger(1);
        AtomicInteger calls = new AtomicInteger();
        Rig rig = rig(helper, failingApplies(new InMemoryStorage(), failures, calls));
        rig.player().getInventory().setItem(0, new ItemStack(Items.PAPER));
        SettlementService service = serviceFor(rig);
        List<SettledOperation> settled = new ArrayList<>();
        service.addSettledListener(settled::add);
        UUID operation = begin(helper, service, rig, turnIn(takeOnePaper(), PLACEHOLDER));

        helper.succeedWhen(() -> {
            service.tick();
            checkSettled(helper, rig, operation, REPUTATION);
            helper.assertValueEqual(count(rig.player(), Items.EMERALD), 1, "emeralds in the inventory");
            helper.assertValueEqual(calls.get(), 2, "storage calls, the failed one and the retry");
            helper.assertValueEqual(settled.size(), 1, "settled listener calls");
        });
    }

    @GameTest(maxTicks = 400)
    public void aFailedDeliverySaveRestoresTheEscrowAndTheRetryIsNotFresh(GameTestHelper helper) {
        Rig rig = rig(helper, new InMemoryStorage());
        rig.player().getInventory().setItem(0, new ItemStack(Items.PAPER));
        SettlementService service = serviceFor(rig);
        List<SettledOperation> settled = new ArrayList<>();
        service.addSettledListener(settled::add);
        UUID operation = begin(helper, service, rig, turnIn(takeOnePaper(), PLACEHOLDER));
        // The save before the storage call has verified. The next save is the delivery's, and it fails.
        rig.saves().failNext(1);
        AtomicBoolean restored = new AtomicBoolean();

        helper.succeedWhen(() -> {
            service.tick();
            if (!restored.get()) {
                helper.assertTrue(rig.saves().attempts() >= 2, "the delivery save was not tried");
                helper.assertValueEqual(count(rig.player(), Items.EMERALD), 0,
                        "the failed save left the reward in the inventory");
                List<PendingOperation> pending = service.pendingFor(rig.player());
                helper.assertTrue(pending.size() == 1 && pending.get(0).applied().isEmpty()
                        && pending.get(0).reward().size() == 1,
                        "the escrow was not restored with the operation pending: " + pending);
                restored.set(true);
            }
            checkSettled(helper, rig, operation, REPUTATION);
            helper.assertValueEqual(count(rig.player(), Items.EMERALD), 1, "emeralds in the inventory");
            helper.assertValueEqual(settled.size(), 1, "settled listener calls");
            helper.assertTrue(!settled.get(0).result().fresh(), "the retried storage call applied the operation again");
            helper.assertValueEqual(settled.get(0).result().reputation(), REPUTATION,
                    "reputation in the retry's result");
        });
    }

    @GameTest(maxTicks = 400)
    public void aNewServiceResumesAnOperationAfterARestart(GameTestHelper helper) {
        Rig rig = rig(helper, new InMemoryStorage());
        rig.player().getInventory().setItem(0, new ItemStack(Items.PAPER));
        UUID operation = begin(helper, serviceFor(rig), rig, turnIn(takeOnePaper(), PLACEHOLDER));
        // The service that began the operation is dropped here and never ticked again. The next one reads the escrow.
        SettlementService restarted = serviceFor(rig);
        List<SettledOperation> settled = new ArrayList<>();
        restarted.addSettledListener(settled::add);

        helper.succeedWhen(() -> {
            restarted.tick();
            checkSettled(helper, rig, operation, REPUTATION);
            helper.assertValueEqual(count(rig.player(), Items.EMERALD), 1, "emeralds in the inventory");
            helper.assertValueEqual(settled.size(), 1, "settled listener calls");
        });
    }

    @GameTest(maxTicks = 500)
    public void aFullInventoryKeepsTheRestOfTheRewardPending(GameTestHelper helper) {
        Rig rig = rig(helper, new InMemoryStorage());
        Inventory inventory = rig.player().getInventory();
        fillMainInventory(inventory);
        inventory.setItem(0, new ItemStack(Items.PAPER));
        helper.assertTrue(inventory.getFreeSlot() < 0, "the inventory has a free slot before the turn-in");
        SettlementService service = serviceFor(rig);
        UUID operation = begin(helper, service, rig, turnIn(takeOnePaper(), TWO_ITEMS));
        AtomicBoolean partial = new AtomicBoolean();

        helper.succeedWhen(() -> {
            service.tick();
            if (!partial.get()) {
                helper.assertValueEqual(count(rig.player(), Items.EMERALD), 1,
                        "the first stack did not reach the freed slot");
                helper.assertValueEqual(count(rig.player(), Items.DIAMOND), 0, "the second stack fit a full inventory");
                List<PendingOperation> pending = service.pendingFor(rig.player());
                helper.assertTrue(pending.size() == 1 && pending.get(0).reward().size() == 1,
                        "the rest of the reward is not pending: " + pending);
                inventory.setItem(1, ItemStack.EMPTY);
                partial.set(true);
            }
            checkSettled(helper, rig, operation, REPUTATION);
            helper.assertValueEqual(count(rig.player(), Items.EMERALD), 1, "emeralds in total");
            helper.assertValueEqual(count(rig.player(), Items.DIAMOND), 1, "diamonds in total");
        });
    }

    @GameTest(maxTicks = 300)
    public void anOperationFromAnotherWorldIsNotAppliedAndIsListed(GameTestHelper helper) {
        Rig rig = rig(helper, new InMemoryStorage());
        rig.player().getInventory().setItem(0, new ItemStack(Items.PAPER));
        SettlementService elsewhere = new SettlementService(rig.server(), rig.storage(), UUID.randomUUID(),
                () -> List.of(rig.player()), rig.saves().proof());
        UUID operation = begin(helper, elsewhere, rig, turnIn(takeOnePaper(), PLACEHOLDER));
        SettlementService service = serviceFor(rig);
        AtomicInteger ticks = new AtomicInteger();

        helper.succeedWhen(() -> {
            service.tick();
            // Forty ticks give any storage call from this world time to complete before the checks.
            helper.assertTrue(ticks.incrementAndGet() >= 40, "the ticks have not run yet");
            helper.assertTrue(service.reconciliation().stream()
                            .anyMatch(entry -> entry.operation().operationId().equals(operation)),
                    "the operation is not listed for reconciliation");
            helper.assertValueEqual(count(rig.player(), Items.EMERALD), 0, "the reward of another world was delivered");
            helper.assertTrue(rig.storage().loadOperation(operation).join().isEmpty(),
                    "storage applied an operation of another world");
            helper.assertValueEqual(rig.storage().loadCharacter(rig.player().getUUID()).join().reputation(), 0,
                    "reputation of another world's operation");
            helper.assertTrue(rig.player().getAttached(SettlementModule.ESCROW) != null,
                    "the operation of another world left the escrow");
        });
    }

    @GameTest(maxTicks = 20)
    public void saveAndVerifyReadsBackTheEscrowFromThePrimaryFile(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = mockPlayer(helper);
        player.setAttached(SettlementModule.ESCROW, "[1]");
        helper.assertTrue(PlayerDataSaves.saveAndVerify(server, player, "[1]"),
                "the saved escrow did not read back from the primary file");
        helper.assertTrue(!PlayerDataSaves.saveAndVerify(server, player, "[2]"),
                "an escrow other than the saved one verified");
        player.removeAttached(SettlementModule.ESCROW);
        helper.assertTrue(PlayerDataSaves.saveAndVerify(server, player, null),
                "an absent escrow did not verify as absent");
        helper.succeed();
    }

    /**
     * Spike S3 (ALINA-PLAN §12): what one verified player-data save costs with a full inventory. It logs the
     * timings and never fails on them. The saves must still verify.
     */
    @GameTest(maxTicks = 100)
    public void s3SaveAndVerifyCostWithAFullInventory(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = mockPlayer(helper);
        fillMainInventory(player.getInventory());
        String escrow = "[" + "x".repeat(510) + "]";
        player.setAttached(SettlementModule.ESCROW, escrow);
        double[] millis = new double[S3_RUNS];
        for (int run = 0; run < S3_RUNS; run++) {
            long start = System.nanoTime();
            boolean verified = PlayerDataSaves.saveAndVerify(server, player, escrow);
            millis[run] = (System.nanoTime() - start) / 1_000_000.0;
            helper.assertTrue(verified, "run " + run + " did not verify");
        }
        Arrays.sort(millis);
        double median = (millis[S3_RUNS / 2 - 1] + millis[S3_RUNS / 2]) / 2;
        Alina.LOGGER.info(String.format(Locale.ROOT, "S3 saveAndVerify ms: min %.2f .. median %.2f .. max %.2f",
                millis[0], median, millis[S3_RUNS - 1]));
        helper.succeed();
    }

    /** One test's player, storage, world id and save step. */
    private record Rig(MinecraftServer server, ServerPlayer player, AlinaStorage storage, UUID world, Saves saves) {}

    /** The save step a test injects. {@link #failNext} makes the next saves report failure without writing anything. */
    private static final class Saves {
        private final MinecraftServer server;
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicInteger attempts = new AtomicInteger();

        Saves(MinecraftServer server) {
            this.server = server;
        }

        void failNext(int count) {
            failures.set(count);
        }

        int attempts() {
            return attempts.get();
        }

        SettlementService.SaveProof proof() {
            return (player, expected) -> {
                attempts.incrementAndGet();
                if (failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    return false;
                }
                return PlayerDataSaves.saveAndVerify(server, player, expected);
            };
        }
    }

    /**
     * A survival mock player that is not in the server's player list. {@code makeMockServerPlayerInLevel} joins
     * the list, and the server's own service would then serve the player's escrow alongside the test's.
     */
    private static ServerPlayer mockPlayer(GameTestHelper helper) {
        ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);
        helper.assertTrue(!player.hasInfiniteMaterials(), "the mock player is in creative mode, which discards stacks");
        return player;
    }

    private static Rig rig(GameTestHelper helper, AlinaStorage storage) {
        MinecraftServer server = helper.getLevel().getServer();
        return new Rig(server, mockPlayer(helper), storage, UUID.randomUUID(), new Saves(server));
    }

    private static SettlementService serviceFor(Rig rig) {
        return new SettlementService(rig.server(), rig.storage(), rig.world(), () -> List.of(rig.player()),
                rig.saves().proof());
    }

    private static SettlementRequest turnIn(SettlementRequest.Removal removal, Identifier reward) {
        return new SettlementRequest(OperationKind.TURN_IN, COURIER, 0, REVISION, Optional.of(reward), REPUTATION,
                new OperationEffect.CommissionSettled(COURIER, CommissionStatus.COMPLETED, Optional.empty()), removal);
    }

    private static UUID begin(GameTestHelper helper, SettlementService service, Rig rig, SettlementRequest request) {
        Optional<UUID> operation = service.begin(rig.player(), request);
        helper.assertTrue(operation.isPresent(), "the turn-in was refused");
        return operation.get();
    }

    /** Takes one paper from the main inventory, as a turn-in takes its proof. */
    private static SettlementRequest.Removal takeOnePaper() {
        return player -> {
            Inventory inventory = player.getInventory();
            int slot = inventory.findSlotMatchingItem(new ItemStack(Items.PAPER));
            if (slot < 0) {
                throw new IllegalStateException("no paper to take");
            }
            return List.of(inventory.removeItem(slot, 1));
        };
    }

    private static void fillMainInventory(Inventory inventory) {
        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
            inventory.setItem(slot, new ItemStack(Items.STONE, 64));
        }
    }

    private static int count(ServerPlayer player, Item item) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** The settled state: the escrow is empty, storage holds the reputation once, and the operation is settled. */
    private static void checkSettled(GameTestHelper helper, Rig rig, UUID operation, int reputation) {
        String escrow = rig.player().getAttached(SettlementModule.ESCROW);
        helper.assertTrue(escrow == null, "the escrow is not empty: " + escrow);
        helper.assertValueEqual(rig.storage().loadCharacter(rig.player().getUUID()).join().reputation(), reputation,
                "reputation in storage");
        Optional<StoredOperation> stored = rig.storage().loadOperation(operation).join();
        helper.assertTrue(stored.isPresent() && stored.get().step() == OperationStep.SETTLED,
                "the operation is not settled: " + stored);
    }

    /**
     * A storage whose {@code applyOperation} fails while {@code failures} is positive, and which counts those
     * calls. Every other call reaches {@code delegate}.
     */
    private static AlinaStorage failingApplies(AlinaStorage delegate, AtomicInteger failures, AtomicInteger calls) {
        return (AlinaStorage) Proxy.newProxyInstance(AlinaStorage.class.getClassLoader(),
                new Class<?>[] {AlinaStorage.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("applyOperation")) {
                        calls.incrementAndGet();
                        if (failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                            return CompletableFuture.failedFuture(new IllegalStateException("injected apply failure"));
                        }
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }
}
