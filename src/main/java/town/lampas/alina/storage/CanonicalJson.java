package town.lampas.alina.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;

/**
 * The text a reward's JSON is stored as. Both backends return this form, so they agree: jsonb keeps no key order and
 * writes its own spacing, so the PostgreSQL backend reads its text back through this form too. The form is compact, object
 * keys are sorted by UTF-16 order, and a number is written in plain notation, as PostgreSQL's numeric writes it. A string
 * or key may not contain a NUL character, which jsonb cannot store.
 */
final class CanonicalJson {
    private static final Gson STRINGS = new GsonBuilder().disableHtmlEscaping().create();

    private CanonicalJson() {}

    /**
     * The canonical form of the JSON text.
     *
     * @throws IllegalArgumentException when the text is blank, is not JSON, or holds a NUL character
     */
    static String of(String json) {
        if (json.isBlank()) {
            throw new IllegalArgumentException("the reward JSON is blank");
        }
        requireStrict(json);
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("the reward JSON is not valid JSON", e);
        }
        StringBuilder out = new StringBuilder();
        write(root, out);
        return out.toString();
    }

    /**
     * Refuses text that is not strict JSON. The tree parser is lenient: it would take comments, unquoted words, single
     * quotes and {@code +1}, and turn some of them into other values.
     */
    private static void requireStrict(String json) {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            reader.skipValue();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("the reward JSON holds more than one value");
            }
        } catch (IOException | IllegalStateException e) {
            throw new IllegalArgumentException("the reward JSON is not valid JSON", e);
        }
    }

    private static void write(JsonElement element, StringBuilder out) {
        if (element.isJsonObject()) {
            Map<String, JsonElement> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                sorted.put(entry.getKey(), entry.getValue());
            }
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonElement> entry : sorted.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                string(entry.getKey(), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (element.isJsonArray()) {
            out.append('[');
            boolean first = true;
            for (JsonElement item : element.getAsJsonArray()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(item, out);
            }
            out.append(']');
        } else if (element.isJsonNull()) {
            out.append("null");
        } else {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                out.append(primitive.getAsBoolean());
            } else if (primitive.isNumber()) {
                number(primitive.getAsString(), out);
            } else {
                string(primitive.getAsString(), out);
            }
        }
    }

    private static void number(String literal, StringBuilder out) {
        try {
            out.append(new BigDecimal(literal).toPlainString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("the reward JSON holds a number that is not valid", e);
        }
    }

    private static void string(String value, StringBuilder out) {
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("the reward JSON holds a NUL character, which storage cannot hold");
        }
        out.append(STRINGS.toJson(value));
    }
}
