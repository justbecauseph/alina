package town.lampas.alina.guild.dialogue;

import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;
import town.lampas.alina.guild.GuildModule;
import town.lampas.alina.guild.GuildView;
import town.lampas.alina.guild.Standing;
import town.lampas.haruhi.dialogue.api.DialogueCondition;
import town.lampas.haruhi.dialogue.api.DialogueContext;
import town.lampas.haruhi.dialogue.api.ParseContext;

/**
 * The Guild's dialogue conditions (ALINA-PLAN §8.4). Each reads the player's record when it runs. While the record is
 * loading, every condition is false.
 */
final class GuildConditions {
    private static final String HAS_ACTIVE = "alina:guild/has_active";
    private static final String CAN_ACCEPT = "alina:guild/can_accept";
    private static final String REPUTATION = "alina:guild/reputation";
    private static final String COMMISSION = "alina:guild/commission";

    private static final Map<String, Standing> STATES = Map.of(
            "available", Standing.AVAILABLE,
            "active", Standing.ACTIVE,
            "cooldown", Standing.COOLDOWN,
            "completed", Standing.COMPLETED);

    private GuildConditions() {}

    static DataResult<DialogueCondition> hasActive(JsonObject json, ParseContext context) {
        return DialogueArgs.<DialogueCondition>parse(context, HAS_ACTIVE, json, Set.of(), args ->
                ctx -> view(ctx).map(view -> view.activeCount() > 0).orElse(false));
    }

    static DataResult<DialogueCondition> canAccept(JsonObject json, ParseContext context) {
        return DialogueArgs.<DialogueCondition>parse(context, CAN_ACCEPT, json, Set.of(), args ->
                ctx -> {
                    var service = GuildModule.service();
                    return service.view(ctx.player())
                            .map(view -> view.activeCount() < service.content().settings().activeCap())
                            .orElse(false);
                });
    }

    static DataResult<DialogueCondition> reputation(JsonObject json, ParseContext context) {
        return DialogueArgs.<DialogueCondition>parse(context, REPUTATION, json, Set.of("op", "value"), args -> {
            Compare compare = Compare.of(args.string("op"));
            int expected = args.integer("value");
            return ctx -> view(ctx).map(view -> compare.test(view.reputation(), expected)).orElse(false);
        });
    }

    static DataResult<DialogueCondition> commission(JsonObject json, ParseContext context) {
        return DialogueArgs.<DialogueCondition>parse(context, COMMISSION, json, Set.of("commission", "state"), args -> {
            Identifier commission = args.commission("commission");
            String state = args.string("state");
            Standing wanted = STATES.get(state);
            if (wanted == null) {
                throw DialogueArgs.invalid("'state' must be one of available, active, cooldown, completed, not '"
                        + state + "'");
            }
            return ctx -> view(ctx).map(view -> view.standing(commission) == wanted).orElse(false);
        });
    }

    private static Optional<GuildView> view(DialogueContext context) {
        return GuildModule.service().view(context.player());
    }

    /** The comparison ops of {@code alina:guild/reputation}, as haruhi's {@code haruhi:flag} spells them. */
    private enum Compare {
        EQ("eq"), NE("ne"), GT("gt"), GTE("gte"), LT("lt"), LTE("lte");

        private static final String NAMES = "eq, ne, gt, gte, lt, lte";

        private final String id;

        Compare(String id) {
            this.id = id;
        }

        static Compare of(String op) {
            for (Compare compare : values()) {
                if (compare.id.equals(op)) {
                    return compare;
                }
            }
            throw DialogueArgs.invalid("'op' must be one of " + NAMES + ", not '" + op + "'");
        }

        boolean test(int actual, int expected) {
            return switch (this) {
                case EQ -> actual == expected;
                case NE -> actual != expected;
                case GT -> actual > expected;
                case GTE -> actual >= expected;
                case LT -> actual < expected;
                case LTE -> actual <= expected;
            };
        }
    }
}
