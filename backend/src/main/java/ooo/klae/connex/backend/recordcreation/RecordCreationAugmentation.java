package ooo.klae.connex.backend.recordcreation;

import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

import ooo.klae.connex.backend.dto.recordcreation.RecordCreationContextDto;

public record RecordCreationAugmentation(
    String templateId,
    int templateVersion,
    int templateSetRevision,
    RecordCreationEntryPoint entryPoint,
    RecordCreationContextDto context,
    Map<Integer, JsonNode> customFields,
    List<Integer> tagIds
) {
    public RecordCreationAugmentation {
        customFields = Map.copyOf(customFields);
        tagIds = List.copyOf(tagIds);
    }
}
