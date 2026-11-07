package town.lampas.alina.achievement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import town.lampas.alina.Alina;
import town.lampas.alina.achievement.HolderLayout.AchievementTier;
import town.lampas.alina.achievement.HolderLayout.Slot;
import town.lampas.alina.achievement.HolderLayout.TabRoot;
import town.lampas.alina.content.AchievementsContent;
import town.lampas.alina.content.AchievementsContent.Achievement;

/**
 * Shows Alina achievements in the vanilla advancement screen (ALINA-PLAN §9.4, spike S1). Alina's achievements are not in
 * the server's advancement tree; for each player this class builds advancement holders and progress from the content
 * and the player's {@link AchievementView}, and sends them through vanilla's advancement packets. Server thread only.
 *
 * <p><b>What the 26.2 client does</b> (read from its bytecode): a packet with the reset flag clears the client's tree
 * and progress; then it removes the packet's removed ids (with their descendants), adds its holders (adding an id that
 * is already there adds a second child under the parent, so an id is always removed before it is added again), and
 * applies its progress. A progress entry shows a toast when the packet has no reset flag, the entry is done, the
 * packet's {@code showAdvancements} flag is true and the display allows toasts, whether or not the entry was done
 * before. With {@code showAdvancements} false the client still adds the holders and applies the progress, without
 * toasts. Holders compare by id, so the client keeps a holder's progress across a remove and add.
 *
 * <p><b>Holders.</b> A tab is a root holder {@code alina:tab/<namespace>/<path>} with the tab's title, icon and
 * background, frame task, no toast, always done. An achievement without tiers is one holder
 * {@code alina:achievement/<namespace>/<path>}; a tiered one has that holder for tier 1 and
 * {@code alina:achievement/<namespace>/<path>/tier_<k>} for tier k, each the child of the previous tier. An achievement's
 * first holder is the child of its parent achievement's first holder, or of its tab's root. Displays use the content's
 * title, description, icon and frame (a tier's own title and frame when it gives them), the content's {@code toast},
 * never announce (the service announces), never hidden. A repeatable's description gains
 * {@link AchievementText#earnedTimes} (or {@link AchievementText#completedTimes}, the earn count divided by the number of
 * tiers) once earned. Positions are Alina's own, so they do not change between server runs or activations (vanilla's
 * {@code TreeNodePosition} orders siblings by identity hash): x is the depth below the tab root, and rows are given
 * depth first with children in id order, a leaf taking the next free row and a parent sitting on its first child's
 * row. Tabs are in id order.
 *
 * <p><b>Progress</b> uses the holder's requirements as segments, so the vanilla screen shows {@code x/y}: one segment
 * per requirements group when the achievement has several groups (a group's segment is done when the group is met);
 * otherwise one segment per counted event, at most 1000 (for a target above 1000, segment i is done at
 * {@code ceil(i * target / 1000)} events). A tier's target is its count. An earned holder has every segment done.
 *
 * <p><b>Hidden</b> achievements, with all their tiers, are withheld until earned once; a hidden achievement cannot be a
 * parent, so no holder ever waits for a withheld parent.
 *
 * <p><b>Packets.</b>
 * <ul>
 *   <li>Reset packets (join, {@code /reload}) carry every visible Alina holder and its progress:
 *       {@link #appendToReset} adds them to vanilla's packet, so no toast fires for what was earned before. Vanilla's
 *       first packet after a join waits while {@link #holdFirstPacket} is true: until the player's record has loaded,
 *       at most 100 ticks. A record that loads later gets a full packet (below); a WARN is logged.</li>
 *   <li>A full packet has no reset flag and {@code showAdvancements} false: it removes the tab roots it sent the player
 *       before (removing a root removes its holders), adds every visible holder, and carries every visible holder's
 *       progress, done or not. No toast fires. It is sent to each online player on a content activation, and to a
 *       player whose record loaded after the first-packet hold ended.</li>
 *   <li>An earn is sent at once in a packet without the reset flag and with {@code showAdvancements} true. It carries the
 *       earned holder's progress, done, whatever a reset, full or count packet sent before, so the client shows its toast.
 *       Holders the client lacks are added with it: a hidden achievement's first earn adds all its tiers, the later ones
 *       with their progress, not done. An earn reported before the client has its holders is sent on the first tick the
 *       client has them.</li>
 *   <li>Count changes are sent at most once every 100 ticks per player, as progress of not-done holders, with
 *       {@code showAdvancements} false.</li>
 *   <li>A cycle start or a revoke sends the achievement's holders' progress, not done, with {@code showAdvancements}
 *       false.</li>
 * </ul>
 * Only an earn's packet has {@code showAdvancements} true, so no toast repeats. Text such as the earn count is
 * refreshed by reset and full packets.
 */
