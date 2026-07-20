package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.WorkflowCanvas;
import ooo.klae.connex.backend.dto.WorkflowCreateRequest;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowDraftRequest;
import ooo.klae.connex.backend.dto.WorkflowPublishRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.LegacyWorkflowGraphConverter.ConvertedWorkflow;
import ooo.klae.connex.backend.services.WorkflowDraftCanonicalizer.CanonicalDraft;
import ooo.klae.connex.backend.services.WorkspaceService.Role;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {

    @Mock private WorkflowMapper workflowMapper;
    @Mock private WorkflowVersionMapper workflowVersionMapper;
    @Mock private RuleMapper ruleMapper;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private WorkspaceService workspaceService;
    @Mock private AuditService auditService;
    @Mock private RuleDefinitionValidator definitionValidator;

    private WorkflowDraftCanonicalizer canonicalizer;
    private LegacyWorkflowGraphConverter graphConverter;
    private RuleDefinitionCodec definitionCodec;
    private WorkflowService service;

    @BeforeEach
    void setUp() {
        canonicalizer = new WorkflowDraftCanonicalizer();
        definitionCodec = new RuleDefinitionCodec(new ObjectMapper());
        graphConverter = new LegacyWorkflowGraphConverter(definitionCodec);
        service = new WorkflowService(
            workflowMapper,
            workflowVersionMapper,
            ruleMapper,
            workspaceMapper,
            workspaceService,
            auditService,
            canonicalizer,
            graphConverter,
            definitionValidator,
            definitionCodec);
        lenient().when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        lenient().when(workspaceService.getCurrentUserId()).thenReturn(41);
        lenient().when(workspaceMapper.lockActiveMembership(7, 41)).thenReturn(41);
    }

    @Test
    void createPinsUserIdentityAndCreatesNoRuleOrVersion() throws Exception {
        WorkflowCreateRequest request = createRequest("user");
        doAnswer(invocation -> {
            invocation.<Workflow>getArgument(0).setId(101);
            return null;
        }).when(workflowMapper).insert(any(Workflow.class));

        var created = service.create(request);

        ArgumentCaptor<Workflow> workflow = ArgumentCaptor.forClass(Workflow.class);
        verify(workflowMapper).insert(workflow.capture());
        assertEquals(101, created.id());
        assertEquals(0, created.draftRevision());
        assertFalse(created.enabled());
        assertEquals(41, created.runAsUserId());
        assertNull(created.activeVersionId());
        assertNull(workflow.getValue().getLegacyRuleId());
        assertEquals(41, workflow.getValue().getCreatedById());
        verify(workspaceService).lockAndRequireMember(7, 41);
        verifyNoInteractions(ruleMapper, workflowVersionMapper);
        verify(auditService).record(
            eq("workflow.create"), eq("workflow"), eq(101), eq("Workflow 101"),
            eq("Workflow created"), eq(Map.of("draftRevision", 0, "executionMode", "user")));
    }

    @Test
    void userCreateFailsBeforeInsertionWhenTheCreatorMembershipIsNotActive() throws Exception {
        WorkflowCreateRequest request = createRequest("user");
        doThrow(new ForbiddenException("User is not an active workspace member"))
            .when(workspaceService).lockAndRequireMember(7, 41);

        assertThrows(ForbiddenException.class, () -> service.create(request));

        verify(workflowMapper, never()).insert(any());
        verifyNoInteractions(ruleMapper, workflowVersionMapper, auditService);
    }

    @Test
    void systemAuthoringRequiresAdminAndNeverAcceptsRunAsInput() throws Exception {
        WorkflowCreateRequest request = createRequest("system");
        doAnswer(invocation -> {
            invocation.<Workflow>getArgument(0).setId(101);
            return null;
        }).when(workflowMapper).insert(any(Workflow.class));

        var created = service.create(request);

        assertNull(created.runAsUserId());
        verify(workspaceService).requireRole(Role.ADMIN);
        verify(workspaceService, never()).lockAndRequireMember(any(Integer.class), any(Integer.class));
    }

    @Test
    void draftSaveUsesCasAndPreservesExactUserRunAs() throws Exception {
        Workflow existing = workflow("Workflow", "user", 3, 999, null, null, false);
        Workflow saved = workflow("Changed", "user", 4, 999, null, null, false);
        when(workflowMapper.getById(7, 101)).thenReturn(existing, saved);
        when(workflowMapper.updateDraft(any(Workflow.class), eq(3))).thenReturn(1);

        var response = service.saveDraft(101, draftRequest("Changed", "user", 3));

        ArgumentCaptor<Workflow> replacement = ArgumentCaptor.forClass(Workflow.class);
        verify(workflowMapper).updateDraft(replacement.capture(), eq(3));
        assertEquals(999, replacement.getValue().getDraftRunAsUserId());
        assertEquals(4, response.draftRevision());
        verify(workspaceMapper, never()).lockActiveMembership(any(Integer.class), any(Integer.class));
        verify(auditService).record(
            eq("workflow.draft.save"), eq("workflow"), eq(101), eq("Workflow 101"),
            eq("Workflow draft saved"), any());
    }

    @Test
    void switchingToUserLocksAndRequiresTheImmutableCreatorMembership() throws Exception {
        Workflow existing = workflow("Workflow", "system", 3, null, null, null, false);
        existing.setCreatedById(55);
        Workflow saved = workflow("Changed", "user", 4, 55, null, null, false);
        saved.setCreatedById(55);
        when(workflowMapper.getById(7, 101)).thenReturn(existing, saved);
        when(workspaceMapper.lockActiveMembership(7, 55)).thenReturn(55);
        when(workflowMapper.updateDraft(any(Workflow.class), eq(3))).thenReturn(1);

        service.saveDraft(101, draftRequest("Changed", "user", 3));

        ArgumentCaptor<Workflow> replacement = ArgumentCaptor.forClass(Workflow.class);
        verify(workflowMapper).updateDraft(replacement.capture(), eq(3));
        assertEquals(55, replacement.getValue().getDraftRunAsUserId());
        verify(workspaceMapper).lockActiveMembership(7, 55);

        when(workflowMapper.getById(7, 102)).thenReturn(existing);
        when(workspaceMapper.lockActiveMembership(7, 55)).thenReturn(null);
        assertThrows(
            ConflictException.class,
            () -> service.saveDraft(102, draftRequest("Changed", "user", 3)));
    }

    @Test
    void switchingToSystemRequiresAdminAndClearsTheDraftIdentity() throws Exception {
        Workflow existing = workflow("Workflow", "user", 3, 55, null, null, false);
        Workflow saved = workflow("Changed", "system", 4, null, null, null, false);
        when(workflowMapper.getById(7, 101)).thenReturn(existing, saved);
        when(workflowMapper.updateDraft(any(Workflow.class), eq(3))).thenReturn(1);

        service.saveDraft(101, draftRequest("Changed", "system", 3));

        ArgumentCaptor<Workflow> replacement = ArgumentCaptor.forClass(Workflow.class);
        verify(workflowMapper).updateDraft(replacement.capture(), eq(3));
        assertNull(replacement.getValue().getDraftRunAsUserId());
        verify(workspaceService).requireRole(Role.ADMIN);
    }

    @Test
    void failedCasDistinguishesMissingFromRevisionConflict() throws Exception {
        Workflow existing = workflow("Workflow", "user", 3, 41, null, null, false);
        when(workflowMapper.getById(7, 101)).thenReturn(existing, existing);
        when(workflowMapper.updateDraft(any(Workflow.class), eq(2))).thenReturn(0);
        assertThrows(
            ConflictException.class,
            () -> service.saveDraft(101, draftRequest("Changed", "user", 2)));

        when(workflowMapper.getById(7, 102)).thenReturn(existing, (Workflow) null);
        when(workflowMapper.updateDraft(any(Workflow.class), eq(3))).thenReturn(0);
        assertThrows(
            ResourceNotFoundException.class,
            () -> service.saveDraft(102, draftRequest("Changed", "user", 3)));
    }

    @Test
    void validateIsReadOnlyAndRechecksTheSharedSemanticBoundary() {
        Workflow workflow = workflow("Workflow", "user", 3, 41, null, null, false);
        when(workflowMapper.getById(7, 101)).thenReturn(workflow);

        var result = service.validate(101);

        assertTrue(result.valid());
        assertEquals(3, result.draftRevision());
        verify(definitionValidator).validateDefinition(
            eq("deal"), any(RuleTrigger.class), eq(null), any(), eq("user"));
        verify(workflowMapper, never()).updateDraft(any(), any(Integer.class));
        verifyNoInteractions(ruleMapper, workflowVersionMapper, auditService);
    }

    @Test
    void validateAndPublishRejectDraftsMissingCanvasPositions() {
        Workflow workflow = workflow("Workflow", "user", 3, 41, null, null, false);
        CanonicalDraft incomplete = canonicalizer.canonicalizeDraftJson(
            "Workflow", null, "deal", "user", definitionJson(), missingCanvasJson());
        workflow.setDraftCanvasJson(incomplete.canvasJson());
        when(workflowMapper.getById(7, 101)).thenReturn(workflow);
        when(workflowMapper.getByIdForUpdate(7, 101)).thenReturn(workflow);

        assertThrows(BadRequestException.class, () -> service.validate(101));
        assertThrows(BadRequestException.class, () -> service.publish(101, publishRequest(3)));

        verify(definitionValidator, never()).validateDefinition(any(), any(), any(), any(), any());
        verifyNoInteractions(ruleMapper, workflowVersionMapper, auditService);
    }

    @Test
    void firstPublishCreatesRuleAndVersionThenAssignsBothPointersAtomically() {
        Workflow workflow = workflow("Workflow", "user", 0, 41, null, null, false);
        when(workflowMapper.getByIdForUpdate(7, 101)).thenReturn(workflow);
        when(workflowVersionMapper.getLatestForUpdate(7, 101)).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<Rule>getArgument(0).setId(77);
            return null;
        }).when(ruleMapper).insert(any(Rule.class));
        doAnswer(invocation -> {
            invocation.<WorkflowVersion>getArgument(0).setId(88L);
            return null;
        }).when(workflowVersionMapper).insert(any(WorkflowVersion.class));
        when(workflowMapper.assignFirstPublication(7, 101, 77, 88L, 41, 0)).thenReturn(1);

        var published = service.publish(101, publishRequest(0));

        assertEquals(88L, published.activeVersionId());
        ArgumentCaptor<Rule> rule = ArgumentCaptor.forClass(Rule.class);
        ArgumentCaptor<WorkflowVersion> version = ArgumentCaptor.forClass(WorkflowVersion.class);
        verify(ruleMapper).insert(rule.capture());
        verify(workflowVersionMapper).insert(version.capture());
        assertEquals(41, rule.getValue().getCreatedById());
        assertEquals(41, rule.getValue().getRunAsUserId());
        assertEquals("entity_change", rule.getValue().getTriggerType());
        assertFalse(rule.getValue().isEnabled());
        assertEquals(1, version.getValue().getVersionNumber());
        assertEquals(41, version.getValue().getCreatedById());
        assertEquals(41, version.getValue().getPublishedById());
        verify(definitionValidator).validateDefinition(
            eq("deal"), any(RuleTrigger.class), eq(null), any(), eq("user"));
        InOrder writes = inOrder(ruleMapper, workflowMapper, workflowVersionMapper);
        writes.verify(ruleMapper).insert(any(Rule.class));
        writes.verify(workflowVersionMapper).insert(any(WorkflowVersion.class));
        writes.verify(workflowMapper).assignFirstPublication(7, 101, 77, 88L, 41, 0);
        verify(workspaceMapper).lockActiveMembership(7, 41);
        verify(auditService).record(
            eq("workflow.publish"), eq("workflow"), eq(101), eq("Workflow 101"),
            eq("Workflow published"), eq(Map.of("versionNumber", 1)));
    }

    @Test
    void userPublishLocksTheExactPersistedDraftIdentityAndRejectsMissingIdentity() {
        Workflow inactive = workflow("Workflow", "user", 0, 999, null, null, false);
        when(workflowMapper.getByIdForUpdate(7, 101)).thenReturn(inactive);
        when(workspaceMapper.lockActiveMembership(7, 999)).thenReturn(null);

        assertThrows(ConflictException.class, () -> service.publish(101, publishRequest(0)));

        verify(workspaceMapper).lockActiveMembership(7, 999);
        verify(workspaceMapper, never()).lockActiveMembership(7, 41);
        verifyNoInteractions(ruleMapper, workflowVersionMapper, auditService);

        Workflow missing = workflow("Workflow", "user", 0, null, null, null, false);
        when(workflowMapper.getByIdForUpdate(7, 102)).thenReturn(missing);
        missing.setId(102);

        assertThrows(ConflictException.class, () -> service.publish(102, publishRequest(0)));
        verifyNoInteractions(ruleMapper, workflowVersionMapper, auditService);
    }

    @Test
    void systemPublishRequiresAdminForTheCurrentActor() {
        Workflow workflow = workflow("Workflow", "system", 0, null, null, null, false);
        when(workflowMapper.getByIdForUpdate(7, 101)).thenReturn(workflow);
        doThrow(new ForbiddenException("Requires admin role"))
            .when(workspaceService).requireRole(Role.ADMIN);

        assertThrows(ForbiddenException.class, () -> service.publish(101, publishRequest(0)));

        verify(workspaceService).requireRole(Role.ADMIN);
        verifyNoInteractions(ruleMapper, workflowVersionMapper, auditService);
    }

    @Test
    void identicalPublishReusesTheActiveVersionAndRefusesRuleDrift() {
        PublishedPair pair = publishedPair("Workflow", false, 4);
        when(workflowMapper.getByIdForUpdate(7, 101)).thenReturn(pair.workflow());
        when(workflowVersionMapper.getByIdForUpdate(7, 101, 88L)).thenReturn(pair.version());
        when(ruleMapper.getByIdForUpdate(7, 77)).thenReturn(pair.rule());

        var unchanged = service.publish(101, publishRequest(3));

        assertEquals(88L, unchanged.activeVersionId());
        verify(workflowVersionMapper, never()).insert(any());
        verify(ruleMapper, never()).update(any());
        verify(workflowMapper, never()).assignFirstPublication(
            any(Integer.class), any(Integer.class), any(Integer.class), any(Long.class),
            any(Integer.class), any(Integer.class));
        verify(workflowMapper, never()).advancePublication(
            any(Integer.class), any(Integer.class), any(Integer.class), any(Long.class),
            any(Long.class), any(Integer.class), any(Integer.class));
        verifyNoInteractions(auditService);

        pair.rule().setName("Drifted");
        assertThrows(ConflictException.class, () -> service.publish(101, publishRequest(3)));
        verify(workflowVersionMapper, never()).getLatestForUpdate(7, 101);
    }

    @Test
    void changedPublishAppendsLatestThenSynchronizesRuleAndPointer() {
        PublishedPair pair = publishedPair("Published", false, 4);
        pair.workflow().setName("Draft rename");
        when(workflowMapper.getByIdForUpdate(7, 101)).thenReturn(pair.workflow());
        when(workflowVersionMapper.getByIdForUpdate(7, 101, 88L)).thenReturn(pair.version());
        when(ruleMapper.getByIdForUpdate(7, 77)).thenReturn(pair.rule());
        when(workflowVersionMapper.getLatestForUpdate(7, 101)).thenReturn(pair.version());
        doAnswer(invocation -> {
            invocation.<WorkflowVersion>getArgument(0).setId(99L);
            return null;
        }).when(workflowVersionMapper).insert(any(WorkflowVersion.class));
        when(ruleMapper.update(any(Rule.class))).thenReturn(1);
        when(workflowMapper.advancePublication(7, 101, 77, 88L, 99L, 41, 3)).thenReturn(1);

        service.publish(101, publishRequest(3));

        ArgumentCaptor<WorkflowVersion> version = ArgumentCaptor.forClass(WorkflowVersion.class);
        verify(workflowVersionMapper).insert(version.capture());
        assertEquals(5, version.getValue().getVersionNumber());
        assertEquals("Draft rename", version.getValue().getName());
        InOrder writes = inOrder(workflowVersionMapper, ruleMapper, workflowMapper);
        writes.verify(workflowVersionMapper).insert(any(WorkflowVersion.class));
        writes.verify(ruleMapper).update(any(Rule.class));
        writes.verify(workflowMapper).advancePublication(7, 101, 77, 88L, 99L, 41, 3);
    }

    @Test
    void publishFailureStopsBeforeAdvancingTheActivePointer() {
        PublishedPair pair = publishedPair("Published", false, 4);
        pair.workflow().setName("Draft rename");
        when(workflowMapper.getByIdForUpdate(7, 101)).thenReturn(pair.workflow());
        when(workflowVersionMapper.getByIdForUpdate(7, 101, 88L)).thenReturn(pair.version());
        when(ruleMapper.getByIdForUpdate(7, 77)).thenReturn(pair.rule());
        when(workflowVersionMapper.getLatestForUpdate(7, 101)).thenReturn(pair.version());
        doAnswer(invocation -> {
            invocation.<WorkflowVersion>getArgument(0).setId(99L);
            return null;
        }).when(workflowVersionMapper).insert(any(WorkflowVersion.class));
        when(ruleMapper.update(any(Rule.class))).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.publish(101, publishRequest(3)));

        InOrder writes = inOrder(workflowVersionMapper, ruleMapper);
        writes.verify(workflowVersionMapper).insert(any(WorkflowVersion.class));
        writes.verify(ruleMapper).update(any(Rule.class));
        verify(workflowMapper, never()).advancePublication(
            any(Integer.class), any(Integer.class), any(Integer.class), any(Long.class),
            any(Long.class), any(Integer.class), any(Integer.class));
        verifyNoInteractions(auditService);
    }

    @Test
    void publicationPointerCompareAndSwapFailureCreatesNoAudit() {
        Workflow workflow = workflow("Workflow", "user", 0, 41, null, null, false);
        when(workflowMapper.getByIdForUpdate(7, 101)).thenReturn(workflow);
        when(workflowVersionMapper.getLatestForUpdate(7, 101)).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<Rule>getArgument(0).setId(77);
            return null;
        }).when(ruleMapper).insert(any(Rule.class));
        doAnswer(invocation -> {
            invocation.<WorkflowVersion>getArgument(0).setId(88L);
            return null;
        }).when(workflowVersionMapper).insert(any(WorkflowVersion.class));
        when(workflowMapper.assignFirstPublication(7, 101, 77, 88L, 41, 0)).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.publish(101, publishRequest(0)));

        verify(ruleMapper).insert(any(Rule.class));
        verify(workflowVersionMapper).insert(any(WorkflowVersion.class));
        verifyNoInteractions(auditService);
    }

    @Test
    void enableAndDisableSynchronizeBothStatesAndAuditOnlyChanges() {
        PublishedPair disabled = publishedPair("Workflow", false, 4);
        when(workflowMapper.getByIdForUpdate(7, 101)).thenReturn(disabled.workflow());
        when(workflowVersionMapper.getByIdForUpdate(7, 101, 88L)).thenReturn(disabled.version());
        when(ruleMapper.getByIdForUpdate(7, 77)).thenReturn(disabled.rule());
        when(ruleMapper.updateEnabled(7, 77, true)).thenReturn(1);
        when(workflowMapper.updateLifecycle(7, 101, true, 41)).thenReturn(1);

        var enabled = service.enable(101);

        assertTrue(enabled.enabled());
        InOrder writes = inOrder(ruleMapper, workflowMapper);
        writes.verify(ruleMapper).updateEnabled(7, 77, true);
        writes.verify(workflowMapper).updateLifecycle(7, 101, true, 41);
        verify(auditService).record(
            eq("workflow.enable"), eq("workflow"), eq(101), eq("Workflow 101"),
            eq("Workflow enabled"), any());

        Workflow draftOnly = workflow("Draft", "user", 0, 41, null, null, false);
        when(workflowMapper.getByIdForUpdate(7, 102)).thenReturn(draftOnly);
        service.disable(102);
        assertThrows(ConflictException.class, () -> service.enable(102));
        verify(ruleMapper, never()).updateEnabled(7, 77, false);
        verify(workflowMapper, never()).updateLifecycle(7, 102, false, 41);
        verify(auditService, never()).record(
            eq("workflow.disable"), any(), any(), any(), any(), any());
    }

    @Test
    void disableSynchronizesThePublishedRuleBeforeTheWorkflow() {
        PublishedPair enabled = publishedPair("Workflow", true, 4);
        when(workflowMapper.getByIdForUpdate(7, 101)).thenReturn(enabled.workflow());
        when(workflowVersionMapper.getByIdForUpdate(7, 101, 88L)).thenReturn(enabled.version());
        when(ruleMapper.getByIdForUpdate(7, 77)).thenReturn(enabled.rule());
        when(ruleMapper.updateEnabled(7, 77, false)).thenReturn(1);
        when(workflowMapper.updateLifecycle(7, 101, false, 41)).thenReturn(1);

        var disabled = service.disable(101);

        assertFalse(disabled.enabled());
        InOrder writes = inOrder(ruleMapper, workflowMapper);
        writes.verify(ruleMapper).updateEnabled(7, 77, false);
        writes.verify(workflowMapper).updateLifecycle(7, 101, false, 41);
        verify(auditService).record(
            eq("workflow.disable"), eq("workflow"), eq(101), eq("Workflow 101"),
            eq("Workflow disabled"), any());
    }

    @Test
    void disableRemainsPossibleAndIdempotentWhenThePersistedUserIdentityIsGone() {
        PublishedPair enabled = publishedPair("Workflow", true, 4, "user", null);
        when(workflowMapper.getByIdForUpdate(7, 101)).thenReturn(enabled.workflow());
        when(workflowVersionMapper.getByIdForUpdate(7, 101, 88L)).thenReturn(enabled.version());
        when(ruleMapper.getByIdForUpdate(7, 77)).thenReturn(enabled.rule());
        when(ruleMapper.updateEnabled(7, 77, false)).thenReturn(1);
        when(workflowMapper.updateLifecycle(7, 101, false, 41)).thenReturn(1);

        var disabled = service.disable(101);
        enabled.rule().setEnabled(false);
        var unchanged = service.disable(101);

        assertFalse(disabled.enabled());
        assertFalse(unchanged.enabled());
        verify(workspaceMapper, never()).lockActiveMembership(any(Integer.class), any(Integer.class));
        verify(workspaceService, never()).requireRole(Role.ADMIN);
        verify(ruleMapper, times(1)).updateEnabled(7, 77, false);
        verify(workflowMapper, times(1)).updateLifecycle(7, 101, false, 41);
        verify(auditService, times(1)).record(
            eq("workflow.disable"), eq("workflow"), eq(101), eq("Workflow 101"),
            eq("Workflow disabled"), any());
    }

    @Test
    void enableLocksTheExactPublishedUserIdentityAndNoOpCreatesNoAuditOrWrite() {
        PublishedPair inactive = publishedPair("Workflow", false, 4, "user", 999);
        when(workflowMapper.getByIdForUpdate(7, 101)).thenReturn(inactive.workflow());
        when(workflowVersionMapper.getByIdForUpdate(7, 101, 88L)).thenReturn(inactive.version());
        when(ruleMapper.getByIdForUpdate(7, 77)).thenReturn(inactive.rule());
        when(workspaceMapper.lockActiveMembership(7, 999)).thenReturn(null);

        assertThrows(ConflictException.class, () -> service.enable(101));

        verify(workspaceMapper).lockActiveMembership(7, 999);
        verify(workspaceMapper, never()).lockActiveMembership(7, 41);
        verify(ruleMapper, never()).updateEnabled(7, 77, true);
        verify(workflowMapper, never()).updateLifecycle(7, 101, true, 41);
        verifyNoInteractions(auditService);

        PublishedPair alreadyEnabled = publishedPair("Workflow", true, 4);
        when(workflowMapper.getByIdForUpdate(7, 102)).thenReturn(alreadyEnabled.workflow());
        alreadyEnabled.workflow().setId(102);
        alreadyEnabled.version().setWorkflowId(102);
        when(workflowVersionMapper.getByIdForUpdate(7, 102, 88L))
            .thenReturn(alreadyEnabled.version());
        when(ruleMapper.getByIdForUpdate(7, 77)).thenReturn(alreadyEnabled.rule());

        var unchanged = service.enable(102);

        assertTrue(unchanged.enabled());
        verify(ruleMapper, never()).updateEnabled(7, 77, true);
        verify(workflowMapper, never()).updateLifecycle(7, 102, true, 41);
        verifyNoInteractions(auditService);
    }

    @Test
    void systemEnableRequiresAdminButDisableBypassesIdentityAndAdminChecks() {
        PublishedPair systemDisabled = publishedPair("Workflow", false, 4, "system", null);
        when(workflowMapper.getByIdForUpdate(7, 101)).thenReturn(systemDisabled.workflow());
        when(workflowVersionMapper.getByIdForUpdate(7, 101, 88L))
            .thenReturn(systemDisabled.version());
        when(ruleMapper.getByIdForUpdate(7, 77)).thenReturn(systemDisabled.rule());
        doThrow(new ForbiddenException("Requires admin role"))
            .when(workspaceService).requireRole(Role.ADMIN);

        assertThrows(ForbiddenException.class, () -> service.enable(101));
        verify(ruleMapper, never()).updateEnabled(7, 77, true);
        verifyNoInteractions(auditService);

        PublishedPair systemEnabled = publishedPair("Workflow", true, 4, "system", null);
        systemEnabled.workflow().setId(102);
        systemEnabled.version().setWorkflowId(102);
        when(workflowMapper.getByIdForUpdate(7, 102)).thenReturn(systemEnabled.workflow());
        when(workflowVersionMapper.getByIdForUpdate(7, 102, 88L))
            .thenReturn(systemEnabled.version());
        when(ruleMapper.getByIdForUpdate(7, 77)).thenReturn(systemEnabled.rule());
        when(ruleMapper.updateEnabled(7, 77, false)).thenReturn(1);
        when(workflowMapper.updateLifecycle(7, 102, false, 41)).thenReturn(1);

        var disabled = service.disable(102);

        assertFalse(disabled.enabled());
        verify(workspaceService).requireRole(Role.ADMIN);
        verify(auditService).record(
            eq("workflow.disable"), eq("workflow"), eq(102), eq("Workflow 102"),
            eq("Workflow disabled"), any());
    }

    @Test
    void legacyBlankDescriptionRemainsReadableAtTheCanonicalBoundary() {
        Workflow workflow = workflow("Workflow", "user", 3, 41, null, null, false);
        workflow.setDescription("   ");
        when(workflowMapper.getById(7, 101)).thenReturn(workflow);

        var response = service.getById(101);

        assertEquals("   ", response.description());
    }

    @Test
    void transactionSemanticsAreDeclaredAtThePublicBoundary() throws Exception {
        Method validate = WorkflowService.class.getMethod("validate", int.class);
        Method publish = WorkflowService.class.getMethod(
            "publish", int.class, WorkflowPublishRequest.class);
        Method saveDraft = WorkflowService.class.getMethod(
            "saveDraft", int.class, WorkflowDraftRequest.class);

        assertTrue(validate.getAnnotation(Transactional.class).readOnly());
        assertFalse(publish.getAnnotation(Transactional.class).readOnly());
        assertFalse(saveDraft.getAnnotation(Transactional.class).readOnly());
    }

    private WorkflowCreateRequest createRequest(String executionMode) throws Exception {
        WorkflowCreateRequest request = new WorkflowCreateRequest();
        request.setName("Workflow");
        request.setRecordType("deal");
        request.setExecutionMode(executionMode);
        request.setDefinition(JsonMapper.builder().build().readTree(definitionJson()));
        request.setCanvas(JsonMapper.builder().build().readTree(canvasJson()));
        return request;
    }

    private WorkflowDraftRequest draftRequest(
            String name, String executionMode, int expectedRevision) throws Exception {
        WorkflowDraftRequest request = new WorkflowDraftRequest();
        request.setExpectedRevision(expectedRevision);
        request.setName(name);
        request.setRecordType("deal");
        request.setExecutionMode(executionMode);
        request.setDefinition(JsonMapper.builder().build().readTree(definitionJson()));
        request.setCanvas(JsonMapper.builder().build().readTree(canvasJson()));
        return request;
    }

    private static WorkflowPublishRequest publishRequest(int expectedRevision) {
        WorkflowPublishRequest request = new WorkflowPublishRequest();
        request.setExpectedRevision(expectedRevision);
        return request;
    }

    private Workflow workflow(
            String name,
            String executionMode,
            int revision,
            Integer runAsUserId,
            Integer legacyRuleId,
            Long activeVersionId,
            boolean enabled) {
        CanonicalDraft draft = canonicalizer.canonicalizeDraftJson(
            name, null, "deal", executionMode, definitionJson(), canvasJson());
        Workflow workflow = new Workflow();
        workflow.setId(101);
        workflow.setWorkspaceId(7);
        workflow.setLegacyRuleId(legacyRuleId);
        workflow.setName(draft.name());
        workflow.setDescription(draft.description());
        workflow.setEnabled(enabled);
        workflow.setDraftRevision(revision);
        workflow.setDraftRecordType(draft.recordType());
        workflow.setDraftExecutionMode(draft.executionMode());
        workflow.setDraftRunAsUserId(runAsUserId);
        workflow.setDraftDefinitionJson(draft.definitionJson());
        workflow.setDraftCanvasJson(draft.canvasJson());
        workflow.setActiveVersionId(activeVersionId);
        workflow.setCreatedById(41);
        workflow.setUpdatedById(41);
        return workflow;
    }

    private PublishedPair publishedPair(String publishedName, boolean enabled, int versionNumber) {
        return publishedPair(publishedName, enabled, versionNumber, "user", 41);
    }

    private PublishedPair publishedPair(
            String publishedName,
            boolean enabled,
            int versionNumber,
            String executionMode,
            Integer runAsUserId) {
        Workflow workflow = workflow(
            "Workflow", executionMode, 3, runAsUserId, 77, 88L, enabled);
        CanonicalDraft published = canonicalizer.canonicalizeDraftJson(
            publishedName, null, "deal", executionMode, definitionJson(), canvasJson());
        ConvertedWorkflow converted = new ConvertedWorkflow(
            77,
            7,
            published.name(),
            published.description(),
            enabled,
            published.recordType(),
            published.executionMode(),
            runAsUserId,
            41,
            canonicalizer.parseDefinition(published.definitionJson()),
            canonicalizer.parseCanvas(published.canvasJson()));
        Rule rule = graphConverter.project(converted);

        WorkflowVersion version = new WorkflowVersion();
        version.setId(88L);
        version.setWorkspaceId(7);
        version.setWorkflowId(101);
        version.setVersionNumber(versionNumber);
        version.setName(rule.getName());
        version.setDescription(rule.getDescription());
        version.setRecordType(rule.getRecordType());
        version.setTriggerType(rule.getTriggerType());
        version.setTriggerConfig(rule.getTriggerConfig());
        version.setConditionJson(rule.getConditionJson());
        version.setActionsJson(rule.getActionsJson());
        version.setExecutionMode(rule.getExecutionMode());
        version.setRunAsUserId(rule.getRunAsUserId());
        version.setCreatedById(rule.getCreatedById());
        version.setPublishedById(41);
        version.setDefinitionJson(published.definitionJson());
        version.setCanvasJson(published.canvasJson());
        version.setDefinitionHash(published.definitionHash());
        return new PublishedPair(workflow, version, rule);
    }

    private String definitionJson() {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of("deal.won"));
        RuleAction action = new RuleAction();
        action.setType("notify");
        action.setTitle("Notify owner");
        WorkflowDefinition definition = new WorkflowDefinition(
            1,
            "eventSource",
            List.of(
                new ooo.klae.connex.backend.dto.WorkflowNode.Trigger("eventSource", trigger),
                new ooo.klae.connex.backend.dto.WorkflowNode.Action("notifyOwner", action),
                new ooo.klae.connex.backend.dto.WorkflowNode.End("complete")),
            List.of(
                new ooo.klae.connex.backend.dto.WorkflowEdge(
                    "edgeA", "eventSource", "notifyOwner",
                    ooo.klae.connex.backend.dto.WorkflowEdge.Outcome.NEXT),
                new ooo.klae.connex.backend.dto.WorkflowEdge(
                    "edgeB", "notifyOwner", "complete",
                    ooo.klae.connex.backend.dto.WorkflowEdge.Outcome.NEXT)));
        try {
            return JsonMapper.builder().build().writeValueAsString(definition);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String canvasJson() {
        WorkflowCanvas canvas = new WorkflowCanvas(
            Map.of(
                "eventSource", new WorkflowCanvas.Position(BigDecimal.ZERO, BigDecimal.ZERO),
                "notifyOwner", new WorkflowCanvas.Position(BigDecimal.valueOf(300), BigDecimal.ZERO),
                "complete", new WorkflowCanvas.Position(BigDecimal.valueOf(600), BigDecimal.ZERO)),
            new WorkflowCanvas.Viewport(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE));
        try {
            return JsonMapper.builder().build().writeValueAsString(canvas);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String missingCanvasJson() {
        WorkflowCanvas canvas = new WorkflowCanvas(
            Map.of(
                "eventSource", new WorkflowCanvas.Position(BigDecimal.ZERO, BigDecimal.ZERO),
                "complete", new WorkflowCanvas.Position(BigDecimal.valueOf(600), BigDecimal.ZERO)),
            new WorkflowCanvas.Viewport(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE));
        try {
            return JsonMapper.builder().build().writeValueAsString(canvas);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record PublishedPair(Workflow workflow, WorkflowVersion version, Rule rule) { }
}
