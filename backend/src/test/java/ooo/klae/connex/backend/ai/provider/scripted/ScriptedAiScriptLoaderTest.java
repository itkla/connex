package ooo.klae.connex.backend.ai.provider.scripted;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the loader's fail-closed rules, which are the only thing standing between a fixture and a
 * green trajectory that rehearsed the wrong protocol.
 *
 * <p>Two of the rules guard failure modes that are otherwise completely silent: a selector the
 * masker rewrites never matches, so every trajectory refuses with {@code provider_error} for a
 * reason no assertion names; and a client-error rejection on the first native step degrades the
 * whole turn to the JSON protocol instead of failing it, so a native golden would pass while
 * exercising JSON. Both are caught here, at fixture-lint time.
 */
class ScriptedAiScriptLoaderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void loadsAValidScriptAndExposesItBySelector(@TempDir Path directory) throws IOException {
        write(directory, "multi_step_read.json", """
                {
                  "id": "multi_step_read",
                  "selector": "connex_script_multi_step_read",
                  "capabilityClass": "scripted-native",
                  "steps": [
                    {
                      "afterToolCalls": 0,
                      "emit": {
                        "kind": "tool_call",
                        "toolName": "search_records",
                        "arguments": "{\\"query\\":\\"renewal\\"}"
                      }
                    },
                    {
                      "afterToolCalls": 1,
                      "emit": {"kind": "final", "text": "done"}
                    }
                  ]
                }
                """);

        ScriptedAiScriptLoader loader = new ScriptedAiScriptLoader(
                directory.toString(), objectMapper);

