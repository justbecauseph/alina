package town.lampas.alina.gametest;

import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import town.lampas.alina.Alina;
import town.lampas.alina.achievement.AchievementModule;
import town.lampas.alina.achievement.AchievementStanding;
import town.lampas.alina.achievement.AdvancementSync;
import town.lampas.alina.content.AlinaContent;
import town.lampas.haruhi.api.HaruhiServices;

/**
 * Server GameTests for the advancement sync (ALINA-PLAN §9.4, spike S1). Most tests build a {@link SyncRig}: their own
 * {@link AdvancementSync} over a view they set, and mock players outside the server's list. The sync runs synchronously,
 * so those tests check the packets in the tick they run, and the hold tests tick the rig. The last test joins a player
 * through the server's own mixin and module, and checks the server's sync sends the first reset packet.
 */
public final class AdvancementSyncGameTests {
    private static final String BUNDLE = "bundle.json";
    private static final String BUNDLE_NEXT = "bundle_next.json";
    private static final Identifier TAB = Alina.id("sync_test/hall");
    private static final Identifier GREETER = Alina.id("sync_test/greeter");
    private static final Identifier VISITOR = Alina.id("sync_test/visitor");
    private static final Identifier BELL = Alina.id("sync_test/bell");
    private static final Identifier HERMIT = Alina.id("sync_test/hermit");
    private static final Identifier PAIR = Alina.id("sync_test/pair");
    private static final Identifier NEWCOMER = Alina.id("sync_test/newcomer");
    private static final Identifier RECLUSE = Alina.id("sync_test/recluse");
    private static final Identifier VANILLA = Identifier.fromNamespaceAndPath("minecraft", "story/root");

    private static final Identifier TAB_HOLDER = AdvancementSync.tabHolderId(TAB);
    private static final Identifier GREETER_HOLDER = AdvancementSync.holderId(GREETER, 1);
    private static final Identifier VISITOR_HOLDER = AdvancementSync.holderId(VISITOR, 1);
    private static final Identifier BELL_HOLDER = AdvancementSync.holderId(BELL, 1);
    private static final Identifier BELL_TIER_2_HOLDER = AdvancementSync.holderId(BELL, 2);
    private static final Identifier HERMIT_HOLDER = AdvancementSync.holderId(HERMIT, 1);
    private static final Identifier PAIR_HOLDER = AdvancementSync.holderId(PAIR, 1);
    private static final Identifier NEWCOMER_HOLDER = AdvancementSync.holderId(NEWCOMER, 1);
    private static final Identifier RECLUSE_1_HOLDER = AdvancementSync.holderId(RECLUSE, 1);
    private static final Identifier RECLUSE_2_HOLDER = AdvancementSync.holderId(RECLUSE, 2);
    private static final Identifier RECLUSE_3_HOLDER = AdvancementSync.holderId(RECLUSE, 3);

    /** The holders of {@code bundle.json} that are visible with no achievement earned: the tab and six Alina holders. */
    private static final Set<Identifier> VISIBLE_WITH_GREETER_AND_VISITOR = Set.of(TAB_HOLDER, GREETER_HOLDER,
            VISITOR_HOLDER, BELL_HOLDER, BELL_TIER_2_HOLDER, PAIR_HOLDER);

    @GameTest
    public void aResetPacketAddsTheTabAndParentsBeforeChildrenAfterVanillasHolders(GameTestHelper helper) {
        SyncRig rig = SyncRig.create(helper, BUNDLE);
        ServerPlayer player = rig.newPlayer();
        rig.setView(player, Map.of());
        List<AdvancementHolder> added = new ArrayList<>(List.of(vanillaHolder()));
        Map<Identifier, AdvancementProgress> progress = new HashMap<>();
        rig.sync().appendToReset(player, added, progress);

        // The visible holders with no achievement earned: the tab, greeter, visitor, bell's two tiers and pair.
        List<Identifier> ids = idsOf(added);
        helper.assertValueEqual(ids.get(0), VANILLA, "vanilla's holder stays first");
        helper.assertValueEqual(ids.size(), 7, "vanilla's holder and the six visible Alina holders: " + ids);
        int tab = ids.indexOf(TAB_HOLDER);
        int greeter = ids.indexOf(GREETER_HOLDER);
        helper.assertTrue(tab > 0, "the tab is missing or is not after vanilla's holders: " + ids);
        helper.assertTrue(tab < greeter, "the tab is after its achievements: " + ids);
        helper.assertTrue(greeter < ids.indexOf(VISITOR_HOLDER), "a child comes before its parent: " + ids);
        helper.assertTrue(greeter < ids.indexOf(BELL_HOLDER) && ids.indexOf(BELL_HOLDER) < ids.indexOf(BELL_TIER_2_HOLDER),
                "the tiers come before their parent or out of order: " + ids);
        helper.assertFalse(ids.contains(HERMIT_HOLDER), "a hidden achievement is in a reset before it is earned");
        helper.assertTrue(progress.keySet().containsAll(ids.subList(1, ids.size())), "a holder has no progress");
        helper.succeed();
    }

