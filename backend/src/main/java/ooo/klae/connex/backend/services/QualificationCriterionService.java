package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.QualificationCriterion;
import ooo.klae.connex.backend.beans.QualificationDimension;
import ooo.klae.connex.backend.dto.QualificationCriterionRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.QualificationCriterionMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Maintains the workspace's qualification criteria — the questions its team answers about a lead
 * and the weight each carries (#559).
 *
 * <p>This is workspace configuration rather than record data, so it is gated on
 * {@link Permission#WORKSPACE_SETTINGS}: deciding what "qualified" means for the whole workspace is
 * a different authority from assessing one contact against that definition, which needs only
 * {@code PERSON_UPDATE}.
 *
 * <p>Retiring a criterion archives it. Deleting would cascade away every answer ever recorded
 * against it, silently rewriting the assessment history of contacts that were qualified under the
 * old definition; archiving stops it scoring and gating while leaving that history intact.
 */
@Service
@RequiredArgsConstructor
public class QualificationCriterionService {

    private static final int MAX_ACTIVE_CRITERIA = 50;

    private final QualificationCriterionMapper criterionMapper;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;

    /** Active criteria in configured order, used for scoring and for the record surface. */
    public List<QualificationCriterion> getActive() {
        return criterionMapper.getActive(workspaceService.getCurrentWorkspaceId());
    }

    /** Every criterion including archived ones, for the configuration surface. */
    public List<QualificationCriterion> getAll() {
        return criterionMapper.getAll(workspaceService.getCurrentWorkspaceId());
    }

    /**
     * Adds a criterion to the workspace's definition of qualified.
     *
     * @param request criterion to create
     * @return the stored criterion
     */
    @Transactional
    @RequirePermission(Permission.WORKSPACE_SETTINGS)
    public QualificationCriterion create(QualificationCriterionRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        QualificationCriterion criterion = validated(request, new QualificationCriterion());
        criterion.setWorkspaceId(workspaceId);
        if (criterionMapper.getActive(workspaceId).size() >= MAX_ACTIVE_CRITERIA) {
            throw new BadRequestException(
                "A workspace can keep at most " + MAX_ACTIVE_CRITERIA + " active criteria");
        }
        criterionMapper.insert(criterion);
        auditService.record("qualification.criterion.create", "qualification_criterion",
            criterion.getId(), criterion.getLabel(),
            "Added qualification criterion " + criterion.getLabel(),
            Map.of("dimension", criterion.getDimension().name(),
                "weight", criterion.getWeight(),
                "required", criterion.isRequired()));
        return criterion;
    }

    /**
     * Replaces the editable fields of one criterion. Changing a weight or a required flag changes
     * what "qualified" means for every contact scored against it, so the whole change is audited.
     *
     * @param id criterion to update
     * @param request new values
     * @return the stored criterion
     */
    @Transactional
    @RequirePermission(Permission.WORKSPACE_SETTINGS)
    public QualificationCriterion update(int id, QualificationCriterionRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        QualificationCriterion before = require(workspaceId, id);
        QualificationCriterion criterion = validated(request, new QualificationCriterion());
        criterion.setId(id);
        criterion.setWorkspaceId(workspaceId);
        criterionMapper.update(criterion);
        auditService.record("qualification.criterion.update", "qualification_criterion", id,
            criterion.getLabel(), "Updated qualification criterion " + criterion.getLabel(),
            auditService.diff(before, criterion,
                java.util.Set.of("label", "dimension", "weight", "required", "position")));
        return require(workspaceId, id);
    }

    /**
     * Retires a criterion so it stops scoring and stops gating, keeping every answer recorded
     * against it.
     *
     * @param id criterion to archive
     */
    @Transactional
    @RequirePermission(Permission.WORKSPACE_SETTINGS)
    public void archive(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        QualificationCriterion before = require(workspaceId, id);
        if (criterionMapper.archive(workspaceId, id) == 0) {
            throw new BadRequestException("That criterion is already archived");
        }
        auditService.record("qualification.criterion.archive", "qualification_criterion", id,
            before.getLabel(), "Archived qualification criterion " + before.getLabel(), Map.of());
    }

    /**
     * Returns an archived criterion to the active definition.
     *
     * @param id criterion to restore
     */
    @Transactional
    @RequirePermission(Permission.WORKSPACE_SETTINGS)
    public void restore(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        QualificationCriterion before = require(workspaceId, id);
        if (criterionMapper.restore(workspaceId, id) == 0) {
            throw new BadRequestException("That criterion is not archived");
        }
        auditService.record("qualification.criterion.restore", "qualification_criterion", id,
            before.getLabel(), "Restored qualification criterion " + before.getLabel(), Map.of());
    }

    private QualificationCriterion require(int workspaceId, int id) {
        QualificationCriterion criterion = criterionMapper.getById(workspaceId, id);
        if (criterion == null) {
            throw new ResourceNotFoundException("Criterion not found with id: " + id);
        }
        return criterion;
    }

    private static QualificationCriterion validated(
            QualificationCriterionRequest request, QualificationCriterion criterion) {
        if (request == null) {
            throw new BadRequestException("A criterion is required");
        }
        String label = request.getLabel() == null ? "" : request.getLabel().trim();
        if (label.isEmpty()) {
            throw new BadRequestException("A criterion needs a question to ask");
        }
        QualificationDimension dimension = request.getDimension();
        if (dimension == null) {
            throw new BadRequestException("A criterion must belong to a dimension");
        }
        int weight = request.getWeight() == null ? 1 : request.getWeight();
        if (weight < 1 || weight > 100) {
            throw new BadRequestException("A criterion weight must be between 1 and 100");
        }
        criterion.setLabel(label);
        criterion.setDimension(dimension);
        criterion.setWeight(weight);
        criterion.setRequired(Boolean.TRUE.equals(request.getRequired()));
        criterion.setPosition(request.getPosition() == null ? 0 : request.getPosition());
        return criterion;
    }
}
