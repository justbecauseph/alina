package town.lampas.alina.trade.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import town.lampas.alina.TestBootstrap;

/**
 * Every trade payload through its stream codec. A payload is written, read back and written again, and the two byte strings
 * must match. Stacks need the item components bound, as in the pricing tests, and the offer id cap is checked on the wire.
 */
class TradePayloadsTest {
    /** Set by {@link #boot}: reading BuiltInRegistries before Minecraft is booted would fail its class initialisation. */
    private static RegistryAccess registries;

    @BeforeAll
    static void boot() {
        TestBootstrap.boot();
        HolderLookup.Provider datapack = VanillaRegistries.createLookup();
        List<HolderLookup.RegistryLookup<?>> lookups = new ArrayList<>();
        BuiltInRegistries.REGISTRY.forEach(lookups::add);
        datapack.listRegistryKeys()
                .filter(key -> !BuiltInRegistries.REGISTRY.containsKey(key.identifier()))
                .forEach(key -> lookups.add(datapack.lookupOrThrow(key)));
        for (DataComponentInitializers.PendingComponents<?> pending
                : BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(HolderLookup.Provider.create(lookups.stream()))) {
            pending.apply();
        }
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    private static RegistryFriendlyByteBuf emptyBuffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
    }

    private static RegistryFriendlyByteBuf bufferOf(byte[] bytes) {
        return new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), registries);
    }

    private static byte[] bytesOf(RegistryFriendlyByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
    }

    /** Writes the value, reads it back, writes the read value again, and checks both writes match. Returns the read value. */
    private static <T> T roundTrip(StreamCodec<? super RegistryFriendlyByteBuf, T> codec, T value) {
        RegistryFriendlyByteBuf written = emptyBuffer();
        codec.encode(written, value);
        byte[] bytes = bytesOf(written);
        T read = codec.decode(bufferOf(bytes));
        RegistryFriendlyByteBuf writtenAgain = emptyBuffer();
        codec.encode(writtenAgain, read);
        assertArrayEquals(bytes, bytesOf(writtenAgain), "the value read back encodes to the same bytes");
        return read;
    }

    private static Component text(String text) {
        return Component.literal(text);
    }

    private static TradeView sampleView() {
        // Aur above the int range, so the long codec is what carries it.
        return new TradeView(12_345_678_901L, true, true, 150, 200,
                List.of(new SellRow(3, new ItemStack(Items.PAPER, 8), 1, 4, 8, true)),
                8, 2,
                List.of(new OfferRow("hat", new ItemStack(Items.LEATHER_HELMET), 250, -1, 1,
                        Optional.of(text("Needs reputation 50")))),
                Optional.of(new ExperienceView(120, 4, 50, 1, 6, 50, 2, 20, Optional.empty())),
                false);
    }

    private static void assertSameView(TradeView expected, TradeView actual) {
        assertEquals(expected.aur(), actual.aur());
        assertEquals(expected.selling(), actual.selling());
        assertEquals(expected.sellsItems(), actual.sellsItems());
        assertEquals(expected.budgetLeft(), actual.budgetLeft());
        assertEquals(expected.dailyBudget(), actual.dailyBudget());
        assertEquals(expected.junkItems(), actual.junkItems());
        assertEquals(expected.junkAur(), actual.junkAur());
        assertEquals(expected.busy(), actual.busy());
        assertEquals(expected.experience(), actual.experience());
        assertEquals(expected.sellRows().size(), actual.sellRows().size());
        SellRow row = actual.sellRows().get(0);
        assertEquals(3, row.slot());
        assertTrue(ItemStack.matches(new ItemStack(Items.PAPER, 8), row.stack()));
        assertEquals(1, row.aur());
        assertEquals(4, row.per());
        assertEquals(8, row.maxCount());
        assertTrue(row.junk());
        assertEquals(expected.offers().size(), actual.offers().size());
        OfferRow offer = actual.offers().get(0);
        assertEquals("hat", offer.id());
        assertTrue(ItemStack.matches(new ItemStack(Items.LEATHER_HELMET), offer.stack()));
        assertEquals(250, offer.price());
        assertEquals(-1, offer.limitLeft());
        assertEquals(1, offer.maxCount());
        assertEquals("Needs reputation 50", offer.locked().orElseThrow().getString());
    }

    @Test
    void tradeOpenRoundTrips() {
        TradeOpenPayload sent = new TradeOpenPayload(7, Identifier.parse("lampas:mira"), text("Mira's stall"),
                OptionalInt.of(42), text("Welcome."), sampleView());

        TradeOpenPayload read = roundTrip(TradeOpenPayload.STREAM_CODEC, sent);

        assertEquals(7, read.sessionId());
        assertEquals(Identifier.parse("lampas:mira"), read.shopId());
        assertEquals("Mira's stall", read.shopName().getString());
        assertEquals(OptionalInt.of(42), read.speakerEntityId());
        assertEquals("Welcome.", read.line().getString());
        assertSameView(sampleView(), read.view());
    }

    @Test
    void tradeOpenWithoutASpeakerRoundTrips() {
        TradeOpenPayload read = roundTrip(TradeOpenPayload.STREAM_CODEC, new TradeOpenPayload(8,
                Identifier.parse("lampas:mira"), text("Mira's stall"), OptionalInt.empty(), text("Hello."), sampleView()));

        assertEquals(OptionalInt.empty(), read.speakerEntityId());
    }

    @Test
    void tradeViewRoundTrips() {
        TradeViewPayload read = roundTrip(TradeViewPayload.STREAM_CODEC, new TradeViewPayload(9, sampleView()));

        assertEquals(9, read.sessionId());
        assertSameView(sampleView(), read.view());
    }

    @Test
    void aTradeViewWithNoRowsOrExperienceRoundTrips() {
        TradeView empty = new TradeView(0, false, false, 0, 0, List.of(), 0, 0, List.of(), Optional.empty(), true);

        TradeView read = roundTrip(TradeViewPayload.STREAM_CODEC, new TradeViewPayload(1, empty)).view();

        assertEquals(List.of(), read.sellRows());
        assertEquals(List.of(), read.offers());
        assertEquals(Optional.empty(), read.experience());
        assertTrue(read.busy());
    }

    @Test
    void sellsItemsRoundTripsWithBothValues() {
        // A shop that pays players but has no sell rules: selling is true, sellsItems is false.
        TradeView paysWithoutRules = new TradeView(0, true, false, 0, 50, List.of(), 0, 0, List.of(), Optional.empty(),
                false);

        TradeView withRules = roundTrip(TradeViewPayload.STREAM_CODEC, new TradeViewPayload(20, sampleView())).view();
        assertTrue(withRules.sellsItems());
        TradeView read = roundTrip(TradeViewPayload.STREAM_CODEC, new TradeViewPayload(21, paysWithoutRules)).view();
        assertTrue(read.selling());
        assertFalse(read.sellsItems());
    }

    @Test
    void tradeResultRoundTrips() {
        TradeResultPayload read = roundTrip(TradeResultPayload.STREAM_CODEC,
                new TradeResultPayload(10, false, text("Not enough Aur.")));

        assertEquals(10, read.sessionId());
        assertFalse(read.accepted());
        assertEquals("Not enough Aur.", read.line().getString());
    }

    @Test
    void tradeCloseRoundTrips() {
        assertEquals(11, roundTrip(TradeClosePayload.STREAM_CODEC, new TradeClosePayload(11)).sessionId());
    }

    @Test
    void tradeSellRoundTrips() {
        assertEquals(new TradeSellPayload(12, 35, 4, 1),
                roundTrip(TradeSellPayload.STREAM_CODEC, new TradeSellPayload(12, 35, 4, 1)));
    }

    @Test
    void tradeSellJunkRoundTrips() {
        assertEquals(new TradeSellJunkPayload(13, 2),
                roundTrip(TradeSellJunkPayload.STREAM_CODEC, new TradeSellJunkPayload(13, 2)));
    }

    @Test
    void tradeBuyRoundTrips() {
        assertEquals(new TradeBuyPayload(14, "hat", 2, 500),
                roundTrip(TradeBuyPayload.STREAM_CODEC, new TradeBuyPayload(14, "hat", 2, 500)));
    }

    @Test
    void tradeExperienceRoundTrips() {
        assertEquals(new TradeExperiencePayload(15, true, 1, 50),
                roundTrip(TradeExperiencePayload.STREAM_CODEC, new TradeExperiencePayload(15, true, 1, 50)));
        assertEquals(new TradeExperiencePayload(16, false, 2, 50),
                roundTrip(TradeExperiencePayload.STREAM_CODEC, new TradeExperiencePayload(16, false, 2, 50)));
    }

    @Test
    void tradeDismissRoundTrips() {
        assertEquals(new TradeDismissPayload(17), roundTrip(TradeDismissPayload.STREAM_CODEC, new TradeDismissPayload(17)));
    }

    @Test
    void anOfferIdOfSixtyFourCharactersIsTheLongestAllowed() {
        String longest = "x".repeat(OfferRow.MAX_ID_LENGTH);

        assertEquals(longest, roundTrip(TradeBuyPayload.STREAM_CODEC, new TradeBuyPayload(18, longest, 1, 1)).offerId());
        assertThrows(IllegalArgumentException.class,
                () -> new TradeBuyPayload(18, "x".repeat(OfferRow.MAX_ID_LENGTH + 1), 1, 1));
    }

    @Test
    void aClientCannotSendAnOfferIdLongerThanSixtyFourCharacters() {
        // The bytes a client would write: session, then a 65-character offer id with no cap, then count and cost.
        RegistryFriendlyByteBuf written = emptyBuffer();
        ByteBufCodecs.VAR_INT.encode(written, 19);
        ByteBufCodecs.STRING_UTF8.encode(written, "x".repeat(OfferRow.MAX_ID_LENGTH + 1));
        ByteBufCodecs.VAR_INT.encode(written, 1);
        ByteBufCodecs.VAR_INT.encode(written, 1);

        assertThrows(RuntimeException.class, () -> TradeBuyPayload.STREAM_CODEC.decode(bufferOf(bytesOf(written))));
    }

    private static MarketView sampleMarket() {
        // Reserves past the int range, and the rates after two changes of the pool, oldest first: 50.00 and 51.01 points per Aur.
        return new MarketView(505_051L, 9_900L, 500_000L, 10_000L, 5, -1, 10, List.of(5_000, 5_101));
    }

    @Test
    void marketViewRoundTrips() {
        assertEquals(sampleMarket(), roundTrip(MarketView.STREAM_CODEC, sampleMarket()));
    }

    @Test
    void aMarketViewWithNoHistoryRoundTrips() {
        MarketView empty = new MarketView(1, 1, 500_000, 10_000, -1, 0, 50, List.of());

        assertEquals(empty, roundTrip(MarketView.STREAM_CODEC, empty));
    }

    @Test
    void aMarketViewKeepsAtMostThirtyTwoRates() {
        List<Integer> rates = java.util.stream.IntStream.rangeClosed(1, MarketView.MAX_HISTORY).boxed().toList();

        assertEquals(MarketView.MAX_HISTORY, new MarketView(1, 1, 1, 1, 0, 0, 10, rates).history().size());
        assertThrows(IllegalArgumentException.class,
                () -> new MarketView(1, 1, 1, 1, 0, 0, 10, java.util.stream.IntStream.rangeClosed(1, 33).boxed().toList()));
    }

    @Test
    void aClientCannotSendMoreThanThirtyTwoRates() {
        // The bytes a client would write: the fields in wire order, then 33 rates where the codec allows 32.
        RegistryFriendlyByteBuf written = emptyBuffer();
        ByteBufCodecs.VAR_LONG.encode(written, 505_051L);
        ByteBufCodecs.VAR_LONG.encode(written, 9_900L);
        ByteBufCodecs.VAR_LONG.encode(written, 500_000L);
        ByteBufCodecs.VAR_LONG.encode(written, 10_000L);
        ByteBufCodecs.VAR_INT.encode(written, 5);
        ByteBufCodecs.VAR_INT.encode(written, -1);
        ByteBufCodecs.VAR_INT.encode(written, 10);
        ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()).encode(written,
                java.util.stream.IntStream.rangeClosed(1, 33).boxed().toList());

        assertThrows(RuntimeException.class, () -> MarketView.STREAM_CODEC.decode(bufferOf(bytesOf(written))));
    }

    @Test
    void anExperienceViewRoundTripsWithAndWithoutItsMarket() {
        ExperienceView withMarket = new ExperienceView(5_345, 50, 0, 0, 100, 0, 0, 3, Optional.of(sampleMarket()));
        ExperienceView withoutMarket = new ExperienceView(120, 4, 50, 1, 6, 50, 2, 20, Optional.empty());

        assertEquals(withMarket, roundTrip(ExperienceView.STREAM_CODEC, withMarket));
        assertEquals(withoutMarket, roundTrip(ExperienceView.STREAM_CODEC, withoutMarket));
    }
}
