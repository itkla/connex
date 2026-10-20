package ooo.klae.connex.backend.ai.mission;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;

import org.springframework.stereotype.Component;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Strict bounded UTF-8 transport for immutable mission source observation envelopes. */
@Component
public final class AiAgentSourceObservationsCodec {
    private final JsonMapper json = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    /** Decodes every field without coercion or a provenance fallback. */
    public AiAgentSourceObservations decode(String value) {
        AiAgentContinuationCodec.require(value != null && value.getBytes(StandardCharsets.UTF_8).length <= 32768);
        try {
            return decodeNode(json.readTree(value));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("mission_continuation_refused");
        }
    }

    /** Materializes the strict envelope shared by actions and schema-two checkpoints. */
    public AiAgentSourceObservations decodeNode(JsonNode node) {
        AiAgentContinuationCodec.fields(node, "schemaVersion", "sources");
        var sources = new ArrayList<AiAgentSourceObservations.Observation>();
        for (JsonNode source : AiAgentContinuationCodec.array(node, "sources")) {
            AiAgentContinuationCodec.fields(source, "kind", "id", "source", "limit", "sha256", "observedAt");
            Instant at;
            try {
                String raw = AiAgentContinuationCodec.string(source, "observedAt");
                AiAgentContinuationCodec.require(raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?Z"));
                at = Instant.parse(raw);
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException("mission_continuation_refused");
            }
            sources.add(new AiAgentSourceObservations.Observation(AiAgentContinuationCodec.string(source, "kind"),
                    AiAgentContinuationCodec.integer(source, "id"), AiAgentContinuationCodec.string(source, "source"),
                    AiAgentContinuationCodec.integer(source, "limit"), AiAgentContinuationCodec.string(source, "sha256"), at));
        }
        return new AiAgentSourceObservations(AiAgentContinuationCodec.integer(node, "schemaVersion"), sources);
    }

    /** Encodes the canonical source order while enforcing the storage byte ceiling. */
    public String encode(AiAgentSourceObservations value) {
        String encoded = json.writeValueAsString(value);
        AiAgentContinuationCodec.require(encoded.getBytes(StandardCharsets.UTF_8).length <= 32768);
        return encoded;
    }
}
