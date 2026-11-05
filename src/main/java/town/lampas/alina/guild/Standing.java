package town.lampas.alina.guild;

/**
 * Where one commission stands for one character, as the Guild shows it.
 *
 * <ul>
 *   <li>{@code AVAILABLE}: no row, or a cooldown that has ended. Whether it can be accepted also depends on the board
 *       and the cap.
 *   <li>{@code ACTIVE}: accepted and not turned in. Counts towards the active cap.
 *   <li>{@code SETTLING}: turned in, and settlement has not delivered the reward yet. Does not count towards the cap,
 *       and cannot be turned in, abandoned or accepted.
 *   <li>{@code COOLDOWN}: a repeatable turned in whose cooldown has not ended.
 *   <li>{@code COMPLETED}: a feat turned in, for good.
 * </ul>
 */
public enum Standing {
    AVAILABLE,
    ACTIVE,
    SETTLING,
    COOLDOWN,
    COMPLETED
}