    @GameTest
    public void theHoldersOfAResetInsertIntoAFreshTreeWithNoOrphans(GameTestHelper helper) {
        SyncRig rig = SyncRig.create(helper, BUNDLE);
        ServerPlayer player = rig.newPlayer();
        rig.setView(player, Map.of(GREETER, standing(GREETER, 1, 1, Map.of("greet", 1))));
        List<AdvancementHolder> added = new ArrayList<>();
        rig.sync().appendToReset(player, added, new HashMap<>());

        AdvancementTree tree = new AdvancementTree();
        tree.addAll(added);
        for (AdvancementHolder holder : added) {
            helper.assertTrue(tree.get(holder.id()) != null, "an orphan in the tree: " + holder.id());
        }
        helper.assertValueEqual(tree.nodes().size(), added.size(), "nodes in the fresh tree");
        int roots = 0;
        for (AdvancementNode ignored : tree.roots()) {
            roots++;
        }
        helper.assertValueEqual(roots, 1, "the tab is the one root");
        helper.succeed();
    }

    @GameTest
    public void aHiddenAchievementIsWithheldUntilEarnedAndThenAdded(GameTestHelper helper) {
        SyncRig rig = SyncRig.create(helper, BUNDLE);
        ServerPlayer player = rig.newPlayer();
        rig.setView(player, Map.of());
        List<AdvancementHolder> added = new ArrayList<>();
        rig.sync().appendToReset(player, added, new HashMap<>());
        helper.assertFalse(idsOf(added).contains(HERMIT_HOLDER), "a withheld achievement is in the reset");

        rig.setView(player, Map.of(HERMIT, standing(HERMIT, 1, 1, Map.of("hide", 1))));
        rig.sync().earned(player, HERMIT, 1);
        List<ClientboundUpdateAdvancementsPacket> sent = rig.sentSince(player);
        helper.assertValueEqual(sent.size(), 1, "packets for the first earn");
        ClientboundUpdateAdvancementsPacket update = sent.get(0);
        helper.assertFalse(update.shouldReset(), "the first earn's packet has the reset flag");
        helper.assertTrue(update.shouldShowAdvancements(), "the first earn's packet has no toast flag");
        helper.assertTrue(idsOf(update.getAdded()).contains(HERMIT_HOLDER), "the hidden holder was not added");
        helper.assertTrue(update.getProgress().containsKey(HERMIT_HOLDER)
                && update.getProgress().get(HERMIT_HOLDER).isDone(), "the earned holder's progress is not done");
        helper.succeed();
    }

    @GameTest
    public void anEarnSendsOneUnresetPacketWithToastsCarryingOnlyTheEarnedHoldersDoneProgress(GameTestHelper helper) {
        SyncRig rig = SyncRig.create(helper, BUNDLE);
        ServerPlayer player = rig.newPlayer();
        rig.setView(player, Map.of());
        rig.sync().appendToReset(player, new ArrayList<>(), new HashMap<>());

        rig.setView(player, Map.of(GREETER, standing(GREETER, 1, 1, Map.of("greet", 1))));
        rig.sync().earned(player, GREETER, 1);
        List<ClientboundUpdateAdvancementsPacket> sent = rig.sentSince(player);
        helper.assertValueEqual(sent.size(), 1, "packets for one earn");
        ClientboundUpdateAdvancementsPacket update = sent.get(0);
        helper.assertFalse(update.shouldReset(), "the earn's packet has the reset flag");
        helper.assertTrue(update.shouldShowAdvancements(), "the earn's packet has no toast flag");
        helper.assertTrue(update.getAdded().isEmpty(), "an earn of a shown achievement added holders");
        helper.assertTrue(update.getRemoved().isEmpty(), "an earn removed holders");
        helper.assertValueEqual(update.getProgress().keySet(), Set.of(GREETER_HOLDER), "the progress entries");
        helper.assertTrue(update.getProgress().get(GREETER_HOLDER).isDone(), "the earned holder's progress is not done");
        helper.succeed();
    }

