package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowDateEnrollment;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.mappers.WorkflowDateEnrollmentMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;

/** Verifies date promotion eligibility against persisted workflow lifecycle state. */
class WorkflowDatePromotionEligibilityIntegrationTest extends AbstractServiceTest {

    private static final String DEFINITION =
        "{\"schemaVersion\":2,\"entryNodeId\":\"trigger\",\"nodes\":[],\"edges\":[]}";
    private static final String CANVAS =
        "{\"positions\":{},\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}";

    @Autowired private WorkflowMapper workflowMapper;
    @Autowired private WorkflowVersionMapper versionMapper;
    @Autowired private WorkflowDateEnrollmentMapper enrollmentMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void pausedAndStaleOccurrencesRemainPlannedUntilTheirGenerationIsEligible() {
        Workflow workflow = workflow();
        workflowMapper.insert(workflow);
        WorkflowVersion version = version(workflow);
        versionMapper.insert(version);
        jdbcTemplate.update(
            "UPDATE workflow SET enabled = TRUE, runtime_owner = 'canonical', "
                + "active_version_id = ? WHERE workspace_id = ? AND id = ?",
            version.getId(), workspace.getId(), workflow.getId());
        LocalDateTime now = enrollmentMapper.currentTimestamp(
            workspace.getId(), workflow.getId());
        if (now == null) {
            throw new IllegalStateException("Database time is unavailable");
        }
        WorkflowDateEnrollment enrollment = enrollment(workflow, version, now);
        enrollmentMapper.insert(enrollment);
        jdbcTemplate.update(
            "UPDATE workflow SET intake_paused_at = ? WHERE workspace_id = ? AND id = ?",
            now, workspace.getId(), workflow.getId());

        assertNull(enrollmentMapper.findDuePlannedIdForUpdate(workspace.getId()));

        jdbcTemplate.update(
            "UPDATE workflow SET intake_paused_at = NULL WHERE workspace_id = ? AND id = ?",
            workspace.getId(), workflow.getId());
        assertEquals(
            enrollment.getId(),
            enrollmentMapper.findDuePlannedIdForUpdate(workspace.getId()));

        jdbcTemplate.update(
            "UPDATE workflow SET runtime_generation = runtime_generation + 1 "
                + "WHERE workspace_id = ? AND id = ?",
            workspace.getId(), workflow.getId());
        assertNull(enrollmentMapper.findDuePlannedIdForUpdate(workspace.getId()));
        assertEquals("planned", enrollmentMapper.getByIdForUpdate(
            workspace.getId(), enrollment.getId()).getState());
    }

    private Workflow workflow() {
        Workflow workflow = new Workflow();
        workflow.setWorkspaceId(workspace.getId());
        workflow.setName("Date promotion " + unique());
        workflow.setEnabled(false);
        workflow.setRuntimeOwner("legacy");
        workflow.setDraftRevision(0);
        workflow.setDraftRecordType("deal");
        workflow.setDraftExecutionMode("user");
        workflow.setDraftRunAsUserId(currentUser.getId());
        workflow.setDraftDefinitionJson(DEFINITION);
        workflow.setDraftCanvasJson(CANVAS);
        workflow.setCreatedById(currentUser.getId());
        workflow.setUpdatedById(currentUser.getId());
        return workflow;
    }

    private WorkflowVersion version(Workflow workflow) {
        WorkflowVersion version = new WorkflowVersion();
        version.setWorkspaceId(workspace.getId());
        version.setWorkflowId(workflow.getId());
        version.setVersionNumber(1);
        version.setName(workflow.getName());
        version.setRecordType("deal");
        version.setTriggerType("date");
        version.setTriggerConfig("{}");
        version.setActionsJson("[]");
        version.setExecutionMode("user");
        version.setRunAsUserId(currentUser.getId());
        version.setCreatedById(currentUser.getId());
        version.setPublishedById(currentUser.getId());
        version.setDefinitionJson(DEFINITION);
        version.setCanvasJson(CANVAS);
        version.setDefinitionHash(new byte[32]);
        return version;
    }

    private WorkflowDateEnrollment enrollment(
            Workflow workflow,
            WorkflowVersion version,
            LocalDateTime now) {
        WorkflowDateEnrollment enrollment = new WorkflowDateEnrollment();
        enrollment.setWorkspaceId(workspace.getId());
        enrollment.setWorkflowId(workflow.getId());
        enrollment.setWorkflowVersionId(version.getId());
        enrollment.setWorkflowRuntimeGeneration(0);
        enrollment.setRecordType("deal");
        enrollment.setRecordId(1);
        enrollment.setDateField("expectedCloseDate");
        enrollment.setSourceDate(LocalDate.of(2027, 3, 31));
        enrollment.setScheduledLocalDate(LocalDate.of(2027, 3, 1));
        enrollment.setDueAt(now.minusSeconds(1));
        enrollment.setState("planned");
        return enrollment;
    }
}
