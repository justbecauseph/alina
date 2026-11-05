package town.lampas.alina.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import town.lampas.haruhi.content.api.ExtensionContext;

/**
 * Typed reads from one JSON object of an extension section, as haruhi's own reader does them: a field that is absent or
 * null is empty, a field that fails its parser records {@code where: key message} and is empty, and {@link #required}
 * also records {@code key is required}. Every problem goes to the extension context, so one validation lists them all.
 */
final class Section {
    private final JsonObject object;
    private final String where;
    private final ExtensionContext context;

    Section(JsonObject object, String where, ExtensionContext context) {
        this.object = object;
        this.where = where;
        this.context = context;
    }

    /** The section of {@code value} when it is an object; otherwise records {@code where: must be an object}. */
    static Optional<Section> of(JsonElement value, String where, ExtensionContext context) {
        if (value == null || !value.isJsonObject()) {
            context.problem(where, "must be an object");
            return Optional.empty();
        }
        return Optional.of(new Section(value.getAsJsonObject(), where, context));
    }

    String where() {
        return where;
    }

    ExtensionContext context() {
        return context;
    }

    JsonObject json() {
        return object;
    }

    boolean has(String key) {
        JsonElement value = object.get(key);
        return value != null && !value.isJsonNull();
    }

    JsonElement raw(String key) {
        return object.get(key);
    }

    <T> Optional<T> optional(String key, Function<JsonElement, DataResult<T>> parser) {
        if (!has(key)) {
            return Optional.empty();
        }
        return parse(key, object.get(key), parser);
    }

    <T> Optional<T> required(String key, Function<JsonElement, DataResult<T>> parser) {
        if (!has(key)) {
            add(key + " is required");
            return Optional.empty();
        }
        return parse(key, object.get(key), parser);
    }

    /** The members of an object-valued field, in file order; none when absent, and a problem when not an object. */
    Map<String, JsonElement> entries(String key) {
        return optional(key, Json::object).map(JsonObject::asMap).orElse(Map.of());
    }

    /** The entries of an array-valued field; none when absent, and a problem when not an array. */
    List<JsonElement> elements(String key) {
        Optional<JsonArray> array = optional(key, Json::array);
        List<JsonElement> list = new ArrayList<>();
        array.ifPresent(entries -> entries.forEach(list::add));
        return list;
    }

    void add(String message) {
        context.problem(where, message);
    }

    /** Records {@code where: unknown key 'key'} for every key not in {@code known}. A misspelt key must fail. */
    void allowOnly(Set<String> known) {
        for (String key : object.keySet()) {
            if (!known.contains(key)) {
                add("unknown key '" + key + "'");
            }
        }
    }

    private <T> Optional<T> parse(String key, JsonElement value, Function<JsonElement, DataResult<T>> parser) {
        DataResult<T> result;
        try {
            result = parser.apply(value);
        } catch (RuntimeException e) {
            add(key + " could not be parsed: " + e.getMessage());
            return Optional.empty();
        }
        Optional<T> parsed = result.result();
        if (parsed.isEmpty()) {
            add(key + " " + result.error().map(error -> error.message()).orElse("is invalid"));
        }
        return parsed;
    }
}
