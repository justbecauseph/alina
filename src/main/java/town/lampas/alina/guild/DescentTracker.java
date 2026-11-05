package town.lampas.alina.guild;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import town.lampas.alina.content.Commission;
import town.lampas.alina.content.CriterionSpec;
import town.lampas.alina.content.GuildContent;
import town.lampas.alina.content.Proof;
import town.lampas.alina.content.Volume;
import town.lampas.alina.criteria.AlinaTriggers;
import town.lampas.alina.criteria.CriterionWatchers;
import town.lampas.alina.storage.CommissionState;
import town.lampas.alina.storage.CommissionStatus;

/**
 * The descent tracker (ALINA-PLAN §8.3). Each check finds the content volumes that hold the player, fires
 * {@code alina:volume_entered} for each volume the player has entered since the last check, and advances the dives of
 * the player's active descent commissions:
 *
 * <ul>
 *   <li>entering the start volume, while the commission counts, arms a dive;
 *   <li>gliding, Slow Falling, death or a dimension other than the finish volume's disarms it, and so does a
 *       {@code minecraft:used_totem} event while any dive is armed;
 *   <li>reaching the finish volume alive while armed meets the proof.
 * </ul>
 *
 * <p>Dive state is memory only. Server thread only.
 */
final class DescentTracker {
    private static final Identifier USED_TOTEM = Identifier.fromNamespaceAndPath("minecraft", "used_totem");

    /** The used_totem watch condition, parsed from {@code {}}: every totem use matches. */
    private final CriterionSpec totem;

    DescentTracker(MinecraftServer server) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        CriterionTriggerInstance instance = CriteriaTriggers.USED_TOTEM.codec().parse(ops, new JsonObject())
                .getOrThrow(message -> new IllegalStateException("used_totem conditions do not parse: " + message));
        this.totem = new CriterionSpec(USED_TOTEM, instance, 1);
    }

    /**
     * Runs one check for the player. Returns the descent commissions whose proofs are met now. The caller records them.
     * The check covers only the record's loaded state, so the caller checks loaded records only.
     */
    List<Identifier> check(ServerPlayer player, PlayerRecord record, GuildContent content) {
        ResourceKey<Level> dimension = player.level().dimension();
        Vec3 position = player.position();
        List<Volume> volumes = sortedVolumes(content);
        Set<Identifier> inside = new HashSet<>();
        for (Volume volume : volumes) {
            if (volume.contains(dimension, position)) {
                inside.add(volume.id());
            }
        }
        List<Identifier> entered = new ArrayList<>();
        for (Volume volume : volumes) {
            if (inside.contains(volume.id()) && !record.volumesIn.contains(volume.id())) {
                entered.add(volume.id());
            }
        }
        record.volumesIn = Set.copyOf(inside);
        for (Identifier volume : entered) {
            AlinaTriggers.VOLUME_ENTERED.trigger(player, volume);
        }

        List<Identifier> met = new ArrayList<>();
        boolean alive = player.isAlive();
        for (Commission commission : sortedCommissions(content)) {
            if (!(commission.proof() instanceof Proof.Descent descent)) {
                continue;
            }
            Identifier id = commission.id();
            CommissionState row = record.rows.get(id);
            boolean counting = row != null && row.status() == CommissionStatus.ACTIVE && row.progress() == 0
                    && !record.settling.containsKey(id);
            Volume finish = content.volumes().get(descent.finish());
            if (!counting || finish == null) {
                record.armed.remove(id);
                continue;
            }
            if (entered.contains(descent.start())) {
                record.armed.add(id);
            }
            if (!record.armed.contains(id)) {
                continue;
            }
            boolean leftDimension = !finish.dimension().equals(dimension);
            if (!alive || player.isFallFlying() || player.hasEffect(MobEffects.SLOW_FALLING) || leftDimension) {
                record.armed.remove(id);
            } else if (inside.contains(descent.finish())) {
                record.armed.remove(id);
                met.add(id);
            }
        }
        sync(record);
        return met;
    }

    /** Opens the used_totem watch while a dive is armed, and closes it when none is. */
    void sync(PlayerRecord record) {
        if (record.armed.isEmpty()) {
            closeTotem(record);
        } else if (record.totem == null) {
            record.totem = CriterionWatchers.watch(record.id, totem, player -> {
                record.armed.clear();
                closeTotem(record);
            });
        }
    }

    /** Disarms every dive of the record and closes the used_totem watch. */
    void disarmAll(PlayerRecord record) {
        record.armed.clear();
        closeTotem(record);
    }

    private static void closeTotem(PlayerRecord record) {
        if (record.totem != null) {
            record.totem.close();
            record.totem = null;
        }
    }

    private static List<Volume> sortedVolumes(GuildContent content) {
        return content.volumes().values().stream()
                .sorted(Comparator.comparing((Volume volume) -> volume.id().toString()))
                .toList();
    }

    private static List<Commission> sortedCommissions(GuildContent content) {
        return content.commissions().values().stream()
                .sorted(Comparator.comparing((Commission commission) -> commission.id().toString()))
                .toList();
    }
}
