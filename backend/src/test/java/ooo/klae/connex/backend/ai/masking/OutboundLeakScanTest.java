package ooo.klae.connex.backend.ai.masking;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void insertedMarkerExemptionDoesNotHideIdenticalProviderSourceInTheSameField() {
        var context = new MaskingContext();
        var field = MaskingEngine.maskFreeTextPreservingIssuedPlaceholdersWithInsertions(
                "Literal [redacted] then alice@example.test", context);
        assertEquals("Literal [redacted] then [redacted]", field.text());
        assertEquals(1, field.insertedSpans().size());
        context.registerMemberCandidate("Redacted");
        var segment = OutboundSegment.jsonString(field, OutboundSegment.Origin.PROVIDER_AUTHORED, objectMapper);
        assertThrows(MaskingLeakException.class, () -> OutboundLeakScan.assertNoLeakInClassifiedEnvelope(
                objectMapper.writeValueAsString(Map.of("messages", List.of(segment.text()))),
                List.of(segment), context, objectMapper));
    }

    @Test
    void insertedMarkerExemptionPreservesJsonDecodingOfEscapedProviderLiterals() {
        var context = new MaskingContext();
        var field = MaskingEngine.maskFreeTextPreservingIssuedPlaceholdersWithInsertions(
                "{\"email\":\"alice@example.test\",\"query\":\"\\u005bredacted\\u005d\"}", context);
        assertEquals(1, field.insertedSpans().size());
        context.registerMemberCandidate("Redacted");
        var segment = new OutboundSegment(field.text(), OutboundSegment.Origin.PROVIDER_AUTHORED, field.insertedSpans());
        assertThrows(MaskingLeakException.class, () -> OutboundLeakScan.assertNoLeakInClassifiedEnvelope(
                objectMapper.writeValueAsString(Map.of("messages", List.of(segment.text()))),
                List.of(segment), context, objectMapper));
    }

    @Test
    void recordedMemberProvenanceRequiresContainmentOnlyWithinItsMessageForReplacementAndScanning() {
        var context = new MaskingContext();
        context.registerMemberCandidate("Bob");
        context.registerMemberCandidate("Li");
        String text = "Bob_team xBob BobBob Little xLi Li_team";
        assertEquals(text, MaskingEngine.maskConversationalFreeText(text, context));
        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakInServerEnvelope(text, context, objectMapper));
        context.withReplayMemberValues(List.of("Bob"), () -> {
            assertThrows(MaskingLeakException.class,
                    () -> OutboundLeakScan.assertNoLeakInReplay(text, context));
            assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakInReplay("Little xLi Li_team", context));
            return null;
        });
        String masked = MaskingEngine.maskConversationalFreeText(text, context, Map.of(), List.of("Bob"));
        assertEquals("{{M1}}_team x{{M1}} {{M1}}{{M1}} Little xLi Li_team", masked);
        context.exposeDispatchedPayload(masked);
        assertEquals(text, Demasker.demask(masked, context).text());
        assertEquals(text, MaskingEngine.maskConversationalFreeText(text, context));
        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakInReplay(text, context));
        assertThrows(IllegalStateException.class, () -> context.withReplayMemberValues(List.of("Bob"), () -> {
            throw new IllegalStateException("scope failed");
        }));
        assertEquals(text, MaskingEngine.maskConversationalFreeText(text, context));
    }

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
    @org.junit.jupiter.api.Test
    void unissuedMemberCandidatesRemainInTheLeakScanWithUnicodeAndLabelParity() {
        MaskingContext context = new MaskingContext();
        context.registerMemberCandidate("Ａｌｉｃｅ Smith");
        context.registerMemberCandidate("山田 太郎");
        org.junit.jupiter.api.Assertions.assertThrows(MaskingLeakException.class, () ->
                OutboundLeakScan.assertNoLeakStrict("Alice Smith", context, objectMapper));
        org.junit.jupiter.api.Assertions.assertThrows(MaskingLeakException.class, () ->
                OutboundLeakScan.assertNoLeakStrict("山田 太郎", context, objectMapper));
        String masked = MaskingEngine.maskFreeText("Alice Smith met 山田 太郎", context);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                OutboundLeakScan.assertNoLeakStrict(masked, context, objectMapper));
        assertEquals(2, context.tokenBindings().size());
    }

    @Test
    void shortMemberCandidatesShareTheReplacersUnicodeAndWordBoundaries() {
        MaskingContext context = new MaskingContext();
        context.registerMemberCandidate("Li");
        context.registerMemberCandidate("李");
        assertThrows(MaskingLeakException.class, () ->
                OutboundLeakScan.assertNoLeakStrict("Ask Li", context, objectMapper));
        assertThrows(MaskingLeakException.class, () ->
                OutboundLeakScan.assertNoLeakStrict("Ask 李", context, objectMapper));
        assertDoesNotThrow(() ->
                OutboundLeakScan.assertNoLeakStrict("Little", context, objectMapper));
        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict(
                MaskingEngine.maskFreeText("Ask Li and 李", context), context, objectMapper));
    }

    @Test
    void shortSymbolMemberRedactionAndScanningShareEveryMatch() throws Exception {
        for (String member : java.util.List.of("😀", "♞", "🛰️")) {
            var context = new MaskingContext();
            context.registerMemberCandidate(member);
            String text = "Ask" + member + ".";
            assertThrows(MaskingLeakException.class, () ->
                    OutboundLeakScan.assertNoLeakStrict(text, context, objectMapper));
            assertThrows(MaskingLeakException.class, () -> OutboundLeakScan.assertNoLeakInServerEnvelope(
                    objectMapper.writeValueAsString(java.util.Map.of("content", text)), context, objectMapper));
            String masked = MaskingEngine.maskFreeText(text, context);
            assertEquals("Ask" + MaskingEngine.REDACTED + ".", masked);
            assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict(masked, context, objectMapper));
            assertTrue(context.tokenBindings().isEmpty());
        }
        var punctuation = new MaskingContext();
        punctuation.registerMemberCandidate(".");
        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict("Keep ordinary periods.", punctuation, objectMapper));
    }

    @Test
    void maskingMarkersAreExemptOnlyInFinalServerMemberScanningAfterSourceReplacement() throws Exception {
        for (String label : List.of("Redacted", "[redacted]", "Omitted by policy")) {
            var context = new MaskingContext();
            context.registerMemberCandidate(label);
            String email = MaskingEngine.maskFreeText("Contact alice@example.test", context);
            assertEquals("Contact [redacted]", email);
            assertTrue(context.tokenBindings().isEmpty());
            assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakInServerEnvelope(
                    objectMapper.writeValueAsString(Map.of("content", email)), context, objectMapper));
            String source = "Ask " + label + " to follow up.";
            String masked = MaskingEngine.maskFreeText(source, context);
            assertEquals("Ask {{M1}} to follow up.", masked);
            assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakInReplay(masked, context));
            context.exposeDispatchedPayload(masked);
            assertEquals(source, Demasker.demask(masked, context).text());
            assertThrows(MaskingLeakException.class,
                    () -> OutboundLeakScan.assertNoLeakStrict(source, context, objectMapper));
        }
        var omitted = new MaskingContext();
        omitted.registerMemberCandidate("Omitted by policy");
        String marker = MaskingEngine.maskFreeText("The note includes a diagnosis from last week.", omitted);
        assertEquals(MaskingEngine.OMITTED_BY_POLICY, marker);
        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakInReplay(marker, omitted));
        assertThrows(MaskingLeakException.class, () -> OutboundLeakScan.assertNoLeakStrict(marker, omitted, objectMapper));
        assertThrows(MaskingLeakException.class,
                () -> OutboundLeakScan.assertNoLeakInReplay(marker + " Omitted by policy", omitted));
    }

    @Test
    void largeMemberDirectoryBuildsEachFieldProjectionOnceWithoutSkippingCandidates() {
        MaskingContext context = new MaskingContext();
        for (int index = 0; index < 1_000; index++) {
            context.registerMemberCandidate("一" + (char) (0x5000 + index));
        }
        context.registerMemberCandidate("Li");
        context.registerMemberCandidate("May");
        context.registerMemberCandidate("李");
        assertEquals(1_003, context.identifierDictionary().size());
        String field = "zebra ".repeat(8_000);
        var constructions = new java.util.concurrent.atomic.AtomicInteger();
        try (var engine = org.mockito.Mockito.mockStatic(MaskingEngine.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            engine.when(() -> MaskingEngine.screenText(field)).thenAnswer(invocation -> {
                assertEquals(1, constructions.incrementAndGet(), "Field projections rebuilt per member label");
                return invocation.callRealMethod();
            });
            assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict(field, context, objectMapper));
            assertEquals(1, constructions.get());
            constructions.set(0);
            assertEquals(field, MaskingEngine.maskFreeText(field, context));
            assertEquals(1, constructions.get());
        }
        String last = "一" + (char) (0x5000 + 999);
        assertThrows(MaskingLeakException.class, () ->
                OutboundLeakScan.assertNoLeakStrict(field + last, context, objectMapper));
        String masked = MaskingEngine.maskFreeText(field + last, context);
        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict(masked, context, objectMapper));
        assertEquals(1_003, context.identifierDictionary().size());
        assertEquals(1, context.tokenBindings().size());
    }

}
