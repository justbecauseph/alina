package town.lampas.alina.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.serialization.DataResult;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import town.lampas.alina.Alina;
import town.lampas.alina.content.AlinaContent;
import town.lampas.alina.content.GuildContent;
import town.lampas.alina.guild.BoardView;
import town.lampas.alina.guild.GuildModule;
import town.lampas.alina.guild.GuildService;
import town.lampas.alina.guild.GuildText;
import town.lampas.alina.guild.GuildView;
import town.lampas.alina.guild.Refusal;
import town.lampas.alina.guild.Standing;
import town.lampas.alina.guild.block.GuildBlocks;
import town.lampas.alina.guild.block.NoticeBoardBlockEntity;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.dialogue.DialogueModule;
import town.lampas.haruhi.dialogue.DialogueSessions;
import town.lampas.haruhi.dialogue.api.ChoiceProvider;
import town.lampas.haruhi.dialogue.api.ChoiceProviderType;
import town.lampas.haruhi.dialogue.api.ConditionType;
import town.lampas.haruhi.dialogue.api.DialogueAnchor;
import town.lampas.haruhi.dialogue.api.DialogueCondition;
import town.lampas.haruhi.dialogue.api.DialogueContext;
import town.lampas.haruhi.dialogue.api.DialogueFlags;
import town.lampas.haruhi.dialogue.api.DialogueRegistries;
import town.lampas.haruhi.dialogue.api.ParseContext;
import town.lampas.haruhi.dialogue.api.ProvidedChoice;
import town.lampas.haruhi.dialogue.runtime.DialogueSession;
import town.lampas.haruhi.dialogue.runtime.VisibleChoice;

/**
 * Server GameTests for the Guild's haruhi types and the Notice Board (ALINA-PLAN §8.4). The validation tests refuse and
 * accept bundles through the content service. The behaviour tests need the server's {@code GuildService}. Each makes
 * its own player and activates the fixture in a step of its own. Before it acts, it waits until the fixture is the
 * active content (it is put back if a sibling test such as {@code ContentGameTests} replaced it) and the player's
 * flags, record and today's board have loaded, stored or not. The step that acts runs in the tick that wait passes,
 * so no other test's activation can fall between the check and the action. The expected board entries come from
 * {@code guild().board(GuildContent.DEFAULT_BOARD)} and the fixture's feats, so they do not depend on which content drew today's board.
 */
@SuppressWarnings("removal")
public final class GuildDialogueGameTests {
    private static final String FIXTURES = "/alina/fixtures/dialogue/";
    private static final Identifier BOARD = Alina.id("dialogue_test/board");
    private static final Identifier COURIER = Alina.id("dialogue_test/courier");
    private static final Identifier LANTERN = Alina.id("dialogue_test/lantern");
    private static final Identifier LEDGER = Alina.id("dialogue_test/ledger");
    /** The boards fixture: two boards with a graph each, the hunters' board with reach 4 and the town's with 3. */
    private static final String BOARDS = "boards.json";
    private static final Identifier HUNTERS = Alina.id("dialogue_test/hunters");
    private static final Identifier TOWN = Alina.id("dialogue_test/town");
    private static final Identifier HUNTERS_BOARD = Alina.id("dialogue_test/hunters_board");
    private static final Identifier TOWN_BOARD = Alina.id("dialogue_test/town_board");
    /** The fixture's feats in id order, which is how the board lists them after today's board commissions. */
    private static final List<Identifier> FEATS = List.of(COURIER, LANTERN, LEDGER);
    private static final Map<Identifier, String> NAMES =
            Map.of(COURIER, "The Courier", LANTERN, "The Lantern", LEDGER, "The Ledger");
    private static final String CAP_REASON = "the most the Guild allows";
    private static final String HINT = "Bring a piece of paper.";
    private static final BlockPos NOTICE_BOARD = new BlockPos(2, 2, 2);
    /** Farther from the Notice Board than any board_reach, which is at most 64 blocks. */
    private static final double BEYOND_ANY_REACH = 70.0;

    /** The player and the block the tests share. */
    private static final class Subject {
        private ServerPlayer player;
        private BlockPos noticeBoard;
        private ChatPlayer chatter;
    }

    /** The context a provider or a condition sees, for a player the test drives directly. */
    private record Ctx(ServerPlayer player, ContentBundle content) implements DialogueContext {
        @Override
        public Optional<Entity> interlocutor() {
            return Optional.empty();
        }

        @Override
        public Identifier graphId() {
            return BOARD;
        }

        @Override
        public String nodeId() {
            return "board";
        }

        @Override
        public DialogueFlags flags() {
            throw new UnsupportedOperationException("the Guild reads no flags");
        }

        @Override
        public int visits() {
            return 0;
        }

        @Override
        public void goTo(String nodeId) {
            throw new UnsupportedOperationException("the Guild moves no node");
        }

        @Override
        public void end() {
            throw new UnsupportedOperationException("the Guild ends no conversation");
        }

        @Override
        public void openGraph(Identifier graphId) {
            throw new UnsupportedOperationException("the Guild opens no graph");
        }
    }

    /**
     * A mock player that is not in the server's player list, so no service joins it. Its connection is an embedded
     * channel that keeps the chat sent to it, as {@code GuildRig}'s players do.
     */
    private static final class ChatPlayer {
        private final ServerPlayer player;
        private final EmbeddedChannel channel;
        private final List<String> said = new ArrayList<>();