    /**
     * A hidden tiered achievement's first earn adds all its tiers, as the client has none of them. The earned tier's progress is
     * done; each later tier's is its count, not done, so the client does not show it complete before its own earn.
     */
    @GameTest
    public void aHiddenTieredAchievementsFirstEarnAddsAllItsTiersAndSendsProgressForEveryTier(GameTestHelper helper) {
        SyncRig rig = SyncRig.create(helper, BUNDLE);
        ServerPlayer player = rig.newPlayer();
        rig.setView(player, Map.of());
        rig.sync().appendToReset(player, new ArrayList<>(), new HashMap<>());

        rig.setView(player, Map.of(RECLUSE, standing(RECLUSE, 1, 1, Map.of("seek", 1))));
        rig.sync().earned(player, RECLUSE, 1);
        List<ClientboundUpdateAdvancementsPacket> sent = rig.sentSince(player);
        helper.assertValueEqual(sent.size(), 1, "packets for the first earn");
        ClientboundUpdateAdvancementsPacket update = sent.get(0);
        helper.assertValueEqual(Set.copyOf(idsOf(update.getAdded())), Set.of(RECLUSE_1_HOLDER, RECLUSE_2_HOLDER, RECLUSE_3_HOLDER),
                "the holders added: every tier of the achievement");
        helper.assertValueEqual(update.getProgress().keySet(), Set.of(RECLUSE_1_HOLDER, RECLUSE_2_HOLDER, RECLUSE_3_HOLDER),
                "the progress entries");
        helper.assertTrue(update.getProgress().get(RECLUSE_1_HOLDER).isDone(), "the earned tier's progress is not done");
        helper.assertFalse(update.getProgress().get(RECLUSE_2_HOLDER).isDone(), "a later tier was sent done");
        helper.assertFalse(update.getProgress().get(RECLUSE_3_HOLDER).isDone(), "a later tier was sent done");
        helper.succeed();
    }

    @GameTest
    public void aProgressedCountIsSentAtMostOnceEvery100TicksAndNeverCarriesDoneProgress(GameTestHelper helper) {
        SyncRig rig = SyncRig.create(helper, BUNDLE);
        ServerPlayer player = rig.newPlayer();
        rig.setView(player, Map.of());
        rig.sync().appendToReset(player, new ArrayList<>(), new HashMap<>());

        rig.setView(player, Map.of(VISITOR, standing(VISITOR, 0, 0, Map.of("visit", 5))));
        rig.sync().progressed(player, VISITOR);
        rig.tick();
        List<ClientboundUpdateAdvancementsPacket> first = rig.sentSince(player);
        helper.assertValueEqual(first.size(), 1, "packets for the first count change");
        assertCountPacket(helper, first.get(0), VISITOR_HOLDER, 5);

        rig.setView(player, Map.of(VISITOR, standing(VISITOR, 0, 0, Map.of("visit", 6))));
        rig.sync().progressed(player, VISITOR);
        for (int tick = 0; tick < 99; tick++) {
            rig.tick();
        }
        helper.assertTrue(rig.sentSince(player).isEmpty(), "a count was sent less than 100 ticks after the last one");
        rig.tick();
        List<ClientboundUpdateAdvancementsPacket> second = rig.sentSince(player);
        helper.assertValueEqual(second.size(), 1, "packets for the count 100 ticks after the last one");
        assertCountPacket(helper, second.get(0), VISITOR_HOLDER, 6);

        // Bell's first tier is earned in the view, but no earn was reported, so the client still has it not done. The
        // count packet carries bell's second tier and must not send the done first tier.
        rig.setView(player, Map.of(BELL, standing(BELL, 1, 1, Map.of("ring", 3))));
        rig.sync().progressed(player, BELL);
        for (int tick = 0; tick < 100; tick++) {
            rig.tick();
        }
        List<ClientboundUpdateAdvancementsPacket> third = rig.sentSince(player);
        helper.assertValueEqual(third.size(), 1, "packets for the bell count");
        assertCountPacket(helper, third.get(0), BELL_TIER_2_HOLDER, 3);
        helper.succeed();
    }

    /** Only an earn sends a holder done: a count change and the ticks after it send none, so the earn's toast is sent once. */
    @GameTest
    public void aDoneHolderIsSentDoneOnlyByItsEarnAndNotRepeatedByTheTicks(GameTestHelper helper) {
        SyncRig rig = SyncRig.create(helper, BUNDLE);
        ServerPlayer player = rig.newPlayer();
        rig.setView(player, Map.of());
        rig.sync().appendToReset(player, new ArrayList<>(), new HashMap<>());

        rig.setView(player, Map.of(GREETER, standing(GREETER, 1, 1, Map.of("greet", 1))));
        rig.sync().earned(player, GREETER, 1);
        rig.sync().progressed(player, GREETER);
        for (int tick = 0; tick < 200; tick++) {
            rig.tick();
        }
        int doneSends = 0;
        for (ClientboundUpdateAdvancementsPacket update : rig.sentSince(player)) {
            if (update.getProgress().containsKey(GREETER_HOLDER) && update.getProgress().get(GREETER_HOLDER).isDone()) {
                doneSends++;
            }
        }
        helper.assertValueEqual(doneSends, 1, "done progress of the greeter sent");
        helper.succeed();
    }

