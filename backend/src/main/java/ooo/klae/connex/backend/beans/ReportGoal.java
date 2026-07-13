package ooo.klae.connex.backend.beans;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A workspace-wide or owner-scoped revenue target used by report attainment widgets.
 * Mapped via {@code GoalMapper} / {@code GoalMapper.xml}.
 */
@Data
@NoArgsConstructor
public class ReportGoal {
    private int id;
    private int workspaceId;
    private Integer ownerId;
    private String metric;
    private String periodType;
    private LocalDate periodStart;
    private BigDecimal targetValue;
    private String currency;
    private Integer createdBy;
    private String createdAt;
    private String updatedAt;
}
