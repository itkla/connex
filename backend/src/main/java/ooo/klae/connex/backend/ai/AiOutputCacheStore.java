package ooo.klae.connex.backend.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.masking.MaskedMessage;
import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.beans.AiOutputCache;
import ooo.klae.connex.backend.mappers.AiOutputCacheMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Persistent, workspace-scoped store for generated AI outputs. Replaces the per-JVM in-memory caches
 * so a brief/rationale is re-prompted only when the deal's assembled context changes, not on every
 * page load. Validity is keyed on {@link #contentHash(MaskedPrompt, MaskingContext)} — a fingerprint
 * of the masked prompt plus its request-local identity bindings — so a stored row is reused only when
 * the freshly assembled prompt is identical. Payloads are stored demasked as JSON.
 */
@Service
@RequiredArgsConstructor
public class AiOutputCacheStore {
    /** Sentinel used for {@code subjectBId} on single-subject (deal-scoped) features. */
    public static final int NO_SUBJECT = 0;

    private final AiOutputCacheMapper aiOutputCacheMapper;
    private final ObjectMapper objectMapper;

    /**
     * Fingerprints a masked prompt and its identity bindings for cache validity.
     * @param prompt masked prompt
     * @param context request-local masking context
     * @return hex SHA-256 of the serialized prompt and bindings
     */
    public String contentHash(MaskedPrompt prompt, MaskingContext context) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(serialized(prompt, context).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    /**
     * Finds the stored output for a subject, if any.
     * @param workspaceId active workspace
     * @param feature feature key
     * @param subjectAId primary subject id
     * @param subjectBId secondary subject id, or {@link #NO_SUBJECT}
     * @return the cached row, or empty
     */
    public Optional<AiOutputCache> find(int workspaceId, String feature, int subjectAId, int subjectBId) {
        return Optional.ofNullable(
                aiOutputCacheMapper.getBySubject(workspaceId, feature, subjectAId, subjectBId));
    }

    /**
     * Upserts the stored output for a subject.
     * @param workspaceId active workspace
     * @param feature feature key
     * @param subjectAId primary subject id
     * @param subjectBId secondary subject id, or {@link #NO_SUBJECT}
     * @param contentHash validity fingerprint
     * @param content demasked structured content, serialized to JSON for storage
     * @param warnings demasking warning count
     * @param generatedAt ISO generation instant
     */
    public void save(int workspaceId, String feature, int subjectAId, int subjectBId, String contentHash,
            Object content, int warnings, String generatedAt) {
        AiOutputCache entry = new AiOutputCache();
        entry.setWorkspaceId(workspaceId);
        entry.setFeature(feature);
        entry.setSubjectAId(subjectAId);
        entry.setSubjectBId(subjectBId);
        entry.setContentHash(contentHash);
        entry.setPayload(objectMapper.writeValueAsString(content));
        entry.setWarnings(warnings);
        entry.setGeneratedAt(generatedAt);
        aiOutputCacheMapper.upsert(entry);
    }

    /**
     * Deserializes a stored payload back into its content type.
     * @param payload stored JSON payload
     * @param type content type
     * @param <T> content type
     * @return the parsed content, or empty when it cannot be read (for example after a schema change)
     */
    public <T> Optional<T> read(String payload, Class<T> type) {
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(payload, type));
        } catch (JacksonException exception) {
            return Optional.empty();
        }
    }

    private static String serialized(MaskedPrompt prompt, MaskingContext context) {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(context, "context");
        StringBuilder serialized = new StringBuilder();
        appendPart(serialized, prompt.getSystemPrompt());
        serialized.append(prompt.getMessages().size()).append(':');
        for (MaskedMessage message : prompt.getMessages()) {
            appendPart(serialized, message.getRole());
            appendPart(serialized, message.getContent());
        }
        List<Map.Entry<String, String>> bindings = context.tokenBindings();
        serialized.append(bindings.size()).append(':');
        for (Map.Entry<String, String> binding : bindings) {
            appendPart(serialized, binding.getKey());
            appendPart(serialized, binding.getValue());
        }
        return serialized.toString();
    }

    private static void appendPart(StringBuilder serialized, String value) {
        if (value == null) {
            serialized.append("-1:");
            return;
        }
        serialized.append(value.length()).append(':').append(value);
    }
}