    @GameTest
    public void holdFirstPacketHoldsWhileTheViewIsEmptyAndStopsAfter100Ticks(GameTestHelper helper) {
        SyncRig rig = SyncRig.create(helper, BUNDLE);
        ServerPlayer player = rig.newPlayer();
        helper.assertTrue(rig.sync().holdFirstPacket(player), "the first packet is not held at the first check");
        for (int tick = 0; tick < 99; tick++) {
            rig.tick();
        }
        helper.assertTrue(rig.sync().holdFirstPacket(player),
                "the first packet is released 99 ticks after the first check");
        rig.tick();
        helper.assertFalse(rig.sync().holdFirstPacket(player),
                "the first packet is still held 100 ticks after the first check");

        ServerPlayer loaded = rig.newPlayer();
        rig.setView(loaded, Map.of());
        helper.assertFalse(rig.sync().holdFirstPacket(loaded), "a player whose record has loaded is held");
        helper.succeed();
    }

    /**
     * A record that loads after the hold ended gets a full packet: no reset flag, no toasts, every visible holder with its
     * progress. Its earned achievement shows done, and a later activation repeats no toast.
     */
    @GameTest
    public void aLateLoadSendsAFullPacketWithProgressAndNoToasts(GameTestHelper helper) {
        SyncRig rig = SyncRig.create(helper, BUNDLE);
        ServerPlayer player = rig.newPlayer();
        helper.assertTrue(rig.sync().holdFirstPacket(player), "the hold did not begin at the first check");
        for (int tick = 0; tick < 100; tick++) {
            rig.tick();
        }
        helper.assertFalse(rig.sync().holdFirstPacket(player), "the hold did not time out");
        List<AdvancementHolder> added = new ArrayList<>(List.of(vanillaHolder()));
        rig.sync().appendToReset(player, added, new HashMap<>());
        helper.assertValueEqual(added.size(), 1, "Alina's holders in a reset packet sent without a record");

        rig.setView(player, Map.of(GREETER, standing(GREETER, 1, 1, Map.of("greet", 1)),
                VISITOR, standing(VISITOR, 0, 0, Map.of("visit", 5))));
        rig.sync().loaded(player);
        List<ClientboundUpdateAdvancementsPacket> sent = rig.sentSince(player);
        helper.assertValueEqual(sent.size(), 1, "packets for the late load");
        ClientboundUpdateAdvancementsPacket late = sent.get(0);
        helper.assertFalse(late.shouldReset(), "the late load's packet has the reset flag");
        helper.assertFalse(late.shouldShowAdvancements(), "the late load's packet shows toasts");
        helper.assertValueEqual(Set.copyOf(idsOf(late.getAdded())), VISIBLE_WITH_GREETER_AND_VISITOR,
                "the late load's holders: every visible holder");
        helper.assertValueEqual(late.getProgress().keySet(), VISIBLE_WITH_GREETER_AND_VISITOR,
                "the late load's progress: every visible holder");
        helper.assertTrue(late.getRemoved().isEmpty(), "the late load removed holders: " + late.getRemoved());
        helper.assertTrue(late.getProgress().get(GREETER_HOLDER).isDone(), "the earned greeter was not sent done");
        helper.assertTrue(late.getProgress().get(TAB_HOLDER).isDone(), "the tab was not sent done");
        helper.assertFalse(late.getProgress().get(VISITOR_HOLDER).isDone(), "the visitor's count was sent as done");

        rig.sync().loaded(player);
        helper.assertTrue(rig.sentSince(player).isEmpty(), "a late load was sent twice");

        rig.sync().contentActivated();
        List<ClientboundUpdateAdvancementsPacket> activation = rig.sentSince(player);
        helper.assertValueEqual(activation.size(), 1, "packets for the activation after the late load");
        ClientboundUpdateAdvancementsPacket update = activation.get(0);
        helper.assertFalse(update.shouldShowAdvancements(), "the activation after a late load shows toasts");
        helper.assertValueEqual(update.getRemoved(), Set.of(TAB_HOLDER), "the activation removed the tab roots it sent");
        helper.assertTrue(update.getProgress().get(GREETER_HOLDER).isDone(), "the earned greeter was not sent done");
        helper.succeed();
    }

