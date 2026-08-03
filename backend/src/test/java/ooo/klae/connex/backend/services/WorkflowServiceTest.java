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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.WorkflowCanvas;
import ooo.klae.connex.backend.dto.WorkflowCreateRequest;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowDraftRequest;
import ooo.klae.connex.backend.dto.WorkflowDto;
import ooo.klae.connex.backend.dto.WorkflowPublishRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.services.LegacyWorkflowGraphConverter.ConvertedWorkflow;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;
import ooo.klae.connex.backend.services.WorkflowDraftCanonicalizer.CanonicalDraft;
import ooo.klae.connex.backend.services.WorkflowPrincipalLockService.LockedPrincipals;
import ooo.klae.connex.backend.tenant.Permission;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {

    @Mock private WorkflowMapper workflowMapper;
    @Mock private WorkflowVersionMapper workflowVersionMapper;
    @Mock private RuleMapper ruleMapper;
    @Mock private WorkflowPrincipalLockService principalLockService;
    @Mock private WorkspaceService workspaceService;
    @Mock private AuditService auditService;
    @Mock private WorkflowDefinitionValidator workflowDefinitionValidator;
    @Mock private WorkflowRuntimeProperties runtimeProperties;

    private WorkflowDraftCanonicalizer canonicalizer;
    private LegacyWorkflowGraphConverter graphConverter;
    private WorkflowService service;

    @BeforeEach
    void setUp() {
        canonicalizer = new WorkflowDraftCanonicalizer();
        RuleDefinitionCodec definitionCodec = new RuleDefinitionCodec(new ObjectMapper());
        graphConverter = new LegacyWorkflowGraphConverter(definitionCodec);
        service = new WorkflowService(
            workflowMapper,
            workflowVersionMapper,
            ruleMapper,
            principalLockService,
            workspaceService,
            auditService,
            canonicalizer,
            workflowDefinitionValidator,
            graphConverter,
            definitionCodec,
            new WorkflowVersionProjection(definitionCodec),
            runtimeProperties);
        lenient().when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        lenient().when(workspaceService.getCurrentUserId()).thenReturn(41);
        lenient().when(workflowDefinitionValidator.validateForMutation(
            any(), any(), any())).thenReturn(Set.of());
    }

    @Test
    void createPinsUserIdentityAndCreatesNoRuleOrVersion() throws Exception {
        stubUserMutation(Set.of(41), Set.of(41));
        doAnswer(invocation -> {
            invocation.<Workflow>getArgument(0).setId(101);
            return null;
        }).when(workflowMapper).insert(any(Workflow.class));

        var created = service.create(createRequest("user"));

        ArgumentCaptor<Workflow> workflow = ArgumentCaptor.forClass(Workflow.class);
        verify(workflowMapper).insert(workflow.capture());
        verify(principalLockService).lockUserMutation(7, 41, Set.of(41), Set.of(41));
        assertEquals(101, created.id());
        assertEquals(0, created.draftRevision());
        assertEquals(41, created.runAsUserId());
        assertFalse(created.enabled());
        assertEquals("legacy", created.runtimeOwner());
        assertNull(created.activeVersionId());
        assertNull(workflow.getValue().getLegacyRuleId());
        assertEquals(41, workflow.getValue().getCreatedById());
        verifyNoInteractions(ruleMapper, workflowVersionMapper);
        verify(auditService).record(
            eq("workflow.create"), eq("workflow"), eq(101), eq("Workflow 101"),
            eq("Workflow created"), eq(Map.of("draftRevision", 0, "executionMode", "user")));
    }

    @Test
    void userCreateFailsBeforeInsertionWhenTheCreatorMembershipIsNotActive() throws Exception {
        doThrow(new ForbiddenException("User is not an active workspace member"))
            .when(principalLockService)
            .lockUserMutation(7, 41, Set.of(41), Set.of(41));

        assertThrows(ForbiddenException.class, () -> service.create(createRequest("user")));

        verify(workflowMapper, never()).insert(any());
        verifyNoInteractions(ruleMapper, workflowVersionMapper, auditService);
    }

    @Test
    void systemAuthoringRequiresAdminAndNeverAcceptsRunAsInput() throws Exception {
        stubSystemMutation(Set.of(41));
        doAnswer(invocation -> {
            invocation.<Workflow>getArgument(0).setId(101);
            return null;
        }).when(workflowMapper).insert(any(Workflow.class));

        var created = service.create(createRequest("system"));

        verify(principalLockService).lockSystemMutation(7, 41, Set.of(41));
        verify(principalLockService, never()).lockUserMutation(
            any(Integer.class), any(Integer.class), any(), any());
        assertNull(created.runAsUserId());
    }

    @Test
    void draftSaveUsesCasAndPreservesExactUserRunAs() throws Exception {
        Workflow discovered = workflow("Workflow", "user", 3, 999, null, null, false);
        Workflow locked = workflow("Workflow", "user", 3, 999, null, null, false);
        Workflow saved = workflow("Changed", "user", 4, 999, null, null, false);
        when(workflowMapper.getById(7, 101)).thenReturn(discovered, saved);
        when(workflowMapper.getByIdForUpdate(7, 101)).thenReturn(locked);
        when(workflowMapper.updateDraft(any(Workflow.class), eq(3))).thenReturn(1);
        stubUserMutation(Set.of(41, 999), Set.of(999));

        var response = service.saveDraft(101, draftRequest("Changed", "user", 3));

        ArgumentCaptor<Workflow> replacement = ArgumentCaptor.forClass(Workflow.class);
        verify(workflowMapper).updateDraft(replacement.capture(), eq(3));
        verify(principalLockService).lockUserMutation(
            7, 41, Set.of(41, 999), Set.of(999));
        assertEquals(999, replacement.getValue().getDraftRunAsUserId());
        assertEquals(4, response.draftRevision());
        verify(auditService).record(
            eq("workflow.draft.save"), eq("workflow"), eq(101), eq("Workflow 101"),
            eq("Workflow draft saved"), any());
    }

    @Test
    void switchingToUserLocksAndRequiresTheImmutableCreatorMembership() throws Exception {
        Workflow discovered = workflow("Workflow", "system", 3, null, null, null, false);
        discovered.setCreatedById(55);
        Workflow locked = workflow("Workflow", "system", 3, null, null, null, false);
        locked.setCreatedById(55);
        Workflow saved = workflow("Changed", "user", 4, 55, null, null, false);
        saved.setCreatedById(55);
        when(workflowMapper.getById(7, 101)).thenReturn(discovered, saved);
        when(workflowMapper.getByIdForUpdate(7, 101)).thenReturn(locked);
        when(workflowMapper.updateDraft(any(Workflow.class), eq(3))).thenReturn(1);
        when(principalLockService.lockUserMutation(
            7, 41, Set.of(41, 55), Set.of(55)))
            .thenReturn(principals(Set.of(41, 55), Set.of(41, 55)))
            .thenThrow(new ConflictException(
                "Workflow run-as user is not an active workspace member"));

        service.saveDraft(101, draftRequest("Changed", "user", 3));

        ArgumentCaptor<Workflow> replacement = ArgumentCaptor.forClass(Workflow.class);
        verify(workflowMapper).updateDraft(replacement.capture(), eq(3));
        assertEquals(55, replacement.getValue().getDraftRunAsUserId());
        verify(principalLockService).lockUserMutation(
            7, 41, Set.of(41, 55), Set.of(55));

        Workflow second = workflow("Workflow", "system", 3, null, null, null, false);
        second.setId(102);
        second.setCreatedById(55);
        when(workflowMapper.getById(7, 102)).thenReturn(second);

        assertThrows(
            ConflictException.class,
            () -> service.saveDraft(102, draftRequest("Changed", "user", 3)));
        verify(workflowMapper, never()).getByIdForUpdate(7, 102);
    }

    @Test
    void switchingToSystemRequiresAdminAndClearsTheDraftIdentity() throws Exception {
        Workflow discovered = workflow("Workflow", "user", 3, 55, null, null, false);
        Workflow locked = workflow("Workflow", "user", 3, 55, null, null, false);
        Workflow saved = workflow("Changed", "system", 4, null, null, null, false);
        when(workflowMapper.getById(7, 101)).thenReturn(discovered, saved);
        when(workflowMapper.getByIdForUpdate(7, 101)).thenReturn(locked);
        when(workflowMapper.updateDraft(any(Workflow.class), eq(3))).thenReturn(1);
        stubSystemMutation(Set.of(41, 55));

        service.saveDraft(101, draftRequest("Changed", "system", 3));

        ArgumentCaptor<Workflow> replacement = ArgumentCaptor.forClass(Workflow.class);
        verify(workflowMapper).updateDraft(replacement.capture(), eq(3));
        assertNull(replacement.getValue().getDraftRunAsUserId());
        verify(principalLockService).lockSystemMutation(7, 41, Set.of(41, 55));
    }

    @Test
    void failedCasDistinguishesMissingFromRevisionConflict() throws Exception {
        Workflow discovered = workflow("Workflow", "user", 2, 41, null, null, false);
        Workflow locked = workflow("Workflow", "user", 2, 41, null, null, false);
        when(workflowMapper.getById(7, 101)).thenReturn(discovered);
        when(workflowMapper.getByIdForUpdate(7, 101)).thenReturn(locked);
        when(workflowMapper.updateDraft(any(Workflow.class), eq(2))).thenReturn(0);
        when(workflowMapper.getById(7, 102)).thenReturn(null);
        stubUserMutation(Set.of(41), Set.of(41));

        assertThrows(
            ConflictException.class,
            () -> service.saveDraft(101, draftRequest("Changed", "user", 2)));
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
        verify(workflowDefinitionValidator).validate(
            eq("deal"), eq("user"), any(WorkflowDefinition.class));
        verify(workflowMapper, never()).updateDraft(any(), any(Integer.class));
        verifyNoInteractions(ruleMapper, workflowVersionMapper, auditService);
    }

    @Test
    void validateKeepsBranchingClosedUntilTheCanonicalRuntimeCutover() {
        Workflow workflow = workflow("Workflow", "user", 3, 41, null, null, false);
        CanonicalDraft branching = canonicalizer.canonicalizeDraftJson(
            "Workflow",
            null,
            "deal",
            "user",
            branchingDefinitionJson(),
            branchingCanvasJson());
        workflow.setDraftDefinitionJson(branching.definitionJson());
        workflow.setDraftCanvasJson(branching.canvasJson());
        when(workflowMapper.getById(7, 101)).thenReturn(workflow);

        BadRequestException exception = assertThrows(
            BadRequestException.class,
            () -> service.validate(101));

        assertEquals("Legacy workflow condition no branch must end", exception.getMessage());
        verify(workflowDefinitionValidator).validate(
            eq("deal"), eq("user"), any(WorkflowDefinition.class));
        verifyNoInteractions(ruleMapper, workflowVersionMapper, auditService);
    }

    @Test
    void validateAndPublishRejectDraftsMissingCanvasPositions() {
        Workflow workflow = workflow("Workflow", "user", 3, 41, null, null, false);
        CanonicalDraft incomplete = canonicalizer.canonicalizeDraftJson(
            "Workflow", null, "deal", "user", definitionJson(), missingCanvasJson());
        workflow.setDraftCanvasJson(incomplete.canvasJson());
        when(workflowMapper.getById(7, 101)).thenReturn(workflow, workflow);
        when(workflowMapper.getByIdForUpdate(7, 101)).thenReturn(workflow);
        when(workflowVersionMapper.getLatest(7, 101)).thenReturn(null);
        stubUserMutation(Set.of(41), Set.of(41));

        assertThrows(BadRequestException.class, () -> service.validate(101));
        assertThrows(BadRequestException.class, () -> service.publish(101, publishRequest(3)));

        verify(workflowDefinitionValidator, never()).validate(any(), any(), any());
        verifyNoInteractions(ruleMapper);
        verify(workflowVersionMapper).getLatest(7, 101);
        verify(workflowVersionMapper, never()).getByIdForUpdate(
            any(Integer.class), any(Integer.class), any(Long.class));
        verify(workflowVersionMapper, never()).insert(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void saveDraftRevalidatesSecurityRelevantDiscoveryAfterTheWorkflowLock() throws Exception {
        Workflow discovered = workflow("Workflow", "user", 3, 55, null, null, false);
        Workflow locked = workflow("Workflow", "system", 3, null, null, null, false);
        when(workflowMapper.getById(7, 101)).thenReturn(discovered);
        when(workflowMapper.getByIdForUpdate(7, 101)).thenReturn(locked);
        stubUserMutation(Set.of(41, 55), Set.of(55));

        assertThrows(
            ConflictException.class,
            () -> service.saveDraft(101, draftRequest("Changed", "user", 3)));

        InOrder authorization = inOrder(workflowMapper, principalLockService);
        authorization.verify(workflowMapper).getById(7, 101);
        authorization.verify(principalLockService).lockUserMutation(
            7, 41, Set.of(41, 55), Set.of(55));
        authorization.verify(workflowMapper).getByIdForUpdate(7, 101);
        verify(workflowMapper, never()).updateDraft(any(), any(Integer.class));
    }

    @Test
    void changedPublishLocksDiscoveredVersionsByExactAscendingIdBeforeTheRule() {
        PublishedPair pair = publishedPair("Published", false, 4, "user", 55, 99L);
        pair.workflow().setName("Draft rename");
        WorkflowVersion latest = version(pair.rule(), 88L, 5, 101);
        stubPublishedMutation(pair, latest, true);
        stubUserMutation(Set.of(41, 55), Set.of(55));
        doAnswer(invocation -> {
            invocation.<WorkflowVersion>getArgument(0).setId(111L);
            return null;
        }).when(workflowVersionMapper).insert(any(WorkflowVersion.class));
        when(ruleMapper.update(any(Rule.class))).thenReturn(1);
        when(workflowMapper.advancePublication(7, 101, 77, 99L, 111L, 41, 3)).thenReturn(1);

        service.publish(101, publishRequest(3));

        InOrder locks = inOrder(
            workflowMapper, workflowVersionMapper, ruleMapper, principalLockService);
        locks.verify(workflowMapper).getById(7, 101);
        locks.verify(workflowVersionMapper).getById(7, 101, 99L);
        locks.verify(workflowVersionMapper).getLatest(7, 101);
        locks.verify(ruleMapper).getById(7, 77);
        locks.verify(principalLockService).lockUserMutation(
            7, 41, Set.of(41, 55), Set.of(55));
        locks.verify(workflowMapper).getByIdForUpdate(7, 101);
        locks.verify(workflowVersionMapper).getByIdForUpdate(7, 101, 88L);
        locks.verify(workflowVersionMapper).getByIdForUpdate(7, 101, 99L);
        locks.verify(ruleMapper).getByIdForUpdate(7, 77);
        ArgumentCaptor<WorkflowVersion> inserted = ArgumentCaptor.forClass(WorkflowVersion.class);
        verify(workflowVersionMapper).insert(inserted.capture());
        assertEquals(6, inserted.getValue().getVersionNumber());
    }

    @Test
    void firstPublishCreatesRuleAndVersionThenAssignsBothPointersAtomically() {
        Workflow workflow = workflow("Workflow", "user", 0, 41, null, null, false);
        when(workflowMapper.getById(7, 101)).thenReturn(workflow);
        when(workflowMapper.getByIdForUpdate(7, 101)).thenReturn(workflow);
        when(workflowVersionMapper.getLatest(7, 101)).thenReturn(null);
        stubUserMutation(Set.of(41), Set.of(41));
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
        verify(workflowDefinitionValidator).validateForMutation(
            eq("deal"), eq("user"), any(WorkflowDefinition.class));
        InOrder writes = inOrder(ruleMapper, workflowVersionMapper, workflowMapper);
        writes.verify(ruleMapper).insert(any(Rule.class));
        writes.verify(workflowVersionMapper).insert(any(WorkflowVersion.class));
        writes.verify(workflowMapper).assignFirstPublication(7, 101, 77, 88L, 41, 0);
        verify(principalLockService).lockUserMutation(7, 41, Set.of(41), Set.of(41));
        verify(auditService).record(
            eq("workflow.publish"), eq("workflow"), eq(101), eq("Workflow 101"),
            eq("Workflow published"), eq(Map.of("versionNumber", 1)));
    }

    @Test
    void firstPublishUsesCanonicalOwnershipWithoutCreatingALegacyRuleWhenGateIsEnabled() {
        when(runtimeProperties.enabled()).thenReturn(true);
        Workflow workflow = workflow("Workflow", "user", 0, 41, null, null, false);
        when(workflowMapper.getById(7, 101)).thenReturn(workflow);
        when(workflowMapper.getByIdForUpdate(7, 101)).thenReturn(workflow);
        when(workflowVersionMapper.getLatest(7, 101)).thenReturn(null);
        stubUserMutation(Set.of(41), Set.of(41));
        WorkflowDefinition definition = canonicalizer.parseDefinition(
            workflow.getDraftDefinitionJson());
        CompiledWorkflow compiled = mock(CompiledWorkflow.class);
        when(workflowDefinitionValidator.validate("deal", "user", definition))
            .thenReturn(compiled);
        when(compiled.entryNodeId()).thenReturn("eventSource");
        when(compiled.node("eventSource")).thenReturn(definition.nodes().stream()
            .filter(node -> "eventSource".equals(node.id()))
            .findFirst()
            .orElseThrow());
        when(compiled.topologicalOrder()).thenReturn(
            List.of("eventSource", "notifyOwner", "complete"));
        when(compiled.node("notifyOwner")).thenReturn(definition.nodes().get(1));
        when(compiled.node("complete")).thenReturn(definition.nodes().get(2));
        when(compiled.enrollmentConditionNodeId()).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<WorkflowVersion>getArgument(0).setId(88L);
            return null;
        }).when(workflowVersionMapper).insert(any(WorkflowVersion.class));
        when(workflowMapper.assignFirstCanonicalPublication(
            7, 101, 88L, 41, 0)).thenReturn(1);

        WorkflowDto published = service.publish(101, publishRequest(0));

        assertEquals("canonical", published.runtimeOwner());
        assertEquals(88L, published.activeVersionId());
        verify(ruleMapper, never()).insert(any());
        verify(ruleMapper, never()).update(any());
        verify(workflowMapper).assignFirstCanonicalPublication(
            7, 101, 88L, 41, 0);
    }

    @Test
    void publishChecksActionRequirementsAgainstTheLockedPermissionSet() {
        Workflow workflow = workflow("Workflow", "user", 0, 41, null, null, false);
        when(workflowMapper.getById(7, 101)).thenReturn(workflow);
        when(workflowMapper.getByIdForUpdate(7, 101)).thenReturn(workflow);
        when(workflowVersionMapper.getLatest(7, 101)).thenReturn(null);
        when(principalLockService.lockUserMutation(7, 41, Set.of(41), Set.of(41)))
            .thenReturn(new LockedPrincipals(
                Set.of(41), Set.of(41), Set.of(Permission.RULE_MANAGE)));
        when(workflowDefinitionValidator.validateForMutation(
            any(), any(), any())).thenReturn(Set.of(Permission.TASK_CREATE));

        assertThrows(
            ForbiddenException.class,
            () -> service.publish(101, publishRequest(0)));

        verify(ruleMapper, never()).insert(any());
        verify(workflowVersionMapper, never()).insert(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void userPublishLocksTheExactPersistedDraftIdentityAndRejectsMissingIdentity() {
        Workflow inactive = workflow("Workflow", "user", 0, 999, null, null, false);
        when(workflowMapper.getById(7, 101)).thenReturn(inactive);
        when(workflowVersionMapper.getLatest(7, 101)).thenReturn(null);
        doThrow(new ConflictException(
            "Workflow run-as user is not an active workspace member"))
            .when(principalLockService)
            .lockUserMutation(7, 41, Set.of(41, 999), Set.of(999));

        assertThrows(ConflictException.class, () -> service.publish(101, publishRequest(0)));

        verify(principalLockService).lockUserMutation(
            7, 41, Set.of(41, 999), Set.of(999));
        verify(principalLockService, never()).lockUserMutation(
            7, 41, Set.of(41, 999), Set.of(41));
        verify(workflowMapper, never()).getByIdForUpdate(7, 101);
        verifyNoInteractions(ruleMapper);
        verify(workflowVersionMapper, never()).insert(any());
        verifyNoInteractions(auditService);

        Workflow missing = workflow("Workflow", "user", 0, null, null, null, false);
        missing.setId(102);
        when(workflowMapper.getById(7, 102)).thenReturn(missing);
        when(workflowVersionMapper.getLatest(7, 102)).thenReturn(null);

        assertThrows(ConflictException.class, () -> service.publish(102, publishRequest(0)));
        verify(workflowMapper, never()).getByIdForUpdate(7, 102);
        verifyNoInteractions(ruleMapper);
        verify(workflowVersionMapper, never()).insert(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void systemPublishRequiresAdminForTheCurrentActor() {
        Workflow workflow = workflow("Workflow", "system", 0, null, null, null, false);
        when(workflowMapper.getById(7, 101)).thenReturn(workflow);
        when(workflowVersionMapper.getLatest(7, 101)).thenReturn(null);
        doThrow(new ForbiddenException("Requires admin role"))
            .when(principalLockService)
            .lockSystemMutation(7, 41, Set.of(41));

        assertThrows(ForbiddenException.class, () -> service.publish(101, publishRequest(0)));

        verify(principalLockService).lockSystemMutation(7, 41, Set.of(41));
        verify(workflowMapper, never()).getByIdForUpdate(7, 101);
        verifyNoInteractions(ruleMapper);
        verify(workflowVersionMapper, never()).insert(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void identicalPublishReusesTheActiveVersionAndRefusesRuleDrift() {
        PublishedPair pair = publishedPair("Workflow", false, 4);
        stubPublishedMutation(pair, pair.version(), true);
        stubUserMutation(Set.of(41), Set.of(41));

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
        verify(workflowVersionMapper, times(2)).getLatest(7, 101);
        verify(workflowVersionMapper, never()).insert(any());
        verify(ruleMapper, never()).update(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void changedPublishAppendsLatestThenSynchronizesRuleAndPointer() {
        PublishedPair pair = publishedPair("Published", false, 4);
        pair.workflow().setName("Draft rename");
        stubPublishedMutation(pair, pair.version(), true);
        stubUserMutation(Set.of(41), Set.of(41));
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
        stubPublishedMutation(pair, pair.version(), true);
        stubUserMutation(Set.of(41), Set.of(41));
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
        when(workflowMapper.getById(7, 101)).thenReturn(workflow);
        when(workflowMapper.getByIdForUpdate(7, 101)).thenReturn(workflow);
        when(workflowVersionMapper.getLatest(7, 101)).thenReturn(null);
        stubUserMutation(Set.of(41), Set.of(41));
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
    void systemPublishFailsClosedWhenThePersistedCreatorWasErased() {
        Workflow workflow = workflow("Workflow", "system", 0, null, null, null, false);
        workflow.setCreatedById(null);
        when(workflowMapper.getById(7, 101)).thenReturn(workflow);
        when(workflowVersionMapper.getLatest(7, 101)).thenReturn(null);
        when(principalLockService.lockSystemMutation(7, 41, Set.of(41)))
            .thenReturn(principals(Set.of(41), Set.of(41)));

        assertThrows(
            ConflictException.class,
            () -> service.publish(101, publishRequest(0)));

        verify(workflowMapper, never()).getByIdForUpdate(7, 101);
        verifyNoInteractions(auditService);
    }

    @Test
    void systemEnableLocksAndRevalidatesTheActiveCreatorBeforeTheWorkflow() {
        PublishedPair pair = publishedPair("Workflow", false, 4, "system", null, 88L);
        pair.version().setCreatedById(55);
        pair.rule().setCreatedById(55);
        when(workflowMapper.getById(7, 101)).thenReturn(pair.workflow());
        when(workflowVersionMapper.getById(7, 101, 88L)).thenReturn(pair.version());
        when(ruleMapper.getById(7, 77)).thenReturn(pair.rule());
        when(principalLockService.lockSystemMutation(7, 41, Set.of(41, 55)))
            .thenReturn(principals(Set.of(41, 55), Set.of(41)));

        assertThrows(ConflictException.class, () -> service.enable(101));

        InOrder order = inOrder(
            workflowMapper, workflowVersionMapper, ruleMapper, principalLockService);
        order.verify(workflowMapper).getById(7, 101);
        order.verify(workflowVersionMapper).getById(7, 101, 88L);
        order.verify(ruleMapper).getById(7, 77);
        order.verify(principalLockService).lockSystemMutation(7, 41, Set.of(41, 55));
        verify(workflowMapper, never()).getByIdForUpdate(7, 101);
    }

    @Test
    void userPublishAllowsMissingImmutableAttributionWithAnActiveRunAs() {
        PublishedPair pair = publishedPair("Workflow", false, 4, "user", 55, 88L);
        pair.workflow().setName("Recovered");
        pair.workflow().setCreatedById(null);
        pair.version().setCreatedById(66);
        pair.version().setPublishedById(67);
        pair.rule().setCreatedById(null);
        stubPublishedMutation(pair, pair.version(), true);
        when(principalLockService.lockUserMutation(
                7, 41, Set.of(41, 55, 66, 67), Set.of(55)))
            .thenReturn(principals(Set.of(41, 55, 66, 67), Set.of(41, 55)));
        doAnswer(invocation -> {
            invocation.<WorkflowVersion>getArgument(0).setId(99L);
            return null;
        }).when(workflowVersionMapper).insert(any(WorkflowVersion.class));
        when(ruleMapper.update(any(Rule.class))).thenReturn(1);
        when(workflowMapper.advancePublication(7, 101, 77, 88L, 99L, 41, 3)).thenReturn(1);

        var published = service.publish(101, publishRequest(3));

        assertEquals(99L, published.activeVersionId());
        ArgumentCaptor<Rule> rule = ArgumentCaptor.forClass(Rule.class);
        ArgumentCaptor<WorkflowVersion> version = ArgumentCaptor.forClass(WorkflowVersion.class);
        verify(ruleMapper).update(rule.capture());
        verify(workflowVersionMapper).insert(version.capture());
        assertEquals("user", rule.getValue().getExecutionMode());
        assertEquals(55, rule.getValue().getRunAsUserId());
        assertNull(rule.getValue().getCreatedById());
        assertEquals("user", version.getValue().getExecutionMode());
        assertEquals(55, version.getValue().getRunAsUserId());
        assertNull(version.getValue().getCreatedById());
        assertEquals(41, version.getValue().getPublishedById());
    }

    @Test
    void userEnableAllowsRedactedCreatorAndMissingPublisherWithAnActiveRunAs() {
        PublishedPair pair = publishedPair("Workflow", false, 4, "user", 55, 88L);
        pair.workflow().setCreatedById(null);
        pair.version().setCreatedById(66);
        pair.version().setPublishedById(67);
        pair.rule().setCreatedById(null);
        stubPublishedMutation(pair, null, false);
        when(principalLockService.lockUserMutation(
                7, 41, Set.of(41, 55, 66, 67), Set.of(55)))
            .thenReturn(principals(Set.of(41, 55, 66, 67), Set.of(41, 55)));
        when(ruleMapper.updateEnabled(7, 77, true)).thenReturn(1);
        when(workflowMapper.updateLifecycle(7, 101, true, 41)).thenReturn(1);

        var enabled = service.enable(101);

        assertTrue(enabled.enabled());
        verify(ruleMapper).updateEnabled(7, 77, true);
        verify(workflowMapper).updateLifecycle(7, 101, true, 41);
    }

    @Test
    void enableAndDisableSynchronizeBothStatesAndAuditOnlyChanges() {
        PublishedPair disabled = publishedPair("Workflow", false, 4);
        stubPublishedMutation(disabled, null, false);
        stubUserMutation(Set.of(41), Set.of(41));
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
        draftOnly.setId(102);
        when(workflowMapper.getById(7, 102)).thenReturn(draftOnly);
        when(workflowMapper.getByIdForUpdate(7, 102)).thenReturn(draftOnly);
        stubUserMutation(Set.of(41), Set.of());

        service.disable(102);
        assertThrows(ConflictException.class, () -> service.enable(102));
        verify(ruleMapper, never()).updateEnabled(7, 77, false);
        verify(workflowMapper, never()).updateLifecycle(7, 102, false, 41);
        verify(auditService, never()).record(
            eq("workflow.disable"), any(), any(), any(), any(), any());
    }

    @Test
    void enableLocksTheExactPublishedUserIdentityAndNoOpCreatesNoAuditOrWrite() {
        PublishedPair inactive = publishedPair("Workflow", false, 4, "user", 999, 88L);
        when(workflowMapper.getById(7, 101)).thenReturn(inactive.workflow());
        when(workflowVersionMapper.getById(7, 101, 88L)).thenReturn(inactive.version());
        when(ruleMapper.getById(7, 77)).thenReturn(inactive.rule());
        doThrow(new ConflictException(
            "Workflow run-as user is not an active workspace member"))
            .when(principalLockService)
            .lockUserMutation(7, 41, Set.of(41, 999), Set.of(999));

        assertThrows(ConflictException.class, () -> service.enable(101));

        verify(principalLockService).lockUserMutation(
            7, 41, Set.of(41, 999), Set.of(999));
        verify(principalLockService, never()).lockUserMutation(
            7, 41, Set.of(41, 999), Set.of(41));
        verify(workflowMapper, never()).getByIdForUpdate(7, 101);
        verify(ruleMapper, never()).updateEnabled(7, 77, true);
        verify(workflowMapper, never()).updateLifecycle(7, 101, true, 41);
        verifyNoInteractions(auditService);

        PublishedPair alreadyEnabled = publishedPair("Workflow", true, 4);
        alreadyEnabled.workflow().setId(102);
        alreadyEnabled.version().setWorkflowId(102);
        stubPublishedMutation(alreadyEnabled, null, false);
        stubUserMutation(Set.of(41), Set.of(41));

        var unchanged = service.enable(102);

        assertTrue(unchanged.enabled());
        verify(ruleMapper, never()).updateEnabled(7, 77, true);
        verify(workflowMapper, never()).updateLifecycle(7, 102, true, 41);
        verifyNoInteractions(auditService);
    }

    @Test
    void userEnableRequiresTheExactActiveRunAsUnderTheSharedLocks() {
        PublishedPair pair = publishedPair("Workflow", false, 4, "user", 55, 88L);
        stubPublishedMutation(pair, null, false);
        stubUserMutation(Set.of(41, 55), Set.of(55));
        when(ruleMapper.updateEnabled(7, 77, true)).thenReturn(1);
        when(workflowMapper.updateLifecycle(7, 101, true, 41)).thenReturn(1);

        var enabled = service.enable(101);

        verify(principalLockService).lockUserMutation(
            7, 41, Set.of(41, 55), Set.of(55));
        assertTrue(enabled.enabled());
        InOrder writes = inOrder(ruleMapper, workflowMapper);
        writes.verify(ruleMapper).updateEnabled(7, 77, true);
        writes.verify(workflowMapper).updateLifecycle(7, 101, true, 41);
    }

    @Test
    void disableSynchronizesThePublishedRuleBeforeTheWorkflow() {
        PublishedPair enabled = publishedPair("Workflow", true, 4);
        stubPublishedMutation(enabled, null, false);
        stubUserMutation(Set.of(41), Set.of());
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
        PublishedPair enabled = publishedPair("Workflow", true, 4, "user", null, 88L);
        stubPublishedMutation(enabled, null, false);
        stubUserMutation(Set.of(41), Set.of());
        when(ruleMapper.updateEnabled(7, 77, false)).thenReturn(1);
        when(workflowMapper.updateLifecycle(7, 101, false, 41)).thenReturn(1);

        var disabled = service.disable(101);
        enabled.rule().setEnabled(false);
        var unchanged = service.disable(101);

        assertFalse(disabled.enabled());
        assertFalse(unchanged.enabled());
        verify(principalLockService, times(2)).lockUserMutation(
            7, 41, Set.of(41), Set.of());
        verify(principalLockService, never()).lockSystemMutation(
            any(Integer.class), any(Integer.class), any());
        verify(ruleMapper, times(1)).updateEnabled(7, 77, false);
        verify(workflowMapper, times(1)).updateLifecycle(7, 101, false, 41);
        verify(auditService, times(1)).record(
            eq("workflow.disable"), eq("workflow"), eq(101), eq("Workflow 101"),
            eq("Workflow disabled"), any());
    }

    @Test
    void systemEnableRequiresAdminButDisableBypassesIdentityAndAdminChecks() {
        PublishedPair systemDisabled = publishedPair(
            "Workflow", false, 4, "system", null, 88L);
        when(workflowMapper.getById(7, 101)).thenReturn(systemDisabled.workflow());
        when(workflowVersionMapper.getById(7, 101, 88L)).thenReturn(systemDisabled.version());
        when(ruleMapper.getById(7, 77)).thenReturn(systemDisabled.rule());
        doThrow(new ForbiddenException("Requires admin role"))
            .when(principalLockService)
            .lockSystemMutation(7, 41, Set.of(41));

        assertThrows(ForbiddenException.class, () -> service.enable(101));
        verify(ruleMapper, never()).updateEnabled(7, 77, true);
        verifyNoInteractions(auditService);

        PublishedPair systemEnabled = publishedPair(
            "Workflow", true, 4, "system", null, 88L);
        systemEnabled.workflow().setId(102);
        systemEnabled.version().setWorkflowId(102);
        stubPublishedMutation(systemEnabled, null, false);
        stubUserMutation(Set.of(41), Set.of());
        when(ruleMapper.updateEnabled(7, 77, false)).thenReturn(1);
        when(workflowMapper.updateLifecycle(7, 102, false, 41)).thenReturn(1);

        var disabled = service.disable(102);

        assertFalse(disabled.enabled());
        verify(principalLockService).lockSystemMutation(7, 41, Set.of(41));
        verify(principalLockService).lockUserMutation(7, 41, Set.of(41), Set.of());
        verify(auditService).record(
            eq("workflow.disable"), eq("workflow"), eq(102), eq("Workflow 102"),
            eq("Workflow disabled"), any());
    }

    @Test
    void disableRemainsPossibleWhenLegacyExecutionIdentitiesAreMissing() {
        PublishedPair pair = publishedPair("Workflow", true, 4, "user", null, 88L);
        pair.workflow().setCreatedById(null);
        pair.workflow().setUpdatedById(999);
        pair.version().setCreatedById(null);
        pair.version().setPublishedById(999);
        pair.version().setRunAsUserId(999);
        pair.rule().setCreatedById(null);
        pair.rule().setRunAsUserId(null);
        stubPublishedMutation(pair, null, false);
        when(principalLockService.lockUserMutation(7, 41, Set.of(999), Set.of()))
            .thenReturn(principals(Set.of(41, 999), Set.of(41)));
        when(ruleMapper.updateEnabled(7, 77, false)).thenReturn(1);
        when(workflowMapper.updateLifecycle(7, 101, false, 41)).thenReturn(1);

        var disabled = service.disable(101);

        assertFalse(disabled.enabled());
        verify(ruleMapper).updateEnabled(7, 77, false);
        verify(workflowMapper).updateLifecycle(7, 101, false, 41);
    }

    @Test
    void archiveDisablesLegacyProjectionAndRestoreLeavesWorkflowDisabled() {
        PublishedPair pair = publishedPair("Workflow", true, 4);
        stubPublishedMutation(pair, null, false);
        stubUserMutation(Set.of(41), Set.of());
        when(ruleMapper.updateEnabled(7, 77, false)).thenReturn(1);
        when(workflowMapper.archive(7, 101, 41)).thenReturn(1);

        WorkflowDto archived = service.archive(101);

        assertFalse(archived.enabled());
        assertNotNull(archived.archivedAt());
        InOrder archiveWrites = inOrder(ruleMapper, workflowMapper);
        archiveWrites.verify(ruleMapper).updateEnabled(7, 77, false);
        archiveWrites.verify(workflowMapper).archive(7, 101, 41);
        verify(auditService).record(
            eq("workflow.archive"), eq("workflow"), eq(101), eq("Workflow 101"),
            eq("Workflow archived"), any());

        pair.workflow().setEnabled(false);
        pair.rule().setEnabled(false);
        pair.workflow().setArchivedAt(LocalDateTime.of(2026, 8, 2, 12, 0));
        when(workflowMapper.restore(7, 101, 41)).thenReturn(1);

        WorkflowDto restored = service.restore(101);

        assertFalse(restored.enabled());
        assertNull(restored.archivedAt());
        verify(workflowMapper).restore(7, 101, 41);
        verify(auditService).record(
            eq("workflow.restore"), eq("workflow"), eq(101), eq("Workflow 101"),
            eq("Workflow restored"), any());
    }

    @Test
    void archivedWorkflowRemainsReadableButRejectsEveryMutableLifecycleOperation() {
        Workflow archived = workflow("Workflow", "user", 3, 41, null, null, false);
        archived.setArchivedAt(LocalDateTime.of(2026, 8, 2, 12, 0));
        when(workflowMapper.getById(7, 101)).thenReturn(archived);

        WorkflowDto readable = service.getById(101);

        assertNotNull(readable.archivedAt());
        assertThrows(
            ConflictException.class,
            () -> service.saveDraft(101, draftRequest("Changed", "user", 3)));
        assertThrows(ConflictException.class, () -> service.validate(101));
        assertThrows(
            ConflictException.class,
            () -> service.publish(101, publishRequest(3)));
        assertThrows(ConflictException.class, () -> service.enable(101));
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

        assertNotNull(validate.getAnnotation(Transactional.class));
        assertNotNull(publish.getAnnotation(Transactional.class));
        assertNotNull(saveDraft.getAnnotation(Transactional.class));
        assertTrue(validate.getAnnotation(Transactional.class).readOnly());
        assertFalse(publish.getAnnotation(Transactional.class).readOnly());
        assertFalse(saveDraft.getAnnotation(Transactional.class).readOnly());
    }

    private void stubUserMutation(
            Set<Integer> principalIds, Set<Integer> requiredActiveIds) {
        TreeSet<Integer> requestedIds = new TreeSet<>(principalIds);
        requestedIds.add(41);
        requestedIds.addAll(requiredActiveIds);
        when(principalLockService.lockUserMutation(
            7, 41, principalIds, requiredActiveIds))
            .thenReturn(principals(requestedIds, requestedIds));
    }

    private void stubSystemMutation(Set<Integer> principalIds) {
        TreeSet<Integer> requestedIds = new TreeSet<>(principalIds);
        requestedIds.add(41);
        when(principalLockService.lockSystemMutation(7, 41, principalIds))
            .thenReturn(principals(requestedIds, requestedIds));
    }

    private void stubPublishedMutation(
            PublishedPair pair, WorkflowVersion latest, boolean publish) {
        when(workflowMapper.getById(7, pair.workflow().getId())).thenReturn(pair.workflow());
        when(workflowMapper.getByIdForUpdate(7, pair.workflow().getId())).thenReturn(pair.workflow());
        when(workflowVersionMapper.getById(
            7, pair.workflow().getId(), pair.version().getId())).thenReturn(pair.version());
        when(workflowVersionMapper.getByIdForUpdate(
            7, pair.workflow().getId(), pair.version().getId())).thenReturn(pair.version());
        when(ruleMapper.getById(7, pair.rule().getId())).thenReturn(pair.rule());
        when(ruleMapper.getByIdForUpdate(7, pair.rule().getId())).thenReturn(pair.rule());
        if (publish) {
            when(workflowVersionMapper.getLatest(7, pair.workflow().getId())).thenReturn(latest);
            if (latest != null && latest.getId() != pair.version().getId()) {
                when(workflowVersionMapper.getByIdForUpdate(
                    7, pair.workflow().getId(), latest.getId())).thenReturn(latest);
            }
        }
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
        workflow.setRuntimeOwner("legacy");
        workflow.setArchivedAt(null);
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
        return publishedPair(publishedName, enabled, versionNumber, "user", 41, 88L);
    }

    private PublishedPair publishedPair(
            String publishedName,
            boolean enabled,
            int versionNumber,
            String executionMode,
            Integer runAsUserId,
            long versionId) {
        Workflow workflow = workflow(
            "Workflow", executionMode, 3, runAsUserId, 77, versionId, enabled);
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
        WorkflowVersion version = version(rule, versionId, versionNumber, 101);
        version.setDefinitionJson(published.definitionJson());
        version.setCanvasJson(published.canvasJson());
        version.setDefinitionHash(published.definitionHash());
        return new PublishedPair(workflow, version, rule);
    }

    private WorkflowVersion version(
            Rule rule, long id, int versionNumber, int workflowId) {
        WorkflowVersion version = new WorkflowVersion();
        version.setId(id);
        version.setWorkspaceId(7);
        version.setWorkflowId(workflowId);
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
        version.setDefinitionJson(canonicalizer.canonicalizeDraftJson(
            rule.getName(), rule.getDescription(), rule.getRecordType(), rule.getExecutionMode(),
            definitionJson(), canvasJson()).definitionJson());
        version.setCanvasJson(canvasJson());
        version.setDefinitionHash(canonicalizer.canonicalizeDraftJson(
            rule.getName(), rule.getDescription(), rule.getRecordType(), rule.getExecutionMode(),
            definitionJson(), canvasJson()).definitionHash());
        return version;
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

    private static String branchingDefinitionJson() {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of("deal.won"));
        SegmentCondition field = new SegmentCondition();
        field.setType("field");
        field.setField("name");
        field.setOp("contains");
        field.setValue("Acme");
        SegmentDefinition condition = new SegmentDefinition();
        condition.setMatch("all");
        condition.setConditions(List.of(field));
        RuleAction yesAction = new RuleAction();
        yesAction.setType("notify");
        yesAction.setTitle("Yes branch");
        RuleAction noAction = new RuleAction();
        noAction.setType("notify");
        noAction.setTitle("No branch");
        WorkflowDefinition definition = new WorkflowDefinition(
            1,
            "trigger",
            List.of(
                new ooo.klae.connex.backend.dto.WorkflowNode.Trigger("trigger", trigger),
                new ooo.klae.connex.backend.dto.WorkflowNode.Condition("condition", condition),
                new ooo.klae.connex.backend.dto.WorkflowNode.Action("yesAction", yesAction),
                new ooo.klae.connex.backend.dto.WorkflowNode.Action("noAction", noAction),
                new ooo.klae.connex.backend.dto.WorkflowNode.End("end")),
            List.of(
                new ooo.klae.connex.backend.dto.WorkflowEdge(
                    "trigger-condition", "trigger", "condition",
                    ooo.klae.connex.backend.dto.WorkflowEdge.Outcome.NEXT),
                new ooo.klae.connex.backend.dto.WorkflowEdge(
                    "condition-yes", "condition", "yesAction",
                    ooo.klae.connex.backend.dto.WorkflowEdge.Outcome.YES),
                new ooo.klae.connex.backend.dto.WorkflowEdge(
                    "condition-no", "condition", "noAction",
                    ooo.klae.connex.backend.dto.WorkflowEdge.Outcome.NO),
                new ooo.klae.connex.backend.dto.WorkflowEdge(
                    "yes-end", "yesAction", "end",
                    ooo.klae.connex.backend.dto.WorkflowEdge.Outcome.NEXT),
                new ooo.klae.connex.backend.dto.WorkflowEdge(
                    "no-end", "noAction", "end",
                    ooo.klae.connex.backend.dto.WorkflowEdge.Outcome.NEXT)));
        try {
            return JsonMapper.builder().build().writeValueAsString(definition);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String branchingCanvasJson() {
        WorkflowCanvas canvas = new WorkflowCanvas(
            Map.of(
                "trigger", new WorkflowCanvas.Position(BigDecimal.ZERO, BigDecimal.ZERO),
                "condition", new WorkflowCanvas.Position(BigDecimal.valueOf(240), BigDecimal.ZERO),
                "yesAction", new WorkflowCanvas.Position(
                    BigDecimal.valueOf(480), BigDecimal.valueOf(-120)),
                "noAction", new WorkflowCanvas.Position(
                    BigDecimal.valueOf(480), BigDecimal.valueOf(120)),
                "end", new WorkflowCanvas.Position(BigDecimal.valueOf(720), BigDecimal.ZERO)),
            new WorkflowCanvas.Viewport(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE));
        try {
            return JsonMapper.builder().build().writeValueAsString(canvas);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static LockedPrincipals principals(Set<Integer> requested, Set<Integer> existing) {
        return new LockedPrincipals(requested, existing);
    }

    private record PublishedPair(Workflow workflow, WorkflowVersion version, Rule rule) { }
}
