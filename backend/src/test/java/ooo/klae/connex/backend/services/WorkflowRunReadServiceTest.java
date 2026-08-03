package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import ooo.klae.connex.backend.beans.RuleExecution;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowRunView;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.WorkflowRunDetailDto;
import ooo.klae.connex.backend.dto.WorkflowRunPageDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.services.WorkflowDraftCanonicalizer.CanonicalDraft;

@ExtendWith(MockitoExtension.class)
class WorkflowRunReadServiceTest {

    @Mock private WorkflowMapper workflowMapper;
    @Mock private WorkflowRunMapper workflowRunMapper;
    @Mock private WorkflowVersionMapper workflowVersionMapper;
    @Mock private RuleMapper ruleMapper;
    @Mock private WorkflowDraftCanonicalizer canonicalizer;
    @Mock private WorkspaceService workspaceService;

    private WorkflowRunReadService service;
    private Workflow workflow;
    private LocalDateTime asOf;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        service = new WorkflowRunReadService(
            workflowMapper,
            workflowRunMapper,
            workflowVersionMapper,
            ruleMapper,
            canonicalizer,
            workspaceService,
            objectMapper);
        workflow = new Workflow();
        workflow.setId(11);
        workflow.setWorkspaceId(7);
        workflow.setLegacyRuleId(13);
        asOf = LocalDateTime.of(2026, 8, 2, 12, 0);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
    }

    @Test
    void equalTimestampsMergeDeterministicallyAndPreserveLegacyPartial() {
        when(workflowMapper.getById(7, 11)).thenReturn(workflow);
        when(workflowRunMapper.currentTimestamp(7, 11)).thenReturn(asOf);
        WorkflowRunView canonical = canonicalRun(31L, asOf.minusMinutes(1));
        when(workflowRunMapper.getPage(7, 11, asOf, null, null, 3))
            .thenReturn(List.of(canonical));
        RuleExecution legacy = legacyExecution(9, "partial", asOf.minusMinutes(1));
        when(ruleMapper.getExecutionPage(7, 13, asOf, null, null, 3))
            .thenReturn(List.of(legacy));

        WorkflowRunPageDto page = service.listRuns(11, 2, null);

        assertEquals(List.of("canonical-31", "legacy-9"), page.items().stream()
            .map(item -> item.runKey())
            .toList());
        assertEquals("partial", page.items().get(1).status());
        assertEquals("partial", page.items().get(1).legacyStatus());
        assertEquals("legacy_partial", page.items().get(1).failure().code());
        assertFalse(page.items().get(1).stepDetailAvailable());
        assertNull(page.nextCursor());
    }

    @Test
    void frozenCursorUsesSourceSpecificFieldNamesAndRejectsTampering() {
        workflow.setLegacyRuleId(null);
        when(workflowMapper.getById(7, 11)).thenReturn(workflow);
        when(workflowRunMapper.currentTimestamp(7, 11)).thenReturn(asOf);
        when(workflowRunMapper.getPage(7, 11, asOf, null, null, 2))
            .thenReturn(List.of(
                canonicalRun(31L, asOf.minusMinutes(1)),
                canonicalRun(30L, asOf.minusMinutes(2))));

        WorkflowRunPageDto first = service.listRuns(11, 1, null);

        assertNotNull(first.nextCursor());
        String cursorJson = new String(
            Base64.getUrlDecoder().decode(first.nextCursor()), StandardCharsets.UTF_8);
        assertTrue(cursorJson.contains("\"asOf\""));
        assertTrue(cursorJson.contains("\"startedAt\""));
        assertFalse(cursorJson.contains("\"timestamp\""));
        assertThrows(
            BadRequestException.class,
            () -> service.listRuns(11, 1, first.nextCursor() + "!"));
    }

    @Test
    void malformedRunKeysAreRejectedWithoutNumericFallback() {
        when(workflowMapper.getById(7, 11)).thenReturn(workflow);

        assertThrows(BadRequestException.class, () -> service.getRun(11, "31"));
        assertThrows(BadRequestException.class, () -> service.getRun(11, "canonical-0"));
        verify(workflowRunMapper, never()).getViewById(any(Integer.class), any(Integer.class), any(Long.class));
        verify(ruleMapper, never()).getExecutionById(any(Integer.class), any(Integer.class), any(Integer.class));
    }

    @Test
    void legacyDetailUsesRetainedRuleLinkAndNeverSynthesizesSteps() {
        when(workflowMapper.getById(7, 11)).thenReturn(workflow);
        RuleExecution execution = legacyExecution(9, "failed", asOf.minusMinutes(1));
        execution.setDetail("{\"message\":\"raw customer content\"}");
        when(ruleMapper.getExecutionById(7, 13, 9)).thenReturn(execution);

        WorkflowRunDetailDto detail = service.getRun(11, "legacy-9");

        assertEquals("failed", detail.status());
        assertEquals("legacy_failed", detail.failure().code());
        assertEquals(List.of(), detail.path());
        assertFalse(detail.stepDetailAvailable());
        assertNull(detail.version());
    }

    @Test
    void canonicalDetailLoadsAndVerifiesTheRunPinnedVersion() {
        when(workflowMapper.getById(7, 11)).thenReturn(workflow);
        WorkflowRunView run = canonicalRun(31L, asOf.minusMinutes(1));
        when(workflowRunMapper.getViewById(7, 11, 31L)).thenReturn(run);
        WorkflowVersion version = new WorkflowVersion();
        version.setId(19L);
        version.setWorkspaceId(7);
        version.setWorkflowId(11);
        version.setVersionNumber(2);
        version.setName("Pinned");
        version.setRecordType("company");
        version.setExecutionMode("user");
        version.setDefinitionJson("{}");
        version.setCanvasJson("{}");
        version.setDefinitionHash(new byte[32]);
        version.setPublishedAt(asOf.minusHours(1));
        when(workflowVersionMapper.getById(7, 11, 19L)).thenReturn(version);
        when(canonicalizer.canonicalizeDraftJson(
            "Pinned", null, "company", "user", "{}", "{}"))
            .thenReturn(new CanonicalDraft(
                "Pinned", null, "company", "user", "{}", "{}", new byte[32]));
        when(workflowRunMapper.getSteps(7, 31L)).thenReturn(List.of());

        WorkflowRunDetailDto detail = service.getRun(11, "canonical-31");

        assertEquals(19L, detail.version().id());
        assertEquals("0".repeat(64), detail.version().definitionHash());
        assertTrue(detail.stepDetailAvailable());
    }

    @Test
    void otherWorkspaceWorkflowIsAWorkspaceSafeNotFound() {
        when(workflowMapper.getById(7, 11)).thenReturn(null);

        assertThrows(
            ResourceNotFoundException.class,
            () -> service.getRun(11, "canonical-31"));

        verify(workflowRunMapper, never()).getViewById(eq(7), eq(11), eq(31L));
    }

    private static WorkflowRunView canonicalRun(long id, LocalDateTime startedAt) {
        WorkflowRunView run = new WorkflowRunView();
        run.setId(id);
        run.setWorkspaceId(7);
        run.setWorkflowId(11);
        run.setWorkflowVersionId(19L);
        run.setStatus("succeeded");
        run.setTriggerType("entity_change");
        run.setTriggerEvent("company.updated");
        run.setRecordType("company");
        run.setRecordId(41);
        run.setStartedAt(startedAt);
        run.setFinishedAt(startedAt.plusSeconds(1));
        run.setVersionNumber(2);
        run.setVersionDefinitionHash(new byte[32]);
        run.setVersionPublishedAt(startedAt.minusHours(1));
        return run;
    }

    private static RuleExecution legacyExecution(
            int id, String status, LocalDateTime executedAt) {
        RuleExecution execution = new RuleExecution();
        execution.setId(id);
        execution.setWorkspaceId(7);
        execution.setRuleId(13);
        execution.setTriggerEntityType("company");
        execution.setTriggerEntityId(41);
        execution.setStatus(status);
        execution.setExecutedAt(executedAt.toString().replace('T', ' '));
        return execution;
    }
}