    /**
     * An activation is a full packet with no toasts: it removes the tab roots, adds every visible holder of the new content,
     * and carries every visible holder's progress, so an achievement that stays earned is sent done without a toast.
     */
    @GameTest
    public void aContentActivationSendsAFullPacketWithProgressAndNoToasts(GameTestHelper helper) {
        SyncRig rig = SyncRig.create(helper, BUNDLE);
        ServerPlayer player = rig.newPlayer();
        rig.setView(player, Map.of(GREETER, standing(GREETER, 1, 1, Map.of("greet", 1))));
        List<AdvancementHolder> reset = new ArrayList<>();
        rig.sync().appendToReset(player, reset, new HashMap<>());
        helper.assertTrue(idsOf(reset).contains(BELL_HOLDER), "the reset has no bell before the activation");

        // Visitor becomes earned in the view. The new content drops bell and adds newcomer.
        rig.setView(player, Map.of(GREETER, standing(GREETER, 1, 1, Map.of("greet", 1)),
                VISITOR, standing(VISITOR, 1, 1, Map.of("visit", 37))));
        rig.useContent(BUNDLE_NEXT);
        rig.sync().contentActivated();

        List<ClientboundUpdateAdvancementsPacket> sent = rig.sentSince(player);
        helper.assertValueEqual(sent.size(), 1, "packets for the activation");
        ClientboundUpdateAdvancementsPacket update = sent.get(0);
        helper.assertFalse(update.shouldReset(), "the activation's packet has the reset flag");
        helper.assertFalse(update.shouldShowAdvancements(), "the activation's packet shows toasts");
        helper.assertValueEqual(update.getRemoved(), Set.of(TAB_HOLDER), "the ids removed: the tab roots it sent");
        Set<Identifier> visible = Set.of(TAB_HOLDER, GREETER_HOLDER, VISITOR_HOLDER, PAIR_HOLDER, NEWCOMER_HOLDER);
        helper.assertValueEqual(Set.copyOf(idsOf(update.getAdded())), visible, "the holders added: every visible holder");
        helper.assertValueEqual(update.getProgress().keySet(), visible, "the progress sent: every visible holder");
        helper.assertTrue(update.getProgress().get(VISITOR_HOLDER).isDone(), "the visitor's progress is not done");
        helper.assertTrue(update.getProgress().get(GREETER_HOLDER).isDone(), "the greeter's progress is not done");
        helper.assertFalse(update.getProgress().get(NEWCOMER_HOLDER).isDone(), "the newcomer is shown as done");
        helper.succeed();
    }

    /**
     * The service tells the listeners of an activation before it earns what the new content finds met, so an earn right
     * after the activation must see the holder the activation sent: it is not added again, and its done progress, with the
     * toast, is the only thing sent.
     */
    @GameTest
    public void anEarnRightAfterAnActivationSendsOnlyTheEarnedHoldersDoneProgressWithAToast(GameTestHelper helper) {
        SyncRig rig = SyncRig.create(helper, BUNDLE);
        ServerPlayer player = rig.newPlayer();
        rig.setView(player, Map.of(GREETER, standing(GREETER, 1, 1, Map.of("greet", 1))));
        rig.sync().appendToReset(player, new ArrayList<>(), new HashMap<>());

        // Newcomer's count meets its requirement in the new content, and nothing earned it yet.
        rig.setView(player, Map.of(GREETER, standing(GREETER, 1, 1, Map.of("greet", 1)),
                NEWCOMER, standing(NEWCOMER, 0, 0, Map.of("arrive", 1))));
        rig.useContent(BUNDLE_NEXT);
        rig.sync().contentActivated();
        helper.assertValueEqual(rig.sentSince(player).size(), 1, "packets for the activation");

        rig.setView(player, Map.of(GREETER, standing(GREETER, 1, 1, Map.of("greet", 1)),
                NEWCOMER, standing(NEWCOMER, 1, 1, Map.of("arrive", 1))));
        rig.sync().earned(player, NEWCOMER, 1);
        List<ClientboundUpdateAdvancementsPacket> sent = rig.sentSince(player);
        helper.assertValueEqual(sent.size(), 1, "packets for the earn");
        ClientboundUpdateAdvancementsPacket update = sent.get(0);
        helper.assertFalse(update.shouldReset(), "the earn's packet has the reset flag");
        helper.assertTrue(update.shouldShowAdvancements(), "the earn's packet has no toast flag");
        helper.assertTrue(update.getAdded().isEmpty(), "an earn re-added a holder the activation had sent: "
                + idsOf(update.getAdded()));
        helper.assertTrue(update.getRemoved().isEmpty(), "an earn removed holders");
        helper.assertValueEqual(update.getProgress().keySet(), Set.of(NEWCOMER_HOLDER), "the progress entries");
        helper.assertTrue(update.getProgress().get(NEWCOMER_HOLDER).isDone(), "the newcomer's progress is not done");
        helper.succeed();
    }

