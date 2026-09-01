package ooo.klae.connex.backend.dto.recordcreation;

import java.util.List;

public record ResolvedCreationGroupDto(
    String key,
    LocalizedTextDto label,
    LocalizedTextDto description,
    List<ResolvedCreationFieldDto> fields
) {
}
