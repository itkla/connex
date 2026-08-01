package ooo.klae.connex.backend.beans;

import java.time.LocalDate;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An immutable generated report snapshot scoped to its definition's workspace.
 * {@code computedResult} contains the frozen deterministic widget data,
 * narrative, appendix, and citations. Mapped via {@code ReportMapper} /
 * {@code ReportMapper.xml}.
 *
 * <p>{@code origin} mirrors the column's {@code 'manual'} default so a snapshot created without an
 * explicit origin is a hand-made one, and never inserts {@code NULL} into the non-null column.
 */
@Data
@NoArgsConstructor
public class ReportSnapshot {
    private static final String ORIGIN_MANUAL = "manual";

    private int id;
    private int workspaceId;
    private int reportDefinitionId;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String computedResult;
    private String origin = ORIGIN_MANUAL;
    private Integer reportScheduleId;
    private Integer generatedBy;
    private String generatedAt;
}