        ChatPlayer(GameTestHelper helper) {
            this.player = (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            this.channel = new EmbeddedChannel(connection);
            new ServerGamePacketListenerImpl(helper.getLevel().getServer(), connection, player,
                    CommonListenerCookie.createInitial(player.getGameProfile(), false));
        }

        /** The text of each chat message the player has been sent so far, oldest first. */
        List<String> messages() {
            Object packet;
            while ((packet = channel.readOutbound()) != null) {
                if (packet instanceof ClientboundSystemChatPacket chat) {
                    said.add(chat.content().getString());
                }
            }
            return List.copyOf(said);
        }
    }

    // ---------------------------------------------------------------- validation

    /** The content of the validation bundles: one graph, and a board node with the given choices. */
    private static String bundle(String choices, String extensions) {
        return """
                { "schema": 1,
                  "dialogues": { "alina:dialogue_test/checks": { "start": "board", "nodes": {
                    "board": { "text": "Board.", "choices": [ %s ] } } } },
                  "extensions": %s }
                """.formatted(choices, extensions);
    }

    /** A {@code alina:guild} section with one commission and the validation graph as its board. */
    private static final String GUILD = """
            { "alina:guild": {
                "settings": { "board_graph": "alina:dialogue_test/checks" },
                "commissions": { "alina:dialogue_test/courier": {
                  "category": "C", "kind": "feat", "name": "The Courier", "hint": "Bring a piece of paper.",
                  "proof": { "type": "item", "all_of": [ { "item": { "items": "minecraft:paper" } } ] } } } } }
            """;

    @GameTest(maxTicks = 40)
    public void anActionNamingAnUnknownCommissionIsRefused(GameTestHelper helper) {
        helper.succeedWhen(() -> {
            requireStartupComplete(helper);
            String message = problems(HaruhiServices.content().parseAndValidate(bundle(
                    "{ \"text\": \"Accept.\", \"actions\": [ { \"type\": \"alina:guild/accept\","
                            + " \"commission\": \"alina:dialogue_test/nobody\" } ] }",
                    GUILD)));
            helper.assertTrue(message.contains("alina:dialogue_test/checks#board: alina:guild/accept: "
                    + "'commission' names 'alina:dialogue_test/nobody', which is not a commission of alina:guild "
                    + "in this bundle"), "no unknown-commission problem in:\n" + message);
        });
    }

    @GameTest(maxTicks = 40)
    public void aMisspeltArgumentKeyIsRefused(GameTestHelper helper) {
        helper.succeedWhen(() -> {
            requireStartupComplete(helper);
            String message = problems(HaruhiServices.content().parseAndValidate(bundle(
                    "{ \"text\": \"Accept.\", \"actions\": [ { \"type\": \"alina:guild/accept\","
                            + " \"comission\": \"alina:dialogue_test/courier\" } ] }",
                    GUILD)));
            helper.assertTrue(message.contains("alina:guild/accept: no argument named 'comission'"),
                    "no misspelt-key problem in:\n" + message);
        });
    }

    @GameTest(maxTicks = 40)
    public void aProviderNextNamingAMissingNodeIsRefused(GameTestHelper helper) {
        helper.succeedWhen(() -> {
            requireStartupComplete(helper);
            String message = problems(HaruhiServices.content().parseAndValidate(bundle(
                    "{ \"provider\": { \"type\": \"alina:guild/board\", \"next\": \"missing\" } }",
                    GUILD)));
            helper.assertTrue(message.contains("alina:guild/board: 'next' names 'missing', which is not a node of "
                    + "this graph"), "no missing-node problem in:\n" + message);
        });
    }

    @GameTest(maxTicks = 40)
    public void aCommissionArgumentWithoutAGuildSectionIsRefused(GameTestHelper helper) {
        helper.succeedWhen(() -> {
            requireStartupComplete(helper);
            String message = problems(HaruhiServices.content().parseAndValidate(bundle(
                    "{ \"text\": \"Accept.\", \"actions\": [ { \"type\": \"alina:guild/accept\","
                            + " \"commission\": \"alina:dialogue_test/courier\" } ] }",
                    "{}")));
            helper.assertTrue(message.contains("the bundle has no alina:guild section, so 'commission' cannot name "
                    + "a commission"), "no missing-section problem in:\n" + message);
        });
    }

    @GameTest(maxTicks = 40)
    public void aBadReputationOpIsRefused(GameTestHelper helper) {
        helper.succeedWhen(() -> {
            requireStartupComplete(helper);
            String message = problems(HaruhiServices.content().parseAndValidate(bundle(
                    "{ \"text\": \"If.\", \"only_if\": [ { \"type\": \"alina:guild/reputation\","
                            + " \"op\": \"between\", \"value\": 1 } ] }",
                    GUILD)));
            helper.assertTrue(message.contains("alina:guild/reputation: 'op' must be one of eq, ne, gt, gte, lt, lte, "
                    + "not 'between'"), "no bad-op problem in:\n" + message);
        });
    }

    @GameTest(maxTicks = 40)
    public void aBundleUsingEveryTypeIsAccepted(GameTestHelper helper) {
        helper.succeedWhen(() -> {
            requireStartupComplete(helper);
            DataResult<ContentBundle> result =
                    HaruhiServices.content().parseAndValidate(fixture(helper, "every_type.json"));
            helper.assertTrue(result.result().isPresent(), "the every-type bundle was refused:\n" + problems(result));
        });
    }

    // ---------------------------------------------------------------- behaviour

