package ooo.klae.connex.backend.ai.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ooo.klae.connex.backend.ai.masking.OutboundSegment.Origin.PROVIDER_AUTHORED;
import static ooo.klae.connex.backend.ai.masking.OutboundSegment.Origin.SERVER_STATIC;
import static ooo.klae.connex.backend.ai.masking.OutboundSegment.Origin.TENANT_DATA;
import static ooo.klae.connex.backend.ai.masking.OutboundSegment.Origin.TENANT_TOOL_RESULT;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ooo.klae.connex.backend.ai.masking.OutboundSegment;
import tools.jackson.databind.ObjectMapper;

class AiProviderDispatchTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void decodedPositionsPreserveEscapesAndExcludeStaticSchemaAndProviderText() {
        var builder = new AiProviderDispatch.Builder(mapper);
        var root = mapper.createObjectNode();
        var segments = List.of(new OutboundSegment("{{M9}} ", SERVER_STATIC),
                new OutboundSegment("quoted \"\\\n日本語 {{ M1 }}", TENANT_DATA),
                new OutboundSegment("{{M8}}", PROVIDER_AUTHORED),
                new OutboundSegment("{{P2}}", TENANT_TOOL_RESULT));
        builder.text(root, "a/b~c", segments.stream().map(OutboundSegment::text)
                .collect(java.util.stream.Collectors.joining()), segments);
        builder.schema(root, "schema", mapper.readTree("{\"enum\":[\"{{M7}}\"]}"));

        var dispatch = builder.build(root, AiProviderAttemptExecutor.DIRECT);

        assertEquals(Set.of("{{M1}}", "{{P2}}"), dispatch.dispatchedTokens());
        assertThrows(UnsupportedOperationException.class, () -> dispatch.dispatchedTokens().add("{{M9}}"));
        assertFalse(dispatch.toString().contains("日本語"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"changed", "missing", "extra", "wrong_position", "wrong_type"})
    void finalDecodedBodyMustCorrespondExactlyToItsProvenance(String corruption) {
        String body = switch (corruption) {
            case "changed" -> "{\"text\":\"{{M2}}\"}";
            case "missing" -> "{}";
            case "extra" -> "{\"text\":\"{{M1}}\",\"extra\":\"{{M2}}\"}";
            case "wrong_position" -> "{\"other\":\"{{M1}}\"}";
            default -> "{\"text\":3}";
        };
        assertThrows(AiProviderException.class, () -> AiProviderDispatch.validate(body,
                Map.of("/text", List.of(new OutboundSegment("{{M1}}", TENANT_DATA))),
                mapper, AiProviderAttemptExecutor.DIRECT));
    }

    @Test
    void builderRefusesUnclassifiedOverwrittenAndRemovedFields() {
        for (String corruption : List.of("unclassified", "overwritten", "removed")) {
            var builder = new AiProviderDispatch.Builder(mapper);
            var root = mapper.createObjectNode();
            builder.text(root, "text", "{{M1}}", TENANT_DATA);
            switch (corruption) {
                case "unclassified" -> root.put("extra", "{{M2}}");
                case "overwritten" -> root.put("text", "{{M2}}");
                default -> root.remove("text");
            }
            assertThrows(AiProviderException.class, () -> builder.build(root, AiProviderAttemptExecutor.DIRECT));
        }
    }

    @Test
    void correspondenceUsesActualUtf8BytesEvenWhenJavaTextHasAnUnpairedSurrogate() {
        String text = "\uD800 {{M1}}";
        assertThrows(AiProviderException.class, () -> AiProviderDispatch.validate(
                "{\"text\":\"" + text + "\"}",
                Map.of("/text", List.of(new OutboundSegment(text, TENANT_DATA))),
                mapper, AiProviderAttemptExecutor.DIRECT));
    }

    @Test
    void preparationAndPreSendStageEvidenceUntilResponseHeaders() {
        AtomicInteger sends = new AtomicInteger();
        AtomicInteger responses = new AtomicInteger();
        AiProviderAttemptExecutor executor = new AiProviderAttemptExecutor() {
            @Override
            public String execute(java.util.function.Supplier<String> attempt) {
                return attempt.get();
            }

            @Override
            public void beforeSend() {
                sends.incrementAndGet();
            }

            @Override
            public void onResponse(Set<String> tokens) {
                assertEquals(Set.of("{{M1}}"), tokens);
                responses.incrementAndGet();
            }
        };
        var builder = new AiProviderDispatch.Builder(mapper);
        var root = mapper.createObjectNode();
        builder.text(root, "text", "{{M1}}", TENANT_DATA);
        var dispatch = builder.build(root, executor);

        assertEquals(0, sends.get());
        dispatch.run();
        assertEquals(1, sends.get());
        assertEquals(0, responses.get());
        AiProviderDispatch.responded(dispatch);
        assertEquals(1, responses.get());
    }

    @Test
    void provenanceCannotJoinStaticOrProviderFragmentsIntoTenantTokens() {
        var builder = new AiProviderDispatch.Builder(mapper);
        var root = mapper.createObjectNode();
        builder.text(root, "text", "{{M1}} {{M2}}", List.of(
                new OutboundSegment("{{M", SERVER_STATIC), new OutboundSegment("1}} ", TENANT_DATA),
                new OutboundSegment("{{M", TENANT_DATA), new OutboundSegment("2}}", PROVIDER_AUTHORED)));
        assertTrue(builder.build(root, AiProviderAttemptExecutor.DIRECT).dispatchedTokens().isEmpty());
    }
}
