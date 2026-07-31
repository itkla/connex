package ooo.klae.connex.backend.dto;

import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Non-editable provenance for provider-derived evidence.
 */
public record CaptureEvidenceDto(
    String provider,
    String stream,
    String sourceId,
    String capturedAt,
    String captureAsOf,
    String visibility,
    List<String> admittedFields,
    List<String> materialExclusions,
    boolean editable
) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Creates immutable provenance lists. */
    public CaptureEvidenceDto {
        admittedFields = List.copyOf(admittedFields);
        materialExclusions = List.copyOf(materialExclusions);
    }

    /** Builds provenance from nullable persistence columns. */
    public static CaptureEvidenceDto from(
            String provider,
            String stream,
            String sourceId,
            String capturedAt,
            String captureAsOf,
            String visibility,
            String admittedFieldsJson,
            String materialExclusionsJson) {
        if (provider == null) {
            return null;
        }
        return new CaptureEvidenceDto(
            provider,
            stream,
            sourceId,
            capturedAt,
            captureAsOf,
            canonicalVisibility(visibility),
            array(admittedFieldsJson),
            array(materialExclusionsJson),
            false);
    }

    private static String canonicalVisibility(String visibility) {
        return switch (visibility) {
            case "workspace" -> "workspace_activity_evidence";
            case "private" -> "owner_only";
            case null -> null;
            default -> visibility;
        };
    }

    private static List<String> array(String value) {
        if (value == null) {
            return List.of();
        }
        JsonNode node = OBJECT_MAPPER.readTree(value);
        if (!node.isArray()) {
            return List.of();
        }
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual()) {
                result.add(item.asString());
            }
        }
        return List.copyOf(result);
    }
}
