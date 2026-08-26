package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.QualificationCriterion;

/**
 * Mapper interface for the per-workspace qualification criteria catalog (#559).
 * SQL is defined in {@code resources/mappers/QualificationCriterionMapper.xml}.
 * Used by {@code QualificationCriterionService}.
 */
public interface QualificationCriterionMapper {

    /** Active criteria in configured order; archived ones neither score nor gate. */
    List<QualificationCriterion> getActive(@Param("workspaceId") int workspaceId);

    /** Every criterion including archived ones, for the configuration surface. */
    List<QualificationCriterion> getAll(@Param("workspaceId") int workspaceId);

    QualificationCriterion getById(
        @Param("workspaceId") int workspaceId, @Param("id") int id);

    int insert(QualificationCriterion criterion);

    /**
     * Replaces the editable fields of one criterion. The dimension is included: moving a question
     * between fit and engagement is a legitimate correction, and the answers already recorded stay
     * valid because they answer the question, not the axis it scores.
     *
     * @param criterion criterion carrying its workspace, id, and new values
     * @return rows updated
     */
    int update(QualificationCriterion criterion);

    /** Archives a criterion, retaining the answers recorded against it. */
    int archive(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /** Restores an archived criterion so it scores and gates again. */
    int restore(@Param("workspaceId") int workspaceId, @Param("id") int id);
}
