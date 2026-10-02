package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.dto.recordcreation.LocalizedTextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDefinitionDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateFieldDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateGroupDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateUpdateRequestDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.DuplicateReviewException;
import ooo.klae.connex.backend.exceptions.RecordCreationTemplateException;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;
import ooo.klae.connex.backend.services.RecordCreationTemplateService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** End-to-end create goldens distinguish preparation controls from authoritative approval checks. */
class AiAssistantScriptedTrajectoryCreateRecordsTest extends AbstractScriptedTrajectoryTest {
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RecordCreationTemplateService templateService;
    @Autowired private AiAssistantToolCallReadService readService;

    @AfterEach
    void removeTemplateReferencesBeforeTenantCleanup() {
        jdbcTemplate.update("UPDATE record_creation_template SET current_version_id = NULL WHERE workspace_id = ?", workspaceId());
        jdbcTemplate.update("UPDATE record_creation_template_set SET default_template_id = NULL WHERE workspace_id = ?", workspaceId());
        jdbcTemplate.update("DELETE FROM record_creation_template_version WHERE workspace_id = ?", workspaceId());
        jdbcTemplate.update("DELETE FROM record_creation_template WHERE workspace_id = ?", workspaceId());
        jdbcTemplate.update("DELETE FROM record_creation_template_set WHERE workspace_id = ?", workspaceId());
    }

    @Test
    void aNameOnlyPersonCandidateRefusesBeforeAnyProposalExists() {
        company("Alderwick Shipping");
        person("Morgan Vale", null, null);
        Trajectory trajectory = run("connex_script_create_person_duplicate", "add a colleague");
        assertEquals("resolved", trajectory.status(), trajectory.terminalReason());
        assertRefused(trajectory, "create_person", "possible_duplicate");
        assertEquals(1, contactCount());
        assertEquals(0, auditRows("person.create"));
    }

    @Test
    void aCandidateArrivingAfterPreparationRefusesApprovalWithoutAnotherContact() {
        company("Alderwick Shipping");
        Trajectory trajectory = run("connex_script_create_person_proposal", "add a colleague");
        AiChatToolCall proposal = proposal(trajectory, "create_person");
        assertEquals(0, contactCount());
        person("Morgan Vale", null, null);
        authenticate();
        try {
            assertThrows(DuplicateReviewException.class,
                    () -> writeToolService().approve(trajectory.sessionId(), proposal.getId()));
        } finally {
            clearAuthentication();
        }
        assertEquals(1, contactCount());
        assertEquals(0, auditRows("person.create"));
    }

    @Test
    void aDealIsCreatedOnlyOnApprovalWithItsReviewedStageAndLiveLink() {
        Company anchor = company("Alderwick Shipping");
        Pipeline pipeline = pipeline("Sales");
        Stage stage = stage(pipeline, "Discovery", 0);
        Trajectory trajectory = run("connex_script_create_deal_proposal", "add an opportunity");
        AiChatToolCall proposal = proposal(trajectory, "create_deal");
        assertEquals(0, dealCount());
        assertEquals(0, auditRows("deal.create"));
        JsonNode arguments = objectMapper.readTree(proposal.getArgumentsJson());
        assertEquals(stage.getId(), arguments.path("resolution").path("id").asInt());
        assertTrue(arguments.path("pinned").path("templateVersion").asInt() > 0);
        jdbcTemplate.update("UPDATE company SET updated_at = NOW() WHERE workspace_id = ? AND id = ?",
                workspaceId(), anchor.getId());
        authenticate();
        try {
            var card = readService.get(trajectory.sessionId(), proposal.getId());
            assertTrue(card.changes().stream().allMatch(change -> "ready".equals(change.state())));
            assertTrue(card.changes().stream().anyMatch(change -> "templateDefaults".equals(change.field())));
            var approved = writeToolService().approve(trajectory.sessionId(), proposal.getId());
            assertEquals("executed", approved.status());
            assertEquals(approved.result(), writeToolService().approve(trajectory.sessionId(), proposal.getId()).result());
            assertEquals("deal", readService.get(trajectory.sessionId(), proposal.getId()).createdRecord().kind());
        } finally {
            clearAuthentication();
        }
        assertEquals(1, dealCount());
        assertEquals(1, auditRows("deal.create"));
        assertEquals(stage.getId(), jdbcTemplate.queryForObject(
                "SELECT stage_id FROM deal WHERE workspace_id = ?", Integer.class, workspaceId()));
    }

