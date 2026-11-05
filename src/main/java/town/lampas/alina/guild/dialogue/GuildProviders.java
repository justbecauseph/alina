package town.lampas.alina.guild.dialogue;

import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import town.lampas.alina.content.Commission;
import town.lampas.alina.content.GuildContent;
import town.lampas.alina.content.Proof;
import town.lampas.alina.guild.CommissionView;
import town.lampas.alina.guild.GuildModule;
import town.lampas.alina.guild.GuildService;
import town.lampas.alina.guild.GuildText;
import town.lampas.alina.guild.GuildView;
import town.lampas.alina.guild.Refusal;
import town.lampas.alina.guild.Standing;
import town.lampas.haruhi.dialogue.api.ChoiceProvider;
import town.lampas.haruhi.dialogue.api.DialogueAction;
import town.lampas.haruhi.dialogue.api.ParseContext;
import town.lampas.haruhi.dialogue.api.ProvidedChoice;

/**
 * The Guild's choice providers (ALINA-PLAN §8.4). Each reads the service when it runs. Entries carry the commission's name
 * as their text, so haruhi can match a pick to the entry it was shown for. A loading record shows one disabled row.
 */
final class GuildProviders {
    private static final String BOARD = "alina:guild/board";
    private static final String TURN_IN = "alina:guild/turn_in";
    private static final String ABANDON = "alina:guild/abandon";
    private static final String STATUS = "alina:guild/status";
    private static final Set<String> NEXT = Set.of("next");
    private static final Comparator<Identifier> BY_ID = Comparator.comparing(Identifier::toString);

    /** The service's check for one operation on a commission. */
    @FunctionalInterface
    private interface Check {
        Optional<Refusal> check(GuildService service, ServerPlayer player, Identifier commission);
    }

    private GuildProviders() {}

    /** Today's board, then the uncompleted feats. Picking an entry accepts it. */
    static DataResult<ChoiceProvider> board(JsonObject json, ParseContext context) {
        return DialogueArgs.<ChoiceProvider>parse(context, BOARD, json, NEXT, args -> {
            Optional<String> next = args.node("next");
            return ctx -> boardRows(ctx.player(), next);
        });
    }

    /** One entry per commission that is active or settling. Picking an entry turns it in. */
    static DataResult<ChoiceProvider> turnIn(JsonObject json, ParseContext context) {
        return DialogueArgs.<ChoiceProvider>parse(context, TURN_IN, json, NEXT, args -> {
            Optional<String> next = args.node("next");
            return ctx -> commissionRows(ctx.player(), next, Set.of(Standing.ACTIVE, Standing.SETTLING),
                    GuildService::checkTurnIn, GuildActions.TURN_IN_OPERATION);
        });
    }

    /** One entry per active commission. Picking an entry abandons it. */
    static DataResult<ChoiceProvider> abandon(JsonObject json, ParseContext context) {
        return DialogueArgs.<ChoiceProvider>parse(context, ABANDON, json, NEXT, args -> {
            Optional<String> next = args.node("next");
            return ctx -> commissionRows(ctx.player(), next, Set.of(Standing.ACTIVE),
                    GuildService::checkAbandon, GuildActions.ABANDON_OPERATION);
        });
    }

    /** Disabled rows: the reputation, then each commission in the record. */
    static DataResult<ChoiceProvider> status(JsonObject json, ParseContext context) {
        return DialogueArgs.<ChoiceProvider>parse(context, STATUS, json, Set.of(), args ->
                ctx -> statusRows(ctx.player()));
    }

    private static List<ProvidedChoice> boardRows(ServerPlayer player, Optional<String> next) {
        GuildService service = GuildModule.service();
        Optional<List<Identifier>> entries = service.boardEntries(player);
        if (entries.isEmpty()) {
            Component text = service.view(player).isEmpty() ? GuildText.loading() : GuildText.boardLoading();
            return List.of(disabled(text));
        }
        // A loaded board with nothing to show has no rows; the graph's own choices follow.
        List<Identifier> ids = entries.get();
        GuildContent content = service.content();
        List<ProvidedChoice> rows = new ArrayList<>();
        for (Identifier id : ids) {
            Optional<Refusal> refusal = service.checkAccept(player, id);
            rows.add(new ProvidedChoice(nameOf(content, id), refusal.isEmpty(), refusal.map(Refusal::message),
                    GuildActions.run(GuildActions.ACCEPT_OPERATION, id), next));
        }
        return rows;
    }

    private static List<ProvidedChoice> commissionRows(ServerPlayer player, Optional<String> next,
            Set<Standing> standings, Check check, GuildActions.Operation operation) {
        GuildService service = GuildModule.service();
        Optional<GuildView> view = service.view(player);
        if (view.isEmpty()) {
            return List.of(disabled(GuildText.loading()));
        }
        GuildContent content = service.content();
        List<Identifier> ids = view.get().commissions().entrySet().stream()
                .filter(entry -> standings.contains(entry.getValue().standing()))
                .map(Map.Entry::getKey)
                .sorted(BY_ID)
                .toList();
        List<ProvidedChoice> rows = new ArrayList<>();
        for (Identifier id : ids) {
            Optional<Refusal> refusal = check.check(service, player, id);
            rows.add(new ProvidedChoice(nameOf(content, id), refusal.isEmpty(), refusal.map(Refusal::message),
                    GuildActions.run(operation, id), next));
        }
        return rows;
    }

    private static List<ProvidedChoice> statusRows(ServerPlayer player) {
        GuildService service = GuildModule.service();
        Optional<GuildView> view = service.view(player);
        if (view.isEmpty()) {
            return List.of(disabled(GuildText.loading()));
        }
        GuildContent content = service.content();
        List<ProvidedChoice> rows = new ArrayList<>();
        rows.add(disabled(GuildText.statusReputation(view.get().reputation())));
        view.get().commissions().keySet().stream().sorted(BY_ID).forEach(id ->
                rows.add(disabled(statusText(service, content, view.get().commissions().get(id)))));
        return rows;
    }

    private static Component statusText(GuildService service, GuildContent content, CommissionView view) {
        Identifier id = view.id();
        Commission commission = content.commissions().get(id);
        Component name = nameOf(content, id);
        return switch (view.standing()) {
            case ACTIVE -> countOf(commission) > 1
                    ? GuildText.statusProgress(name, view.progress(), countOf(commission))
                    : GuildText.statusActive(name);
            case SETTLING -> GuildText.statusSettling(name);
            case COOLDOWN -> GuildText.statusCooldown(name,
                    Duration.between(service.now(), view.cooldownUntil().orElse(service.now())));
            case COMPLETED -> GuildText.statusCompleted(name);
            case AVAILABLE -> throw new IllegalStateException("an available commission has no view");
        };
    }

    /** The count of a criterion proof, or 1 for any other proof or a commission the content lacks. */
    private static int countOf(Commission commission) {
        return commission != null && commission.proof() instanceof Proof.Criterion criterion
                ? criterion.criterion().count()
                : 1;
    }

    /** The commission's name, or its id when the active content does not have it. */
    static Component nameOf(GuildContent content, Identifier id) {
        Commission commission = content.commissions().get(id);
        return commission == null ? Component.literal(id.toString()) : commission.name();
    }

    /** A row that cannot be picked. Its text is the message. */
    private static ProvidedChoice disabled(Component text) {
        return new ProvidedChoice(text, false, Optional.empty(), DialogueAction.NONE, Optional.empty());
    }
}