        ScriptedAiScript script = loader.bySelector("connex_script_multi_step_read");
        assertNotNull(script);
        assertEquals("multi_step_read", script.id());
        assertEquals(ScriptedAiCapabilityClass.NATIVE, script.capabilityClass());
        assertEquals(2, script.steps().size());
        assertEquals(List.of("connex_script_multi_step_read"), List.copyOf(loader.selectors()));
    }

    @Test
    void everyLoadedSelectorSurvivesMaskingUnchanged(@TempDir Path directory) throws IOException {
        write(directory, "masked.json", script("connex_script_masked_egress", "masked_egress"));

        ScriptedAiScriptLoader loader = new ScriptedAiScriptLoader(
                directory.toString(), objectMapper);

        for (String selector : loader.selectors()) {
            assertEquals(
                    selector,
                    MaskingEngine.maskFreeText(selector, new MaskingContext()),
                    "selector must survive masking unchanged: " + selector);
        }
    }

    @Test
    void refusesASelectorCarryingTheDigitsAMaskerDetectorCouldRewrite(@TempDir Path directory)
            throws IOException {
        write(directory, "digits.json", script("connex_script_case_1234567", "digits"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new ScriptedAiScriptLoader(directory.toString(), objectMapper));

        assertTrue(exception.getMessage().contains("selector is invalid"),
                exception.getMessage());
    }

    @Test
    void refusesAClientErrorRejectionOnTheFirstStepWithoutADeclaredDegradation(
            @TempDir Path directory) throws IOException {
        write(directory, "degradation.json", """
                {
                  "id": "accidental_degradation",
                  "selector": "connex_script_accidental",
                  "capabilityClass": "scripted-native",
                  "steps": [
                    {"afterToolCalls": 0, "emit": {"kind": "failure", "failureKind": "rejected"}}
                  ]
                }
                """);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new ScriptedAiScriptLoader(directory.toString(), objectMapper));

        assertTrue(exception.getMessage().contains("expectsNativeDegradation"),
                exception.getMessage());
    }

    @Test
    void acceptsAClientErrorRejectionOnTheFirstStepWhenTheDegradationIsDeclared(
            @TempDir Path directory) throws IOException {
        write(directory, "degradation.json", """
                {
                  "id": "native_degradation",
                  "selector": "connex_script_native_degradation",
                  "capabilityClass": "scripted-native",
                  "expectsNativeDegradation": true,
                  "steps": [
                    {"afterToolCalls": 0, "emit": {"kind": "failure", "failureKind": "rejected"}}
                  ]
                }
                """);

        ScriptedAiScriptLoader loader = new ScriptedAiScriptLoader(
                directory.toString(), objectMapper);

        assertTrue(loader.bySelector("connex_script_native_degradation")
                .expectsNativeDegradation());
    }

    @Test
    void refusesDeltasThatDoNotReconstructTheBufferedText(@TempDir Path directory)
            throws IOException {
        write(directory, "streamed.json", """
                {
                  "id": "streamed_answer",
                  "selector": "connex_script_streamed_answer",
                  "capabilityClass": "scripted-native-stream",
                  "steps": [
                    {
                      "afterToolCalls": 0,
                      "emit": {
                        "kind": "final",
                        "text": "hello there",
                        "deltas": ["hello", " world"]
                      }
                    }
                  ]
                }
                """);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new ScriptedAiScriptLoader(directory.toString(), objectMapper));

        assertTrue(exception.getMessage().contains("concatenate"), exception.getMessage());
    }

    @Test
    void refusesACredentialShapedValue(@TempDir Path directory) throws IOException {
        write(directory, "secret.json", """
                {
                  "id": "leaky",
                  "selector": "connex_script_leaky",
                  "capabilityClass": "scripted-native",
                  "steps": [
                    {"afterToolCalls": 0, "emit": {"kind": "final", "text": "api_key rotated"}}
                  ]
                }
                """);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new ScriptedAiScriptLoader(directory.toString(), objectMapper));

        assertTrue(exception.getMessage().contains("credential-shaped"), exception.getMessage());
    }

    @Test
    void refusesDuplicateIds(@TempDir Path directory) throws IOException {
        write(directory, "first.json", script("connex_script_first", "shared_id"));
        write(directory, "second.json", script("connex_script_second", "shared_id"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new ScriptedAiScriptLoader(directory.toString(), objectMapper));

        assertTrue(exception.getMessage().contains("ids must be unique"), exception.getMessage());
    }

    @Test
    void refusesDuplicateSelectors(@TempDir Path directory) throws IOException {
        write(directory, "first.json", script("connex_script_shared", "first_id"));
        write(directory, "second.json", script("connex_script_shared", "second_id"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new ScriptedAiScriptLoader(directory.toString(), objectMapper));

        assertTrue(exception.getMessage().contains("selectors must be unique"),
                exception.getMessage());
    }

    @Test
    void refusesAnUnknownCapabilityClass(@TempDir Path directory) throws IOException {
        write(directory, "unknown.json", """
                {
                  "id": "unknown_class",
                  "selector": "connex_script_unknown_class",
                  "capabilityClass": "gpt-4o",
                  "steps": [
                    {"afterToolCalls": 0, "emit": {"kind": "final", "text": "done"}}
                  ]
                }
                """);

        assertThrows(RuntimeException.class,
                () -> new ScriptedAiScriptLoader(directory.toString(), objectMapper));
    }

    @Test
    void refusesAnUnknownField(@TempDir Path directory) throws IOException {
        write(directory, "extra.json", """
                {
                  "id": "extra_field",
                  "selector": "connex_script_extra_field",
                  "capabilityClass": "scripted-native",
                  "endpoint": "https://example.test/v1",
                  "steps": [
                    {"afterToolCalls": 0, "emit": {"kind": "final", "text": "done"}}
                  ]
                }
                """);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new ScriptedAiScriptLoader(directory.toString(), objectMapper));

        assertTrue(exception.getMessage().contains("unknown field endpoint"),
                exception.getMessage());
    }

    @Test
    void refusesAnUnsetFixtureDirectory() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new ScriptedAiScriptLoader("", objectMapper));

        assertEquals(ScriptedAiScriptLoader.FIXTURE_DIR_REFUSAL, exception.getMessage());
    }

    @Test
    void refusesAMissingFixtureDirectory(@TempDir Path directory) {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new ScriptedAiScriptLoader(
                        directory.resolve("absent").toString(), objectMapper));

        assertEquals(ScriptedAiScriptLoader.FIXTURE_DIR_REFUSAL, exception.getMessage());
    }

    @Test
    void refusesAnEmptyFixtureDirectory(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("readme.txt"), "not a script");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new ScriptedAiScriptLoader(directory.toString(), objectMapper));

        assertEquals(ScriptedAiScriptLoader.FIXTURE_DIR_REFUSAL, exception.getMessage());
    }

    @Test
    void refusesAFileAboveTheSizeCeiling(@TempDir Path directory) throws IOException {
        String padding = "x".repeat(ScriptedAiScriptLoader.MAX_SCRIPT_BYTES);
        write(directory, "big.json", """
                {
                  "id": "oversized",
                  "selector": "connex_script_oversized",
                  "capabilityClass": "scripted-native",
                  "steps": [
                    {"afterToolCalls": 0, "emit": {"kind": "final", "text": "%s"}}
                  ]
                }
                """.formatted(padding));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new ScriptedAiScriptLoader(directory.toString(), objectMapper));

        assertTrue(exception.getMessage().contains("exceeds"), exception.getMessage());
    }

    @Test
    void refusesTwoStepsSharingOnePredicate(@TempDir Path directory) throws IOException {
        write(directory, "ambiguous.json", """
                {
                  "id": "ambiguous",
                  "selector": "connex_script_ambiguous",
                  "capabilityClass": "scripted-native",
                  "steps": [
                    {"afterToolCalls": 0, "emit": {"kind": "final", "text": "first"}},
                    {"afterToolCalls": 0, "emit": {"kind": "final", "text": "second"}}
                  ]
                }
                """);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new ScriptedAiScriptLoader(directory.toString(), objectMapper));

        assertTrue(exception.getMessage().contains("share a predicate"), exception.getMessage());
    }

    @Test
    void ignoresFilesThatAreNotScripts(@TempDir Path directory) throws IOException {
        write(directory, "valid.json", script("connex_script_valid", "valid_script"));
        Files.writeString(directory.resolve("notes.md"), "ignored");
        Files.createDirectory(directory.resolve("nested.json"));

        ScriptedAiScriptLoader loader = new ScriptedAiScriptLoader(
                directory.toString(), objectMapper);

        assertEquals(1, loader.scripts().size());
    }

    private static String script(String selector, String id) {
        return """
                {
                  "id": "%s",
                  "selector": "%s",
                  "capabilityClass": "scripted-native",
                  "steps": [
                    {"afterToolCalls": 0, "emit": {"kind": "final", "text": "done"}}
                  ]
                }
                """.formatted(id, selector);
    }

    private static void write(Path directory, String name, String content) throws IOException {
        Files.writeString(directory.resolve(name), content, StandardCharsets.UTF_8);
    }
}