    @GameTest(maxTicks = 600)
    public void theBoardListsTheFeatsAndAPickAccepts(GameTestHelper helper) {
        Subject subject = new Subject();
        joinedWithFixture(helper, subject)
                .thenExecute(() -> {
                    DialogueSession session = openBoard(helper, subject.player);
                    List<Identifier> expected = expectedBoard(helper);
                    helper.assertValueEqual(boardEntriesOf(helper, subject.player), expected, "the board entries");
                    List<VisibleChoice> rows = providedRows(session);
                    helper.assertValueEqual(rows.size(), expected.size(), "entries from the board provider");
                    for (int index = 0; index < rows.size() && index < expected.size(); index++) {
                        VisibleChoice row = rows.get(index);
                        helper.assertValueEqual(row.text().getString(), nameOf(helper, expected.get(index)),
                                "the entry's text is the commission's name");
                        helper.assertTrue(row.enabled(), "a feat the player can accept is shown enabled");
                    }
                    pick(helper, subject.player, session, "The Lantern");
                    helper.assertTrue(viewOf(helper, subject.player).standing(LANTERN) == Standing.ACTIVE,
                            "the pick did not accept the lantern");
                    helper.assertTrue(presentedAt(helper, subject.player, "board").presented().stream()
                                    .anyMatch(row -> row.text().getString().equals("The Lantern") && !row.enabled()),
                            "the accepted feat is not grayed out on the board");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 600)
    public void aCapGraysOutTheRestAndStaleAndForgedPicksAreRefused(GameTestHelper helper) {
        Subject subject = new Subject();
        joinedWithFixture(helper, subject)
                .thenExecute(() -> {
                    DialogueSession session = openBoard(helper, subject.player);
                    helper.assertTrue(providedRows(session).stream().allMatch(VisibleChoice::enabled),
                            "every feat is shown enabled before the cap is reached");
                    // The cap is two. Accepting through the service after the node is shown makes the ledger stale.
                    helper.assertTrue(guild().accept(subject.player, COURIER).isEmpty(), "the courier was refused");
                    helper.assertTrue(guild().accept(subject.player, LANTERN).isEmpty(), "the lantern was refused");
                    helper.assertValueEqual(viewOf(helper, subject.player).activeCount(), 2,
                            "active commissions at the cap");
                    pick(helper, subject.player, session, "The Ledger");
                    // The stale pick has run and the board is shown again, in the same tick.
                    DialogueSession shown = presentedAt(helper, subject.player, "board");
                    VisibleChoice ledger = row(helper, shown, "The Ledger");
                    helper.assertFalse(ledger.enabled(), "the stale pick's entry is grayed out when the node is shown again");
                    helper.assertTrue(reason(ledger).contains(CAP_REASON), "the reason is the cap: " + reason(ledger));
                    GuildView view = viewOf(helper, subject.player);
                    helper.assertValueEqual(view.standing(LEDGER), Standing.AVAILABLE, "the stale pick accepted nothing");
                    helper.assertValueEqual(view.activeCount(), 2, "active commissions after the stale pick");

                    int entries = shown.presented().size();
                    // A forged index past the end, then the entry of a commission that is already active.
                    sessions().choose(subject.player, shown.id(), "board", entries + 5);
                    sessions().choose(subject.player, shown.id(), "board", indexOf(helper, shown, "The Courier"));
                    helper.assertTrue(sessionOf(helper, subject.player) == shown, "the same conversation is still open");
                    helper.assertValueEqual(shown.presentedNode(), "board", "the node after the forged picks");
                    helper.assertValueEqual(shown.presented().size(), entries, "the shown entries after the forged picks");
                    helper.assertValueEqual(viewOf(helper, subject.player).activeCount(), 2,
                            "active commissions after the forged picks");
                })
                .thenSucceed();
    }

    /**
     * At the cap, a drop through the abandon provider opens the board with the freed slot open: the feats on it are enabled,
     * and {@code alina:guild/can_accept} agrees with {@code checkAccept} both at the cap and after the drop.
     */
    @GameTest(maxTicks = 600)
    public void aDropAtTheCapOpensTheBoardWithAnEnabledFeat(GameTestHelper helper) {
        Subject subject = new Subject();
        joinedWithFixture(helper, subject)
                .thenExecute(() -> {
                    helper.assertTrue(guild().accept(subject.player, COURIER).isEmpty(), "the courier was refused");
                    helper.assertTrue(guild().accept(subject.player, LANTERN).isEmpty(), "the lantern was refused");
                })
                .thenWaitUntil(() -> {
                    ensureFixtureActive(helper);
                    // A commission whose acceptance is being written is busy, and cannot be dropped until it is written.
                    helper.assertTrue(guild().checkAbandon(subject.player, COURIER).isEmpty(),
                            "the courier is still being written");
                    helper.assertTrue(guild().checkAbandon(subject.player, LANTERN).isEmpty(),
                            "the lantern is still being written");
                    ContentBundle bundle = parse(helper, fixture(helper, "bundle.json"));
                    DialogueCondition canAccept = condition(helper, "alina:guild/can_accept", "{}",
                            new ParseContext(bundle, BOARD, "board"));
                    helper.assertFalse(canAccept.test(new Ctx(subject.player, bundle)), "can_accept is true at the cap");
                    assertRefusal(helper, guild().checkAccept(subject.player, LEDGER), Refusal.Reason.CAP_REACHED);
                })
                .thenExecute(() -> {
                    DialogueSession board = openBoard(helper, subject.player);
                    pick(helper, subject.player, board, "Drop a commission.");
                    DialogueSession abandon = presentedAt(helper, subject.player, "abandon");
                    helper.assertValueEqual(providedRows(abandon).size(), 2, "the drop lists the active commissions");
                    pick(helper, subject.player, abandon, "The Courier");
                    // The drop leads to the board in the same tick, and the board shows the freed slot.
                    DialogueSession shown = presentedAt(helper, subject.player, "board");
                    VisibleChoice ledger = row(helper, shown, "The Ledger");
                    helper.assertTrue(ledger.enabled(),
                            "an available feat is grayed out on the board that a drop opens: " + reason(ledger));
                    helper.assertTrue(providedRows(shown).stream().noneMatch(choice -> reason(choice).contains(CAP_REASON)),
                            "a board row gives the cap as its reason after a drop");
                    ContentBundle bundle = parse(helper, fixture(helper, "bundle.json"));
                    DialogueCondition canAccept = condition(helper, "alina:guild/can_accept", "{}",
                            new ParseContext(bundle, BOARD, "board"));
                    helper.assertTrue(canAccept.test(new Ctx(subject.player, bundle)), "can_accept is false after a drop");
                    helper.assertTrue(guild().checkAccept(subject.player, LEDGER).isEmpty(),
                            "checkAccept refused the ledger after a drop");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 600)
    public void aTurnInThroughTheTurnInProviderSettlesTheProofOnce(GameTestHelper helper) {
        Subject subject = new Subject();
        joinedWithFixture(helper, subject)
                .thenExecute(() -> helper.assertTrue(guild().accept(subject.player, COURIER).isEmpty(),
                        "the courier was refused"))
                .thenWaitUntil(() -> {
                    ensureFixtureActive(helper);
                    assertRefusal(helper, guild().checkTurnIn(subject.player, COURIER), Refusal.Reason.PROOF_NOT_MET);
                })
                .thenExecute(() -> {
                    DialogueSession turnIn = openTurnIn(helper, subject.player);
                    VisibleChoice courier = row(helper, turnIn, "The Courier");
                    helper.assertFalse(courier.enabled(), "the proof is not in the inventory");
                    helper.assertTrue(reason(courier).contains(HINT), "the grayed entry gives the hint: " + reason(courier));
                    subject.player.getInventory().setItem(0, new ItemStack(Items.PAPER));
                })
                .thenWaitUntil(() -> {
                    ensureFixtureActive(helper);
                    helper.assertTrue(guild().checkTurnIn(subject.player, COURIER).isEmpty(),
                            "the turn-in is not enabled yet");
                })
                .thenExecute(() -> {
                    DialogueSession turnIn = openTurnIn(helper, subject.player);
                    helper.assertTrue(row(helper, turnIn, "The Courier").enabled(), "the proof is in the inventory");
                    pick(helper, subject.player, turnIn, "The Courier");
                    DialogueSession shown = presentedAt(helper, subject.player, "turn_in");
                    helper.assertFalse(row(helper, shown, "The Courier").enabled(),
                            "a commission being turned in cannot be turned in again");
                })
                .thenWaitUntil(() -> {
                    ensureFixtureActive(helper);
                    helper.assertValueEqual(count(subject.player, Items.PAPER), 0, "paper left in the inventory");
                    helper.assertValueEqual(count(subject.player, Items.EMERALD), 1, "the placeholder reward");
                    GuildView view = viewOf(helper, subject.player);
                    helper.assertValueEqual(view.standing(COURIER), Standing.COMPLETED, "the courier's standing");
                    helper.assertValueEqual(view.reputation(), 2, "the courier's reputation, applied once");
                })
                .thenExecute(() -> {
                    DialogueSession turnIn = openTurnIn(helper, subject.player);
                    helper.assertTrue(providedRows(turnIn).isEmpty(), "a completed feat is still offered for turn-in");
                    helper.assertValueEqual(count(subject.player, Items.EMERALD), 1, "the reward after a second look");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 300)
    public void theNoticeBoardOpensTheBoardAnchoredAndEndsWhenThePlayerWalksAway(GameTestHelper helper) {
        Subject subject = new Subject();
        joinedWithFixture(helper, subject)
                .thenExecute(() -> {
                    helper.setBlock(NOTICE_BOARD, GuildBlocks.NOTICE_BOARD);
                    subject.noticeBoard = helper.absolutePos(NOTICE_BOARD);
                    ServerLevel level = helper.getLevel();
                    helper.assertTrue(level.getBlockState(subject.noticeBoard).is(GuildBlocks.NOTICE_BOARD),
                            "the block was not placed");
                    subject.player.setPos(subject.noticeBoard.getX() + 1.5, subject.noticeBoard.getY(),
                            subject.noticeBoard.getZ() + 0.5);
                    BlockState state = level.getBlockState(subject.noticeBoard);
                    state.useWithoutItem(level, subject.player, new BlockHitResult(
                            Vec3.atCenterOf(subject.noticeBoard), Direction.UP, subject.noticeBoard, false));

                    DialogueSession session = presentedAt(helper, subject.player, "board");
                    helper.assertTrue(session.graphId().equals(BOARD), "the Notice Board opens the board graph");
                    DialogueAnchor anchor = session.anchor();
                    helper.assertTrue(anchor != null, "the conversation is not anchored");
                    helper.assertTrue(anchor.position().equals(Vec3.atCenterOf(subject.noticeBoard)),
                            "the anchor is the centre of the block: " + anchor.position());
                    // The fixture's board_reach is 5, not the default of 6, so this reads the setting.
                    helper.assertValueEqual(anchor.maxDistance(), 5.0, "the anchor's reach is the board_reach setting");
                })
                .thenExecute(() -> subject.player.setPos(subject.noticeBoard.getX() + 20.5,
                        subject.noticeBoard.getY(), subject.noticeBoard.getZ() + 0.5))
                .thenWaitUntil(() -> helper.assertFalse(sessions().isInDialogue(subject.player),
                        "the conversation is still open after the player walked away"))
                .thenSucceed();
    }

    @GameTest(maxTicks = 300)
    public void aPlayerBeyondTheBoardsReachIsToldToStepCloser(GameTestHelper helper) {
        Subject subject = new Subject();
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> subject.chatter = new ChatPlayer(helper))
                .thenExecute(() -> {
                    ServerPlayer player = subject.chatter.player;
                    helper.setBlock(NOTICE_BOARD, GuildBlocks.NOTICE_BOARD);
                    BlockPos board = helper.absolutePos(NOTICE_BOARD);
                    ServerLevel level = helper.getLevel();
                    player.setPos(board.getX() + BEYOND_ANY_REACH, board.getY(), board.getZ() + 0.5);
                    level.getBlockState(board).useWithoutItem(level, player, new BlockHitResult(
                            Vec3.atCenterOf(board), Direction.UP, board, false));
                    helper.assertTrue(subject.chatter.messages().contains(GuildText.tooFar().getString()),
                            "the player was not told to step closer; chat: " + subject.chatter.messages());
                    helper.assertFalse(sessions().isInDialogue(player), "a conversation opened beyond the reach");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 40)
    public void theNoticeBoardIsImmuneToTheWitherAndTheDragonAndCannotBeMinedOrPushed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos board = helper.absolutePos(NOTICE_BOARD);
        Player survivor = helper.makeMockServerPlayer(GameType.SURVIVAL);
        WitherSkull skull = new WitherSkull(EntityTypes.WITHER_SKULL, level);
        skull.setDangerous(true);
        helper.succeedWhen(() -> {
            BlockState state = GuildBlocks.NOTICE_BOARD.defaultBlockState();
            helper.assertTrue(state.is(BlockTags.WITHER_IMMUNE), "the Notice Board is not in minecraft:wither_immune");
            helper.assertTrue(state.is(BlockTags.DRAGON_IMMUNE), "the Notice Board is not in minecraft:dragon_immune");
            helper.assertTrue(!WitherBoss.canDestroy(state), "a wither can destroy the Notice Board");
            // A blue skull caps the resistance of blocks a wither can destroy at 0.8. The board must keep its own.
            float resistance = GuildBlocks.NOTICE_BOARD.getExplosionResistance();
            helper.assertValueEqual(skull.getBlockExplosionResistance(null, level, board, state, state.getFluidState(),
                    resistance), resistance, "a dangerous wither skull's resistance for the Notice Board");
            // Destroy time -1 means a survival player makes no progress, and a piston cannot move the block.
            helper.assertValueEqual(state.getDestroyProgress(survivor, level, board), 0.0F,
                    "a survival player's break progress on the Notice Board");
            helper.assertTrue(!PistonBaseBlock.isPushable(state, level, board, Direction.UP, true, Direction.DOWN),
                    "a piston can push the Notice Board");
        });
    }

    @GameTest(maxTicks = 40)
    public void theAlinaCommandIsOfferedOnlyToGamemasters(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);
        helper.succeedWhen(() -> {
            CommandNode<CommandSourceStack> alina = server.getCommands().getDispatcher().getRoot().getChild("alina");
            helper.assertTrue(alina != null, "no /alina command is registered");
            helper.assertTrue(!alina.canUse(player.createCommandSourceStack()),
                    "a player without gamemaster permission can use /alina");
            helper.assertTrue(alina.canUse(server.createCommandSourceStack()), "the server cannot use /alina");
        });
    }

    @GameTest(maxTicks = 40)
    public void aPlayerWhoseRecordHasNotLoadedSeesLoadingRowsAndFalseConditions(GameTestHelper helper) {
        ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);
        helper.succeedWhen(() -> {
            requireStartupComplete(helper);
            ContentBundle bundle = parse(helper, fixture(helper, "bundle.json"));
            ParseContext parsing = new ParseContext(bundle, BOARD, "board");
            Ctx ctx = new Ctx(player, bundle);
            helper.assertTrue(guild().view(player).isEmpty(), "a player who never joined has a record");
            for (String type : List.of("alina:guild/board", "alina:guild/turn_in", "alina:guild/abandon",
                    "alina:guild/status")) {
                List<ProvidedChoice> rows = provider(helper, type, parsing).provide(ctx);
                helper.assertValueEqual(rows.size(), 1, type + " shows one row while the record loads");
                helper.assertFalse(rows.get(0).enabled(), type + " shows its loading row enabled");
                helper.assertValueEqual(rows.get(0).text().getString(), GuildText.loading().getString(),
                        type + " loading text");
            }
            for (String[] spec : new String[][] {
                    {"alina:guild/has_active", "{}"},
                    {"alina:guild/can_accept", "{}"},
                    {"alina:guild/reputation", "{\"op\": \"ne\", \"value\": 5}"},
                    {"alina:guild/commission", "{\"commission\": \"alina:dialogue_test/courier\", \"state\": \"available\"}"}}) {
                helper.assertFalse(condition(helper, spec[0], spec[1], parsing).test(ctx),
                        spec[0] + " is true while the record loads");
            }
        });
    }

    // ---------------------------------------------------------------- boards

    /** A bundle with the two boards of the boards fixture as its section, their graphs, and {@code extraGraph} (empty or a graph after a comma). */
    private static String twoBoards(String extraGraph) {
        return """
                { "schema": 1,
                  "dialogues": {
                    "alina:dialogue_test/hunters_board": { "start": "board", "nodes": { "board": { "text": "Hunters.",
                      "choices": [ { "provider": { "type": "alina:guild/board" } } ] } } },
                    "alina:dialogue_test/town_board": { "start": "board", "nodes": { "board": { "text": "Town.",
                      "choices": [ { "provider": { "type": "alina:guild/board" } } ] } } }%s },
                  "extensions": { "alina:guild": {
                    "settings": {},
                    "boards": {
                      "alina:dialogue_test/hunters": { "graph": "alina:dialogue_test/hunters_board", "categories": [ "A" ] },
                      "alina:dialogue_test/town": { "graph": "alina:dialogue_test/town_board", "categories": [ "C" ] } },
                    "commissions": { "alina:dialogue_test/courier": { "category": "C", "kind": "feat", "name": "The Courier",
                      "hint": "Bring a piece of paper.",
                      "proof": { "type": "item", "all_of": [ { "item": { "items": "minecraft:paper" } } ] } } } } } }
                """.formatted(extraGraph);
    }

    /** The board provider reads the board of the graph it is in: each graph's rows are that board's draw, then its feats. */
    @GameTest(maxTicks = 600, environment = "alina:guild_boards")
    public void theBoardProviderShowsTheBoardOfTheGraphItIsIn(GameTestHelper helper) {
        Subject subject = new Subject();
        joinedWithBoards(helper, subject)
                .thenExecute(() -> {
                    ContentBundle bundle = parse(helper, fixture(helper, BOARDS));
                    Ctx ctx = new Ctx(subject.player, bundle);
                    ChoiceProvider hunters = provider(helper, "alina:guild/board",
                            new ParseContext(bundle, HUNTERS_BOARD, "board"));
                    ChoiceProvider town = provider(helper, "alina:guild/board",
                            new ParseContext(bundle, TOWN_BOARD, "board"));
                    // The hunters' pool has one repeatable, which its board always draws; the town's has none.
                    helper.assertValueEqual(rowTexts(hunters.provide(ctx)), List.of("The Trapper", "The Lantern"),
                            "the hunters' board rows");
                    helper.assertValueEqual(rowTexts(town.provide(ctx)), List.of("The Courier", "The Ledger"),
                            "the town's board rows");
                })
                .thenSucceed();
    }

    /** With several boards, a board provider in a graph that no board uses is refused, unless it names its board. */
    @GameTest(maxTicks = 40)
    public void aBoardProviderOutsideABoardsGraphIsRefusedWhenThereAreSeveralBoards(GameTestHelper helper) {
        helper.succeedWhen(() -> {
            requireStartupComplete(helper);
            String stray = """
                    , "alina:dialogue_test/stray": { "start": "board", "nodes": { "board": { "text": "Stray.",
                      "choices": [ { "provider": { "type": "alina:guild/board" } } ] } } }
                    """;
            String message = problems(HaruhiServices.content().parseAndValidate(twoBoards(stray)));
            helper.assertTrue(message.contains("alina:dialogue_test/stray#board: alina:guild/board: name the board with "
                    + "board, or use this provider in a board's graph"), "no board problem in:\n" + message);
            String named = """
                    , "alina:dialogue_test/stray": { "start": "board", "nodes": { "board": { "text": "Stray.",
                      "choices": [ { "provider": { "type": "alina:guild/board", "board": "alina:dialogue_test/town" } } ] } } }
                    """;
            DataResult<ContentBundle> result = HaruhiServices.content().parseAndValidate(twoBoards(named));
            helper.assertTrue(result.result().isPresent(), "a provider that names its board was refused:\n" + problems(result));
        });
    }

    /**
     * The Notice Board whose block entity names the town board, assigned through {@code /alina guild noticeboard}, opens the
     * town's graph, anchored with the town's reach.
     */
    @GameTest(maxTicks = 400, environment = "alina:guild_boards")
    public void aNoticeBoardAssignedThroughTheCommandOpensItsBoardWithItsReach(GameTestHelper helper) {
        Subject subject = new Subject();
        joinedWithBoards(helper, subject)
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    helper.setBlock(NOTICE_BOARD, GuildBlocks.NOTICE_BOARD);
                    BlockPos board = helper.absolutePos(NOTICE_BOARD);
                    subject.noticeBoard = board;
                    runCommand(helper, "alina guild noticeboard " + coordinates(board) + " " + TOWN);
                    helper.assertValueEqual(assignedBoard(level, board), Optional.of(TOWN), "the board the command assigned");
                    subject.player.setPos(board.getX() + 1.5, board.getY(), board.getZ() + 0.5);
                    useNoticeBoard(level, subject.player, board);
                    DialogueSession session = presentedAt(helper, subject.player, "board");
                    helper.assertValueEqual(session.graphId(), TOWN_BOARD, "the graph the Notice Board opened");
                    DialogueAnchor anchor = session.anchor();
                    helper.assertTrue(anchor != null, "the conversation is not anchored");
                    // The town's board_reach is 3: the default is 6, and the hunters' board has 4.
                    helper.assertValueEqual(anchor.maxDistance(), 3.0, "the anchor's reach is the town board's reach");
                })
                .thenExecute(() -> subject.player.setPos(subject.noticeBoard.getX() + 20.5, subject.noticeBoard.getY(),
                        subject.noticeBoard.getZ() + 0.5))
                .thenWaitUntil(() -> helper.assertFalse(sessions().isInDialogue(subject.player),
                        "the conversation is still open after the player walked away"))
                .thenSucceed();
    }

    /**
     * A Notice Board with no board, while the content has several, tells the player the board is unassigned. Clearing an
     * assignment through {@code /alina guild noticeboard <pos> clear} leaves the block with no board again.
     */
    @GameTest(maxTicks = 300, environment = "alina:guild_boards")
    public void aNoticeBoardWithNoBoardWhileTheContentHasSeveralSaysSo(GameTestHelper helper) {
        Subject subject = new Subject();
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> subject.chatter = new ChatPlayer(helper))
                .thenExecute(() -> activateBoards(helper))
                .thenWaitUntil(() -> {
                    ensureBoardsActive(helper);
                    helper.assertValueEqual(AlinaContent.guild().boards().size(), 2, "the boards of the active content");
                })
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    helper.setBlock(NOTICE_BOARD, GuildBlocks.NOTICE_BOARD);
                    BlockPos board = helper.absolutePos(NOTICE_BOARD);
                    ServerPlayer player = subject.chatter.player;
                    player.setPos(board.getX() + 1.5, board.getY(), board.getZ() + 0.5);
                    useNoticeBoard(level, player, board);
                    helper.assertTrue(subject.chatter.messages().contains(GuildText.boardUnassigned().getString()),
                            "no unassigned message; chat: " + subject.chatter.messages());
                    helper.assertFalse(sessions().isInDialogue(player), "a conversation opened on a block with no board");
                    runCommand(helper, "alina guild noticeboard " + coordinates(board) + " " + TOWN);
                    runCommand(helper, "alina guild noticeboard " + coordinates(board) + " clear");
                    helper.assertTrue(assignedBoard(level, board).isEmpty(), "clear left a board on the block");
                    useNoticeBoard(level, player, board);
                    helper.assertValueEqual(subject.chatter.messages().stream()
                            .filter(text -> text.equals(GuildText.boardUnassigned().getString())).count(), 2L,
                            "the unassigned message after the clear");
                })
                .thenSucceed();
    }

