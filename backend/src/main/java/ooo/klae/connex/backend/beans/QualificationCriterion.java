package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One workspace-authored qualification question (#559), belonging to a single
 * {@link QualificationDimension} and carrying the weight it contributes within that dimension.
 *
 * <p>A {@code required} criterion additionally gates the move to {@code QUALIFIED}: the stage is a
 * claim about the workspace's own standards, so the standards have to be enforceable rather than
 * advisory. Retiring a criterion archives it instead of deleting it, so the answers already given
 * against it survive as history while it stops scoring and stops gating.
 *
 * <p>Mapped via {@code QualificationCriterionMapper} / {@code QualificationCriterionMapper.xml}.
 */
@Data
@NoArgsConstructor
public class QualificationCriterion {
    private int id;
    private int workspaceId;
    private String label;
    private QualificationDimension dimension;
    private int weight;
    private boolean required;
    private int position;
    private LocalDateTime archivedAt;
    private String createdAt;
    private String updatedAt;
}
