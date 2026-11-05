package town.lampas.alina.content;

import java.util.Map;
import net.minecraft.resources.Identifier;

/** The parsed {@code alina:guild} extension: settings, commissions and world volumes. Immutable. */
public record GuildContent(GuildSettings settings, Map<Identifier, Commission> commissions, Map<Identifier, Volume> volumes) {
    public static final GuildContent EMPTY = new GuildContent(GuildSettings.DEFAULTS, Map.of(), Map.of());

    public GuildContent {
        commissions = Map.copyOf(commissions);
        volumes = Map.copyOf(volumes);
    }
}
