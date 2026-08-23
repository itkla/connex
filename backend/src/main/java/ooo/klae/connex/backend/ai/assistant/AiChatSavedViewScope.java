package ooo.klae.connex.backend.ai.assistant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import ooo.klae.connex.backend.beans.SavedView;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The single test of whether a saved view's whole scope can be executed as a segment definition.
 *
 * <p>Admission and execution ask the same question through this class. A view that is accepted at
 * request time and then edited before the turn reads it is re-checked here and refused, rather than
 * degrading into a workspace-wide cohort behind a scope chip that still claims the view applied.
 *
 * <p>Re-checking executability is not enough on its own. An edit that leaves the view executable but
 * changes which records it selects would otherwise run the new definition under the preview and the
 * persisted echo of the old one, so admission also takes a {@link #fingerprint} of the definition it
 * accepted and execution refuses when the digest no longer matches.
 */
final class AiChatSavedViewScope {

    /** Stable refusal reason for a saved view the server cannot apply in full. */
    static final String UNSUPPORTED = "saved_view_scope_unsupported";

    /** Stable refusal reason for a saved view edited between admission and the executed read. */
    static final String CHANGED = "saved_view_scope_changed";

    private AiChatSavedViewScope() {
    }

    /**
     * Returns the fully executable segment definition of a saved view.
     *
     * <p>{@code segments} is a server-evaluable segment definition. A non-blank {@code query}, a
     * non-empty {@code filters} object, an absent or malformed {@code segments} node, and a segment
     * definition that constrains nothing all mean the executed cohort would be wider than the view
     * the caller named, so all of them yield an empty result instead of a partial application.
     *
     * @param objectMapper mapper used to read the stored segment definition
     * @param view accessible saved view
     * @return the segment definition to evaluate, or empty when the view cannot apply in full
     */
    static Optional<SegmentDefinition> definition(ObjectMapper objectMapper, SavedView view) {
        if (view == null) {
            return Optional.empty();
        }
        JsonNode config = view.getConfig();
        if (config == null || !config.isObject()) {
            return Optional.empty();
        }
        JsonNode query = config.get("query");
        if (query != null && query.isString() && !query.asString().isBlank()) {
            return Optional.empty();
        }
        JsonNode filters = config.get("filters");
        if (filters != null && !filters.isNull()
                && !(filters.isObject() && filters.isEmpty())) {
            return Optional.empty();
        }
        JsonNode segments = config.get("segments");
        if (segments == null || !segments.isObject() || segments.isEmpty()) {
            return Optional.empty();
        }
        SegmentDefinition definition;
        try {
            definition = objectMapper.treeToValue(segments, SegmentDefinition.class);
        } catch (JacksonException exception) {
            return Optional.empty();
        }
        if (definition == null
                || (isEmpty(definition.getConditions()) && isEmpty(definition.getGroups()))) {
            return Optional.empty();
        }
        return Optional.of(definition);
    }

    /**
     * Returns an opaque digest of the executable segment definition of a saved view.
     *
     * <p>The digest is taken over the definition the server would evaluate rather than over the
     * stored configuration node, so a cosmetic rewrite that reorders keys or renames the view keeps
     * the same fingerprint while any change to the records it selects produces a different one. It
     * is a hash, never the definition itself: the value is carried through the turn and must not
     * become a second copy of tenant filter content.
     *
     * @param objectMapper mapper used to read and canonicalize the stored segment definition
     * @param view accessible saved view
     * @return the digest, or empty when the view cannot apply in full
     */
    static Optional<String> fingerprint(ObjectMapper objectMapper, SavedView view) {
        return definition(objectMapper, view)
                .map(definition -> digest(objectMapper.writeValueAsString(definition)));
    }

    private static String digest(String canonical) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean isEmpty(List<?> values) {
        return values == null || values.isEmpty();
    }
}
