package town.lampas.alina.settlement;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import town.lampas.alina.storage.OperationEffect;
import town.lampas.alina.storage.OperationKind;

/**
 * What a turn-in or an achievement reward asks settlement to do (ALINA-PLAN §7). The caller plans the proof
 * removal, and settlement runs it once, inside {@link SettlementService#begin}.
 *
 * @param kind what the operation settles
 * @param subjectId the commission or achievement the operation is for
 * @param tier the achievement tier, 0 for a turn-in
 * @param revisionId the content revision the operation was planned under
 * @param reward the loot table rolled once with the operation's seed, if the operation has one
 * @param reputationDelta the reputation change, applied once
 * @param effect what the operation does to the character's commissions or achievements
 * @param removal removes the proof from the player and returns copies of what it removed
 */
public record SettlementRequest(
        OperationKind kind,
        Identifier subjectId,
        int tier,
        long revisionId,
        Optional<Identifier> reward,
        int reputationDelta,
        OperationEffect effect,
        Removal removal) {

    public SettlementRequest {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(reward, "reward");
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(removal, "removal");
        if (tier < 0) {
            throw new IllegalArgumentException("tier must not be negative, was " + tier);
        }
    }

    /**
     * Removes the proof from the player's inventory on the server thread, once, inside {@code begin}. It must take
     * all of the proof or none of it. It returns copies of the stacks it took.
     */
    @FunctionalInterface
    public interface Removal {
        List<ItemStack> remove(ServerPlayer player);
    }
}
