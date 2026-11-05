package town.lampas.alina.guild.dialogue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;
import town.lampas.alina.content.AlinaContent;
import town.lampas.alina.content.GuildContent;
import town.lampas.haruhi.dialogue.api.ParseContext;
import town.lampas.haruhi.content.model.DialogueGraph;

/**
 * Reads the JSON arguments of one Guild dialogue type. A parser runs its body inside {@link #parse}, which refuses argument
 * names the type does not take and turns a bad argument into a {@link DataResult} error. The error starts with the parse
 * context's {@link ParseContext#where()} and the type id, as haruhi's built-ins do. Haruhi's own reader is
 * package-private, so Alina keeps this copy.
 */
final class DialogueArgs {

    /** Thrown by the accessors. Its message is the argument problem, without the context prefix. */
    static final class Invalid extends RuntimeException {
        private Invalid(String message) {
            super(message, null, false, false);
        }
    }

    /** Reads the arguments and returns the parsed value. */
    @FunctionalInterface
    interface Body<T> {
        T read(DialogueArgs args);
    }

    private final JsonObject json;
    private final String type;
    private final ParseContext context;

    private DialogueArgs(JsonObject json, String type, ParseContext context) {
        this.json = json;
        this.type = type;
        this.context = context;
    }

    static <T> DataResult<T> parse(
            ParseContext context, String type, JsonObject json, Set<String> allowed, Body<T> body) {
        try {
            DialogueArgs args = new DialogueArgs(json, type, context);
            args.allowOnly(allowed);
            return DataResult.success(body.read(args));
        } catch (Invalid e) {
            String message = context.where() + type + ": " + e.getMessage();
            return DataResult.error(() -> message);
        }
    }

    static Invalid invalid(String message) {
        return new Invalid(message);
    }

    private void allowOnly(Set<String> allowed) {
        for (String key : json.keySet()) {
            if (!allowed.contains(key)) {
                throw invalid("no argument named '" + key + "'");
            }
        }
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

    /** A node id of the graph the type is used in, when the argument is present. The graph is checked when it parsed. */
    Optional<String> node(String key) {
        Optional<String> node = has(key) ? Optional.of(string(key)) : Optional.empty();
        node.ifPresent(name -> {
            DialogueGraph graph = context.bundle().dialogues().get(context.graphId());
            if (graph != null && !graph.nodes().containsKey(name)) {
                throw invalid("'" + key + "' names '" + name + "', which is not a node of this graph");
            }
        });
        return node;
    }

    /**
     * A commission id that names a commission of the bundle's {@code alina:guild} section. A bundle without that section
     * refuses every commission argument.
     */
    Identifier commission(String key) {
        String text = string(key);
        Identifier id = Identifier.tryParse(text);
        if (id == null) {
            throw invalid("'" + key + "' is not an id: " + text);
        }
        GuildContent guild = context.extension(AlinaContent.GUILD).orElseThrow(() -> invalid(
                "the bundle has no alina:guild section, so '" + key + "' cannot name a commission"));
        if (!guild.commissions().containsKey(id)) {
            throw invalid("'" + key + "' names '" + id + "', which is not a commission of alina:guild in this bundle");
        }
        return id;
    }

    private static boolean isString(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    private static boolean isNumber(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber();
    }
}
