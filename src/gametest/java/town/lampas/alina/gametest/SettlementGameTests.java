package town.lampas.alina.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.advancements.triggers.CriterionTrigger;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import town.lampas.alina.Alina;
import town.lampas.alina.content.CriterionSpec;
import town.lampas.alina.criteria.CriterionWatchers;
import town.lampas.alina.settlement.PendingOperation;
import town.lampas.alina.settlement.PlayerDataSaves;
import town.lampas.alina.settlement.SettledOperation;
import town.lampas.alina.settlement.SettlementModule;
import town.lampas.alina.settlement.SettlementRequest;
import town.lampas.alina.settlement.SettlementService;
import town.lampas.alina.settlement.SettlementTriggers;
import town.lampas.alina.storage.AlinaStorage;
import town.lampas.alina.storage.ApplyResult;
import town.lampas.alina.storage.CommissionStatus;
import town.lampas.alina.storage.InMemoryStorage;
import town.lampas.alina.storage.Operation;
import town.lampas.alina.storage.OperationEffect;
import town.lampas.alina.storage.OperationKind;
import town.lampas.alina.storage.OperationStep;
import town.lampas.alina.storage.StoredOperation;

/**
 * Server GameTests owned by the settlement component (ALINA-PLAN section 7 and section 12, spike S3). Most tests make their
 * own {@link SettlementService} over an in-memory storage and tick it by hand, and their players are not in the server's
 * player list, so the server's own service never serves them. The one test that goes through {@code SettlementModule}
 * adds its player to the list, and it is the only test whose service is the server's. Save and storage failures are
 * injected by wrapping the save step and the storage.
 */
@SuppressWarnings("removal")
public final class SettlementGameTests {
    private static final int REPUTATION = 2;
    private static final int REVISION = 7;
    private static final int S3_RUNS = 20;
    private static final int OFFHAND = 40;
    private static final Identifier COURIER = Alina.id("settlement_test/courier");
    private static final Identifier ACHIEVEMENT = Alina.id("settlement_test/achievement");
    private static final Identifier PLACEHOLDER = Alina.id("rewards/placeholder");
    private static final Identifier TWO_ITEMS = Alina.id("test/two_items");
    private static final Identifier FIVE_EMERALDS = Alina.id("test/five_emeralds");