public final class AdvancementSync implements AchievementListener {
    /** Server ticks vanilla's first packet waits for a record to load: 5 seconds. */
    static final long HOLD_TICKS = 100;
    /** Server ticks between two progress-only packets to one player: 5 seconds. */
    static final long PROGRESS_TICKS = 100;

    private final Function<ServerPlayer, Optional<AchievementView>> views;
    private final Supplier<AchievementsContent> content;
    private final Supplier<? extends Collection<ServerPlayer>> online;
    private final Map<UUID, SyncState> states = new HashMap<>();
    /** The content {@link #layout} was built from. Compared by identity. */
    private AchievementsContent layoutOf;
    private HolderLayout layout;
    /** Server ticks counted by {@link #tick}. */
    private long ticks;

    /**
     * @param views each player's achievements, as {@link AchievementService#view} gives them: empty while the record
     *     loads
     * @param content the active {@code alina:achievements} content
     * @param online the players to send updates to
     */
    public AdvancementSync(Function<ServerPlayer, Optional<AchievementView>> views,
            Supplier<AchievementsContent> content, Supplier<? extends Collection<ServerPlayer>> online) {
        this.views = Objects.requireNonNull(views, "views");
        this.content = Objects.requireNonNull(content, "content");
        this.online = Objects.requireNonNull(online, "online");
    }

    /** The holder id of a tab's root. */
    public static Identifier tabHolderId(Identifier tab) {
        return Identifier.fromNamespaceAndPath(Alina.MOD_ID, "tab/" + tab.getNamespace() + "/" + tab.getPath());
    }

    /** The holder id of an achievement's tier, 1-based; tier 1 is the achievement's first holder. */
    public static Identifier holderId(Identifier achievement, int tier) {
        if (tier < 1) {
            throw new IllegalArgumentException("tier must be 1 or more, not " + tier);
        }
        String path = "achievement/" + achievement.getNamespace() + "/" + achievement.getPath();
        return Identifier.fromNamespaceAndPath(Alina.MOD_ID, tier == 1 ? path : path + "/tier_" + tier);
    }

    /** True while vanilla's first advancement packet for the player should wait. See the class comment. */
    public boolean holdFirstPacket(ServerPlayer player) {
        SyncState state = state(player);
        if (views.apply(player).isPresent()) {
            return false;
        }
        if (state.firstAskedAt < 0) {
            state.firstAskedAt = ticks;
        }
        if (ticks - state.firstAskedAt < HOLD_TICKS) {
            return true;
        }
        if (!state.pendingLoad) {
            Alina.LOGGER.warn("Alina achievements of {} had not loaded {} ticks after the join; the first advancement "
                    + "packet goes without them, and they follow when they load", player.getUUID(), HOLD_TICKS);
        }
        state.pendingLoad = true;
        return false;
    }

    /**
     * Appends the player's visible Alina holders, parents before children and tabs in id order, and their progress to
     * a reset packet vanilla is about to send, and records them as sent. The mixin passes mutable copies of vanilla's
     * holders and progress and builds the packet from them, so Alina's tabs come after vanilla's (the client opens tabs
     * in the order the packet lists their roots).
     */
    public void appendToReset(ServerPlayer player, List<AdvancementHolder> added,
            Map<Identifier, AdvancementProgress> progress) {
        SyncState state = state(player);
        state.resetSent = true;
        state.sent.clear();
        state.dirty.clear();
        Optional<AchievementView> view = views.apply(player);
        state.pendingLoad = view.isEmpty();
        if (view.isEmpty()) {
            return;
        }
        for (Shown shown : shown(view.get())) {
            added.add(holderOf(shown));
            progress.put(shown.id(), progressOf(shown));
            state.sent.put(shown.id(), shown.state());
        }
    }

