package town.lampas.alina.trade.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import town.lampas.alina.storage.OperationCursor;

/**
 * Reads one trade management method's param leniently so malformed fields return a clean {@code invalid} answer
 * instead of failing vanilla's JSON-RPC decoder. Never quotes the rejected value in error messages. Unknown fields are
 * ignored.
 */
final class TradeParams {
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");

    /** A field of the wrong type or form. */
    static final class Invalid extends Exception {
        private final String field;

        Invalid(String field, String message) {
            super(message, null, false, false);
            this.field = field;
        }

        String field() {
            return field;
        }
    }

    private final JsonObject object;

    private TradeParams(JsonObject object) {
        this.object = object;
    }

    /** The param, which must be a JSON object. A missing or null param reads as an empty object. */
    static TradeParams of(@Nullable JsonElement param) throws Invalid {
        if (param == null || param.isJsonNull()) {
            return new TradeParams(new JsonObject());
        }
        if (!param.isJsonObject()) {
            throw new Invalid("", "the param must be a JSON object");
        }
        return new TradeParams(param.getAsJsonObject());
    }

    /**
     * An optional whole number of at least {@code min}, read from the number's text so decimals, exponents, negatives,
     * and strings are refused. Values past {@link Long#MAX_VALUE} clamp to {@link Long#MAX_VALUE}.
     */
    OptionalLong optionalWhole(String field, long min) throws Invalid {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            return OptionalLong.empty();
        }
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()
                || !DIGITS.matcher(primitive.getAsString()).matches()) {
            throw new Invalid(field, field + " must be a whole number of at least " + min);
        }
        long value;
        try {
            value = Long.parseLong(primitive.getAsString());
        } catch (NumberFormatException e) {
            value = Long.MAX_VALUE;
        }
        if (value < min) {
            throw new Invalid(field, field + " must be a whole number of at least " + min);
        }
        return OptionalLong.of(value);
    }

    /** An optional strict ISO-8601 local date ({@code YYYY-MM-DD}). */
    Optional<LocalDate> optionalDate(String field) throws Invalid {
        Optional<String> text = optionalString(field);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        try {
            LocalDate date = LocalDate.parse(text.get());
            if (!text.get().equals(date.toString())) {
                throw new Invalid(field, field + " must be an ISO date such as 2026-11-24");
            }
            return Optional.of(date);
        } catch (DateTimeException e) {
            throw new Invalid(field, field + " must be an ISO date such as 2026-11-24");
        }
    }

    /** An optional Minecraft identifier ({@code namespace:path}). */
    Optional<Identifier> optionalIdentifier(String field) throws Invalid {
        Optional<String> text = optionalString(field);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        if (text.get().isBlank()) {
            throw new Invalid(field, field + " must be a valid identifier");
        }
        Identifier id = Identifier.tryParse(text.get());
        if (id == null) {
            throw new Invalid(field, field + " must be a valid identifier");
        }
        return Optional.of(id);
    }

    /** An optional dashed UUID. */
    Optional<UUID> optionalUuid(String field) throws Invalid {
        Optional<String> text = optionalString(field);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        if (!UUID_TEXT.matcher(text.get()).matches()) {
            throw new Invalid(field, field + " must be a UUID such as 123e4567-e89b-12d3-a456-426614174000");
        }
        return Optional.of(UUID.fromString(text.get()));
    }

    /** An optional strict ISO-8601 instant ({@code YYYY-MM-DDTHH:MM:SSZ}). */
    Optional<Instant> optionalInstant(String field) throws Invalid {
        Optional<String> text = optionalString(field);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(text.get()));
        } catch (DateTimeException e) {
            throw new Invalid(field, field + " must be an ISO instant such as 2026-11-24T12:00:00Z");
        }
    }

    /** An optional JSON array of strings. */
    Optional<List<String>> optionalStringList(String field) throws Invalid {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            return Optional.empty();
        }
        if (!element.isJsonArray()) {
            throw new Invalid(field, field + " must be an array of strings");
        }
        JsonArray array = element.getAsJsonArray();
        List<String> values = new ArrayList<>(array.size());
        for (JsonElement item : array) {
            if (!(item instanceof JsonPrimitive primitive) || !primitive.isString()) {
                throw new Invalid(field, field + " must be an array of strings");
            }
            values.add(primitive.getAsString());
        }
        return Optional.of(List.copyOf(values));
    }

    /** An optional keyset cursor in {@code <iso-instant>|<uuid>} form. */
    Optional<OperationCursor> optionalCursor(String field) throws Invalid {
        Optional<String> text = optionalString(field);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        String raw = text.get();
        int sep = raw.indexOf('|');
        if (sep <= 0 || sep >= raw.length() - 1) {
            throw new Invalid(field, field + " must be <iso-instant>|<uuid>");
        }
        String instantPart = raw.substring(0, sep);
        String uuidPart = raw.substring(sep + 1);
        Instant createdAt;
        try {
            createdAt = Instant.parse(instantPart);
        } catch (DateTimeException e) {
            throw new Invalid(field, field + " must be <iso-instant>|<uuid>");
        }
        if (!UUID_TEXT.matcher(uuidPart).matches()) {
            throw new Invalid(field, field + " must be <iso-instant>|<uuid>");
        }
        return Optional.of(new OperationCursor(createdAt, UUID.fromString(uuidPart)));
    }

    private Optional<String> optionalString(String field) throws Invalid {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            return Optional.empty();
        }
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isString()) {
            throw new Invalid(field, field + " must be a string");
        }
        return Optional.of(primitive.getAsString());
    }
}
