package ooo.klae.connex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Compact, name-resolved projection of a {@link ooo.klae.connex.backend.beans.Deal}
 * for hover previews and inline references — stage, pipeline, company, and owner
 * are hydrated to display names rather than raw IDs. Workspace-scoped.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DealSummaryDto {
    private int id;
    private String name;
    private double value;
    private double actualValue;
    private String currency;
    private String status;
    private String expectedCloseDate;
    private String stageName;
    private String pipelineName;
    private String companyName;
    private String ownerName;
}
