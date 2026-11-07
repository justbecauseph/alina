package town.lampas.alina.guild.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import town.lampas.alina.content.Commission;
import town.lampas.alina.content.Proof;
import town.lampas.alina.content.Reward;

/** The daily board draw (ALINA-PLAN §8.2). Expected values come from the definitions in the javadoc of BoardDraw. */
class BoardDrawTest {

    private static final Identifier BOARD = Identifier.parse("alina:board");
    private static final Identifier HUNTERS = Identifier.parse("alina:guild/hunters");

    private static Commission commission(String path, int weight) {
        return new Commission(Identifier.fromNamespaceAndPath("alina", "guild/" + path), 'A',
                Commission.Kind.REPEATABLE, Component.literal(path), Component.literal("hint"),
                new Proof.Items(true, List.of()), Optional.empty(), weight, true, Reward.NONE);
    }

    private static List<Identifier> ids(List<Commission> commissions) {
        return commissions.stream().map(Commission::id).toList();
    }

    @Test
    void theSeedIsTheFirstEightBytesOfTheDefinedSha256() throws Exception {
        // SHA-256 over "maraha", 0, "2026-11-05", 0, "7", 0, "alina:board", read as a big-endian long.
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        input.write("maraha".getBytes(StandardCharsets.UTF_8));
        input.write(0);
        input.write("2026-11-05".getBytes(StandardCharsets.UTF_8));
        input.write(0);
        input.write("7".getBytes(StandardCharsets.UTF_8));
        input.write(0);
        input.write("alina:board".getBytes(StandardCharsets.UTF_8));
        long expected = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(input.toByteArray())).getLong();

        assertEquals(expected, BoardDraw.seed("maraha", LocalDate.of(2026, 11, 5), 7L, BOARD));
    }

    @Test
    void theSeedChangesWithEachPartOfItsInput() {
        long base = BoardDraw.seed("maraha", LocalDate.of(2026, 11, 5), 7L, BOARD);
        assertTrue(base != BoardDraw.seed("other", LocalDate.of(2026, 11, 5), 7L, BOARD), "the salt");
        assertTrue(base != BoardDraw.seed("maraha", LocalDate.of(2026, 11, 6), 7L, BOARD), "the date");
        assertTrue(base != BoardDraw.seed("maraha", LocalDate.of(2026, 11, 5), 8L, BOARD), "the revision");
        assertTrue(base != BoardDraw.seed("maraha", LocalDate.of(2026, 11, 5), 7L, HUNTERS), "the board");
        assertEquals(base, BoardDraw.seed("maraha", LocalDate.of(2026, 11, 5), 7L, BOARD), "the same input");
    }

    @Test
    void boardsWithOnePoolDrawDifferentlyOnMostDays() {
        List<Commission> pool = List.of(commission("a", 1), commission("b", 1), commission("c", 1),
                commission("d", 1), commission("e", 1), commission("f", 1));
        int differing = 0;
        for (int day = 1; day <= 20; day++) {
            LocalDate date = LocalDate.of(2026, 11, day);
            List<Identifier> board = BoardDraw.draw(pool, 4, BoardDraw.seed("maraha", date, 7L, BOARD));
            List<Identifier> hunters = BoardDraw.draw(pool, 4, BoardDraw.seed("maraha", date, 7L, HUNTERS));
            if (!board.equals(hunters)) {
                differing++;
            }
        }
        // Each board draws 4 of 6 in order, 360 outcomes, so two boards agree on a day about once in 360 days.
        assertTrue(differing >= 15, "the two boards drew the same board on " + (20 - differing) + " of 20 days");
    }

    @Test
    void theDrawIsTheSameForAShuffledPool() {
        List<Commission> pool = new ArrayList<>(List.of(commission("a", 1), commission("b", 2), commission("c", 3),
                commission("d", 1), commission("e", 5), commission("f", 2)));
        for (long seed = 0; seed < 50; seed++) {
            List<Identifier> expected = BoardDraw.draw(pool, 4, seed);
            for (int shuffle = 0; shuffle < 5; shuffle++) {
                Collections.shuffle(pool, new Random(seed * 31 + shuffle));
                assertEquals(expected, BoardDraw.draw(pool, 4, seed), "seed " + seed + ", shuffle " + shuffle);
            }
        }
    }

    @Test
    void aDrawNeverRepeatsACommission() {
        List<Commission> pool = List.of(commission("a", 1), commission("b", 4), commission("c", 9), commission("d", 1));
        for (long seed = 0; seed < 100; seed++) {
            List<Identifier> drawn = BoardDraw.draw(pool, 4, seed);
            assertEquals(4, new HashSet<>(drawn).size(), "seed " + seed + " drew a commission twice: " + drawn);
        }
    }

    @Test
    void aSizeLargerThanThePoolReturnsThePool() {
        List<Commission> pool = List.of(commission("a", 1), commission("b", 2), commission("c", 3));
        for (long seed = 0; seed < 20; seed++) {
            List<Identifier> drawn = BoardDraw.draw(pool, 10, seed);
            assertEquals(3, drawn.size());
            assertEquals(Set.copyOf(ids(pool)), Set.copyOf(drawn), "seed " + seed);
        }
    }

    @Test
    void aSizeOfZeroOrLessAndAnEmptyPoolDrawNothing() {
        List<Commission> pool = List.of(commission("a", 1));
        assertEquals(List.of(), BoardDraw.draw(pool, 0, 1L));
        assertEquals(List.of(), BoardDraw.draw(pool, -2, 1L));
        assertEquals(List.of(), BoardDraw.draw(List.of(), 5, 1L));
    }

    @Test
    void aHeavyCommissionIsDrawnFirstForNearlyEverySeed() {
        Identifier heavy = Identifier.fromNamespaceAndPath("alina", "guild/heavy");
        List<Commission> pool = List.of(commission("light", 1), commission("heavy", 1000));
        int heavyFirst = 0;
        for (long seed = 0; seed < 1000; seed++) {
            if (BoardDraw.draw(pool, 1, seed).get(0).equals(heavy)) {
                heavyFirst++;
            }
        }
        // The light one is first with probability 1 in 1001 per seed, so about one seed of 1000 should pick it.
        assertTrue(heavyFirst >= 990, "the weight 1000 commission was first for " + heavyFirst + " of 1000 seeds");
    }

    @Test
    void theFirstPickIsTheFirstCommissionInIdOrderWhoseWeightSumPassesTheRoll() {
        // Ids sort as alina:guild/a before alina:guild/b. The roll is nextLong(2) over the weights 1 and 1.
        List<Commission> pool = List.of(commission("b", 1), commission("a", 1));
        for (long seed = 0; seed < 20; seed++) {
            long roll = new Random(seed).nextLong(2);
            Identifier expected = Identifier.fromNamespaceAndPath("alina", roll == 0 ? "guild/a" : "guild/b");
            assertEquals(expected, BoardDraw.draw(pool, 1, seed).get(0), "seed " + seed);
        }
    }

    @Test
    void aWeightBelowOneIsRefused() {
        List<Commission> pool = List.of(commission("a", 0));
        assertThrows(IllegalArgumentException.class, () -> BoardDraw.draw(pool, 1, 1L));
    }
}
