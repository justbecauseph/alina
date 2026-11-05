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
 * through the server's own mixin and module, and waits for the server's sync to send the first reset packet.
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
    private static final Identifier VANILLA = Identifier.fromNamespaceAndPath("minecraft", "story/root");

    private static final Identifier TAB_HOLDER = AdvancementSync.tabHolderId(TAB);
    private static final Identifier GREETER_HOLDER = AdvancementSync.holderId(GREETER, 1);
    private static final Identifier VISITOR_HOLDER = AdvancementSync.holderId(VISITOR, 1);
    private static final Identifier BELL_HOLDER = AdvancementSync.holderId(BELL, 1);
    private static final Identifier BELL_TIER_2_HOLDER = AdvancementSync.holderId(BELL, 2);
    private static final Identifier HERMIT_HOLDER = AdvancementSync.holderId(HERMIT, 1);
    private static final Identifier PAIR_HOLDER = AdvancementSync.holderId(PAIR, 1);
    private static final Identifier NEWCOMER_HOLDER = AdvancementSync.holderId(NEWCOMER, 1);

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
        helper.assertTrue(idsOf(update.getAdded()).contains(HERMIT_HOLDER), "the hidden holder was not added");
        helper.assertTrue(update.getProgress().containsKey(HERMIT_HOLDER)
                && update.getProgress().get(HERMIT_HOLDER).isDone(), "the earned holder's progress is not done");
        helper.succeed();
    }

    @GameTest
    public void anEarnSendsOneUnresetPacketCarryingOnlyTheEarnedHoldersDoneProgress(GameTestHelper helper) {
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
        helper.assertTrue(update.getAdded().isEmpty(), "an earn of a shown achievement added holders");
        helper.assertTrue(update.getRemoved().isEmpty(), "an earn removed holders");
        helper.assertValueEqual(update.getProgress().keySet(), Set.of(GREETER_HOLDER), "the progress entries");
        helper.assertTrue(update.getProgress().get(GREETER_HOLDER).isDone(), "the earned holder's progress is not done");
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
        helper.succeed();
    }

    @GameTest
    public void aDoneHolderIsNeverSentDoneTwice(GameTestHelper helper) {
        SyncRig rig = SyncRig.create(helper, BUNDLE);
        ServerPlayer player = rig.newPlayer();
        rig.setView(player, Map.of());
        rig.sync().appendToReset(player, new ArrayList<>(), new HashMap<>());

        rig.setView(player, Map.of(GREETER, standing(GREETER, 1, 1, Map.of("greet", 1))));
        rig.sync().earned(player, GREETER, 1);
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

    @GameTest
    public void aLateLoadSendsNoDoneProgressForAchievements(GameTestHelper helper) {
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
        helper.assertTrue(idsOf(late.getAdded()).containsAll(List.of(TAB_HOLDER, GREETER_HOLDER, VISITOR_HOLDER)),
                "the late load did not add the tab and the achievements: " + idsOf(late.getAdded()));
        for (Map.Entry<Identifier, AdvancementProgress> entry : late.getProgress().entrySet()) {
            helper.assertTrue(!entry.getValue().isDone() || entry.getKey().equals(TAB_HOLDER),
                    "done progress was sent for " + entry.getKey());
        }
        helper.assertTrue(late.getProgress().containsKey(VISITOR_HOLDER)
                && !late.getProgress().get(VISITOR_HOLDER).isDone(), "the visitor's count was not sent");
        rig.sync().loaded(player);
        helper.assertTrue(rig.sentSince(player).isEmpty(), "a late load was sent twice");
        helper.succeed();
    }

    @GameTest
    public void aContentActivationRemovesTheOldIdsAddsTheVisibleHoldersAndSendsChangedDoneStates(GameTestHelper helper) {
        SyncRig rig = SyncRig.create(helper, BUNDLE);
        ServerPlayer player = rig.newPlayer();
        rig.setView(player, Map.of(GREETER, standing(GREETER, 1, 1, Map.of("greet", 1))));
        List<AdvancementHolder> reset = new ArrayList<>();
        rig.sync().appendToReset(player, reset, new HashMap<>());
        Set<Identifier> before = Set.copyOf(idsOf(reset));

        // The visitor is earned in the view, while the client has it not done. Greeter is done in both.
        rig.setView(player, Map.of(GREETER, standing(GREETER, 1, 1, Map.of("greet", 1)),
                VISITOR, standing(VISITOR, 1, 1, Map.of("visit", 37))));
        rig.useContent(BUNDLE_NEXT);
        rig.sync().contentActivated();

        List<ClientboundUpdateAdvancementsPacket> sent = rig.sentSince(player);
        helper.assertValueEqual(sent.size(), 1, "packets for the activation");
        ClientboundUpdateAdvancementsPacket update = sent.get(0);
        helper.assertFalse(update.shouldReset(), "the activation's packet has the reset flag");
        helper.assertValueEqual(Set.copyOf(update.getRemoved()), before, "the ids removed");
        helper.assertValueEqual(Set.copyOf(idsOf(update.getAdded())),
                Set.of(TAB_HOLDER, GREETER_HOLDER, VISITOR_HOLDER, PAIR_HOLDER, NEWCOMER_HOLDER),
                "the holders added: every visible holder of the new content");
        helper.assertValueEqual(update.getProgress().keySet(), Set.of(VISITOR_HOLDER, NEWCOMER_HOLDER),
                "the progress sent: the changed done state and the new holder");
        helper.assertTrue(update.getProgress().get(VISITOR_HOLDER).isDone(), "the visitor's progress is not done");
        helper.assertFalse(update.getProgress().get(NEWCOMER_HOLDER).isDone(), "the newcomer is shown as done");
        helper.succeed();
    }

    /**
     * Joins a mock player through the server's own player list, so the server's mixin and {@link AchievementModule} serve
     * it. The first update packet that resets the client must carry the tab and the achievements of the active content.
     * The test runs in its own environment, {@code alina:sync_join}, which the test server runs as a batch of its own, so
     * no other test replaces the content between the join and the reset.
     */
    @GameTest(maxTicks = 400, environment = "alina:sync_join")
    public void aJoiningPlayersFirstResetCarriesTheTabAndTheHoldersThroughTheServersOwnSync(GameTestHelper helper) {
        AtomicReference<EmbeddedChannel> channel = new AtomicReference<>();
        List<ClientboundUpdateAdvancementsPacket> updates = new ArrayList<>();
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> helper.assertTrue(AchievementModule.sync() != null,
                        "the server's achievement sync is not running; the achievement module has no sync yet"))
                .thenWaitUntil(() -> ensureFixtureActive(helper))
                .thenExecute(() -> channel.set(joinThroughTheServer(helper)))
                .thenWaitUntil(() -> {
                    drain(channel.get(), updates);
                    ClientboundUpdateAdvancementsPacket reset = updates.stream()
                            .filter(ClientboundUpdateAdvancementsPacket::shouldReset)
                            .findFirst()
                            .orElse(null);
                    helper.assertTrue(reset != null, "the joining player has not been sent a reset packet yet");
                    List<Identifier> ids = idsOf(reset.getAdded());
                    helper.assertTrue(ids.contains(TAB_HOLDER), "the first reset has no Alina tab: " + ids);
                    helper.assertTrue(ids.contains(GREETER_HOLDER) && ids.contains(VISITOR_HOLDER),
                            "the first reset has no Alina achievements: " + ids);
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

    /**
     * Joins a mock player the way the server joins a player: the player is placed in the server's list with a connection
     * whose outbound packets the test reads. The server's services then serve the player.
     */
    private static EmbeddedChannel joinThroughTheServer(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false));
        return channel;
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
     * {@code done} segments done, and no entry in it is done.
     */
    private static void assertCountPacket(GameTestHelper helper, ClientboundUpdateAdvancementsPacket update,
            Identifier holder, int done) {
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
