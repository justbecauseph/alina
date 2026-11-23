package town.lampas.alina.client.trade;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The rules for which row of a trade list stays selected when the server sends a new view, and how far a list scrolls.
 * Pure logic, so it is unit tested.
 */
final class Selection {
    private Selection() {}

    /**
     * The key to select after a view: {@code previous} when {@code available} still holds it, else the first key, else
     * nothing when the list is empty. {@code previous} may be null, which selects the first key.
     */
    static <K> Optional<K> keep(K previous, List<K> available) {
        for (K key : available) {
            if (Objects.equals(key, previous)) {
                return Optional.of(key);
            }
        }
        return available.isEmpty() ? Optional.empty() : Optional.of(available.getFirst());
    }

    /** A list scroll offset clamped so that {@code visible} rows of {@code count} rows fill the list as far as they go. */
    static int scroll(int first, int count, int visible) {
        return Math.clamp((long) first, 0, Math.max(0, count - visible));
    }
}