    /** Called every server tick: throttled count updates. */
    public void tick() {
        ticks++;
        for (ServerPlayer player : List.copyOf(online.get())) {
            SyncState state = states.get(player.getUUID());
            if (state == null || !ready(state)) {
                continue;
            }
            sendOwed(player, state);
            if (state.dirty.isEmpty()) {
                continue;
            }
            if (state.progressSent && ticks - state.lastProgressTick < PROGRESS_TICKS) {
                continue;
            }
            Optional<AchievementView> view = views.apply(player);
            if (view.isEmpty()) {
                continue;
            }
            Set<Identifier> dirty = Set.copyOf(state.dirty);
            state.dirty.clear();
            Batch batch = scopeDiff(state, byId(shown(view.get())),
                    slot -> slot instanceof AchievementTier tier && dirty.contains(tier.achievement().id()), true);
            if (!batch.isEmpty()) {
                send(player, batch, false);
                state.lastProgressTick = ticks;
                state.progressSent = true;
            }
        }
    }

    /** Sends the earns reported before the client had its holders, in the order they were reported. */
    private void sendOwed(ServerPlayer player, SyncState state) {
        List<SyncState.Owed> owed = List.copyOf(state.owed);
        state.owed.clear();
        for (int index = 0; index < owed.size(); index++) {
            SyncState.Owed earn = owed.get(index);
            if (!earnedNow(player, state, earn.achievement(), earn.tier())) {
                state.owed.addAll(owed.subList(index, owed.size()));
                return;
            }
        }
    }

    /** Drops what was sent to the player. */
    public void leave(ServerPlayer player) {
        states.remove(player.getUUID());
    }

    /** A count changed and nothing was earned: the achievement's progress goes out with the next throttled packet. */
    @Override
    public void progressed(ServerPlayer player, Identifier achievement) {
        SyncState state = states.get(player.getUUID());
        if (state != null && ready(state)) {
            state.dirty.add(achievement);
        }
    }

    /**
     * The tier was durably earned. The earned tier's holder is sent done with {@code showAdvancements} true, so its toast fires,
     * whatever was sent before: a tick or a reset may already have sent it done. Holders the client lacks are added with their
     * progress: the earned tier's done, and each other tier's not done, even when the view shows that tier done, because its own
     * earn sends it done with its toast. An earn reported before the client has its holders is kept, and sent on the first tick
     * the client has them. The achievement's holders are marked, so their counts go out with the next throttled packet.
     */
    @Override
    public void earned(ServerPlayer player, Identifier achievement, int tier) {
        SyncState state = state(player);
        if (!ready(state) || !earnedNow(player, state, achievement, tier)) {
            state.owed.add(new SyncState.Owed(achievement, tier));
        }
    }

    /**
     * Sends one earn's packet, or returns false when the view is empty so the earn must wait. See {@link #earned}.
     */
    private boolean earnedNow(ServerPlayer player, SyncState state, Identifier achievement, int tier) {
        Optional<AchievementView> view = views.apply(player);
        if (view.isEmpty()) {
            return false;
        }
        Map<Identifier, Shown> now = byId(shown(view.get()));
        Batch batch = new Batch();
        for (AchievementTier slot : layout().tiersOf(achievement)) {
            Shown shown = now.get(slot.id());
            if (shown == null) {
                continue;
            }
            boolean earnedHere = slot.tier() == tier;
            HolderProgress.State previous = state.sent.get(slot.id());
            if (previous == null) {
                batch.added.add(holderOf(shown));
                HolderProgress.State sent = earnedHere || !shown.state().done() ? shown.state() : notStarted(shown);
                batch.progress.put(slot.id(), progressOf(sent));
                state.sent.put(slot.id(), sent);
            } else if (earnedHere && shown.state().done()) {
                batch.progress.put(slot.id(), progressOf(shown.state()));
                state.sent.put(slot.id(), shown.state());
            }
        }
        state.dirty.add(achievement);
        send(player, batch, true);
        return true;
    }

