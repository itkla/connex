package ooo.klae.connex.backend.dto.recordcreation;

import java.util.Map;

public record RecordCreationErrorDto(
    String code,
    String message,
    Map<String, String> fieldErrors,
    Integer currentSetRevision,
    Integer currentTemplateRevision,
    Integer currentTemplateVersion,
    RecordCreationImpactDto impact
) {
}