    @GameTest(maxTicks = 400)
    public void aTurnInIsSettledOnce(GameTestHelper helper) {
        Rig rig = rig(helper);
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
        AtomicInteger attemptsAtApply = new AtomicInteger(-1);
        Rig rig = rig(helper, saves -> hooked(new InMemoryStorage(), (delegate, operation) -> {
            attemptsAtApply.compareAndSet(-1, saves.attempts());
            return delegate.applyOperation(operation);
        }));
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
            // The begin save failed (attempt 1). The next tick saved the escrow again and verified it (attempt 2), and
            // only then did storage get the operation. Storage was never called before the second attempt.
            helper.assertValueEqual(attemptsAtApply.get(), 2,
                    "saves made before the storage call: the failed one, then the one that verified");
        });
    }

    @GameTest(maxTicks = 400)
    public void aFailedStorageCallIsRetriedAndAppliedOnce(GameTestHelper helper) {
        AtomicInteger calls = new AtomicInteger();
        Rig rig = rig(helper, saves -> hooked(new InMemoryStorage(), (delegate, operation) -> {
            if (calls.incrementAndGet() == 1) {
                return CompletableFuture.failedFuture(new IllegalStateException("injected apply failure"));
            }
            return delegate.applyOperation(operation);
        }));
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
        Rig rig = rig(helper);
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
                                && stillToDeliver(service, rig.player()).size() == 1,
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
        Rig rig = rig(helper);
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
        Rig rig = rig(helper);
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
                List<ItemStack> pending = stillToDeliver(service, rig.player());
                helper.assertTrue(pending.size() == 1 && pending.get(0).is(Items.DIAMOND),
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
        Rig rig = rig(helper);
        rig.player().getInventory().setItem(0, new ItemStack(Items.PAPER));
        SettlementService elsewhere = new SettlementService(rig.server(), rig.storage(), UUID.randomUUID(),
                rig::online, rig.saves().proof());
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
        ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
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
     * Spike S3 (ALINA-PLAN section 12): what one verified player-data save costs with a full inventory of shulker boxes,
     * armour and an offhand item, and a real pending operation in the escrow. It logs the timings and the saved file's
     * size, and never fails on the timings. The saves must still verify.
     */
    @GameTest(maxTicks = 600)
    public void s3SaveAndVerifyCostWithAFullInventory(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Rig rig = rig(helper);
        Inventory inventory = rig.player().getInventory();
        inventory.setItem(0, new ItemStack(Items.PAPER));
        for (int slot = 1; slot < 36; slot++) {
            inventory.setItem(slot, filledShulker(server, slot));
        }
        inventory.setItem(36, equipment(server, Items.DIAMOND_BOOTS, "Stride"));
        inventory.setItem(37, equipment(server, Items.DIAMOND_LEGGINGS, "Stride"));
        inventory.setItem(38, equipment(server, Items.DIAMOND_CHESTPLATE, "Bulwark"));
        inventory.setItem(39, equipment(server, Items.DIAMOND_HELMET, "Lookout"));
        inventory.setItem(OFFHAND, equipment(server, Items.SHIELD, "Guard"));
        SettlementService service = serviceFor(rig);
        begin(helper, service, rig, turnIn(takeOnePaper(), PLACEHOLDER));
        String escrow = rig.player().getAttached(SettlementModule.ESCROW);
        helper.assertTrue(escrow != null, "the pending operation is not in the escrow");

        double[] millis = new double[S3_RUNS];
        for (int run = 0; run < S3_RUNS; run++) {
            long start = System.nanoTime();
            boolean verified = PlayerDataSaves.saveAndVerify(server, rig.player(), escrow);
            millis[run] = (System.nanoTime() - start) / 1_000_000.0;
            helper.assertTrue(verified, "run " + run + " did not verify");
        }
        Arrays.sort(millis);
        double median = (millis[S3_RUNS / 2 - 1] + millis[S3_RUNS / 2]) / 2;
        Path file = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(rig.player().getUUID() + ".dat");
        long bytes;
        try {
            bytes = Files.size(file);
        } catch (IOException failure) {
            throw new IllegalStateException("could not measure " + file, failure);
        }
        Alina.LOGGER.info(String.format(Locale.ROOT,
                "S3 saveAndVerify ms: min %.2f .. median %.2f .. max %.2f; player data file %d bytes; escrow %d chars",
                millis[0], median, millis[S3_RUNS - 1], bytes, escrow.length()));
        helper.succeed();
    }

    /** The active content for the trigger test: the courier commission, in category C, repeatable. */
    private static final String COURIER_BUNDLE = """
            { "schema": 1,
              "dialogues": { "alina:settlement_test/board": { "start": "hello", "nodes": { "hello": { "text": "Board." } } } },
              "extensions": { "alina:guild": {
                "settings": { "board_graph": "alina:settlement_test/board" },
                "commissions": { "alina:settlement_test/courier": {
                  "category": "C", "kind": "repeatable", "name": "Courier", "hint": "Bring paper.",
                  "proof": { "type": "item", "all_of": [ { "item": { "items": "minecraft:paper" } } ] } } } } } }
            """;

    @GameTest(maxTicks = 400)
    public void aSettledTurnInFiresTheReputationAndCommissionTriggers(GameTestHelper helper) {
        Rig rig = rig(helper);
        UUID character = rig.player().getUUID();
        rig.storage().setReputation(character, 5, Instant.now()).join();
        rig.player().getInventory().setItem(0, new ItemStack(Items.PAPER));
        SettlementService service = serviceFor(rig);
        SettlementTriggers.register(service);
        AtomicInteger sevens = new AtomicInteger();
        AtomicInteger twos = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        CriterionWatchers.Watch seven = CriterionWatchers.watch(character,
                alinaSpec(helper, "alina:reputation_changed", "{ \"reputation\": { \"min\": 7, \"max\": 7 } }"),
                player -> sevens.incrementAndGet());
        CriterionWatchers.Watch two = CriterionWatchers.watch(character,
                alinaSpec(helper, "alina:reputation_changed", "{ \"reputation\": { \"min\": 2, \"max\": 2 } }"),
                player -> twos.incrementAndGet());
        CriterionWatchers.Watch completed = CriterionWatchers.watch(character, alinaSpec(helper,
                "alina:commission_completed",
                "{ \"commission\": \"alina:settlement_test/courier\", \"category\": \"C\", \"kind\": \"repeatable\" }"),
                player -> completions.incrementAndGet());
        UUID operation = begin(helper, service, rig, turnIn(takeOnePaper(), PLACEHOLDER));

        helper.succeedWhen(() -> {
            service.tick();
            checkSettled(helper, rig, operation, 5 + REPUTATION);
            helper.assertValueEqual(count(rig.player(), Items.EMERALD), 1, "emeralds in the inventory");
            helper.assertValueEqual(sevens.get(), 1, "reputation_changed at the reputation after the change, 7");
            helper.assertValueEqual(twos.get(), 0, "reputation_changed at the delta, 2, which is not the reputation");
            helper.assertValueEqual(completions.get(), 1, "commission_completed events for the courier");
            seven.close();
            two.close();
            completed.close();
        });
    }

    @GameTest(maxTicks = 400)
    public void aTurnInWithoutAReputationChangeFiresNoReputationTrigger(GameTestHelper helper) {
        Rig rig = rig(helper);
        rig.player().getInventory().setItem(0, new ItemStack(Items.PAPER));
        SettlementService service = serviceFor(rig);
        SettlementTriggers.register(service);
        AtomicInteger reputationEvents = new AtomicInteger();
        CriterionWatchers.Watch any = CriterionWatchers.watch(rig.player().getUUID(),
                alinaSpec(helper, "alina:reputation_changed", "{}"), player -> reputationEvents.incrementAndGet());
        UUID operation = begin(helper, service, rig,
                turnIn(takeOnePaper(), PLACEHOLDER, 0, Optional.of('C'), Optional.of("repeatable")));

        helper.succeedWhen(() -> {
            service.tick();
            checkSettled(helper, rig, operation, 0);
            helper.assertValueEqual(count(rig.player(), Items.EMERALD), 1, "the reward was not delivered");
            helper.assertValueEqual(reputationEvents.get(), 0, "reputation_changed for a delta of zero");
            any.close();
        });
    }

    @GameTest(maxTicks = 400)
    public void aTurnInWithoutACategoryAndKindFiresNoCommissionTriggerAndStillDelivers(GameTestHelper helper) {
        Rig rig = rig(helper);
        rig.player().getInventory().setItem(0, new ItemStack(Items.PAPER));
        SettlementService service = serviceFor(rig);
        SettlementTriggers.register(service);
        AtomicInteger completions = new AtomicInteger();
        CriterionWatchers.Watch any = CriterionWatchers.watch(rig.player().getUUID(),
                alinaSpec(helper, "alina:commission_completed", "{}"), player -> completions.incrementAndGet());
        UUID operation = begin(helper, service, rig,
                turnIn(takeOnePaper(), PLACEHOLDER, REPUTATION, Optional.empty(), Optional.empty()));

        helper.succeedWhen(() -> {
            service.tick();
            checkSettled(helper, rig, operation, REPUTATION);
            helper.assertValueEqual(count(rig.player(), Items.EMERALD), 1, "the reward was not delivered");
            helper.assertValueEqual(completions.get(), 0, "commission_completed without a category and kind");
            any.close();
        });
    }

    @GameTest(maxTicks = 400)
    public void theServersServiceFiresTheReputationTriggerForATurnIn(GameTestHelper helper) {
        // The one test whose player is in the server's list, so the server's service serves it.
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.getInventory().setItem(0, new ItemStack(Items.PAPER));
        AtomicInteger twos = new AtomicInteger();
        CriterionWatchers.Watch watch = CriterionWatchers.watch(player.getUUID(),
                alinaSpec(helper, "alina:reputation_changed", "{ \"reputation\": { \"min\": 2, \"max\": 2 } }"),
                fired -> twos.incrementAndGet());
        Optional<UUID> operation = SettlementModule.service().begin(player, turnIn(takeOnePaper(), PLACEHOLDER));
        helper.assertTrue(operation.isPresent(), "the turn-in was refused");

        helper.succeedWhen(() -> {
            helper.assertValueEqual(count(player, Items.EMERALD), 1, "emeralds in the inventory");
            helper.assertValueEqual(twos.get(), 1, "reputation_changed from the server's own service");
            helper.assertTrue(player.getAttached(SettlementModule.ESCROW) == null, "the escrow is not empty");
            watch.close();
        });
    }

    @GameTest(maxTicks = 400)
    public void aRestartAfterTheTransactionCommittedDeliversOnceWithoutASecondEffect(GameTestHelper helper) {
        InMemoryStorage memory = new InMemoryStorage();
        Rig rig = rig(helper, saves -> memory);
        rig.player().getInventory().setItem(0, new ItemStack(Items.PAPER));
        // Storage records the operation and then the answer never comes back: a crash after the transaction.
        AlinaStorage crashing = hooked(memory, (delegate, operation) -> {
            delegate.applyOperation(operation).join();
            return new CompletableFuture<>();
        });
        SettlementService beforeCrash = new SettlementService(rig.server(), crashing, rig.world(), rig::online,
                rig.saves().proof());
        UUID operation = begin(helper, beforeCrash, rig, turnIn(takeOnePaper(), PLACEHOLDER));
        beforeCrash.tick();
        helper.assertTrue(memory.loadOperation(operation).join().isPresent(),
                "storage did not record the operation before the crash");
        // The service that made the call is never ticked again. The restarted one reads the escrow of the same player.
        SettlementService restarted = serviceFor(rig);
        List<SettledOperation> settled = new ArrayList<>();
        List<SettledOperation> applied = new ArrayList<>();
        restarted.addSettledListener(settled::add);
        restarted.addAppliedListener(applied::add);

        helper.succeedWhen(() -> {
            restarted.tick();
            checkSettled(helper, rig, operation, REPUTATION);
            helper.assertValueEqual(count(rig.player(), Items.EMERALD), 1, "emeralds in the inventory");
            helper.assertValueEqual(settled.size(), 1, "settled listener calls");
            helper.assertValueEqual(applied.size(), 1, "applied listener calls");
            helper.assertTrue(!settled.get(0).result().fresh(), "the restarted call found the operation recorded");
        });
    }

    @GameTest(maxTicks = 900)
    public void aDeadPlayerReceivesNothingAndTheRestIsDeliveredOnceAfterRespawn(GameTestHelper helper) {
        Rig rig = rig(helper);
        Inventory inventory = rig.player().getInventory();
        fillMainInventory(inventory);
        inventory.setItem(0, new ItemStack(Items.PAPER));
        SettlementService service = serviceFor(rig);
        List<SettledOperation> settled = new ArrayList<>();
        service.addSettledListener(settled::add);
        UUID operation = begin(helper, service, rig, turnIn(takeOnePaper(), TWO_ITEMS));
        AtomicInteger deadTicks = new AtomicInteger();

        helper.startSequence()
                .thenWaitUntil(() -> {
                    service.tick();
                    helper.assertValueEqual(count(rig.player(), Items.EMERALD), 1,
                            "the first stack did not reach the freed slot");
                    helper.assertValueEqual(stillToDeliver(service, rig.player()).size(), 1,
                            "the second stack is not pending");
                })
                .thenExecute(() -> kill(rig.player()))
                .thenWaitUntil(() -> {
                    service.tick();
                    helper.assertTrue(!rig.player().isAlive(), "the player is alive after the kill");
                    helper.assertTrue(deadTicks.incrementAndGet() >= 120, "the ticks while dead have not run yet");
                    helper.assertValueEqual(count(rig.player(), Items.DIAMOND), 0,
                            "a diamond reached the dead inventory");
                    helper.assertValueEqual(stillToDeliver(service, rig.player()).size(), 1,
                            "the reward left the escrow while the player was dead");
                    helper.assertTrue(settled.isEmpty(), "the operation settled while the player was dead");
                })
                .thenExecute(() -> rig.respawn())
                .thenWaitUntil(() -> {
                    service.tick();
                    checkSettled(helper, rig, operation, REPUTATION);
                    helper.assertValueEqual(count(rig.player(), Items.DIAMOND), 1,
                            "diamonds in the respawned player's inventory");
                    helper.assertValueEqual(count(rig.player(), Items.EMERALD), 0,
                            "the emerald of the dead inventory came back");
                    helper.assertValueEqual(settled.size(), 1, "settled listener calls");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 500)
    public void aCompletionForAReplacedEntityCommitsNothingToIt(GameTestHelper helper) {
        List<Runnable> releases = new ArrayList<>();
        Rig rig = rig(helper, saves -> hooked(new InMemoryStorage(), (delegate, operation) -> {
            ApplyResult result = delegate.applyOperation(operation).join();
            CompletableFuture<ApplyResult> held = new CompletableFuture<>();
            releases.add(() -> held.complete(result));
            return held;
        }));
        rig.player().getInventory().setItem(0, new ItemStack(Items.PAPER));
        SettlementService service = serviceFor(rig);
        List<SettledOperation> settled = new ArrayList<>();
        service.addSettledListener(settled::add);
        UUID operation = begin(helper, service, rig, turnIn(takeOnePaper(), PLACEHOLDER));
        ServerPlayer old = rig.player();

        helper.startSequence()
                .thenExecute(() -> {
                    service.tick();
                    helper.assertValueEqual(releases.size(), 1, "the first storage call");
                })
                .thenExecute(() -> {
                    kill(old);
                    rig.respawn();
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    service.tick();
                    helper.assertValueEqual(releases.size(), 1,
                            "the new entity called storage while the old call was in flight");
                    releases.get(0).run();
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertTrue(settled.isEmpty(), "the old call's answer settled the operation");
                    helper.assertValueEqual(count(old, Items.EMERALD), 0, "the reward reached the replaced entity");
                    helper.assertTrue(service.pendingFor(old).get(0).applied().isEmpty(),
                            "the old call's answer was recorded in the replaced entity's escrow");
                    service.tick();
                    helper.assertValueEqual(releases.size(), 2, "storage calls for the new entity");
                    releases.get(1).run();
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    service.tick();
                    checkSettled(helper, rig, operation, REPUTATION);
                    helper.assertValueEqual(count(rig.player(), Items.EMERALD), 1, "emeralds on the new entity");
                    helper.assertValueEqual(settled.size(), 1, "settled listener calls");
                    helper.assertTrue(!settled.get(0).result().fresh(), "the new entity's call applied the operation");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 400)
    public void anOffhandStackIsNotTouchedByAFailedDelivery(GameTestHelper helper) {
        Rig rig = rig(helper);
        Inventory inventory = rig.player().getInventory();
        inventory.setItem(0, new ItemStack(Items.PAPER));
        inventory.setItem(OFFHAND, new ItemStack(Items.EMERALD));
        SettlementService service = serviceFor(rig);
        List<SettledOperation> settled = new ArrayList<>();
        service.addSettledListener(settled::add);
        UUID operation = begin(helper, service, rig, turnIn(takeOnePaper(), PLACEHOLDER));
        rig.saves().failNext(1);
        AtomicBoolean failed = new AtomicBoolean();

        helper.succeedWhen(() -> {
            service.tick();
            if (!failed.get()) {
                helper.assertTrue(rig.saves().attempts() >= 2, "the delivery save was not tried");
                helper.assertValueEqual(count(rig.player(), Items.EMERALD), 1,
                        "the failed delivery left a stack in the inventory or the offhand");
                helper.assertValueEqual(inventory.getItem(OFFHAND).getCount(), 1,
                        "the offhand stack changed by the failed save");
                failed.set(true);
            }
            checkSettled(helper, rig, operation, REPUTATION);
            helper.assertValueEqual(count(rig.player(), Items.EMERALD), 2,
                    "emeralds: the offhand's one and the reward");
            helper.assertValueEqual(inventory.getItem(OFFHAND).getCount(), 1, "the offhand stack after delivery");
            helper.assertValueEqual(settled.size(), 1, "settled listener calls");
        });
    }

    @GameTest(maxTicks = 500)
    public void aCreativePlayerKeepsThePartOfARewardThatDoesNotFit(GameTestHelper helper) {
        Rig rig = rig(helper, GameType.CREATIVE, saves -> new InMemoryStorage());
        Inventory inventory = rig.player().getInventory();
        fillMainInventory(inventory);
        // Two papers in slot 0: the proof takes one, so the slot stays occupied and no empty slot opens.
        inventory.setItem(0, new ItemStack(Items.PAPER, 2));
        inventory.setItem(1, new ItemStack(Items.EMERALD, 62));
        SettlementService service = serviceFor(rig);
        UUID operation = begin(helper, service, rig, turnIn(takeOnePaper(), FIVE_EMERALDS));
        AtomicBoolean partial = new AtomicBoolean();

        helper.succeedWhen(() -> {
            service.tick();
            if (!partial.get()) {
                List<ItemStack> pending = stillToDeliver(service, rig.player());
                helper.assertTrue(pending.size() == 1 && pending.get(0).getCount() == 3,
                        "the three emeralds that do not fit are not pending: " + pending);
                helper.assertValueEqual(count(rig.player(), Items.EMERALD), 64,
                        "the stack that took two emeralds is not full");
                inventory.setItem(2, ItemStack.EMPTY);
                partial.set(true);
            }
            checkSettled(helper, rig, operation, REPUTATION);
            helper.assertValueEqual(count(rig.player(), Items.EMERALD), 67, "emeralds in total");
        });
    }

    @GameTest(maxTicks = 40)
    public void aStackWithCustomDataRoundTripsThroughTheEscrow(GameTestHelper helper) {
        Rig rig = rig(helper);
        rig.player().getInventory().setItem(0, new ItemStack(Items.PAPER));
        CompoundTag data = new CompoundTag();
        data.putInt("i", 1);
        data.putLong("l", 2L);
        data.putDouble("d", 0.5);
        data.putByte("b", (byte) 1);
        data.putIntArray("ints", new int[] {1, 2});
        AtomicReference<ItemStack> proof = new AtomicReference<>();
        SettlementRequest.Removal removal = player -> {
            ItemStack taken = player.getInventory().removeItem(0, 1);
            taken.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
            proof.set(taken.copy());
            return List.of(taken);
        };
        SettlementService service = serviceFor(rig);
        begin(helper, service, rig, turnIn(removal, PLACEHOLDER));

        PendingOperation pending = service.pendingFor(rig.player()).get(0);
        helper.assertValueEqual(pending.removed().size(), 1, "removed stacks in the escrow");
        helper.assertTrue(ItemStack.matches(proof.get(), pending.removed().get(0)),
                "the stack read back from the escrow is not the stack that was written: "
                        + pending.removed().get(0));
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aTooLargeEscrowIsRefusedAndChangesNothing(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Rig rig = rig(helper);
        Inventory inventory = rig.player().getInventory();
        ItemStack[] before = new ItemStack[36];
        for (int slot = 0; slot < before.length; slot++) {
            inventory.setItem(slot, filledShulker(server, slot));
            before[slot] = inventory.getItem(slot).copy();
        }
        // The proof is all 36 shulker boxes, so the escrow keeps 36 filled boxes as its audit record.
        SettlementRequest.Removal removal = player -> {
            List<ItemStack> taken = new ArrayList<>();
            for (int slot = 0; slot < 36; slot++) {
                taken.add(player.getInventory().removeItem(slot, 1));
            }
            return taken;
        };
        SettlementService service = serviceFor(rig);

        helper.assertTrue(service.begin(rig.player(), turnIn(removal, PLACEHOLDER)).isEmpty(),
                "an escrow over the limit was accepted");
        for (int slot = 0; slot < before.length; slot++) {
            helper.assertTrue(ItemStack.matches(before[slot], inventory.getItem(slot)),
                    "slot " + slot + " changed by the refused turn-in");
        }
        helper.assertTrue(rig.player().getAttached(SettlementModule.ESCROW) == null, "an escrow was written");
        helper.assertTrue(service.pendingFor(rig.player()).isEmpty(), "an operation was recorded");
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aStackTheCodecRefusesLeavesTheInventoryAsItWas(GameTestHelper helper) {
        Rig rig = rig(helper);
        Inventory inventory = rig.player().getInventory();
        inventory.setItem(0, new ItemStack(Items.PAPER));
        SettlementRequest.Removal removal = player -> {
            ItemStack taken = player.getInventory().removeItem(0, 1);
            // A max stack size of 0 is outside the range the codec accepts, so the escrow cannot be written.
            taken.set(DataComponents.MAX_STACK_SIZE, 0);
            return List.of(taken);
        };
        SettlementService service = serviceFor(rig);

        helper.assertTrue(service.begin(rig.player(), turnIn(removal, PLACEHOLDER)).isEmpty(),
                "a stack the codec refuses was accepted");
        helper.assertValueEqual(count(rig.player(), Items.PAPER), 1, "the proof is not back in the inventory");
        helper.assertTrue(rig.player().getAttached(SettlementModule.ESCROW) == null, "an escrow was written");
        helper.assertTrue(service.pendingFor(rig.player()).isEmpty(), "an operation was recorded");
        helper.succeed();
    }

    @GameTest(maxTicks = 600)
    public void aStuckPartialDeliveryDoesNotSaveUntilSpaceFrees(GameTestHelper helper) {
        Rig rig = rig(helper);
        Inventory inventory = rig.player().getInventory();
        fillMainInventory(inventory);
        inventory.setItem(0, new ItemStack(Items.PAPER));
        SettlementService service = serviceFor(rig);
        UUID operation = begin(helper, service, rig, turnIn(takeOnePaper(), TWO_ITEMS));
        AtomicInteger attemptsAtPartial = new AtomicInteger(-1);
        AtomicInteger waited = new AtomicInteger();

        helper.startSequence()
                .thenWaitUntil(() -> {
                    service.tick();
                    helper.assertValueEqual(count(rig.player(), Items.EMERALD), 1,
                            "the first stack did not reach the freed slot");
                    helper.assertValueEqual(stillToDeliver(service, rig.player()).size(), 1,
                            "the rest of the reward is not pending");
                })
                .thenExecute(() -> attemptsAtPartial.set(rig.saves().attempts()))
                .thenWaitUntil(() -> {
                    // Several retries fall due in this time (one every 5 seconds), and none may save: nothing moved.
                    service.tick();
                    helper.assertTrue(waited.incrementAndGet() >= 250, "the ticks have not run yet");
                    helper.assertValueEqual(rig.saves().attempts(), attemptsAtPartial.get(),
                            "saves while no stack moved");
                    helper.assertValueEqual(count(rig.player(), Items.DIAMOND), 0, "a diamond fit a full inventory");
                })
                .thenExecute(() -> inventory.setItem(1, ItemStack.EMPTY))
                .thenWaitUntil(() -> {
                    service.tick();
                    checkSettled(helper, rig, operation, REPUTATION);
                    helper.assertValueEqual(count(rig.player(), Items.DIAMOND), 1, "diamonds in total");
                    helper.assertValueEqual(count(rig.player(), Items.EMERALD), 1, "emeralds in total");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 40)
    public void aSecondTurnInForTheSameTierIsRefusedAndANewTierIsNot(GameTestHelper helper) {
        Rig rig = rig(helper);
        SettlementService service = serviceFor(rig);
        SettlementRequest.Removal nothing = player -> List.of();
        Optional<UUID> first = service.begin(rig.player(), achievement(1, nothing));
        Optional<UUID> sameTier = service.begin(rig.player(), achievement(1, nothing));
        Optional<UUID> nextTier = service.begin(rig.player(), achievement(2, nothing));
        helper.assertTrue(first.isPresent(), "the first tier was refused");
        helper.assertTrue(sameTier.isEmpty(), "a second begin for the same tier was accepted");
        helper.assertTrue(nextTier.isPresent(), "a new tier was refused");
        helper.assertValueEqual(service.pendingFor(rig.player()).size(), 2, "pending operations");
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aRemovedStackIsKeptInPiecesNoLargerThanItsMaxStackSize(GameTestHelper helper) {
        Rig rig = rig(helper);
        SettlementService service = serviceFor(rig);
        SettlementRequest.Removal removal = player -> List.of(new ItemStack(Items.ENDER_PEARL, 40));
        begin(helper, service, rig, turnIn(removal, PLACEHOLDER));

        PendingOperation pending = service.pendingFor(rig.player()).get(0);
        helper.assertTrue(pending.removed().stream().map(ItemStack::getCount).toList().equals(List.of(16, 16, 8)),
                "ender pearls stack to 16, so 40 are kept as 16, 16 and 8: " + pending.removed());
        helper.succeed();
    }

    private static CriterionSpec alinaSpec(GameTestHelper helper, String trigger, String conditions) {
        Identifier id = Identifier.parse(trigger);
        CriterionTrigger<?> type = BuiltInRegistries.TRIGGER_TYPES.getValue(id);
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().getServer().registryAccess());
        CriterionTriggerInstance instance = type.codec().parse(ops, JsonParser.parseString(conditions))
                .getOrThrow(message -> new IllegalStateException("conditions do not parse: " + message));
        return new CriterionSpec(id, instance, 1);
    }

    /** One test's server, storage, world id, save step, online players and player. A respawn replaces the player. */
    private static final class Rig {
        private final MinecraftServer server;
        private final AlinaStorage storage;
        private final UUID world = UUID.randomUUID();
        private final Saves saves;
        private final List<ServerPlayer> online = new ArrayList<>();
        private ServerPlayer player;

        Rig(MinecraftServer server, Saves saves, AlinaStorage storage, ServerPlayer player) {
            this.server = server;
            this.saves = saves;
            this.storage = storage;
            this.player = player;
            this.online.add(player);
        }

        MinecraftServer server() {
            return server;
        }

        AlinaStorage storage() {
            return storage;
        }

        UUID world() {
            return world;
        }

        Saves saves() {
            return saves;
        }

        List<ServerPlayer> online() {
            return online;
        }

        ServerPlayer player() {
            return player;
        }

        /**
         * Replaces the player with the entity a respawn leaves, with full health. {@code PlayerList.respawn} would join the
         * entity to the server's list, where the server's own service would serve it, and it needs a connection. This
         * builds the entity as PlayerList does (restoreFrom, which leaves the inventory out without keepInventory) and copies
         * the escrow as Fabric's AFTER_RESPAWN does. The new entity is online for this test's service only.
         */
        ServerPlayer respawn() {
            ServerPlayer fresh = new ServerPlayer(server, player.level(), player.getGameProfile(),
                    ClientInformation.createDefault());
            fresh.restoreFrom(player, false);
            fresh.setHealth(fresh.getMaxHealth());
            String escrow = player.getAttached(SettlementModule.ESCROW);
            if (escrow == null) {
                fresh.removeAttached(SettlementModule.ESCROW);
            } else {
                fresh.setAttached(SettlementModule.ESCROW, escrow);
            }
            online.clear();
            online.add(fresh);
            player = fresh;
            return fresh;
        }
    }

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

    /** What a test does with one applyOperation call. {@code delegate} is the storage the call would reach. */
    @FunctionalInterface
    private interface ApplyHook {
        CompletableFuture<ApplyResult> apply(AlinaStorage delegate, Operation operation);
    }

    /** A storage whose applyOperation is {@code hook}, and whose other calls reach {@code delegate}. */
    private static AlinaStorage hooked(AlinaStorage delegate, ApplyHook hook) {
        return (AlinaStorage) Proxy.newProxyInstance(AlinaStorage.class.getClassLoader(),
                new Class<?>[] {AlinaStorage.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("applyOperation")) {
                        return hook.apply(delegate, (Operation) args[0]);
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    /**
     * A survival or creative mock player that is not in the server's player list. {@code makeMockServerPlayerInLevel}
     * joins the list, and the server's own service would then serve the player's escrow alongside the test's.
     */
    private static ServerPlayer mockPlayer(GameTestHelper helper, GameType type) {
        ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayer(type);
        helper.assertTrue(player.hasInfiniteMaterials() == (type == GameType.CREATIVE),
                "the mock player is not in " + type + " mode");
        return player;
    }

    private static Rig rig(GameTestHelper helper) {
        return rig(helper, GameType.SURVIVAL, saves -> new InMemoryStorage());
    }

    private static Rig rig(GameTestHelper helper, Function<Saves, AlinaStorage> storage) {
        return rig(helper, GameType.SURVIVAL, storage);
    }

    private static Rig rig(GameTestHelper helper, GameType type, Function<Saves, AlinaStorage> storage) {
        MinecraftServer server = helper.getLevel().getServer();
        Saves saves = new Saves(server);
        return new Rig(server, saves, storage.apply(saves), mockPlayer(helper, type));
    }

    private static SettlementService serviceFor(Rig rig) {
        return new SettlementService(rig.server(), rig.storage(), rig.world(), rig::online, rig.saves().proof());
    }

    private static SettlementRequest turnIn(SettlementRequest.Removal removal, Identifier reward) {
        return turnIn(removal, Optional.of(reward), REPUTATION, Optional.of('C'), Optional.of("repeatable"));
    }

    private static SettlementRequest turnIn(SettlementRequest.Removal removal, Identifier reward, int reputation,
            Optional<Character> category, Optional<String> kind) {
        return turnIn(removal, Optional.of(reward), reputation, category, kind);
    }

    private static SettlementRequest turnIn(SettlementRequest.Removal removal, Optional<Identifier> reward,
            int reputation, Optional<Character> category, Optional<String> kind) {
        return new SettlementRequest(OperationKind.TURN_IN, COURIER, 0, REVISION, reward, reputation,
                new OperationEffect.CommissionSettled(COURIER, CommissionStatus.COMPLETED, Optional.empty()), removal,
                category, kind);
    }

    private static SettlementRequest achievement(int tier, SettlementRequest.Removal removal) {
        return new SettlementRequest(OperationKind.ACHIEVEMENT_REWARD, ACHIEVEMENT, tier, REVISION, Optional.empty(), 0,
                new OperationEffect.AchievementEarned(ACHIEVEMENT, tier, Optional.empty()), removal, Optional.empty(),
                Optional.empty());
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

    /** A shulker box holding 27 full stacks, each with a name and an enchantment, and the box itself named. */
    private static ItemStack filledShulker(MinecraftServer server, int index) {
        List<ItemStack> contents = new ArrayList<>();
        for (int slot = 0; slot < 27; slot++) {
            ItemStack stack = new ItemStack(Items.ARROW, 64);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("Crate " + index + "." + slot));
            stack.enchant(enchantment(server, Enchantments.SHARPNESS), 5);
            contents.add(stack);
        }
        ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
        shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
        shulker.set(DataComponents.CUSTOM_NAME, Component.literal("Haul " + index));
        return shulker;
    }

    /** One named, enchanted piece of equipment. */
    private static ItemStack equipment(MinecraftServer server, Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        stack.enchant(enchantment(server, Enchantments.PROTECTION), 4);
        return stack;
    }

    private static Holder<Enchantment> enchantment(MinecraftServer server,
            net.minecraft.resources.ResourceKey<Enchantment> key) {
        return server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
    }

    /** Kills the player the way a death leaves it: not alive, and the inventory dropped as the death drops it. */
    private static void kill(ServerPlayer player) {
        player.setHealth(0.0F);
        player.getInventory().dropAll();
    }

    /** The stacks an operation in the player's escrow still has to deliver. */
    private static List<ItemStack> stillToDeliver(SettlementService service, ServerPlayer player) {
        return service.pendingFor(player).stream()
                .flatMap(operation -> operation.reward().stream())
                .filter(stack -> !stack.isEmpty())
                .toList();
    }

    /** Counts the item in every slot of the player's inventory: the main slots, the armour and the offhand. */
    private static int count(ServerPlayer player, Item item) {
        Inventory inventory = player.getInventory();
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
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
}
