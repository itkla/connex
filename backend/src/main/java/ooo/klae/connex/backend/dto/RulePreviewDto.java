package ooo.klae.connex.backend.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a rule preview: how many workspace records the WHEN condition matches and a bounded
 * sample of them for display.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RulePreviewDto {
    private int matchCount;
    private List<RecordLabelDto> sample;
}
