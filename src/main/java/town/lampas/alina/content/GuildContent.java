package town.lampas.alina.content;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The parsed {@code alina:guild} extension: settings, commissions, world volumes and boards. Immutable.
 *
 * <p>A section with a {@code boards} key has those boards. A section without one has the implicit board
 * {@link #DEFAULT_BOARD}, whose graph is {@code settings.board_graph} and whose pool is every commission. A parsed section
 * puts every commission in exactly one board. {@link #boards()} is in id order and never empty.
 */
public record GuildContent(
        GuildSettings settings,
        Map<Identifier, Commission> commissions,
        Map<Identifier, Volume> volumes,
        Map<Identifier, Board> boards) {

    /** The id of the board a section without {@code boards} has. */
    public static final Identifier DEFAULT_BOARD = Identifier.fromNamespaceAndPath("alina", "board");

    public static final GuildContent EMPTY = new GuildContent(GuildSettings.DEFAULTS, Map.of(), Map.of(),
            Map.of(DEFAULT_BOARD, implicitBoard(GuildSettings.DEFAULT_BOARD_GRAPH, GuildSettings.DEFAULTS, Set.of())));

    public GuildContent {
        commissions = Map.copyOf(commissions);
        volumes = Map.copyOf(volumes);
        if (boards.isEmpty()) {
            throw new IllegalArgumentException("a guild section has at least one board");
        }
        for (Map.Entry<Identifier, Board> entry : boards.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().id())) {
                throw new IllegalArgumentException("board " + entry.getValue().id() + " is stored under " + entry.getKey());
            }
        }
        boards = Collections.unmodifiableMap(new TreeMap<>(boards));
    }

    /** The board whose pool holds the commission; empty when the commission is not in this section. */
    public Optional<Board> boardOf(Identifier commission) {
        Commission found = commissions.get(commission);
        if (found == null) {
            return Optional.empty();
        }
        return boards.values().stream().filter(board -> board.holds(found)).findFirst();
    }

    /** The board a section without {@code boards} gets: the whole pool, sized and reached by the settings. */
    static Board implicitBoard(Identifier graph, GuildSettings settings, Set<Identifier> commissions) {
        return new Board(DEFAULT_BOARD, Component.literal(DEFAULT_BOARD.toString()), graph, settings.boardSize(),
                settings.boardReach(), Set.of(), commissions);
    }
}
