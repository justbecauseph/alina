package town.lampas.alina.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import town.lampas.alina.Alina;
import town.lampas.alina.guild.GuildModule;
import town.lampas.alina.guild.GuildService;
import town.lampas.alina.guild.GuildText;
import town.lampas.alina.guild.GuildView;
import town.lampas.alina.guild.Refusal;
import town.lampas.alina.guild.Standing;
import town.lampas.alina.guild.block.GuildBlocks;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.dialogue.DialogueModule;
import town.lampas.haruhi.dialogue.DialogueSessions;
import town.lampas.haruhi.dialogue.api.ChoiceProvider;
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
 * accept bundles through the content service. The behaviour tests need the server's {@code GuildService}: each joins a mock
 * player, so the service loads its record, and each activates the fixture in the same step as the call it checks, so the
 * commissions under test are the active content's. The board is stored per date, not per revision, and the feats are the
 * fixture's only commissions, so the entries do not depend on which content drew today's board.
 */
@SuppressWarnings("removal")
public final class GuildDialogueGameTests {
    private static final String FIXTURES = "/alina/fixtures/dialogue/";
    private static final Identifier BOARD = Alina.id("dialogue_test/board");
    private static final Identifier COURIER = Alina.id("dialogue_test/courier");
    private static final Identifier LANTERN = Alina.id("dialogue_test/lantern");
    private static final Identifier LEDGER = Alina.id("dialogue_test/ledger");
    private static final Map<Identifier, String> NAMES =
            Map.of(COURIER, "The Courier", LANTERN, "The Lantern", LEDGER, "The Ledger");
    private static final String CAP_REASON = "the most the Guild allows";
    private static final String HINT = "Bring a piece of paper.";
    private static final BlockPos NOTICE_BOARD = new BlockPos(2, 2, 2);

