package town.lampas.alina.gametest;

import com.mojang.serialization.DataResult;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.GameType;
import town.lampas.alina.achievement.AchievementService;
import town.lampas.alina.content.AchievementsContent;
import town.lampas.alina.content.AlinaContent;
import town.lampas.alina.settlement.PlayerDataSaves;
import town.lampas.alina.settlement.SettlementService;
import town.lampas.alina.storage.AlinaStorage;
import town.lampas.alina.storage.InMemoryStorage;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;

/**
 * One achievement GameTest's world: an {@link AchievementService} and its {@link SettlementService} over one in-memory
 * storage, a clock the test moves, the content the test parsed from a fixture, and mock players that are not in the
 * server's player list (so the server's own services never serve them). The test calls {@link #tick} each game tick, which
 * ticks both services. Nothing here activates content on the server.
 */
final class AchievementRig {
    /** 2026-11-06 10:00 in Asia/Manila. */
    static final Instant START = Instant.parse("2026-11-06T02:00:00Z");
    /** The content revision every service in these tests reports. */
    static final long REVISION = 4;
    private static final String FIXTURES = "/alina/fixtures/achievement/";

    private final GameTestHelper helper;
    private final MinecraftServer server;
    private final AlinaStorage storage;
    private final GuildTestClock clock = new GuildTestClock(START);
    private final AtomicReference<AchievementsContent> content = new AtomicReference<>();
    /** What the service reports as contentReady. True unless a test sets it false. */
    private final AtomicBoolean ready = new AtomicBoolean(true);
    private final List<ServerPlayer> online = new ArrayList<>();
    private final Map<UUID, EmbeddedChannel> channels = new HashMap<>();
    private final Map<UUID, List<String>> said = new HashMap<>();
    private final SettlementService settlement;
    private final AchievementService service;
    private long ticks;

    private AchievementRig(GameTestHelper helper, String fixture, Function<AlinaStorage, AlinaStorage> wrap) {
        this.helper = helper;
        this.server = helper.getLevel().getServer();
        this.storage = wrap.apply(new InMemoryStorage());
        this.content.set(parse(fixture));
        this.settlement = new SettlementService(server, storage, UUID.randomUUID(), () -> online,
                (player, expected) -> PlayerDataSaves.saveAndVerify(server, player, expected));
        this.service = new AchievementService(server, storage, settlement, content::get, () -> REVISION, clock,
                () -> online, ready::get);
    }

    /** A rig over a fresh in-memory storage, with the content of {@code fixture} active. */
    static AchievementRig create(GameTestHelper helper, String fixture) {
        return create(helper, fixture, Function.identity());
    }

    /**
     * A rig whose storage is {@code wrap} applied to a fresh in-memory storage, so a test can hold or fail one kind of call.
     */
    static AchievementRig create(GameTestHelper helper, String fixture, Function<AlinaStorage, AlinaStorage> wrap) {
        return new AchievementRig(helper, fixture, wrap);
    }

    AchievementService service() {
        return service;
    }

    SettlementService settlement() {
        return settlement;
    }

    AlinaStorage storage() {
        return storage;
    }

    GuildTestClock clock() {
        return clock;
    }

    /** Ticks the achievement service and the settlement service, as the server does once per game tick. */
    void tick() {
        ticks++;
        service.tick();
        settlement.tick();
    }

    /** The number of ticks this rig has run. */
    long ticks() {
        return ticks;
    }

    /** Replaces the active content the service reads. Call {@link AchievementService#contentActivated} for a change. */
    void useContent(String fixture) {
        content.set(parse(fixture));
    }

    /** Sets what the service reports as contentReady: false stands for the startup activation not having finished. */
    void setReady(boolean value) {
        ready.set(value);
    }

    /**
     * A mock player that is not in the server's player list and not yet in this rig's online list. Its connection is an
     * embedded channel, so the chat messages sent to the player are kept for {@link #messages}. {@link #comeOnline} puts it
     * in the online list.
     */
    ServerPlayer newOfflinePlayer() {
        ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        channels.put(player.getUUID(), new EmbeddedChannel(connection));
        new ServerGamePacketListenerImpl(server, connection, player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false));
        return player;
    }

    /** Puts a player from {@link #newOfflinePlayer} in the online list. The services serve it from the next tick. */
    void comeOnline(ServerPlayer player) {
        online.add(player);
    }

    /** A mock player that is online for this rig's services: {@link #newOfflinePlayer}, then {@link #comeOnline}. */
    ServerPlayer newPlayer() {
        ServerPlayer player = newOfflinePlayer();
        comeOnline(player);
        return player;
    }

    /** A new player, joined to this rig's service. */
    ServerPlayer joined() {
        ServerPlayer player = newPlayer();
        service.join(player);
        return player;
    }

    /** The text of every chat message the player has been sent so far, oldest first. */
    List<String> messages(ServerPlayer player) {
        EmbeddedChannel channel = channels.get(player.getUUID());
        List<String> texts = said.computeIfAbsent(player.getUUID(), id -> new ArrayList<>());
        Object packet;
        while ((packet = channel.readOutbound()) != null) {
            if (packet instanceof ClientboundSystemChatPacket chat) {
                texts.add(chat.content().getString());
            }
        }
        return List.copyOf(texts);
    }

    /** The text of a fixture, with the test dimension filled in. */
    static String fixture(GameTestHelper helper, String name) {
        try (InputStream in = AchievementRig.class.getResourceAsStream(FIXTURES + name)) {
            if (in == null) {
                throw helper.assertionException(Component.literal("missing fixture " + name));
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Parses a fixture. A refused fixture or one without an {@code alina:achievements} section fails with a GameTest
     * assertion, so a step that uses it fails its test rather than the server.
     */
    private AchievementsContent parse(String fixture) {
        String json = fixture(helper, fixture);
        DataResult<ContentBundle> result = HaruhiServices.content().parseAndValidate(json);
        ContentBundle bundle = result.result().orElseThrow(() -> helper.assertionException(Component.literal(
                "the fixture " + fixture + " was refused:\n" + result.error().map(error -> error.message()).orElse(""))));
        return bundle.extension(AlinaContent.ACHIEVEMENTS).orElseThrow(() -> helper.assertionException(Component.literal(
                "the fixture " + fixture + " has no alina:achievements section")));
    }
}
