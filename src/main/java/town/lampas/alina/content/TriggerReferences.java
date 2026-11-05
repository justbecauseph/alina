package town.lampas.alina.content;

import java.util.Optional;
import net.minecraft.resources.Identifier;
import town.lampas.alina.criteria.AchievementEarnedTrigger;
import town.lampas.alina.criteria.CommissionAcceptedTrigger;
import town.lampas.alina.criteria.CommissionCompletedTrigger;
import town.lampas.alina.criteria.VolumeEnteredTrigger;

/**
 * The content ids an Alina trigger's conditions name: the volume of {@code alina:volume_entered}, the commission of
 * {@code alina:commission_accepted} and {@code alina:commission_completed}, and the achievement of
 * {@code alina:achievement_earned}. The trigger codecs accept any id, so the readers check these against the content,
 * or a misspelt id would make a criterion that can never match.
 */
final class TriggerReferences {
    private TriggerReferences() {}

    static Optional<Identifier> volume(CriterionSpec spec) {
        return spec.instance() instanceof VolumeEnteredTrigger.TriggerInstance instance
                ? instance.volume()
                : Optional.empty();
    }

    static Optional<Identifier> commission(CriterionSpec spec) {
        if (spec.instance() instanceof CommissionAcceptedTrigger.TriggerInstance accepted) {
            return accepted.commission();
        }
        if (spec.instance() instanceof CommissionCompletedTrigger.TriggerInstance completed) {
            return completed.commission();
        }
        return Optional.empty();
    }

    static Optional<Identifier> achievement(CriterionSpec spec) {
        return spec.instance() instanceof AchievementEarnedTrigger.TriggerInstance instance
                ? instance.achievement()
                : Optional.empty();
    }
}