    // ---------------------------------------------------------------- helpers

    private static GuildService guild() {
        return GuildModule.service();
    }

    private static DialogueSessions sessions() {
        return DialogueModule.sessions();
    }

    private static void requireStartupComplete(GameTestHelper helper) {
        helper.assertTrue(HaruhiServices.content().isStartupComplete(), "the startup content activation has not finished");
    }

    /**
     * Makes the subject's player, activates the fixture in its own step, and waits until the fixture is active and the
     * player's flags, record and today's board have loaded. The steps the caller adds after it run in the tick the wait
     * passes.
     */
    private static GameTestSequence joinedWithFixture(GameTestHelper helper, Subject subject) {
        return helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> subject.player = helper.makeMockServerPlayerInLevel())
                .thenExecute(() -> activateFixture(helper))
                .thenWaitUntil(() -> {
                    ensureFixtureActive(helper);
                    assertReady(helper, subject.player);
                });
    }

    /** The record, the flags and today's board (stored or not) have loaded for the player. */
    private static void assertReady(GameTestHelper helper, ServerPlayer player) {
        helper.assertTrue(sessions().loadedFlags(player).isPresent(), "the flags have not loaded");
        helper.assertTrue(guild().view(player).isPresent(), "the Guild record has not loaded");
        helper.assertTrue(guild().boardEntries(player, GuildContent.DEFAULT_BOARD).isPresent(), "today's board has not loaded");
    }

    /**
     * Activates the fixture again when a sibling test has replaced it. A check that follows runs against the fixture
     * only if this runs first, and a wait's check runs in the tick it passes.
     */
    private static void ensureFixtureActive(GameTestHelper helper) {
        if (!AlinaContent.guild().commissions().containsKey(COURIER)) {
            activateFixture(helper);
        }
    }

    private static void assertRefusal(GameTestHelper helper, Optional<Refusal> refusal, Refusal.Reason reason) {
        helper.assertTrue(refusal.isPresent() && refusal.get().reason() == reason,
                "expected " + reason + " but the check gave " + refusal.map(Refusal::reason));
    }

    /** The board's entries as the spec gives them: today's board commissions, then the feats by id. */
    private static List<Identifier> expectedBoard(GameTestHelper helper) {
        Optional<BoardView> today = guild().board(GuildContent.DEFAULT_BOARD);
        helper.assertTrue(today.isPresent(), "today's board has not loaded");
        List<Identifier> entries = new ArrayList<>(today.get().commissions());
        entries.addAll(FEATS);
        return entries;
    }

    private static List<Identifier> boardEntriesOf(GameTestHelper helper, ServerPlayer player) {
        Optional<List<Identifier>> entries = guild().boardEntries(player, GuildContent.DEFAULT_BOARD);
        helper.assertTrue(entries.isPresent(), "the board entries have not loaded");
        return entries.get();
    }

    private static GuildView viewOf(GameTestHelper helper, ServerPlayer player) {
        Optional<GuildView> view = guild().view(player);
        helper.assertTrue(view.isPresent(), "the Guild record has not loaded");
        return view.get();
    }

    private static String nameOf(GameTestHelper helper, Identifier commission) {
        String name = NAMES.get(commission);
        helper.assertTrue(name != null, "the test has no name for " + commission);
        return name;
    }

    /** Opens the board for a player whose flags are loaded. The caller has the fixture active in the same tick. */
    private static DialogueSession openBoard(GameTestHelper helper, ServerPlayer player) {
        helper.assertTrue(sessions().open(player, BOARD, null), "the board graph did not open");
        return sessionOf(helper, player);
    }

    /** The board, then the static choice that leads to the turn-in node. */
    private static DialogueSession openTurnIn(GameTestHelper helper, ServerPlayer player) {
        DialogueSession board = openBoard(helper, player);
        pick(helper, player, board, "Turn in a commission.");
        return presentedAt(helper, player, "turn_in");
    }

    private static DialogueSession sessionOf(GameTestHelper helper, ServerPlayer player) {
        Optional<DialogueSession> session = sessions().session(player);
        helper.assertTrue(session.isPresent(), "no conversation is open");
        return session.get();
    }

    /** The player's open conversation, once it shows the expected node. */
    private static DialogueSession presentedAt(GameTestHelper helper, ServerPlayer player, String node) {
        DialogueSession session = sessionOf(helper, player);
        helper.assertTrue(node.equals(session.presentedNode()),
                "expected node " + node + " but the conversation shows " + session.presentedNode());
        return session;
    }

    /** Picks the entry labelled {@code text} on the node the session shows, as the client's payload does. */
    private static void pick(GameTestHelper helper, ServerPlayer player, DialogueSession session, String text) {
        sessions().choose(player, session.id(), session.presentedNode(), indexOf(helper, session, text));
    }

    private static int indexOf(GameTestHelper helper, DialogueSession session, String text) {
        for (int index = 0; index < session.presented().size(); index++) {
            if (session.presented().get(index).text().getString().equals(text)) {
                return index;
            }
        }
        throw helper.assertionException(Component.literal(
                "the entry '" + text + "' is not shown; shown: " + texts(session)));
    }

    private static VisibleChoice row(GameTestHelper helper, DialogueSession session, String text) {
        return session.presented().get(indexOf(helper, session, text));
    }

    /** The entries the Guild's providers made, which carry a provided index; static choices do not. */
    private static List<VisibleChoice> providedRows(DialogueSession session) {
        return session.presented().stream().filter(choice -> choice.providedIndex() >= 0).toList();
    }

    private static String reason(VisibleChoice choice) {
        return choice.unavailableReason().map(Component::getString).orElse("");
    }

    private static List<String> texts(DialogueSession session) {
        return session.presented().stream().map(choice -> choice.text().getString()).toList();
    }

    private static int count(ServerPlayer player, net.minecraft.world.item.Item item) {
        return player.getInventory().countItem(item);
    }

    /** Makes the subject's player, activates the boards fixture, and waits until it is active and the player's board is loaded. */
    private static GameTestSequence joinedWithBoards(GameTestHelper helper, Subject subject) {
        return helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> subject.player = helper.makeMockServerPlayerInLevel())
                .thenExecute(() -> activateBoards(helper))
                .thenWaitUntil(() -> {
                    ensureBoardsActive(helper);
                    assertBoardsReady(helper, subject.player);
                });
    }

    /** The flags, the record, and both boards of the boards fixture have loaded. */
    private static void assertBoardsReady(GameTestHelper helper, ServerPlayer player) {
        helper.assertTrue(sessions().loadedFlags(player).isPresent(), "the flags have not loaded");
        helper.assertTrue(guild().view(player).isPresent(), "the Guild record has not loaded");
        helper.assertTrue(guild().board(HUNTERS).isPresent() && guild().board(TOWN).isPresent(),
                "the boards have not loaded");
    }

    /** Activates the boards fixture again when a sibling test has replaced the active content. */
    private static void ensureBoardsActive(GameTestHelper helper) {
        if (!AlinaContent.guild().boards().containsKey(HUNTERS)) {
            activateBoards(helper);
        }
    }

    private static void activateBoards(GameTestHelper helper) {
        HaruhiServices.content().activateUnstored(parse(helper, fixture(helper, BOARDS)));
    }

    private static List<String> rowTexts(List<ProvidedChoice> rows) {
        return rows.stream().map(row -> row.text().getString()).toList();
    }

    /** The block's coordinates as the command takes them. */
    private static String coordinates(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /** Runs a command as the server, which holds every permission level. */
    private static void runCommand(GameTestHelper helper, String command) {
        MinecraftServer server = helper.getLevel().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }

    /** The board the block entity at {@code pos} names, or empty when it has none or is not a Notice Board. */
    private static Optional<Identifier> assignedBoard(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof NoticeBoardBlockEntity entity ? entity.board() : Optional.empty();
    }

    /** Uses the block at {@code pos} as a player does, from where the player stands. */
    private static void useNoticeBoard(ServerLevel level, ServerPlayer player, BlockPos pos) {
        level.getBlockState(pos).useWithoutItem(level, player, new BlockHitResult(
                Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    /** Parses the fixture with the content service and makes it the active content, unstored. */
    private static void activateFixture(GameTestHelper helper) {
        HaruhiServices.content().activateUnstored(parse(helper, fixture(helper, "bundle.json")));
    }

    private static ContentBundle parse(GameTestHelper helper, String json) {
        DataResult<ContentBundle> result = HaruhiServices.content().parseAndValidate(json);
        Optional<ContentBundle> bundle = result.result();
        helper.assertTrue(bundle.isPresent(), "the bundle was refused:\n" + problems(result));
        return bundle.get();
    }

    private static String problems(DataResult<?> result) {
        return result.error().map(error -> error.message()).orElse("");
    }

    private static ChoiceProvider provider(GameTestHelper helper, String type, ParseContext context) {
        Optional<ChoiceProviderType> providerType = DialogueRegistries.provider(Identifier.parse(type));
        helper.assertTrue(providerType.isPresent(), type + " is not a registered provider");
        DataResult<ChoiceProvider> parsed = providerType.get().parse(new JsonObject(), context);
        helper.assertTrue(parsed.result().isPresent(), type + " should parse: " + problems(parsed));
        return parsed.result().get();
    }

    private static DialogueCondition condition(GameTestHelper helper, String type, String args, ParseContext context) {
        Optional<ConditionType> conditionType = DialogueRegistries.condition(Identifier.parse(type));
        helper.assertTrue(conditionType.isPresent(), type + " is not a registered condition");
        DataResult<DialogueCondition> parsed = conditionType.get()
                .parse(JsonParser.parseString(args).getAsJsonObject(), context);
        helper.assertTrue(parsed.result().isPresent(), type + " " + args + " should parse: " + problems(parsed));
        return parsed.result().get();
    }

    private static String fixture(GameTestHelper helper, String name) {
        try (InputStream in = GuildDialogueGameTests.class.getResourceAsStream(FIXTURES + name)) {
            if (in == null) {
                throw helper.assertionException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw helper.assertionException("could not read fixture " + name + ": " + e);
        }
    }
}
