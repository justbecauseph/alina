package town.lampas.alina.trade.dialogue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;
import town.lampas.alina.content.AlinaContent;
import town.lampas.alina.content.TradeContent;
import town.lampas.haruhi.dialogue.api.ParseContext;

/**
 * Reads the JSON arguments of one trade dialogue type. A parser runs its body inside {@link #parse}, which refuses argument
 * names the type does not take and turns a bad argument into a {@link DataResult} error. The error starts with the parse
 * context's {@link ParseContext#where()} and the type id, as the Guild's types do. The Guild's reader is package-private to
 * its own package, so this package keeps its own copy.
 */
final class TradeArgs {

    /** Thrown by the accessors. Its message is the argument problem, without the context prefix. */
    static final class Invalid extends RuntimeException {
        private Invalid(String message) {
            super(message, null, false, false);
        }
    }

    /** Reads the arguments and returns the parsed value. */
    @FunctionalInterface
    interface Body<T> {
        T read(TradeArgs args);
    }

    private final JsonObject json;
    private final ParseContext context;

    private TradeArgs(JsonObject json, ParseContext context) {
        this.json = json;
        this.context = context;
    }

    static <T> DataResult<T> parse(ParseContext context, String type, JsonObject json, Set<String> allowed, Body<T> body) {
        try {
            TradeArgs args = new TradeArgs(json, context);
            for (String key : json.keySet()) {
                if (!allowed.contains(key)) {
                    throw invalid("no argument named '" + key + "'");
                }
            }
            return DataResult.success(body.read(args));
        } catch (Invalid e) {
            String message = context.where() + type + ": " + e.getMessage();
            return DataResult.error(() -> message);
        }
    }

    static Invalid invalid(String message) {
        return new Invalid(message);
    }

    private boolean has(String key) {
        return json.has(key) && !json.get(key).isJsonNull();
    }

    private JsonElement require(String key) {
        if (!has(key)) {
            throw invalid("'" + key + "' is required");
        }
        return json.get(key);
    }

    /** A non-empty string argument. */
    String string(String key) {
        JsonElement element = require(key);
        if (!isString(element) || element.getAsString().isEmpty()) {
            throw invalid("'" + key + "' must be a non-empty string");
        }
        return element.getAsString();
    }

    /** An integer argument that fits in 32 bits. */
    int integer(String key) {
        JsonElement element = require(key);
        if (!isNumber(element)) {
            throw invalid("'" + key + "' must be an integer");
        }
        try {
            return element.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException e) {
            throw invalid("'" + key + "' must be a whole number that fits in 32 bits");
        }
    }

    /** An integer argument that fits in 32 bits and is at least 0. */
    int nonNegative(String key) {
        int value = integer(key);
        if (value < 0) {
            throw invalid("'" + key + "' must be at least 0, not " + value);
        }
        return value;
    }

    /** A shop id that names a shop of the bundle's {@code alina:trade} section. A bundle without the section refuses it. */
    Identifier shop(String key) {
        String text = string(key);
        Identifier id = Identifier.tryParse(text);
        if (id == null) {
            throw invalid("'" + key + "' is not an id: " + text);
        }
        TradeContent trade = context.extension(AlinaContent.TRADE).orElseThrow(() -> invalid(
                "the bundle has no alina:trade section, so '" + key + "' cannot name a shop"));
        if (!trade.shops().containsKey(id)) {
            throw invalid("'" + key + "' names '" + id + "', which is not a shop of alina:trade in this bundle");
        }
        return id;
    }

    /** A graph id that names a graph of the bundle, when the argument is present. */
    Optional<Identifier> graph(String key) {
        if (!has(key)) {
            return Optional.empty();
        }
        String text = string(key);
        Identifier id = Identifier.tryParse(text);
        if (id == null) {
            throw invalid("'" + key + "' is not an id: " + text);
        }
        if (!context.bundle().dialogues().containsKey(id)) {
            throw invalid("'" + key + "' names '" + id + "', which is not a graph of this bundle");
        }
        return Optional.of(id);
    }

    private static boolean isString(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    private static boolean isNumber(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber();
    }
}
