package town.lampas.alina.content;

import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.resources.Identifier;

/**
 * A criterion: a registered criterion trigger, its instance parsed from vanilla's condition JSON with the trigger's own
 * codec, and how many matching events it needs.
 *
 * @param count the number of matching events; 1 for a plain criterion
 */
public record CriterionSpec(Identifier trigger, CriterionTriggerInstance instance, int count) {}
