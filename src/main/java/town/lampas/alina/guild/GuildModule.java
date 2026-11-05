package town.lampas.alina.guild;

import town.lampas.alina.guild.block.GuildBlocks;
import town.lampas.alina.guild.command.GuildCommands;
import town.lampas.alina.guild.dialogue.GuildDialogue;

/**
 * Owned by the guild component (ALINA-PLAN §8). Registers the Notice Board, the Guild's haruhi dialogue types and
 * {@code /alina guild} during {@code onInitialize}, makes one {@link GuildService} when the server starts, and drives it
 * from server, player and content events.
 */
public final class GuildModule {

    private GuildModule() {}

    public static void init() {
        GuildBlocks.init();
        GuildDialogue.init();
        GuildCommands.init();
        // The lifecycle wiring is added by the service's implementer.
    }

    /** The running server's Guild service. Throws before the server has started. */
    public static GuildService service() {
        throw new UnsupportedOperationException("not built yet");
    }
}