    /** The mock player and the block the tests share. */
    private static final class Subject {
        private ServerPlayer player;
        private BlockPos noticeBoard;
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
            DataResult<ContentBundle> result = HaruhiServices.content().parseAndValidate(fixture("every_type.json"));
            helper.assertTrue(result.result().isPresent(), "the every-type bundle was refused:\n" + problems(result));
        });
    }

    // ---------------------------------------------------------------- behaviour

    @GameTest(maxTicks = 600)
    public void theBoardListsTheFeatsAndAPickAccepts(GameTestHelper helper) {
        Subject subject = new Subject();
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> subject.player = helper.makeMockServerPlayerInLevel())
                .thenWaitUntil(() -> assertReady(helper, subject.player))
                .thenExecute(() -> {
                    DialogueSession session = openBoard(helper, subject.player);
                    List<Identifier> board = guild().boardEntries(subject.player).orElseThrow();
                    // The repeatables are not drawn in this content; the feats the player has not done are listed by id.
                    helper.assertTrue(board.equals(List.of(COURIER, LANTERN, LEDGER)),
                            "the board entries are not the feats by id: " + board);
                    List<VisibleChoice> rows = providedRows(session);
                    helper.assertValueEqual(rows.size(), 3, "entries from the board provider");
                    for (int index = 0; index < board.size(); index++) {
                        VisibleChoice row = rows.get(index);
                        helper.assertValueEqual(row.text().getString(), NAMES.get(board.get(index)),
                                "the entry's text is the commission's name");
                        helper.assertTrue(row.enabled(), "a feat the player can accept is shown enabled");
                    }
                    pick(helper, subject.player, session, "The Lantern");
                    helper.assertTrue(guild().view(subject.player).orElseThrow().standing(LANTERN) == Standing.ACTIVE,
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
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> subject.player = helper.makeMockServerPlayerInLevel())
                .thenWaitUntil(() -> assertReady(helper, subject.player))
                .thenExecute(() -> {
                    DialogueSession session = openBoard(helper, subject.player);
                    helper.assertTrue(providedRows(session).stream().allMatch(VisibleChoice::enabled),
                            "every feat is shown enabled before the cap is reached");
                    // The cap is two. Accepting through the service after the node is shown makes the ledger stale.
                    helper.assertTrue(guild().accept(subject.player, COURIER).isEmpty(), "the courier was refused");
                    helper.assertTrue(guild().accept(subject.player, LANTERN).isEmpty(), "the lantern was refused");
                    helper.assertValueEqual(guild().view(subject.player).orElseThrow().activeCount(), 2,
                            "active commissions at the cap");
                    pick(helper, subject.player, session, "The Ledger");
                })
                .thenExecute(() -> {
                    DialogueSession session = presentedAt(helper, subject.player, "board");
                    VisibleChoice ledger = row(helper, session, "The Ledger");
                    helper.assertFalse(ledger.enabled(), "the stale pick's entry is grayed out when the node is shown again");
                    helper.assertTrue(reason(ledger).contains(CAP_REASON), "the reason is the cap: " + reason(ledger));
                    GuildView view = guild().view(subject.player).orElseThrow();
                    helper.assertValueEqual(view.standing(LEDGER), Standing.AVAILABLE, "the stale pick accepted nothing");
                    helper.assertValueEqual(view.activeCount(), 2, "active commissions after the stale pick");

                    int shown = session.presented().size();
                    // A forged index past the end, then the entry of a commission that is already active.
                    sessions().choose(subject.player, session.id(), "board", shown + 5);
                    sessions().choose(subject.player, session.id(), "board", indexOf(helper, session, "The Courier"));
                    helper.assertTrue(sessions().session(subject.player).orElseThrow() == session,
                            "the same conversation is still open");
                    helper.assertValueEqual(session.presentedNode(), "board", "the node after the forged picks");
                    helper.assertValueEqual(session.presented().size(), shown, "the shown entries after the forged picks");
                    helper.assertValueEqual(guild().view(subject.player).orElseThrow().activeCount(), 2,
                            "active commissions after the forged picks");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 600)
    public void aTurnInThroughTheTurnInProviderSettlesTheProofOnce(GameTestHelper helper) {
        Subject subject = new Subject();
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> subject.player = helper.makeMockServerPlayerInLevel())
                .thenWaitUntil(() -> assertReady(helper, subject.player))
                .thenExecute(() -> helper.assertTrue(guild().accept(subject.player, COURIER).isEmpty(),
                        "the courier was refused"))
                .thenWaitUntil(() -> assertRefusal(helper, guild().checkTurnIn(subject.player, COURIER),
                        Refusal.Reason.PROOF_NOT_MET))
                .thenExecute(() -> {
                    DialogueSession turnIn = openTurnIn(helper, subject.player);
                    VisibleChoice courier = row(helper, turnIn, "The Courier");
                    helper.assertFalse(courier.enabled(), "the proof is not in the inventory");
                    helper.assertTrue(reason(courier).contains(HINT), "the grayed entry gives the hint: " + reason(courier));
                    subject.player.getInventory().setItem(0, new ItemStack(Items.PAPER));
                })
                .thenWaitUntil(() -> helper.assertTrue(guild().checkTurnIn(subject.player, COURIER).isEmpty(),
                        "the turn-in is not enabled yet"))
                .thenExecute(() -> {
                    DialogueSession turnIn = openTurnIn(helper, subject.player);
                    helper.assertTrue(row(helper, turnIn, "The Courier").enabled(), "the proof is in the inventory");
                    pick(helper, subject.player, turnIn, "The Courier");
                })
                .thenExecute(() -> {
                    DialogueSession turnIn = presentedAt(helper, subject.player, "turn_in");
                    helper.assertFalse(row(helper, turnIn, "The Courier").enabled(),
                            "a commission being turned in cannot be turned in again");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(count(subject.player, Items.PAPER), 0, "paper left in the inventory");
                    helper.assertValueEqual(count(subject.player, Items.EMERALD), 1, "the placeholder reward");
                    GuildView view = guild().view(subject.player).orElseThrow();
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
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> subject.player = helper.makeMockServerPlayerInLevel())
                .thenWaitUntil(() -> helper.assertTrue(sessions().loadedFlags(subject.player).isPresent(),
                        "the flags have not loaded"))
                .thenExecute(() -> {
                    activateFixture(helper);
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
                    helper.assertValueEqual(anchor.maxDistance(), 6.0, "the anchor's reach is the board_reach setting");
                })
                .thenExecute(() -> subject.player.setPos(subject.noticeBoard.getX() + 20.5,
                        subject.noticeBoard.getY(), subject.noticeBoard.getZ() + 0.5))
                .thenWaitUntil(() -> helper.assertFalse(sessions().isInDialogue(subject.player),
                        "the conversation is still open after the player walked away"))
                .thenSucceed();
    }

    @GameTest(maxTicks = 40)
    public void aPlayerWhoseRecordHasNotLoadedSeesLoadingRowsAndFalseConditions(GameTestHelper helper) {
        ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);
        helper.succeedWhen(() -> {
            requireStartupComplete(helper);
            ContentBundle bundle = parse(helper, fixture("bundle.json"));
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

    /** The record and the board have loaded, and so have the flags the conversation needs. */
    private static void assertReady(GameTestHelper helper, ServerPlayer player) {
        helper.assertTrue(sessions().loadedFlags(player).isPresent(), "the flags have not loaded");
        helper.assertTrue(guild().view(player).isPresent(), "the Guild record has not loaded");
        helper.assertTrue(guild().boardEntries(player).isPresent(), "today's board has not loaded");
    }

    private static void assertRefusal(GameTestHelper helper, Optional<Refusal> refusal, Refusal.Reason reason) {
        helper.assertTrue(refusal.isPresent() && refusal.get().reason() == reason,
                "expected " + reason + " but the check gave " + refusal.map(Refusal::reason));
    }

    /** Activates the fixture and opens the board in this step, for a player whose flags are loaded. */
    private static DialogueSession openBoard(GameTestHelper helper, ServerPlayer player) {
        activateFixture(helper);
        helper.assertTrue(sessions().open(player, BOARD, null), "the board graph did not open");
        Optional<DialogueSession> session = sessions().session(player);
        helper.assertTrue(session.isPresent(), "the flags were loaded, so the board started in this step");
        return session.get();
    }

    /** The board, then the static choice that leads to the turn-in node. */
    private static DialogueSession openTurnIn(GameTestHelper helper, ServerPlayer player) {
        DialogueSession board = openBoard(helper, player);
        pick(helper, player, board, "Turn in a commission.");
        return presentedAt(helper, player, "turn_in");
    }

    /** The player's open conversation, once it shows the expected node. */
    private static DialogueSession presentedAt(GameTestHelper helper, ServerPlayer player, String node) {
        Optional<DialogueSession> session = sessions().session(player);
        helper.assertTrue(session.isPresent(), "no conversation is open");
        helper.assertTrue(node.equals(session.get().presentedNode()),
                "expected node " + node + " but the conversation shows " + session.get().presentedNode());
        return session.get();
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
        helper.assertTrue(false, "the entry '" + text + "' is not shown; shown: " + texts(session));
        throw new IllegalStateException("unreachable");
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

    /** Parses the fixture with the content service and makes it the active content, unstored. */
    private static void activateFixture(GameTestHelper helper) {
        HaruhiServices.content().activateUnstored(parse(helper, fixture("bundle.json")));
    }

    private static ContentBundle parse(GameTestHelper helper, String json) {
        DataResult<ContentBundle> result = HaruhiServices.content().parseAndValidate(json);
        helper.assertTrue(result.result().isPresent(), "the bundle was refused:\n" + problems(result));
        return result.result().orElseThrow();
    }

    private static String problems(DataResult<?> result) {
        return result.error().map(error -> error.message()).orElse("");
    }

    private static ChoiceProvider provider(GameTestHelper helper, String type, ParseContext context) {
        DataResult<ChoiceProvider> parsed = DialogueRegistries.provider(Identifier.parse(type)).orElseThrow()
                .parse(new JsonObject(), context);
        helper.assertTrue(parsed.result().isPresent(), type + " should parse: " + problems(parsed));
        return parsed.result().orElseThrow();
    }

    private static DialogueCondition condition(GameTestHelper helper, String type, String args, ParseContext context) {
        DataResult<DialogueCondition> parsed = DialogueRegistries.condition(Identifier.parse(type)).orElseThrow()
                .parse(JsonParser.parseString(args).getAsJsonObject(), context);
        helper.assertTrue(parsed.result().isPresent(), type + " " + args + " should parse: " + problems(parsed));
        return parsed.result().orElseThrow();
    }

    private static String fixture(String name) {
        try (InputStream in = GuildDialogueGameTests.class.getResourceAsStream(FIXTURES + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
