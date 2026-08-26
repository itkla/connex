package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.QualificationCriterion;
import ooo.klae.connex.backend.dto.QualificationCriterionRequest;
import ooo.klae.connex.backend.services.QualificationCriterionService;

/**
 * Workspace configuration for what "qualified" means (#559): the criteria a lead is scored against
 * and which of them are required before a contact can be moved to {@code QUALIFIED}.
 *
 * <p>Reads are open to any workspace member because the record surface renders the same questions;
 * writes require {@code WORKSPACE_SETTINGS}, enforced in the service.
 */
@RestController
@RequestMapping("/api/qualification-criteria")
@RequiredArgsConstructor
public class QualificationCriterionController {

    private final QualificationCriterionService qualificationCriterionService;

    /**
     * GET endpoint for the workspace's criteria.
     * @param includeArchived whether retired criteria are included, for the configuration surface
     * @return criteria in configured order
     */
    @GetMapping
    public List<QualificationCriterion> getCriteria(
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return includeArchived
            ? qualificationCriterionService.getAll()
            : qualificationCriterionService.getActive();
    }

    /**
     * POST endpoint to add a criterion.
     * @param request criterion to create
     * @return the stored criterion
     */
    @PostMapping
    public QualificationCriterion create(
            @Valid @RequestBody QualificationCriterionRequest request) {
        return qualificationCriterionService.create(request);
    }

    /**
     * PUT endpoint to replace a criterion's editable fields.
     * @param id criterion id
     * @param request new values
     * @return the stored criterion
     */
    @PutMapping("/{id}")
    public QualificationCriterion update(
            @PathVariable int id, @Valid @RequestBody QualificationCriterionRequest request) {
        return qualificationCriterionService.update(id, request);
    }

    /**
     * DELETE endpoint that archives a criterion. There is deliberately no hard delete: removing the
     * row would cascade away every answer recorded against it and rewrite the assessment history of
     * contacts qualified under the old definition.
     * @param id criterion id
     */
    @DeleteMapping("/{id}")
    public void archive(@PathVariable int id) {
        qualificationCriterionService.archive(id);
    }

    /**
     * POST endpoint to return an archived criterion to the active definition.
     * @param id criterion id
     */
    @PostMapping("/{id}/restore")
    public void restore(@PathVariable int id) {
        qualificationCriterionService.restore(id);
    }
}
