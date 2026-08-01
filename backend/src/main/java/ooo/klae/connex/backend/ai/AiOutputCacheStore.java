package ooo.klae.connex.backend.ai;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.masking.MaskedMessage;
import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.beans.AiOutputCache;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.mappers.AiOutputCacheMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Persistent, workspace-scoped store for generated AI outputs. Replaces the per-JVM in-memory caches
 * so a brief/rationale is re-prompted only when the deal's assembled context changes, not on every
 * page load. Validity is keyed on
 * {@link #contentHash(AiGenerationProfile, MaskedPrompt, MaskingContext)} — a fingerprint of the
 * credential-free provider profile, masked prompt, and request-local identity bindings — so a
 * stored row is reused only when the output-shaping inputs are identical. Payloads are stored
 * demasked as JSON.
 */
@Service
@RequiredArgsConstructor
public class AiOutputCacheStore {
    /** Sentinel used for {@code subjectBId} on single-subject (deal-scoped) features. */
    public static final int NO_SUBJECT = 0;

    /**
     * Folded into every content hash so that changes to how output is generated or displayed — which
     * a purely prompt-derived hash would not otherwise reflect — invalidate previously stored rows on
     * deploy. Bump this when output-shaping logic changes without a corresponding prompt change.
     */
    static final String HASH_VERSION = "v3-provider-profile-grounding";

    private final AiOutputCacheMapper aiOutputCacheMapper;
    private final PersonMapper personMapper;
    private final ObjectMapper objectMapper;

    /**
     * Fingerprints the credential-free generation profile, masked prompt, and identity bindings.
     * @param profile credential-free provider and sampling profile
     * @param prompt masked prompt
     * @param context request-local masking context
     * @return hex SHA-256 of the serialized prompt and bindings
     */
    public String contentHash(
            AiGenerationProfile profile,
            MaskedPrompt prompt,
            MaskingContext context) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(serialized(profile, prompt, context).getBytes(StandardCharsets.UTF_8));
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
     * Upserts the stored output for a subject. A serialization failure skips persistence rather than
     * failing the caller, since a cache write must never break the user-facing response.
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
        Optional<String> payload = serialize(content);
        if (payload.isEmpty()) {
            return;
        }
        aiOutputCacheMapper.upsert(entry(
                workspaceId, feature, subjectAId, subjectBId, contentHash,
                payload.get(), warnings, generatedAt));
    }

    /**
     * Upserts generated content only while every directly contributing contact remains visible and
     * unrestricted. Exact contact rows are locked in ascending id order so restriction changes and
     * cache admission serialize without range-lock ordering ambiguity. Serialization failure skips
     * persistence only after contributor admission succeeds.
     * @param workspaceId active workspace
     * @param feature feature key
     * @param subjectAId primary subject id
     * @param subjectBId secondary subject id, or {@link #NO_SUBJECT}
     * @param contentHash validity fingerprint
     * @param content demasked structured content, serialized to JSON for storage
     * @param warnings demasking warning count
     * @param generatedAt ISO generation instant
     * @param contributorPersonIds exact directly contributing contact ids
     * @return true when the generated content remains safe to serve, whether or not serialization
     *         allowed persistence; false when contributor admission fails
     */
    @Transactional
    public boolean saveForPersons(int workspaceId, String feature, int subjectAId, int subjectBId,
            String contentHash, Object content, int warnings, String generatedAt,
            List<Integer> contributorPersonIds) {
        Optional<String> payload = serialize(content);
        if (contributorPersonIds == null) {
            return false;
        }
        TreeSet<Integer> sortedPersonIds = new TreeSet<>();
        for (Integer personId : contributorPersonIds) {
            if (personId == null || personId <= 0) {
                return false;
            }
            sortedPersonIds.add(personId);
        }
        for (int personId : sortedPersonIds) {
            Person person = personMapper.getVisiblePersonByIdForUpdate(workspaceId, personId);
            if (person == null || person.getSuspendedAt() != null || person.getProvisionCeasedAt() != null) {
                return false;
            }
        }
        if (payload.isEmpty()) {
            return true;
        }
        aiOutputCacheMapper.upsert(entry(
                workspaceId, feature, subjectAId, subjectBId, contentHash,
                payload.get(), warnings, generatedAt));
        return true;
    }

    private Optional<String> serialize(Object content) {
        try {
            return Optional.ofNullable(objectMapper.writeValueAsString(content));
        } catch (JacksonException exception) {
            return Optional.empty();
        }
    }

    private static AiOutputCache entry(int workspaceId, String feature, int subjectAId, int subjectBId,
            String contentHash, String payload, int warnings, String generatedAt) {
        AiOutputCache entry = new AiOutputCache();
        entry.setWorkspaceId(workspaceId);
        entry.setFeature(feature);
        entry.setSubjectAId(subjectAId);
        entry.setSubjectBId(subjectBId);
        entry.setContentHash(contentHash);
        entry.setPayload(payload);
        entry.setWarnings(warnings);
        entry.setGeneratedAt(generatedAt);
        return entry;
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

    private static String serialized(
            AiGenerationProfile profile,
            MaskedPrompt prompt,
            MaskingContext context) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(context, "context");
        StringBuilder serialized = new StringBuilder();
        appendPart(serialized, HASH_VERSION);
        appendPart(serialized, profile.provider());
        appendPart(serialized, profile.region());
        appendPart(serialized, profile.modelId());
        appendPart(serialized, Integer.toString(profile.maxTokens()));
        appendPart(serialized, canonicalTemperature(profile.temperature()));
        appendPart(serialized, prompt.getSystemPrompt());
        appendPart(serialized, Integer.toString(prompt.getMessages().size()));
        for (MaskedMessage message : prompt.getMessages()) {
            appendPart(serialized, message.getRole());
            appendPart(serialized, message.getContent());
        }
        List<Map.Entry<String, String>> bindings = context.tokenBindings().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        appendPart(serialized, Integer.toString(bindings.size()));
        for (Map.Entry<String, String> binding : bindings) {
            appendPart(serialized, binding.getKey());
            appendPart(serialized, binding.getValue());
        }
        return serialized.toString();
    }

    private static String canonicalTemperature(double temperature) {
        return BigDecimal.valueOf(temperature).stripTrailingZeros().toPlainString();
    }

    private static void appendPart(StringBuilder serialized, String value) {
        if (value == null) {
            serialized.append("-1:");
            return;
        }
        serialized.append(value.length()).append(':').append(value);
    }
}
