package town.lampas.alina.guild;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.Identifier;

/**
 * A snapshot of one character's Guild record, taken on the server thread. {@code commissions} holds every commission
 * that is not {@link Standing#AVAILABLE}: active, settling, on cooldown or completed. The map is an immutable copy.
 */
public record GuildView(UUID characterId, int reputation, Map<Identifier, CommissionView> commissions) {

    public GuildView {
        Objects.requireNonNull(characterId, "characterId");
        commissions = Map.copyOf(commissions);
    }

    /** The commission's standing, {@link Standing#AVAILABLE} when it has no view. */
    public Standing standing(Identifier commission) {
        CommissionView view = commissions.get(commission);
        return view == null ? Standing.AVAILABLE : view.standing();
    }

    /** How many commissions are {@link Standing#ACTIVE}, the number the active cap limits. */
    public int activeCount() {
        return (int) commissions.values().stream().filter(view -> view.standing() == Standing.ACTIVE).count();
    }
}
