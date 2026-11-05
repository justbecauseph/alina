package town.lampas.alina.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.function.Function;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;

/** Parsers for single JSON values, each returning a result whose error reads after the key it belongs to. */
final class Json {
    private Json() {}

    static boolean isString(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    static DataResult<String> string(JsonElement value) {
        return isString(value) ? DataResult.success(value.getAsString()) : DataResult.error(() -> "must be a string");
    }

    static DataResult<String> nonEmptyString(JsonElement value) {
        return string(value).flatMap(text -> text.isEmpty()
                ? DataResult.error(() -> "must not be empty")
                : DataResult.success(text));
    }

    static DataResult<Integer> integer(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return DataResult.error(() -> "must be a whole number");
        }
        double number = value.getAsDouble();
        if (number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            return DataResult.error(() -> "must be a whole number");
        }
        return DataResult.success((int) number);
    }

    static Function<JsonElement, DataResult<Integer>> integerAtLeast(int minimum) {
        return value -> integer(value).flatMap(number -> number < minimum
                ? DataResult.error(() -> "must be at least " + minimum)
                : DataResult.success(number));
    }

    static DataResult<Double> number(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return DataResult.error(() -> "must be a number");
        }
        double number = value.getAsDouble();
        return Double.isFinite(number) ? DataResult.success(number) : DataResult.error(() -> "must be a finite number");
    }

    static DataResult<Boolean> bool(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            return DataResult.error(() -> "must be true or false");
        }
        return DataResult.success(value.getAsBoolean());
    }

    static DataResult<JsonObject> object(JsonElement value) {
        return value.isJsonObject()
                ? DataResult.success(value.getAsJsonObject())
                : DataResult.error(() -> "must be an object");
    }

    static DataResult<JsonArray> array(JsonElement value) {
        return value.isJsonArray() ? DataResult.success(value.getAsJsonArray()) : DataResult.error(() -> "must be an array");
    }

    static DataResult<Identifier> identifier(JsonElement value) {
        return string(value).flatMap(text -> {
            Identifier id = Identifier.tryParse(text);
            return id == null ? DataResult.error(() -> "'" + text + "' is not a valid id") : DataResult.success(id);
        });
    }

    /** An id parsed from a map key, or null when the key is not a valid id. */
    static Identifier keyId(String key) {
        return Identifier.tryParse(key);
    }

    /** A positive ISO-8601 duration such as {@code PT20H} or {@code P7D}. */
    static DataResult<Duration> duration(JsonElement value) {
        return string(value).flatMap(text -> {
            try {
                Duration duration = Duration.parse(text);
                return duration.isNegative() || duration.isZero()
                        ? DataResult.error(() -> "must be a positive duration")
                        : DataResult.success(duration);
            } catch (DateTimeParseException e) {
                return DataResult.error(() -> "'" + text + "' is not an ISO-8601 duration such as PT20H");
            }
        });
    }

    /** A time of day, {@code HH:mm}. */
    static DataResult<LocalTime> timeOfDay(JsonElement value) {
        return string(value).flatMap(text -> {
            try {
                return DataResult.success(LocalTime.parse(text));
            } catch (DateTimeParseException e) {
                return DataResult.error(() -> "'" + text + "' is not a time of day such as 04:00");
            }
        });
    }

    static DataResult<ZoneId> zone(JsonElement value) {
        return string(value).flatMap(text -> {
            try {
                return DataResult.success(ZoneId.of(text));
            } catch (RuntimeException e) {
                return DataResult.error(() -> "'" + text + "' is not a time zone id such as Asia/Manila");
            }
        });
    }

    static Function<JsonElement, DataResult<Component>> text(DynamicOps<JsonElement> ops) {
        return codec(ComponentSerialization.CODEC, ops);
    }

    /**
     * Parses vanilla JSON with its Minecraft codec and refuses keys the codec ignores (see {@link StrictCodec}). Use it
     * for every vanilla object content writes: criterion conditions, item predicates, item stacks, bounds.
     */
    static <T> Function<JsonElement, DataResult<T>> strict(Codec<T> codec, DynamicOps<JsonElement> ops) {
        return value -> StrictCodec.parse(codec, ops, value);
    }

    /** Parses with a Minecraft codec. The codec's own message is kept. */
    static <T> Function<JsonElement, DataResult<T>> codec(Codec<T> codec, DynamicOps<JsonElement> ops) {
        return value -> codec.parse(ops, value);
    }
}