    @Test
    void aStageNameSwapRefusesThroughTheFrameworkPin() {
        company("Alderwick Shipping");
        Pipeline pipeline = pipeline("Sales");
        Stage reviewed = stage(pipeline, "Discovery", 0);
        Stage successor = stage(pipeline, "Qualified", 1);
        Trajectory trajectory = run("connex_script_create_deal_proposal", "add an opportunity");
        AiChatToolCall proposal = proposal(trajectory, "create_deal");
        jdbcTemplate.update("UPDATE stage SET name = ? WHERE workspace_id = ? AND id = ?", "Retired", workspaceId(), reviewed.getId());
        jdbcTemplate.update("UPDATE stage SET name = ? WHERE workspace_id = ? AND id = ?", "Discovery", workspaceId(), successor.getId());
        authenticate();
        try {
            assertEquals("Assistant proposal target changed", assertThrows(ConflictException.class,
                    () -> writeToolService().approve(trajectory.sessionId(), proposal.getId())).getMessage());
        } finally {
            clearAuthentication();
        }
        assertEquals(0, dealCount());
        assertEquals(0, auditRows("deal.create"));
    }

    @Test
    void anEditedTemplateRefusesThePinnedVersionInsteadOfApplyingNewDefaults() {
        company("Alderwick Shipping");
        stage(pipeline("Sales"), "Discovery", 0);
        RecordCreationTemplateDto template = createTemplate(RecordCreationRecordType.deal,
                new RecordCreationTemplateFieldDto("expectedCloseDate", false, null, null, null));
        Trajectory trajectory = run("connex_script_create_deal_proposal", "add an opportunity");
        AiChatToolCall proposal = proposal(trajectory, "create_deal");
        assertEquals(template.id(), objectMapper.readTree(proposal.getArgumentsJson()).path("pinned").path("templateId").asString());
        authenticate();
        try {
            templateService.update(template.id(), new RecordCreationTemplateUpdateRequestDto(
                    new LocalizedTextDto("Edited", "更新済み"), null, template.definition(), true,
                    template.revision(), template.version(), 1, true));
            RecordCreationTemplateException refused = assertThrows(RecordCreationTemplateException.class,
                    () -> writeToolService().approve(trajectory.sessionId(), proposal.getId()));
            assertTrue(List.of("TEMPLATE_VERSION_STALE", "TEMPLATE_SET_STALE").contains(refused.error().code()));
        } finally {
            clearAuthentication();
        }
        assertEquals(0, dealCount());
        assertEquals(0, auditRows("deal.create"));
    }

    @Test
    void anUnsuppliedRequiredTemplateFieldRefusesBeforeDuplicateCheckingOrProposal() {
        company("Alderwick Shipping");
        stage(pipeline("Sales"), "Discovery", 0);
        createTemplate(RecordCreationRecordType.deal,
                new RecordCreationTemplateFieldDto("expectedCloseDate", true, null, null, null));
        Trajectory trajectory = run("connex_script_create_deal_proposal", "add an opportunity");
        assertRefused(trajectory, "create_deal", "template_requires_fields");
        assertEquals(0, dealCount());
        assertEquals(0, auditRows("deal.create"));
    }

    private RecordCreationTemplateDto createTemplate(
            RecordCreationRecordType kind, RecordCreationTemplateFieldDto field) {
        authenticate();
        try {
            return templateService.create(new RecordCreationTemplateCreateRequestDto(kind,
                    new LocalizedTextDto("Reviewed", "確認済み"), null,
                    new RecordCreationTemplateDefinitionDto(1, List.of(new RecordCreationTemplateGroupDto(
                            "basics", new LocalizedTextDto("Basics", "基本情報"), null, List.of(field)))), true, 0));
        } finally {
            clearAuthentication();
        }
    }

    private int contactCount() {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM person WHERE workspace_id = ?", Integer.class, workspaceId()));
    }

    private int dealCount() {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM deal WHERE workspace_id = ?", Integer.class, workspaceId()));
    }

    private static AiChatToolCall proposal(Trajectory trajectory, String tool) {
        assertEquals("resolved", trajectory.status(), trajectory.terminalReason());
        List<AiChatToolCall> rows = trajectory.toolCalls().stream().filter(call -> tool.equals(call.getToolName())).toList();
        assertEquals(1, rows.size());
        assertEquals("proposed", rows.getFirst().getStatus());
        return rows.getFirst();
    }

    private static void assertRefused(Trajectory trajectory, String tool, String reason) {
        List<AiChatToolCall> rows = trajectory.toolCalls().stream().filter(call -> tool.equals(call.getToolName())).toList();
        assertEquals(1, rows.size());
        assertEquals("failed", rows.getFirst().getStatus());
        assertTrue(rows.getFirst().getResultJson().contains(reason));
        assertFalse(trajectory.toolCalls().stream().anyMatch(call -> "proposed".equals(call.getStatus())));
    }
}