    /** A cycle start sends the achievement's holders' progress, not done, and no toast. */
    @GameTest
    public void aCycleStartSendsTheHoldersProgressNotDoneWithoutAToast(GameTestHelper helper) {
        SyncRig rig = SyncRig.create(helper, BUNDLE);
        ServerPlayer player = rig.newPlayer();
        rig.setView(player, Map.of(GREETER, standing(GREETER, 1, 1, Map.of("greet", 1))));
        rig.sync().appendToReset(player, new ArrayList<>(), new HashMap<>());

        // The cycle has started: nothing of greeter is earned in it, and its earn count is kept.
        rig.setView(player, Map.of(GREETER, standing(GREETER, 0, 1, Map.of())));
        rig.sync().cycleStarted(player, GREETER);
        List<ClientboundUpdateAdvancementsPacket> sent = rig.sentSince(player);
        helper.assertValueEqual(sent.size(), 1, "packets for the cycle start");
        ClientboundUpdateAdvancementsPacket update = sent.get(0);
        helper.assertFalse(update.shouldReset(), "the cycle start's packet has the reset flag");
        helper.assertFalse(update.shouldShowAdvancements(), "the cycle start's packet shows toasts");
        helper.assertTrue(update.getAdded().isEmpty() && update.getRemoved().isEmpty(), "holders changed on a cycle start");
        helper.assertValueEqual(update.getProgress().keySet(), Set.of(GREETER_HOLDER), "the progress entries");
        helper.assertFalse(update.getProgress().get(GREETER_HOLDER).isDone(), "the cycle start sent the greeter done");
        helper.succeed();
    }

    /**
     * A revoke that withholds a hidden tiered achievement of three tiers removes only its first tier: the client drops the
     * descendants of a removed holder with it, so listing the later tiers as well, the second's child the third included, would
     * log a warning for each one that the client no longer has.
     */
    @GameTest
    public void aRevokedHiddenTieredAchievementListsOnlyItsFirstTierForRemoval(GameTestHelper helper) {
        SyncRig rig = SyncRig.create(helper, BUNDLE);
        ServerPlayer player = rig.newPlayer();
        rig.setView(player, Map.of());
        rig.sync().appendToReset(player, new ArrayList<>(), new HashMap<>());
        rig.setView(player, Map.of(RECLUSE, standing(RECLUSE, 1, 1, Map.of("seek", 1))));
        rig.sync().earned(player, RECLUSE, 1);
        rig.sentSince(player);

        // Staff revoke the achievement: with no earn left it is withheld again.
        rig.setView(player, Map.of());
        rig.sync().revoked(player, RECLUSE);
        List<ClientboundUpdateAdvancementsPacket> sent = rig.sentSince(player);
        helper.assertValueEqual(sent.size(), 1, "packets for the revoke");
        ClientboundUpdateAdvancementsPacket update = sent.get(0);
        helper.assertValueEqual(update.getRemoved(), Set.of(RECLUSE_1_HOLDER), "the ids removed");
        helper.assertTrue(update.getAdded().isEmpty() && update.getProgress().isEmpty(), "the revoke sent more than removals");

        // The achievement is earned again: all three tiers are added, so the revoke left nothing sent behind.
        rig.setView(player, Map.of(RECLUSE, standing(RECLUSE, 1, 1, Map.of("seek", 1))));
        rig.sync().earned(player, RECLUSE, 1);
        List<ClientboundUpdateAdvancementsPacket> again = rig.sentSince(player);
        helper.assertValueEqual(again.size(), 1, "packets for the second first earn");
        helper.assertValueEqual(Set.copyOf(idsOf(again.get(0).getAdded())), Set.of(RECLUSE_1_HOLDER, RECLUSE_2_HOLDER, RECLUSE_3_HOLDER),
                "the holders added after the revoke");
        helper.succeed();
    }

    /**
     * A count packet can carry a hidden achievement's earned holder as done before the earn is reported, with no toast. The earn's
     * own packet still toasts it, because each earn sends its tier's done progress with toasts on.
     */
    @GameTest
    public void anEarnStillToastsAHiddenHolderThatATickSentDoneBeforeIt(GameTestHelper helper) {
        SyncRig rig = SyncRig.create(helper, BUNDLE);
        ServerPlayer player = rig.newPlayer();
        rig.setView(player, Map.of());
        rig.sync().appendToReset(player, new ArrayList<>(), new HashMap<>());

        // The hermit is earned in the view, and its count change is sent by a tick before the earn is reported.
        rig.setView(player, Map.of(HERMIT, standing(HERMIT, 1, 1, Map.of("hide", 1))));
        rig.sync().progressed(player, HERMIT);
        rig.tick();
        List<ClientboundUpdateAdvancementsPacket> tick = rig.sentSince(player);
        helper.assertValueEqual(tick.size(), 1, "packets for the count change");
        helper.assertFalse(tick.get(0).shouldShowAdvancements(), "the count change's packet shows toasts");

        rig.sync().earned(player, HERMIT, 1);
        List<ClientboundUpdateAdvancementsPacket> sent = rig.sentSince(player);
        helper.assertValueEqual(sent.size(), 1, "packets for the earn");
        helper.assertTrue(sent.get(0).shouldShowAdvancements(), "the earn's packet has no toast flag");
        helper.assertValueEqual(sent.get(0).getProgress().keySet(), Set.of(HERMIT_HOLDER), "the progress entries");
        helper.assertTrue(sent.get(0).getProgress().get(HERMIT_HOLDER).isDone(), "the earned holder's progress is not done");
        helper.succeed();
    }

