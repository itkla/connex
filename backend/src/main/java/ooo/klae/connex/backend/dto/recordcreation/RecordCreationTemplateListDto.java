package ooo.klae.connex.backend.dto.recordcreation;

import java.util.List;

/**
 * The admin template list for one record type, carrying the aggregate set revision every
 * mutating request must echo as {@code expectedSetRevision}, alongside the availability-aware
 * current selection.
 *
 * @param setRevision        compare-and-swap revision of the workspace's template set aggregate
 * @param selectedTemplateId wire id of the template current resolution would select
 * @param templates          workspace templates plus the trailing virtual system preset
 */
public record RecordCreationTemplateListDto(
    int setRevision,
    String selectedTemplateId,
    List<RecordCreationTemplateSummaryDto> templates
) {
}
