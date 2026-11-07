package town.lampas.alina.content;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * A Guild notice board (ALINA-PLAN §8.4): its pool, how many it draws each day, and the dialogue graph its Notice Boards
 * open. The sets are immutable copies, in id order.
 *
 * @param id the board's id, {@code namespace:path}
 * @param name the name staff commands show
 * @param graph the dialogue graph of the bundle that the board's Notice Boards open
 * @param size the commissions drawn per day, at least 1
 * @param reach how far, in blocks, a player may move from a Notice Board before its conversation ends: above 0 and at
 *     most 64
 * @param categories the categories whose commissions are in the pool
 * @param commissions the commissions named in the pool, whatever their category
 */
public record Board(
        Identifier id,
        Component name,
        Identifier graph,
        int size,
        double reach,
        Set<Character> categories,
        Set<Identifier> commissions) {

    public Board {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(graph, "graph");
        if (size < 1) {
            throw new IllegalArgumentException("size must be at least 1, was " + size);
        }
        if (!(reach > 0 && reach <= 64)) {
            throw new IllegalArgumentException("reach must be above 0 and at most 64, was " + reach);
        }
        categories = Collections.unmodifiableSortedSet(new TreeSet<>(categories));
        commissions = Collections.unmodifiableSortedSet(new TreeSet<>(commissions));
    }

    /** Whether the commission is in this board's pool: its id is named, or its category is listed. */
    public boolean holds(Commission commission) {
        return commissions.contains(commission.id()) || categories.contains(commission.category());
    }
}