    /** A repeatable's cooldown ended: the achievement's holders go back to not done, and their progress is sent. */
    @Override
    public void cycleStarted(ServerPlayer player, Identifier achievement) {
        refresh(player, achievement);
    }

    /** Staff revoked the achievement or some of its tiers: its holders' progress is sent, and withheld ones removed. */
    @Override
    public void revoked(ServerPlayer player, Identifier achievement) {
        refresh(player, achievement);
    }

    /**
     * A content bundle was activated: each online player gets a full packet, which removes the tab roots the client has,
     * adds every visible holder of the new layout, and carries every visible holder's progress, with no toasts.
     */
    @Override
    public void contentActivated() {
        for (ServerPlayer player : List.copyOf(online.get())) {
            SyncState state = states.get(player.getUUID());
            if (state == null || !ready(state)) {
                continue;
            }
            Optional<AchievementView> view = views.apply(player);
            if (view.isEmpty()) {
                continue;
            }
            sendFull(player, state, view.get());
        }
    }

    /**
     * The player's record loaded after vanilla's first packet went out without Alina's holders (or with none built): the
     * holders go out in a full packet, with their progress and no toasts, so the earned ones show as done.
     */
    @Override
    public void loaded(ServerPlayer player) {
        SyncState state = states.get(player.getUUID());
        if (state == null || !state.pendingLoad) {
            return;
        }
        Optional<AchievementView> view = views.apply(player);
        if (view.isEmpty()) {
            return;
        }
        state.pendingLoad = false;
        // The full packet gives the client Alina's holders, even when vanilla built no reset packet for this join.
        state.resetSent = true;
        Alina.LOGGER.warn("Alina loaded the achievements of {} after the first advancement packet; they are sent in a full "
                + "packet, without toasts", player.getUUID());
        sendFull(player, state, view.get());
    }

    /** Sends the progress of one achievement's holders, not done, and removes or adds those whose visibility changed. */
    private void refresh(ServerPlayer player, Identifier achievement) {
        SyncState state = states.get(player.getUUID());
        if (state == null || !ready(state)) {
            return;
        }
        Optional<AchievementView> view = views.apply(player);
        if (view.isEmpty()) {
            return;
        }
        Batch batch = scopeDiff(state, byId(shown(view.get())),
                slot -> slot instanceof AchievementTier tier && tier.achievement().id().equals(achievement), false);
        send(player, batch, false);
    }

    /**
     * The full packet: no reset flag and no toasts. It removes the tab roots the client has, which takes their holders
     * with them, adds every visible holder in tree order, and carries every visible holder's progress.
     */
    private void sendFull(ServerPlayer player, SyncState state, AchievementView view) {
        Batch batch = new Batch();
        for (Identifier id : state.sent.keySet()) {
            if (isTabHolder(id)) {
                batch.removed.add(id);
            }
        }
        state.sent.clear();
        state.dirty.clear();
        for (Shown shown : shown(view)) {
            batch.added.add(holderOf(shown));
            batch.progress.put(shown.id(), progressOf(shown));
            state.sent.put(shown.id(), shown.state());
        }
        send(player, batch, false);
    }

    /**
     * The packet that brings the client up to date for the holders {@code inScope} picks, in tree order. A holder no longer
     * shown is removed, unless one of its ancestors is withheld in the same packet, which takes it along: the ancestor is then
     * listed or was itself taken along, and the client removes this holder with it. A holder the client
     * lacks is added, with its progress. A holder the client has as not done gets its progress when it changed, or always
     * when {@code changedOnly} is false. A holder the client has as done gets nothing, so no done progress is repeated.
     */
    private Batch scopeDiff(SyncState state, Map<Identifier, Shown> now, Predicate<Slot> inScope, boolean changedOnly) {
        Batch batch = new Batch();
        // Every holder of this packet's scope that is withheld, listed or not: the client takes its descendants with it.
        Set<Identifier> withheld = new HashSet<>();
        for (Slot slot : layout().slots()) {
            if (!inScope.test(slot)) {
                continue;
            }
            Identifier id = slot.id();
            Shown shown = now.get(id);
            HolderProgress.State previous = state.sent.get(id);
            if (shown == null) {
                if (previous != null) {
                    if (!slot.parent().map(withheld::contains).orElse(false)) {
                        batch.removed.add(id);
                    }
                    state.sent.remove(id);
                }
                withheld.add(id);
                continue;
            }
            if (previous == null) {
                batch.added.add(holderOf(shown));
                batch.progress.put(id, progressOf(shown));
                state.sent.put(id, shown.state());
                continue;
            }
            if (shown.state().done() || (changedOnly && previous.equals(shown.state()))) {
                continue;
            }
            batch.progress.put(id, progressOf(shown));
            state.sent.put(id, shown.state());
        }
        return batch;
    }

