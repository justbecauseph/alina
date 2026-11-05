package town.lampas.alina.gametest;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import town.lampas.alina.Alina;
import town.lampas.alina.api.AlinaServices;
import town.lampas.alina.storage.AlinaStorage;
import town.lampas.alina.storage.ApplyResult;
import town.lampas.alina.storage.CharacterState;
import town.lampas.alina.storage.CommissionState;
import town.lampas.alina.storage.CommissionStatus;
import town.lampas.alina.storage.Operation;
import town.lampas.alina.storage.OperationEffect;
import town.lampas.alina.storage.OperationKind;

/** Server GameTests owned by the storage component. */
public final class StorageGameTests {
    private static final Instant ACCEPTED_AT = Instant.parse("2026-11-05T12:00:00Z");
    private static final Instant TURNED_IN_AT = Instant.parse("2026-11-05T12:05:00Z");
    private static final Instant COOLDOWN_UNTIL = Instant.parse("2026-11-05T13:00:00Z");
    private static final Identifier COURIER = Alina.id("storage_test/courier");

    /**
     * The storage the server registered round-trips a commission accept, a turn-in and a load. Runs on whichever backend
     * {@code config/alina.json} selects: in-memory by default, PostgreSQL when the run's config says so.
     */
    @GameTest(maxTicks = 200)
    public void registeredStorageRoundTripsAcceptTurnInAndLoad(GameTestHelper helper) {
        AlinaStorage storage = AlinaServices.storage();
        UUID character = UUID.randomUUID();
        Operation turnIn = new Operation(UUID.randomUUID(), character, OperationKind.TURN_IN, COURIER, 0, 7,
                "minecraft:overworld", "{\"coins\":5}", 2,
                new OperationEffect.CommissionSettled(COURIER, CommissionStatus.COOLDOWN, Optional.of(COOLDOWN_UNTIL)),
                TURNED_IN_AT);

        CompletableFuture<Trip> trip = storage.acceptCommission(character, COURIER, 7, ACCEPTED_AT)
                .thenCompose(ignored -> storage.applyOperation(turnIn))
                .thenCompose(applied -> storage.loadCharacter(character)
                        .thenApply(state -> new Trip(applied, state)));

        helper.succeedWhen(() -> {
            helper.assertTrue(trip.isDone(), "the storage round trip has not completed");
            Trip result = trip.join();
            helper.assertTrue(new ApplyResult(true, 2).equals(result.applied()),
                    "the apply result was " + result.applied());
            CharacterState expected = new CharacterState(character, 2, Map.of(COURIER, new CommissionState(COURIER,
                    CommissionStatus.COOLDOWN, 0, ACCEPTED_AT, Optional.of(TURNED_IN_AT), Optional.of(COOLDOWN_UNTIL), 7)),
                    Map.of(), Map.of());
            helper.assertTrue(expected.equals(result.state()), "the loaded character was " + result.state());
        });
    }

    private record Trip(ApplyResult applied, CharacterState state) {}
}
