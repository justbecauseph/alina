package town.lampas.alina.storage;

import java.util.Objects;
import net.minecraft.resources.Identifier;

/** One named criterion of one achievement. */
public record CriterionKey(Identifier achievementId, String criterion) {

    public CriterionKey {
        Objects.requireNonNull(achievementId, "achievementId");
        Objects.requireNonNull(criterion, "criterion");
    }
}
