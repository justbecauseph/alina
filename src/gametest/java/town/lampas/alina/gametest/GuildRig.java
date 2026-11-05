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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.GameType;
import town.lampas.alina.content.AlinaContent;
import town.lampas.alina.content.GuildContent;
import town.lampas.alina.guild.GuildService;
import town.lampas.alina.settlement.PlayerDataSaves;
import town.lampas.alina.settlement.SettlementService;
import town.lampas.alina.storage.AlinaStorage;
import town.lampas.alina.storage.InMemoryStorage;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;

/**
 * One Guild GameTest's world: a {@link GuildService} and its {@link SettlementService} over one storage, a clock the test
 * moves, the content the test parsed from a fixture, and mock players that are not in the server's player list (so the
 * server's own services never serve them). The test calls {@link #tick} each game tick, which ticks both services. Nothing
 * here activates content on the server.
 */
final class GuildRig {
    /** 2026-11-05 10:00 in Asia/Manila, a few hours before the board resets at 04:00 the next morning. */
    static final Instant START = Instant.parse("2026-11-05T02:00:00Z");
    /** The content revision every service in these tests reports. */
    static final long REVISION = 3;
    private static final String FIXTURES = "/alina/fixtures/guild/";

    private final GameTestHelper helper;
    private final MinecraftServer server;
    private final AlinaStorage storage;
    private final GuildTestClock clock = new GuildTestClock(START);
    private final AtomicReference<GuildContent> content = new AtomicReference<>();
    private final List<ServerPlayer> online = new ArrayList<>();
    private final Map<UUID, EmbeddedChannel> channels = new HashMap<>();
    private final Map<UUID, List<String>> said = new HashMap<>();
    private final SettlementService settlement;
    private final GuildService service;
    private long ticks;

    private GuildRig(GameTestHelper helper, AlinaStorage storage, String fixture) {
        this.helper = helper;
        this.server = helper.getLevel().getServer();
        this.storage = storage;
        this.content.set(parse(fixture));
        this.settlement = settlementOver(storage);
        this.service = new GuildService(server, storage, settlement, content::get, () -> REVISION, clock,
                () -> online, () -> true);
    }

    /**
     * A rig whose storage is {@code wrap} applied to a fresh in-memory storage, so a test can make one storage call fail.
     */
    static GuildRig create(GameTestHelper helper, String fixture, Function<AlinaStorage, AlinaStorage> wrap) {
        return new GuildRig(helper, wrap.apply(new InMemoryStorage()), fixture);
    }

    static GuildRig create(GameTestHelper helper, String fixture) {
        return create(helper, fixture, Function.identity());
    }

    GuildService service() {
        return service;
    }

    /** The content the rig's service reads now. */
    GuildContent currentContent() {
        return content.get();
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

    /** The number of ticks this rig has run. */
    long ticks() {
        return ticks;
    }

    /** Ticks the Guild service and the settlement service, as the server does once per game tick. */
    void tick() {
        ticks++;
        service.tick();
        settlement.tick();
    }

    /** Replaces the active content the service reads. Use it with {@link GuildService#contentActivated} for a change. */
    void useContent(String fixture) {
        content.set(parse(fixture));
    }

    /**
     * A mock player that is not in the server's player list. Its connection is an embedded channel, so messages sent to the
     * player are kept for {@link #messages}. It is online for this rig's services.
     */
    ServerPlayer newPlayer() {
        ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        channels.put(player.getUUID(), new EmbeddedChannel(connection));
        new ServerGamePacketListenerImpl(server, connection, player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false));
        online.add(player);
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

    /** A service over {@code over}, with its own settlement and this rig's clock, content and players. */
    GuildService serviceOver(AlinaStorage over, Supplier<GuildContent> from) {
        SettlementService settle = settlementOver(over);
        return new GuildService(server, over, settle, from, () -> REVISION, clock, () -> online, () -> true);
    }

    private SettlementService settlementOver(AlinaStorage over) {
        return new SettlementService(server, over, UUID.randomUUID(), () -> online,
                (player, expected) -> PlayerDataSaves.saveAndVerify(server, player, expected));
    }

    private GuildContent parse(String fixture) {
        String json = read(fixture).replace("{{dimension}}", helper.getLevel().dimension().identifier().toString());
        DataResult<ContentBundle> result = HaruhiServices.content().parseAndValidate(json);
        ContentBundle bundle = result.result().orElseThrow(() -> new IllegalStateException(
                "the fixture " + fixture + " was refused:\n" + result.error().map(error -> error.message()).orElse("")));
        return bundle.extension(AlinaContent.GUILD).orElseThrow(() ->
                new IllegalStateException("the fixture " + fixture + " has no alina:guild section"));
    }

    private static String read(String name) {
        try (InputStream in = GuildRig.class.getResourceAsStream(FIXTURES + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