    /**
     * An earn sends the done progress of its own tier only. A later tier the view already shows as done is not toasted by an earn
     * of the first tier: its own earn sends it, with its toast.
     */
    @GameTest
    public void anEarnOfOneTierDoesNotToastItsLaterTier(GameTestHelper helper) {
        SyncRig rig = SyncRig.create(helper, BUNDLE);
        ServerPlayer player = rig.newPlayer();
        rig.setView(player, Map.of());
        rig.sync().appendToReset(player, new ArrayList<>(), new HashMap<>());

        // Both tiers of bell are earned in the view, and only the first tier's earn is reported.
        rig.setView(player, Map.of(BELL, standing(BELL, 2, 2, Map.of("ring", 5))));
        rig.sync().earned(player, BELL, 1);
        List<ClientboundUpdateAdvancementsPacket> sent = rig.sentSince(player);
        helper.assertValueEqual(sent.size(), 1, "packets for the first tier's earn");
        ClientboundUpdateAdvancementsPacket update = sent.get(0);
        helper.assertTrue(update.shouldShowAdvancements(), "the earn's packet has no toast flag");
        helper.assertValueEqual(update.getProgress().keySet(), Set.of(BELL_HOLDER), "the progress entries");
        helper.assertTrue(update.getProgress().get(BELL_HOLDER).isDone(), "the earned tier's progress is not done");
        helper.succeed();
    }

    /**
     * An earn reported before the client has any holders is sent on the first tick after the reset is built, with its toast: the
     * reset carries the holder done, and the earn's own packet toasts it.
     */
    @GameTest
    public void anEarnReportedBeforeTheResetIsSentWithAToastOnTheNextTick(GameTestHelper helper) {
        SyncRig rig = SyncRig.create(helper, BUNDLE);
        ServerPlayer player = rig.newPlayer();
        rig.setView(player, Map.of(GREETER, standing(GREETER, 1, 1, Map.of("greet", 1))));
        rig.sync().earned(player, GREETER, 1);
        helper.assertTrue(rig.sentSince(player).isEmpty(), "an earn was sent before the client had a reset");

        Map<Identifier, AdvancementProgress> progress = new HashMap<>();
        rig.sync().appendToReset(player, new ArrayList<>(), progress);
        helper.assertTrue(progress.get(GREETER_HOLDER).isDone(), "the reset did not carry the greeter done");
        rig.tick();
        List<ClientboundUpdateAdvancementsPacket> sent = rig.sentSince(player);
        helper.assertValueEqual(sent.size(), 1, "packets after the reset");
        ClientboundUpdateAdvancementsPacket update = sent.get(0);
        helper.assertTrue(update.shouldShowAdvancements(), "the owed earn's packet has no toast flag");
        helper.assertTrue(update.getProgress().get(GREETER_HOLDER).isDone(), "the owed earn's progress is not done");
        helper.succeed();
    }

