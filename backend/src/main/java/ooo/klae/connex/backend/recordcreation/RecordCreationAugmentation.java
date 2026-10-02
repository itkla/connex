package ooo.klae.connex.backend.recordcreation;

import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

import ooo.klae.connex.backend.dto.recordcreation.RecordCreationContextDto;

/** Server-only creation state; a reviewed stage name is revalidated under its canonical lock. */
public record RecordCreationAugmentation(
    String templateId,
    int templateVersion,
    int templateSetRevision,
    RecordCreationEntryPoint entryPoint,
    RecordCreationContextDto context,
    Map<Integer, JsonNode> customFields,
    List<Integer> tagIds,
    String reviewedStageName
) {
    public RecordCreationAugmentation(
            String templateId,
            int templateVersion,
            int templateSetRevision,
            RecordCreationEntryPoint entryPoint,
            RecordCreationContextDto context,
            Map<Integer, JsonNode> customFields,
            List<Integer> tagIds) {
        this(templateId, templateVersion, templateSetRevision, entryPoint, context, customFields, tagIds, null);
    }

    public RecordCreationAugmentation {
        customFields = Map.copyOf(customFields);
        tagIds = List.copyOf(tagIds);
    }
}
