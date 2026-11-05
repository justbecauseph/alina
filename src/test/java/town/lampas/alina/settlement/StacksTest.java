package town.lampas.alina.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link Stacks#split} builds item stacks, which need the item components bound by the game's registry load, so it is
 * covered by {@code SettlementGameTests} instead.
 */
class StacksTest {

    /**
     * The expected length comes from the JDK: {@code DataOutputStream.writeUTF} writes the text with a two-byte length in
     * front, so the text's own bytes are the stream's size less two.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "\u0000", "café", "€5", "a😀b", "߿ࠀ"})
    void theModifiedUtf8LengthIsTheBytesWriteUTFWrites(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new DataOutputStream(bytes).writeUTF(text);

        assertEquals(bytes.size() - 2, Stacks.modifiedUtf8Length(text));
    }

    @Test
    void aNulCharacterCountsAsTwoBytes() {
        assertEquals(2, Stacks.modifiedUtf8Length("\u0000"));
    }
}
