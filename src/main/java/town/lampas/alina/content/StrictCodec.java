package town.lampas.alina.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Parses vanilla JSON (criterion conditions, item predicates, item stacks) with its own codec, and refuses keys the
 * codec ignores. Minecraft's record codecs skip unknown keys, so a misspelt {@code "entitiy"} in a kill condition
 * would silently drop the entity check and let any kill count. haruhi's rule, which Alina follows, is that a
 * misspelling must fail validation.
 *
 * <p>A key is ignored when removing it leaves the parsed value the same, compared by encoding both values back to
 * JSON. A key that only repeats its default value is ignored in the same way, and is refused too: the message says so.
 */
final class StrictCodec {
    private StrictCodec() {}

    static <T> DataResult<T> parse(Codec<T> codec, DynamicOps<JsonElement> ops, JsonElement input) {
        DataResult<T> result = codec.parse(ops, input);
        Optional<T> value = result.result();
        if (value.isEmpty()) {
            return result;
        }
        Optional<JsonElement> encoded = codec.encodeStart(ops, value.get()).result();
        List<String> ignored = new ArrayList<>();
        List<List<Object>> ignoredPaths = new ArrayList<>();
        for (List<Object> path : keyPaths(input)) {
            // Paths come parent first; under an ignored key every key is ignored too, so report only the top one.
            if (ignoredPaths.stream().anyMatch(parent -> path.size() > parent.size()
                    && path.subList(0, parent.size()).equals(parent))) {
                continue;
            }
            JsonElement without = input.deepCopy();
            remove(without, path);
            Optional<T> reparsed = codec.parse(ops, without).result();
            if (reparsed.isEmpty()) {
                continue;
            }
            boolean same = encoded.isPresent()
                    ? encoded.equals(codec.encodeStart(ops, reparsed.get()).result())
                    : Objects.equals(value.get(), reparsed.get());
            if (same) {
                ignored.add(describe(path));
                ignoredPaths.add(path);
            }
        }
        if (ignored.isEmpty()) {
            return result;
        }
        String keys = String.join(", ", ignored);
        return DataResult.error(() -> ignored.size() == 1
                ? "key " + keys + " has no effect: it is not a key at that place, or only repeats its default; remove it"
                : "keys " + keys + " have no effect: each is not a key at that place, or only repeats its default; "
                        + "remove them");
    }

    /** Every object key in the tree, as a path of keys and array indexes from the root. */
    private static List<List<Object>> keyPaths(JsonElement root) {
        List<List<Object>> paths = new ArrayList<>();
        collect(root, new ArrayList<>(), paths);
        return paths;
    }

    private static void collect(JsonElement element, List<Object> prefix, List<List<Object>> paths) {
        if (element.isJsonObject()) {
            for (String key : element.getAsJsonObject().keySet()) {
                List<Object> path = new ArrayList<>(prefix);
                path.add(key);
                paths.add(path);
                collect(element.getAsJsonObject().get(key), path, paths);
            }
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                List<Object> path = new ArrayList<>(prefix);
                path.add(i);
                collect(array.get(i), path, paths);
            }
        }
    }

    /** Removes the key at the end of {@code path}; the path was taken from the same tree. */
    private static void remove(JsonElement root, List<Object> path) {
        JsonElement parent = root;
        for (Object step : path.subList(0, path.size() - 1)) {
            parent = step instanceof Integer index
                    ? parent.getAsJsonArray().get(index)
                    : parent.getAsJsonObject().get((String) step);
        }
        ((JsonObject) parent).remove((String) path.get(path.size() - 1));
    }

    /** {@code 'entity'}, or {@code 'entity[0].predicate.minecraft:distance'} for a nested key. */
    private static String describe(List<Object> path) {
        StringBuilder text = new StringBuilder("'");
        for (int i = 0; i < path.size(); i++) {
            Object step = path.get(i);
            if (step instanceof Integer index) {
                text.append('[').append(index).append(']');
            } else {
                if (i > 0) {
                    text.append('.');
                }
                text.append(step);
            }
        }
        return text.append("'").toString();
    }
}
