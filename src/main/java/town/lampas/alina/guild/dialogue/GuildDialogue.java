package town.lampas.alina.guild.dialogue;

import town.lampas.alina.Alina;
import town.lampas.haruhi.dialogue.api.DialogueRegistries;

/** Registers the Guild's haruhi conditions, actions and choice providers (ALINA-PLAN §8.4). Called from {@code GuildModule.init}. */
public final class GuildDialogue {

    private GuildDialogue() {}

    public static void init() {
        DialogueRegistries.registerProvider(Alina.id("guild/board"), GuildProviders::board);
        DialogueRegistries.registerProvider(Alina.id("guild/turn_in"), GuildProviders::turnIn);
        DialogueRegistries.registerProvider(Alina.id("guild/abandon"), GuildProviders::abandon);
        DialogueRegistries.registerProvider(Alina.id("guild/status"), GuildProviders::status);

        DialogueRegistries.registerCondition(Alina.id("guild/has_active"), GuildConditions::hasActive);
        DialogueRegistries.registerCondition(Alina.id("guild/can_accept"), GuildConditions::canAccept);
        DialogueRegistries.registerCondition(Alina.id("guild/reputation"), GuildConditions::reputation);
        DialogueRegistries.registerCondition(Alina.id("guild/commission"), GuildConditions::commission);

        DialogueRegistries.registerAction(Alina.id("guild/accept"), GuildActions::accept);
        DialogueRegistries.registerAction(Alina.id("guild/turn_in"), GuildActions::turnIn);
        DialogueRegistries.registerAction(Alina.id("guild/abandon"), GuildActions::abandon);
        DialogueRegistries.registerAction(Alina.id("achievement/trigger"), GuildActions::achievementTrigger);
    }
}
