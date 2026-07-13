package ooo.klae.connex.backend.beans;

import java.time.LocalDate;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An immutable generated report snapshot scoped to its definition's workspace.
 * {@code computedResult} contains the frozen deterministic widget data,
 * narrative, appendix, and citations. Mapped via {@code ReportMapper} /
 * {@code ReportMapper.xml}.
 */
@Data
@NoArgsConstructor
public class ReportSnapshot {
    private int id;
    private int workspaceId;
    private int reportDefinitionId;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String computedResult;
    private Integer generatedBy;
    private String generatedAt;
}
