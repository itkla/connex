package ooo.klae.connex.backend.ai.assistant;

import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.ai.AiRawOutputGuard;
import ooo.klae.connex.backend.ai.masking.AiGeneratedContentScreen;
import tools.jackson.databind.JsonNode;

/** Raw masked-output guard for generated conversation summaries. */
@Component
public class AiAssistantSummaryGuard implements AiRawOutputGuard {
    static final int MAX_SUMMARY_CHARS = 1_000;
    private static final Set<String> FIELDS = Set.of("summary");
    private static final Pattern RECORD_HANDLE = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])r[1-9][0-9]*(?![\\p{L}\\p{N}_])");

    @Override
    public boolean permits(JsonNode output) {
        return rejectionReason(output) == null;
    }

    @Override
    public String rejectionReason(JsonNode output) {
        if (output == null || !output.isObject()
                || output.propertyNames().size() != FIELDS.size()
                || !output.propertyNames().containsAll(FIELDS)) {
            return "summary_fields";
        }
        JsonNode summary = output.get("summary");
        if (summary == null || !summary.isString()
                || summary.asString().isBlank()
                || summary.asString().length() > MAX_SUMMARY_CHARS) {
            return "summary_shape";
        }
        if (AiGeneratedContentScreen.containsBarePlaceholder(summary.asString())) {
            return "summary_placeholder";
        }
        if (RECORD_HANDLE.matcher(summary.asString()).find()) {
            return "summary_record_handle";
        }
        String rejection = AiGeneratedContentScreen.rejectionReason(summary.asString());
        return rejection == null ? null : "summary_" + rejection;
    }
}
