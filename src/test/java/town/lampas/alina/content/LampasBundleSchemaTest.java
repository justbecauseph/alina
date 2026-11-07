package town.lampas.alina.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Validates {@code content/lampas.bundle.json} against haruhi's bundle schema with Alina's two sections plugged in, the
 * schema {@link AlinaSchemaTest} checks. The checks that need a server (loot tables, registry ids and the references
 * across the bundle) run in {@code LampasContentGameTests}.
 */
class LampasBundleSchemaTest {
    private static final Path SCHEMA = Path.of("docs", "alina.schema.json");
    private static final Path BUNDLE = Path.of("content", "lampas.bundle.json");
    private static final String HARUHI_DOCS = "https://raw.githubusercontent.com/justbecauseph/haruhi/main/docs/";

    private static Schema schema;
    private static String bundleText;

    @BeforeAll
    static void load() throws IOException {
        String text = Files.readString(SCHEMA);
        String haruhiSchema = haruhiSchema();
        schema = SchemaRegistry.withDialect(Dialects.getDraft202012(),
                        builder -> builder.schemaLoader(loader -> loader.resourceLoaders(
                                resources -> resources.resources(Map.of(HARUHI_DOCS + "bundle.schema.json", haruhiSchema)))))
                .getSchema(text, InputFormat.JSON);
        bundleText = Files.readString(BUNDLE);
    }

    /** haruhi's bundle schema, which the haruhi jar ships as {@code haruhi/bundle.schema.json}. */
    private static String haruhiSchema() throws IOException {
        try (InputStream in = LampasBundleSchemaTest.class.getClassLoader().getResourceAsStream("haruhi/bundle.schema.json")) {
            if (in == null) {
                throw new IllegalStateException("haruhi/bundle.schema.json is not on the test classpath; "
                        + "the haruhi jar ships it from 0.1.0");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> errors(JsonElement bundle) {
        return schema.validate(bundle.toString(), InputFormat.JSON).stream()
                .map(error -> error.getInstanceLocation() + ": " + error.getMessage())
                .toList();
    }

    @Test
    void lampasBundleMatchesHaruhisSchemaWithAlinasSections() {
        assertEquals(List.of(), errors(JsonParser.parseString(bundleText)));
    }

    @Test
    void schemaRefusesAMisspeltGateInTheLampasBundle() {
        // A misspelt only_if must fail, never drop a gate silently. This shows the file above is checked at all.
        JsonObject bundle = JsonParser.parseString(bundleText).getAsJsonObject();
        bundle.getAsJsonObject("extensions").getAsJsonObject("alina:guild").getAsJsonObject("commissions")
                .getAsJsonObject("lampas:guild/e1_longshot_marksman").addProperty("only_if", true);
        assertFalse(errors(bundle).isEmpty(), "the schema accepts an unknown commission key");
    }
}