    /** The holders the player is shown now, in tree order: visible tabs, and visible achievements under visible parents. */
    private List<Shown> shown(AchievementView view) {
        List<Shown> shown = new ArrayList<>();
        Set<Identifier> visible = new HashSet<>();
        for (Slot slot : layout().slots()) {
            if (slot instanceof TabRoot root) {
                visible.add(root.id());
                shown.add(new Shown(root, HolderProgress.TAB, 0));
            } else if (slot instanceof AchievementTier tier) {
                Achievement achievement = tier.achievement();
                AchievementStanding standing = view.achievements().get(achievement.id());
                boolean earnedOnce = standing != null && standing.everEarned();
                boolean parentShown = tier.parent().map(visible::contains).orElse(true);
                if ((achievement.hidden() && !earnedOnce) || !parentShown) {
                    continue;
                }
                visible.add(tier.id());
                shown.add(new Shown(tier, HolderProgress.of(achievement, tier.tier(), standing),
                        standing == null ? 0 : standing.earnCount()));
            }
        }
        return shown;
    }

    private static Map<Identifier, Shown> byId(List<Shown> shown) {
        Map<Identifier, Shown> byId = new LinkedHashMap<>();
        for (Shown entry : shown) {
            byId.put(entry.id(), entry);
        }
        return byId;
    }

    private AdvancementHolder holderOf(Shown shown) {
        HolderLayout.Position at = layout().position(shown.id());
        return HolderBuilder.holder(shown.slot(), shown.state().segments(), shown.earnCount(), at.x(), at.y());
    }

    private static AdvancementProgress progressOf(Shown shown) {
        return progressOf(shown.state());
    }

    private static AdvancementProgress progressOf(HolderProgress.State state) {
        return HolderBuilder.progress(state.segments(), state.doneSegments());
    }

    /** The state the client has for a holder added without progress: not done, nothing done. */
    private static HolderProgress.State notStarted(Shown shown) {
        return new HolderProgress.State(false, shown.state().segments(), 0);
    }

    /** A tab's root holder, by its id. Achievement holders are named {@code achievement/...}. */
    private static boolean isTabHolder(Identifier id) {
        return id.getPath().startsWith("tab/");
    }

    private static void send(ServerPlayer player, Batch batch, boolean showAdvancements) {
        if (batch.isEmpty()) {
            return;
        }
        player.connection.send(new ClientboundUpdateAdvancementsPacket(false, batch.added, batch.removed,
                batch.progress, showAdvancements));
    }

    private SyncState state(ServerPlayer player) {
        return states.computeIfAbsent(player.getUUID(), id -> new SyncState());
    }

    /** True once the client has the reset packet and no holders are owed. */
    private static boolean ready(SyncState state) {
        return state.resetSent && !state.pendingLoad;
    }

    /** The layout of the active content, rebuilt when the content object changes. */
    private HolderLayout layout() {
        AchievementsContent current = content.get();
        if (layout == null || current != layoutOf) {
            layout = HolderLayout.of(current);
            layoutOf = current;
        }
        return layout;
    }

    /** A holder the player is shown now: its slot, its state, and the earn count its description shows. */
    private record Shown(Slot slot, HolderProgress.State state, int earnCount) {
        Identifier id() {
            return slot.id();
        }
    }

    /** The contents of one packet being built. */
    private static final class Batch {
        final List<AdvancementHolder> added = new ArrayList<>();
        final Set<Identifier> removed = new LinkedHashSet<>();
        final Map<Identifier, AdvancementProgress> progress = new LinkedHashMap<>();

        boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty() && progress.isEmpty();
        }
    }
}
