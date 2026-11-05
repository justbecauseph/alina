package town.lampas.alina.content;

import java.util.Optional;
import net.minecraft.resources.Identifier;

/**
 * What a turn-in, achievement or tier grants: a loot table rolled once with the settlement operation's seed, and
 * reputation. Both are optional; a reward with neither is {@link #NONE}.
 */
public record Reward(Optional<Identifier> lootTable, int reputation) {
    public static final Reward NONE = new Reward(Optional.empty(), 0);

    public boolean isEmpty() {
        return lootTable.isEmpty() && reputation == 0;
    }
}