    /**
     * The first packet after a join goes through the server's own mixin and module. The hold is engaged on purpose: the
     * first flush is run right after the join, before the record can have loaded, so vanilla's packet must be held. The
     * reset then comes after the record loads, and must carry vanilla's holders and Alina's holders with their progress.
     */
    @GameTest(maxTicks = 400, environment = "alina:sync_join")
    public void aJoiningPlayersFirstResetCarriesVanillasHoldersAndTheAlinaHoldersWithTheirProgress(GameTestHelper helper) {
        AtomicReference<Joined> joined = new AtomicReference<>();
        List<ClientboundUpdateAdvancementsPacket> updates = new ArrayList<>();
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> helper.assertTrue(AchievementModule.sync() != null,
                        "the server's achievement sync is not running; the achievement module has no sync yet"))
                .thenWaitUntil(() -> ensureFixtureActive(helper))
                .thenExecute(() -> {
                    Joined join = joinThroughTheServer(helper);
                    joined.set(join);
                    // The JOIN handler's load may complete before the player is in the server's list, and a refused load
                    // is retried only 100 ticks later, the length of the hold. Starting the load again now that the player
                    // is in the list makes the record load within a few ticks, so the hold is not left to that race.
                    AchievementModule.service().join(join.player());
                    helper.assertTrue(AchievementModule.service().view(join.player()).isEmpty(),
                            "the record loaded before the first flush, so the hold is not exercised");
                    join.player().getAdvancements().flushDirty(join.player(), true);
                    drain(join.channel(), updates);
                    helper.assertTrue(updates.isEmpty(), "a packet was sent while the first packet was held: " + updates.size());
                })
                .thenWaitUntil(() -> {
                    drain(joined.get().channel(), updates);
                    ClientboundUpdateAdvancementsPacket reset = updates.stream()
                            .filter(ClientboundUpdateAdvancementsPacket::shouldReset)
                            .findFirst()
                            .orElse(null);
                    helper.assertTrue(reset != null, "the joining player has not been sent a reset packet yet");
                    List<Identifier> ids = idsOf(reset.getAdded());
                    helper.assertTrue(ids.stream().anyMatch(id -> !id.getNamespace().equals(Alina.MOD_ID)),
                            "the first reset lost vanilla's holders: " + ids);
                    helper.assertTrue(ids.containsAll(List.of(TAB_HOLDER, GREETER_HOLDER, VISITOR_HOLDER)),
                            "the first reset has no Alina tab and achievements: " + ids);
                    helper.assertTrue(reset.getProgress().keySet().containsAll(List.of(TAB_HOLDER, GREETER_HOLDER, VISITOR_HOLDER)),
                            "the first reset has no progress for Alina's holders: " + reset.getProgress().keySet());
                })
                .thenSucceed();
    }

    private static void requireStartupComplete(GameTestHelper helper) {
        helper.assertTrue(HaruhiServices.content().isStartupComplete(), "the startup content activation has not finished");
    }

    /** Activates the fixture when it is not the active content, and asserts that it is. */
    private static void ensureFixtureActive(GameTestHelper helper) {
        if (!AlinaContent.achievements().tabs().containsKey(TAB)) {
            HaruhiServices.content().activateUnstored(SyncRig.bundle(helper, BUNDLE));
        }
        helper.assertTrue(AlinaContent.achievements().tabs().containsKey(TAB), "the fixture is not the active content");
    }

    /** A player joined through the server's player list, with the channel its outbound packets are read from. */
    private record Joined(ServerPlayer player, EmbeddedChannel channel) {}

    /**
     * Joins a mock player the way the server joins a player: the player is placed in the server's list with a connection
     * whose outbound packets the test reads. The server's services then serve the player.
     */
    private static Joined joinThroughTheServer(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false));
        return new Joined(player, channel);
    }

    private static void drain(EmbeddedChannel channel, List<ClientboundUpdateAdvancementsPacket> into) {
        Object packet;
        while ((packet = channel.readOutbound()) != null) {
            if (packet instanceof ClientboundUpdateAdvancementsPacket update) {
                into.add(update);
            }
        }
    }

    /** The stand-in for a vanilla holder that is already in the packet: a root with no display. */
    private static AdvancementHolder vanillaHolder() {
        return new AdvancementHolder(VANILLA, new Advancement(Optional.empty(), Optional.empty(), AdvancementRewards.EMPTY,
                Map.of(), AdvancementRequirements.EMPTY, false, Optional.empty()));
    }

    /** A standing of an achievement: its earned tiers, its earns and its counts. */
    private static AchievementStanding standing(Identifier id, int earnedTiers, int earnCount, Map<String, Integer> counts) {
        return new AchievementStanding(id, earnedTiers, earnedTiers, earnCount, Optional.empty(), Optional.empty(),
                counts, false);
    }

    private static List<Identifier> idsOf(List<AdvancementHolder> holders) {
        List<Identifier> ids = new ArrayList<>();
        for (AdvancementHolder holder : holders) {
            ids.add(holder.id());
        }
        return ids;
    }

    /**
     * Checks a packet that carries only the count of {@code holder}: its one progress entry is not done and has
     * {@code done} segments done, no entry in it is done, and it shows no toast.
     */
    private static void assertCountPacket(GameTestHelper helper, ClientboundUpdateAdvancementsPacket update,
            Identifier holder, int done) {
        helper.assertFalse(update.shouldShowAdvancements(), "a count packet shows toasts");
        helper.assertValueEqual(update.getProgress().keySet(), Set.of(holder), "the progress entries");
        AdvancementProgress progress = update.getProgress().get(holder);
        helper.assertFalse(progress.isDone(), "a count was sent as done");
        int completed = 0;
        for (String ignored : progress.getCompletedCriteria()) {
            completed++;
        }
        helper.assertValueEqual(completed, done, "the segments done for the count");
    }
}
