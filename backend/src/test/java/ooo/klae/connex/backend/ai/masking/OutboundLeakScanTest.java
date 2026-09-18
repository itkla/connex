package ooo.klae.connex.backend.ai.masking;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class OutboundLeakScanTest {
    private static final String SYSTEM_PROMPT = "Using ONLY the supplied CRM context, give the "
            + "real read on this deal. Respond with exactly one JSON object and nothing else. Each "
            + "section has a title (a short plain-text heading) and a body (plain-text prose, never "
            + "Markdown). Some field values contain placeholder tokens wrapped in double curly "
            + "braces; copy every such token exactly as it appears.";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void topLevelJsonNullIsSkippedButTheStringNullIsScreened() {
        MaskingContext context = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, "null", context);

        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakInServerEnvelope("null", context, objectMapper));
        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict("null", context, objectMapper));
        assertThrows(MaskingLeakException.class,
                () -> OutboundLeakScan.assertNoLeakStrict("\"null\"", context, objectMapper));
    }

    @Test
    void nestedNullScalarsAreSkippedWhileSurroundingTextAndKeysAreScreened() {
        MaskingContext context = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, "Ann Smith", context);

        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict(
                "{\"before\":\"safe\",\"nested\":[null,{\"optional\":null}],\"after\":\"safe\"}",
                context, objectMapper));
        for (String payload : List.of(
                "{\"before\":\"Ann Smith\",\"nested\":[null,{\"optional\":null}],\"after\":\"safe\"}",
                "{\"before\":\"safe\",\"nested\":[null,{\"optional\":null}],\"after\":\"Ann Smith\"}",
                "{\"nested\":[null,{\"message\":\"Ann Smith\",\"optional\":null}]}",
                "{\"nested\":[null,{\"Ann Smith\":null}]}")) {
            assertThrows(MaskingLeakException.class,
                    () -> OutboundLeakScan.assertNoLeakStrict(payload, context, objectMapper));
        }
    }

    @Test
    void duplicateKeyNullsDoNotHideEarlierOrLaterText() {
        MaskingContext context = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, "Ann Smith", context);

        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict(
                "{\"message\":null,\"message\":\"safe\",\"message\":null}", context, objectMapper));
        for (String payload : List.of(
                "{\"message\":\"Ann Smith\",\"message\":null}",
                "{\"message\":null,\"message\":\"Ann Smith\"}",
                "{\"Ann Smith\":null,\"Ann Smith\":\"safe\"}")) {
            assertThrows(MaskingLeakException.class,
                    () -> OutboundLeakScan.assertNoLeakStrict(payload, context, objectMapper));
        }
    }

    /**
     * A record named after an envelope key used to poison every request that seeded it: the scan
     * read the server's own property names as tenant text and refused, fail-closed, before send.
     * The envelope's structure is authored here from literals, so it carries no tenant signal.
     */
    @Test
    void recordsNamedAfterEnvelopeStructureNoLongerRefuseACorrectlyMaskedRequest() {
        for (String name : List.of(
                "Content", "Role", "Model", "System", "Text", "Type", "User", "Assistant",
                "Tokens", "Messages", "Tools", "Result")) {
            MaskingContext context = new MaskingContext();
            String masked = MaskingEngine.maskField(EntityKind.COMPANY, name, context);
            PromptAssembly.builder(context).system(SYSTEM_PROMPT).build();

            assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakInServerEnvelope(
                    envelope("Summarize " + masked + "."), context, objectMapper),
                    "a record named " + name + " must not poison its own request");
        }
    }

    /** Values are where tenant text lives, so an unmasked identifier in one is still a leak. */
    @Test
    void anIdentifierLeakedIntoAnEnvelopeValueIsStillRefused() {
        MaskingContext context = new MaskingContext();
        MaskingEngine.maskField(EntityKind.COMPANY, "Content", context);

        assertThrows(MaskingLeakException.class, () -> OutboundLeakScan.assertNoLeakInServerEnvelope(
                envelope("Summarize Content."), context, objectMapper));
    }

    /**
     * A masked tool result reaches the envelope as one string scalar, so its own JSON keys are
     * ordinary text to this parser and an identifier among them is still refused.
     */
    @Test
    void anIdentifierAmongTheKeysOfANestedResultStringIsStillRefused() {
        MaskingContext context = new MaskingContext();
        MaskingEngine.maskField(EntityKind.COMPANY, "Ann Smith", context);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolExchanges", List.of(Map.of(
                "call", Map.of("id", "c1", "name", "search_records", "arguments", "{}"),
                "result", "{\"Ann Smith\":{\"rows\":1}}")));

        assertThrows(MaskingLeakException.class, () -> OutboundLeakScan.assertNoLeakInServerEnvelope(
                objectMapper.writeValueAsString(payload), context, objectMapper));
    }

    /**
     * Provider-authored text has no trustworthy position: a raw identifier the model emitted as a
     * property name is a leak, so the strict variant keeps scanning structure.
     */
    @Test
    void providerAuthoredStructureIsNeverExempt() {
        MaskingContext context = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, "Ann Smith", context);

        for (String payload : List.of(
                "{\"Ann Smith\":\"noted\"}",
                "{\"role\":\"Ann Smith\"}",
                "{\"messages\":[{\"Ann Smith\":null}]}")) {
            assertThrows(MaskingLeakException.class,
                    () -> OutboundLeakScan.assertNoLeakStrict(payload, context, objectMapper));
        }
    }

    /**
     * The role exemption covers one closed vocabulary, not the property name: a value outside it
     * is scanned like any other, so a future writer cannot smuggle tenant text through {@code role}.
     */
    @Test
    void theRoleExemptionIsBoundedByItsClosedVocabulary() {
        MaskingContext context = new MaskingContext();
        MaskingEngine.maskField(EntityKind.COMPANY, "Ann Smith", context);

        assertThrows(MaskingLeakException.class, () -> OutboundLeakScan.assertNoLeakInServerEnvelope(
                "{\"messages\":[{\"role\":\"Ann Smith\",\"content\":\"hi\"}]}",
                context, objectMapper));
        assertEquals(java.util.Set.of("user", "assistant"), MaskedMessage.ROLES);
        assertThrows(IllegalArgumentException.class, () -> new MaskedMessage("tool", "hi"));
    }

    /** A record named a role is exempt only where the role discriminator actually sits. */
    @Test
    void aRecordNamedAfterARoleIsStillRefusedOutsideTheDiscriminator() {
        MaskingContext context = new MaskingContext();
        MaskingEngine.maskField(EntityKind.COMPANY, "Assistant", context);

        assertThrows(MaskingLeakException.class, () -> OutboundLeakScan.assertNoLeakInServerEnvelope(
                envelope("Ask Assistant about the renewal."), context, objectMapper));
    }

    private String envelope(String userContent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("system", SYSTEM_PROMPT);
        payload.put("messages", List.of(
                Map.of("role", "user", "content", userContent),
                Map.of("role", "assistant", "content", "Working.")));
        payload.put("responseSchema", Map.of(
                "type", "object",
                "properties", Map.of(
                        "model", Map.of("type", "string"),
                        "text", Map.of("type", "string"),
                        "tokens", Map.of("type", "integer")),
                "additionalProperties", false));
        payload.put("tools", List.of(Map.of(
                "name", "search_records", "description", "Search records",
                "parameters", Map.of("type", "object"))));
        payload.put("toolExchanges", List.of(Map.of(
                "call", Map.of("id", "c1", "name", "search_records", "arguments", "{}"),
                "result", "{}")));
        return objectMapper.writeValueAsString(payload);
    }
}
