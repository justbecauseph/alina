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
 * @param commissionCategory a turn-in's commission category, A to F. Present for a turn-in whose
 *     {@code commission_completed} trigger should fire, and absent otherwise
 * @param commissionKind a turn-in's commission kind, {@code repeatable} or {@code feat}. Present with the category
 */
public record SettlementRequest(
        OperationKind kind,
        Identifier subjectId,
        int tier,
        long revisionId,
        Optional<Identifier> reward,
        int reputationDelta,
        OperationEffect effect,
        Removal removal,
        Optional<Character> commissionCategory,
        Optional<String> commissionKind) {

    public SettlementRequest {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(reward, "reward");
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(removal, "removal");
        Objects.requireNonNull(commissionCategory, "commissionCategory");
        Objects.requireNonNull(commissionKind, "commissionKind");
        if (tier < 0) {
            throw new IllegalArgumentException("tier must not be negative, was " + tier);
        }
        if (commissionCategory.isPresent() != commissionKind.isPresent()) {
            throw new IllegalArgumentException("the commission category and kind come together");
        }
        if (commissionCategory.isPresent()) {
            if (kind != OperationKind.TURN_IN) {
                throw new IllegalArgumentException("only a turn-in names a commission's category and kind");
            }
            char category = commissionCategory.get();
            if (category < 'A' || category > 'F') {
                throw new IllegalArgumentException("the commission category must be A to F, was " + category);
            }
            String value = commissionKind.get();
            if (!value.equals("repeatable") && !value.equals("feat")) {
                throw new IllegalArgumentException("the commission kind must be repeatable or feat, was " + value);
            }
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
