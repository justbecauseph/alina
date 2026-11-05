package town.lampas.alina.criteria;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.advancements.predicates.ContextAwarePredicate;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.CriterionTrigger;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.loot.LootContext;
import town.lampas.alina.Alina;
import town.lampas.alina.content.CriterionSpec;

/**
 * Criterion watchers (ALINA-PLAN §9.1): a commission proof or an achievement criterion that waits for one player's
 * criterion trigger events. {@code SimpleCriterionTriggerMixin} hands every {@code SimpleCriterionTrigger.trigger} call
 * to {@link #dispatch} before vanilla looks at the player's advancements, so a watcher sees the event whether or not the
 * player has finished the vanilla advancements that use the same trigger.
 *
 * <p>Watchers are indexed by trigger, then by player, so an event on a trigger nobody watches for that player costs two
 * map lookups. Server thread only.
 */
public final class CriterionWatchers {
    private static final Map<CriterionTrigger<?>, Map<UUID, List<Watch>>> WATCHERS = new IdentityHashMap<>();

    private CriterionWatchers() {}

    /**
     * Calls {@code onMatch} with the player each time the player fires the spec's trigger with an event its conditions
     * match, until the returned watch is closed. The spec's {@code count} is not applied here: the owner counts.
     */
    public static Watch watch(UUID player, CriterionSpec spec, Consumer<ServerPlayer> onMatch) {
        CriterionTrigger<?> trigger = BuiltInRegistries.TRIGGER_TYPES.getOptional(spec.trigger())
                .orElseThrow(() -> new IllegalArgumentException("trigger " + spec.trigger() + " is not registered"));
        if (!(spec.instance() instanceof SimpleCriterionTrigger.SimpleInstance)) {
            throw new IllegalArgumentException("trigger " + spec.trigger() + " is not a simple criterion trigger");
        }
        Watch watch = new Watch(trigger, Objects.requireNonNull(player, "player"), spec, onMatch);
        WATCHERS.computeIfAbsent(trigger, key -> new HashMap<>())
                .computeIfAbsent(player, key -> new ArrayList<>())
                .add(watch);
        return watch;
    }

    /** Closes every watch of the player, as when the player leaves. */
    public static void clear(UUID player) {
        WATCHERS.values().forEach(byPlayer -> byPlayer.remove(player));
        WATCHERS.values().removeIf(Map::isEmpty);
    }

    /** Closes every watch, as when the server stops. */
    public static void clearAll() {
        WATCHERS.clear();
    }

    /** How many watches are open on the trigger, for {@code /alina status}. */
    public static int count(Identifier trigger) {
        CriterionTrigger<?> type = BuiltInRegistries.TRIGGER_TYPES.getValue(trigger);
        Map<UUID, List<Watch>> byPlayer = type == null ? null : WATCHERS.get(type);
        return byPlayer == null ? 0 : byPlayer.values().stream().mapToInt(List::size).sum();
    }

    /**
     * One trigger event, from the mixin: {@code predicate} is the event's own test, as vanilla applies it to the
     * player's advancement criteria. Each of the player's watches on the trigger whose instance passes it, and whose
     * {@code player} condition matches, is called. A watch that throws is logged and does not stop the event.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void dispatch(CriterionTrigger<?> trigger, ServerPlayer player, Predicate<?> predicate) {
        if (WATCHERS.isEmpty() || !player.level().getServer().isSameThread()) {
            return;
        }
        Map<UUID, List<Watch>> byPlayer = WATCHERS.get(trigger);
        List<Watch> watches = byPlayer == null ? null : byPlayer.get(player.getUUID());
        if (watches == null || watches.isEmpty()) {
            return;
        }
        LootContext context = null;
        // A copy, so a watch may close itself or open another while it runs.
        for (Watch watch : List.copyOf(watches)) {
            if (watch.closed) {
                continue;
            }
            SimpleCriterionTrigger.SimpleInstance instance = (SimpleCriterionTrigger.SimpleInstance) watch.spec.instance();
            try {
                if (!((Predicate) predicate).test(instance)) {
                    continue;
                }
                Optional<ContextAwarePredicate> condition = instance.player();
                if (condition.isPresent()) {
                    if (context == null) {
                        context = EntityPredicate.createContext(player, player);
                    }
                    if (!condition.get().matches(context)) {
                        continue;
                    }
                }
                watch.onMatch.accept(player);
            } catch (RuntimeException e) {
                Alina.LOGGER.error("A criterion watcher on {} failed for {}", watch.spec.trigger(), player.getUUID(), e);
            }
        }
    }

    /** One open watch. Close it when the commission ends or the criterion is met. */
    public static final class Watch implements AutoCloseable {
        private final CriterionTrigger<?> trigger;
        private final UUID player;
        private final CriterionSpec spec;
        private final Consumer<ServerPlayer> onMatch;
        private boolean closed;

        private Watch(CriterionTrigger<?> trigger, UUID player, CriterionSpec spec, Consumer<ServerPlayer> onMatch) {
            this.trigger = trigger;
            this.player = player;
            this.spec = spec;
            this.onMatch = onMatch;
        }

        public CriterionSpec spec() {
            return spec;
        }

        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            Map<UUID, List<Watch>> byPlayer = WATCHERS.get(trigger);
            if (byPlayer == null) {
                return;
            }
            List<Watch> watches = byPlayer.get(player);
            if (watches != null) {
                watches.remove(this);
                if (watches.isEmpty()) {
                    byPlayer.remove(player);
                }
            }
            if (byPlayer.isEmpty()) {
                WATCHERS.remove(trigger);
            }
        }
    }
}
