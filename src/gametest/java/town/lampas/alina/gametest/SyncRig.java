package town.lampas.alina.gametest;

import com.mojang.serialization.DataResult;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.GameType;
import town.lampas.alina.achievement.AchievementStanding;
import town.lampas.alina.achievement.AchievementView;
import town.lampas.alina.achievement.AdvancementSync;
import town.lampas.alina.content.AchievementsContent;
import town.lampas.alina.content.AlinaContent;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;

/**
 * One sync GameTest's world: an {@link AdvancementSync} over a view the test sets for each player, the content the test
 * parsed from a fixture, and mock players outside the server's player list, so the server's own services and sync never
 * send them anything. Each player's connection is an embedded channel, and {@link #sentSince} returns the update packets
 * sent to it since the last call.
 */
final class SyncRig {
    private static final String FIXTURES = "/alina/fixtures/sync/";

    private final GameTestHelper helper;
    private final MinecraftServer server;
    private final AtomicReference<AchievementsContent> content = new AtomicReference<>();
    private final Map<UUID, AchievementView> views = new HashMap<>();
    private final List<ServerPlayer> online = new ArrayList<>();
    private final Map<UUID, EmbeddedChannel> channels = new HashMap<>();
    private final AdvancementSync sync;

    private SyncRig(GameTestHelper helper, String fixture) {
        this.helper = helper;
        this.server = helper.getLevel().getServer();
        this.content.set(parse(fixture));
        this.sync = new AdvancementSync(player -> Optional.ofNullable(views.get(player.getUUID())), content::get,
                () -> online);
    }

    static SyncRig create(GameTestHelper helper, String fixture) {
        return new SyncRig(helper, fixture);
    }

    AdvancementSync sync() {
        return sync;
    }

    /** Makes {@code next} the active content the sync reads, as a bundle activation would before the listener runs. */
    void useContent(String fixture) {
        content.set(parse(fixture));
    }

    /** Sets what the player's achievements are. A player with no view has a record that has not loaded. */
    void setView(ServerPlayer player, Map<Identifier, AchievementStanding> standings) {
        views.put(player.getUUID(), new AchievementView(player.getUUID(), standings));
    }

    /** Empties the player's view, as a record that has not loaded. */
    void clearView(ServerPlayer player) {
        views.remove(player.getUUID());
    }

    /** A mock player that is not in the server's player list. It is online for this rig's sync. */
    ServerPlayer newPlayer() {
        ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        channels.put(player.getUUID(), new EmbeddedChannel(connection));
        new ServerGamePacketListenerImpl(server, connection, player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false));
        online.add(player);
        return player;
    }

    /** Runs one server tick of the sync. */
    void tick() {
        sync.tick();
    }

    /** The update packets sent to the player since the previous call, oldest first. */
    List<ClientboundUpdateAdvancementsPacket> sentSince(ServerPlayer player) {
        EmbeddedChannel channel = channels.get(player.getUUID());
        List<ClientboundUpdateAdvancementsPacket> updates = new ArrayList<>();
        Object packet;
        while ((packet = channel.readOutbound()) != null) {
            if (packet instanceof ClientboundUpdateAdvancementsPacket update) {
                updates.add(update);
            }
        }
        return updates;
    }

    /** Parses a fixture and returns its {@code alina:achievements} section, failing the test if it is refused. */
    private AchievementsContent parse(String fixture) {
        return bundle(helper, fixture).extension(AlinaContent.ACHIEVEMENTS).orElseThrow(() ->
                helper.assertionException(Component.literal("the fixture " + fixture + " has no alina:achievements section")));
    }

    /** Parses a fixture bundle with haruhi, failing the test if it is refused. */
    static ContentBundle bundle(GameTestHelper helper, String fixture) {
        String json = read(helper, fixture).replace("{{dimension}}", helper.getLevel().dimension().identifier().toString());
        DataResult<ContentBundle> result = HaruhiServices.content().parseAndValidate(json);
        return result.result().orElseThrow(() -> helper.assertionException(Component.literal(
                "the fixture " + fixture + " was refused:\n" + result.error().map(error -> error.message()).orElse(""))));
    }

    private static String read(GameTestHelper helper, String name) {
        try (InputStream in = SyncRig.class.getResourceAsStream(FIXTURES + name)) {
            if (in == null) {
                throw helper.assertionException(Component.literal("missing fixture " + name));
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
