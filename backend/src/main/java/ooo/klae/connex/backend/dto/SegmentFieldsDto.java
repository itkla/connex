package ooo.klae.connex.backend.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The field value-options that power the segment builder for a record type: the distinct industry
 * values present in the workspace and the workspace's tags. Predicates and operators are a fixed
 * catalog on the client; this supplies the values users pick from.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SegmentFieldsDto {
    private List<String> industries;
    private List<TagDto> tags;
}
