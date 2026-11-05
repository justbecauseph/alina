package town.lampas.alina.content;

import java.time.Duration;

/** How long a turned-in repeatable commission waits before it can be accepted again. */
public sealed interface Cooldown {

    /** A fixed real-time duration from the turn-in. */
    record Fixed(Duration duration) implements Cooldown {}

    /** Until the next board reset ({@code "board_reset"} in content). */
    record UntilBoardReset() implements Cooldown {}
}
