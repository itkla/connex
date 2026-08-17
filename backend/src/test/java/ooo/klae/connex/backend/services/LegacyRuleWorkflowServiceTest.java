package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleRequest;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.WorkflowCanvas;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.services.LegacyWorkflowGraphConverter.ConvertedWorkflow;
import ooo.klae.connex.backend.services.WorkflowDraftCanonicalizer.CanonicalDraft;
import ooo.klae.connex.backend.services.WorkflowPrincipalLockService.LockedPrincipals;

@ExtendWith(MockitoExtension.class)
class LegacyRuleWorkflowServiceTest {

    @Mock private RuleMapper ruleMapper;
    @Mock private WorkflowMapper workflowMapper;
    @Mock private WorkflowVersionMapper workflowVersionMapper;
    @Mock private WorkflowPrincipalLockService principalLockService;
    @Mock private RuleDefinitionValidator definitionValidator;

    private RuleDefinitionCodec definitionCodec;
    private LegacyWorkflowGraphConverter graphConverter;
    private WorkflowDraftCanonicalizer canonicalizer;
    private LegacyRuleWorkflowService service;

    @BeforeEach
    void setUp() {
        definitionCodec = new RuleDefinitionCodec(new ObjectMapper());
        graphConverter = new LegacyWorkflowGraphConverter(definitionCodec);
        canonicalizer = new WorkflowDraftCanonicalizer();
        service = new LegacyRuleWorkflowService(
            ruleMapper,
            workflowMapper,
            workflowVersionMapper,
            principalLockService,
            definitionValidator,
            definitionCodec,
            graphConverter,
            canonicalizer);
        lenient().when(definitionValidator.normalize(any())).thenAnswer(invocation -> {
            String value = invocation.getArgument(0);
            return value == null ? null : value.trim().toLowerCase();
        });
        lenient().when(definitionValidator.validateForMutation(any())).thenReturn(Set.of());
        lenient().when(principalLockService.lockUserMutation(
                anyInt(), anyInt(), any(), any())).thenAnswer(invocation ->
                    lockedPrincipals(
                        invocation.getArgument(1), invocation.getArgument(2)));
        lenient().when(principalLockService.lockSystemMutation(
                anyInt(), anyInt(), any())).thenAnswer(invocation ->
                    lockedPrincipals(
                        invocation.getArgument(1), invocation.getArgument(2)));
    }

    @Test
    void createPersistsRuleWorkflowAndVersionBeforeActivation() {
        AtomicReference<Rule> insertedRule = new AtomicReference<>();
        doAnswer(invocation -> {
            Rule rule = invocation.getArgument(0);
            rule.setId(23);
            insertedRule.set(rule);
            return null;
        }).when(ruleMapper).insert(any(Rule.class));
        doAnswer(invocation -> {
            invocation.<Workflow>getArgument(0).setId(101);
            return null;
        }).when(workflowMapper).insert(any(Workflow.class));
        doAnswer(invocation -> {
            invocation.<WorkflowVersion>getArgument(0).setId(202L);
            return null;
        }).when(workflowVersionMapper).insert(any(WorkflowVersion.class));
        when(workflowMapper.updateActiveVersion(7, 101, 202L, 9)).thenReturn(1);
        when(workflowMapper.updateLifecycle(7, 101, true, 9)).thenReturn(1);
        when(ruleMapper.getById(7, 23)).thenAnswer(invocation -> insertedRule.get());

        Rule created = service.create(7, 9, request("Rule", true, "user", "deal.won"));

        assertEquals(23, created.getId());
        assertEquals(9, created.getRunAsUserId());
        ArgumentCaptor<Workflow> workflow = ArgumentCaptor.forClass(Workflow.class);
        ArgumentCaptor<WorkflowVersion> version = ArgumentCaptor.forClass(WorkflowVersion.class);
        verify(workflowMapper).insert(workflow.capture());
        verify(workflowVersionMapper).insert(version.capture());
        assertEquals(1, workflow.getValue().getDraftRevision());
        assertNull(workflow.getValue().getActiveVersionId());
        assertEquals(1, version.getValue().getVersionNumber());
        assertEquals(9, version.getValue().getPublishedById());
        InOrder order = inOrder(
            principalLockService, ruleMapper, workflowMapper, workflowVersionMapper);
        order.verify(principalLockService).lockUserMutation(
            7, 9, Set.of(9), Set.of(9));
        order.verify(ruleMapper).insert(any(Rule.class));
        order.verify(workflowMapper).insert(any(Workflow.class));
        order.verify(workflowVersionMapper).insert(any(WorkflowVersion.class));
        order.verify(workflowMapper).updateActiveVersion(7, 101, 202L, 9);
        order.verify(workflowMapper).updateLifecycle(7, 101, true, 9);
    }

    @Test
    void semanticNoOpPreservesDraftGraphAndCreatesNoVersion() {
        PersistedAggregate aggregate = persistedAggregate(true);
        stubAggregate(aggregate);

        service.update(7, 9, 23, request("Rule", true, "user", "deal.won"));

        verify(workflowVersionMapper, never()).insert(any());
        verify(ruleMapper, never()).update(any());
        verify(ruleMapper, never()).updateEnabled(anyInt(), anyInt(), eq(false));
        verify(workflowMapper, never()).replaceLegacyPublication(
            any(), anyLong(), anyInt(), anyLong(), anyInt());
        verify(workflowMapper, never()).updateLifecycle(anyInt(), anyInt(), eq(false), anyInt());
    }

    @Test
    void normalizedDiscriminatorsPreserveCustomPublishedGraphAndCreateNoVersion() {
        PersistedAggregate aggregate = persistedAggregate(true);
        customizePublishedGraph(aggregate);
        stubAggregate(aggregate);
        RuleRequest request = request("Rule", true, "user", "deal.won");
        request.getTrigger().setType(" ENTITY_CHANGE ");
        request.getActions().getFirst().setType(" Notify ");

        service.update(7, 9, 23, request);

        verify(workflowVersionMapper, never()).insert(any());
        verify(ruleMapper, never()).update(any());
        verify(workflowMapper, never()).replaceLegacyPublication(
            any(), anyLong(), anyInt(), anyLong(), anyInt());
    }

    @Test
    void enabledOnlyUpdateCreatesNoVersionAndSynchronizesBothRows() {
        PersistedAggregate aggregate = persistedAggregate(true);
        stubAggregate(aggregate);
        when(ruleMapper.updateEnabled(7, 23, false)).thenReturn(1);
        when(workflowMapper.updateLifecycle(7, 101, false, 9)).thenReturn(1);

        service.update(7, 9, 23, request("Rule", false, "user", "deal.won"));

        verify(workflowVersionMapper, never()).insert(any());
        verify(ruleMapper, never()).update(any());
        InOrder order = inOrder(ruleMapper, workflowMapper);
        order.verify(ruleMapper).updateEnabled(7, 23, false);
        order.verify(workflowMapper).updateLifecycle(7, 101, false, 9);
    }

    @Test
    void aChangedResponseDeadlineIsASemanticUpdateNotANoOp() {
        PersistedAggregate aggregate = persistedAggregate(true);
        stubAggregate(aggregate);
        doAnswer(invocation -> {
            invocation.<WorkflowVersion>getArgument(0).setId(303L);
            return null;
        }).when(workflowVersionMapper).insert(any(WorkflowVersion.class));
        when(ruleMapper.update(any(Rule.class))).thenReturn(1);
        when(workflowMapper.replaceLegacyPublication(
                any(Workflow.class), eq(303L), eq(23), eq(202L), eq(4)))
            .thenReturn(1);
        RuleRequest request = request("Rule", true, "user", "deal.won");
        request.getActions().getFirst().setDueInHours(8);

        service.update(7, 9, 23, request);

        verify(workflowVersionMapper).insert(any(WorkflowVersion.class));
        verify(ruleMapper).update(any(Rule.class));
    }

    @Test
    void semanticUpdatePreservesRunAsAndCreatesOneDeterministicVersion() {
        PersistedAggregate aggregate = persistedAggregate(true);
        stubAggregate(aggregate);
        doAnswer(invocation -> {
            invocation.<WorkflowVersion>getArgument(0).setId(303L);
            return null;
        }).when(workflowVersionMapper).insert(any(WorkflowVersion.class));
        when(ruleMapper.update(any(Rule.class))).thenReturn(1);
        when(workflowMapper.replaceLegacyPublication(
                any(Workflow.class), eq(303L), eq(23), eq(202L), eq(4)))
            .thenReturn(1);

        RuleRequest request = request("Renamed", true, "user", "deal.lost");
        request.setDescription("   ");

        service.update(7, 9, 23, request);

        ArgumentCaptor<Rule> rule = ArgumentCaptor.forClass(Rule.class);
        ArgumentCaptor<WorkflowVersion> version = ArgumentCaptor.forClass(WorkflowVersion.class);
        ArgumentCaptor<Workflow> workflow = ArgumentCaptor.forClass(Workflow.class);
        verify(ruleMapper).update(rule.capture());
        verify(workflowVersionMapper).insert(version.capture());
        verify(workflowMapper).replaceLegacyPublication(
            workflow.capture(), eq(303L), eq(23), eq(202L), eq(4));
        assertEquals(1, rule.getValue().getRunAsUserId());
        assertEquals(2, version.getValue().getVersionNumber());
        assertEquals(1, version.getValue().getRunAsUserId());
        assertEquals(9, version.getValue().getPublishedById());
        assertEquals("   ", version.getValue().getDescription());
        assertEquals("Renamed", workflow.getValue().getName());
        assertEquals("   ", workflow.getValue().getDescription());
        assertEquals("user", workflow.getValue().getDraftExecutionMode());
    }

    @Test
    void systemToUserUsesActiveCreatorAsTheRequiredRunAsIdentity() {
        PersistedAggregate aggregate = persistedAggregate(true);
        aggregate.rule().setExecutionMode("system");
        aggregate.rule().setRunAsUserId(null);
        aggregate.workflow().setDraftExecutionMode("system");
        aggregate.workflow().setDraftRunAsUserId(null);
        aggregate.version().setExecutionMode("system");
        aggregate.version().setRunAsUserId(null);
        rebuildCanonicalAggregate(aggregate);
        stubAggregate(aggregate);
        doAnswer(invocation -> {
            invocation.<WorkflowVersion>getArgument(0).setId(303L);
            return null;
        }).when(workflowVersionMapper).insert(any(WorkflowVersion.class));
        when(ruleMapper.update(any(Rule.class))).thenReturn(1);
        when(workflowMapper.replaceLegacyPublication(
                any(Workflow.class), eq(303L), eq(23), eq(202L), eq(4)))
            .thenReturn(1);

        service.update(7, 9, 23, request("Rule", true, "user", "deal.won"));

        verify(principalLockService).lockUserMutation(
            eq(7), eq(9), any(), eq(Set.of(1)));
        ArgumentCaptor<Rule> replacement = ArgumentCaptor.forClass(Rule.class);
        verify(ruleMapper).update(replacement.capture());
        assertEquals(1, replacement.getValue().getRunAsUserId());
    }

    @Test
    void systemUpdateUsesLockedBuiltInAdminAndPreservesCreatorIdentity() {
        PersistedAggregate aggregate = persistedAggregate(true);
        stubAggregate(aggregate);
        doAnswer(invocation -> {
            invocation.<WorkflowVersion>getArgument(0).setId(303L);
            return null;
        }).when(workflowVersionMapper).insert(any(WorkflowVersion.class));
        when(ruleMapper.update(any(Rule.class))).thenReturn(1);
        when(workflowMapper.replaceLegacyPublication(
                any(Workflow.class), eq(303L), eq(23), eq(202L), eq(4)))
            .thenReturn(1);

        service.update(7, 9, 23, request("Rule", true, "system", "deal.won"));

        verify(principalLockService).lockSystemMutation(eq(7), eq(9), any());
        ArgumentCaptor<Rule> replacement = ArgumentCaptor.forClass(Rule.class);
        verify(ruleMapper).update(replacement.capture());
        assertEquals(1, replacement.getValue().getCreatedById());
        assertNull(replacement.getValue().getRunAsUserId());
    }

    @Test
    void userUpdateAllowsMissingImmutableAttributionWithAnActiveRunAs() {
        PersistedAggregate aggregate = persistedAggregate(false);
        aggregate.rule().setCreatedById(null);
        aggregate.rule().setRunAsUserId(3);
        aggregate.workflow().setCreatedById(null);
        aggregate.workflow().setUpdatedById(null);
        aggregate.workflow().setDraftRunAsUserId(3);
        aggregate.version().setRunAsUserId(3);
        stubAggregate(aggregate);
        when(principalLockService.lockUserMutation(
                7, 9, Set.of(1, 2, 3), Set.of(3)))
            .thenReturn(new LockedPrincipals(Set.of(1, 2, 3, 9), Set.of(3, 9)));
        doAnswer(invocation -> {
            invocation.<WorkflowVersion>getArgument(0).setId(303L);
            return null;
        }).when(workflowVersionMapper).insert(any(WorkflowVersion.class));
        when(ruleMapper.update(any(Rule.class))).thenReturn(1);
        when(workflowMapper.replaceLegacyPublication(
                any(Workflow.class), eq(303L), eq(23), eq(202L), eq(4)))
            .thenReturn(1);

        service.update(7, 9, 23, request("Recovered", true, "user", "deal.won"));

        ArgumentCaptor<Rule> rule = ArgumentCaptor.forClass(Rule.class);
        ArgumentCaptor<WorkflowVersion> version = ArgumentCaptor.forClass(WorkflowVersion.class);
        verify(ruleMapper).update(rule.capture());
        verify(workflowVersionMapper).insert(version.capture());
        assertEquals("user", rule.getValue().getExecutionMode());
        assertNull(rule.getValue().getCreatedById());
        assertEquals(3, rule.getValue().getRunAsUserId());
        assertEquals("user", version.getValue().getExecutionMode());
        assertNull(version.getValue().getCreatedById());
        assertEquals(3, version.getValue().getRunAsUserId());
        assertEquals(9, version.getValue().getPublishedById());
        assertEquals(1, aggregate.version().getCreatedById());
        assertEquals(3, aggregate.version().getRunAsUserId());
    }

    @Test
    void deleteArchivesAndDisablesWithoutDeletingHistory() {
        PersistedAggregate aggregate = persistedAggregate(false);
        aggregate.rule().setEnabled(true);
        aggregate.workflow().setEnabled(true);
        stubAggregate(aggregate);
        when(ruleMapper.updateEnabled(7, 23, false)).thenReturn(1);
        when(workflowMapper.archive(7, 101, 9)).thenReturn(1);

        Rule deleted = service.delete(7, 9, 23);

        assertEquals("Rule", deleted.getName());
        InOrder order = inOrder(
            principalLockService, workflowMapper, workflowVersionMapper, ruleMapper);
        order.verify(principalLockService).lockUserMutation(
            eq(7), eq(9), any(), eq(Set.of()));
        order.verify(workflowMapper).getByIdForUpdate(7, 101);
        order.verify(workflowVersionMapper).getByIdForUpdate(7, 101, 202L);
        order.verify(ruleMapper).getByIdForUpdate(7, 23);
        order.verify(ruleMapper).updateEnabled(7, 23, false);
        order.verify(workflowMapper).archive(7, 101, 9);
    }

    @Test
    void deleteRejectsCanonicalOwnerWithoutMutatingEitherProjection() {
        PersistedAggregate aggregate = persistedAggregate(true);
        aggregate.workflow().setRuntimeOwner("canonical");
        stubAggregate(aggregate);

        assertThrows(
            ConflictException.class,
            () -> service.delete(7, 9, 23));

        verify(ruleMapper, never()).updateEnabled(anyInt(), anyInt(), eq(false));
        verify(workflowMapper, never()).archive(anyInt(), anyInt(), anyInt());
    }

    @Test
    void repeatedDeleteOfArchivedLegacyOwnerIsIdempotent() {
        PersistedAggregate aggregate = persistedAggregate(false);
        aggregate.workflow().setArchivedAt(java.time.LocalDateTime.now());
        stubAggregate(aggregate);

        Rule deleted = service.delete(7, 9, 23);

        assertEquals(23, deleted.getId());
        verify(ruleMapper, never()).updateEnabled(anyInt(), anyInt(), eq(false));
        verify(workflowMapper, never()).archive(anyInt(), anyInt(), anyInt());
    }

    private void stubAggregate(PersistedAggregate aggregate) {
        when(ruleMapper.getById(7, 23)).thenReturn(aggregate.rule());
        when(workflowMapper.getByLegacyRuleId(7, 23)).thenReturn(aggregate.workflow());
        when(workflowVersionMapper.listByWorkflow(7, 101))
            .thenReturn(List.of(aggregate.version()));
        when(workflowMapper.getByIdForUpdate(7, 101)).thenReturn(aggregate.workflow());
        when(workflowVersionMapper.getByIdForUpdate(7, 101, 202L))
            .thenReturn(aggregate.version());
        when(ruleMapper.getByIdForUpdate(7, 23)).thenReturn(aggregate.rule());
    }

    private PersistedAggregate persistedAggregate(boolean enabled) {
        Rule rule = rule(23, 7, 1, 1, enabled, "user", "Rule", "deal.won");
        ConvertedWorkflow converted = graphConverter.convert(rule);
        CanonicalDraft draft = canonicalizer.canonicalizeDraft(
            converted.name(),
            converted.description(),
            converted.recordType(),
            converted.executionMode(),
            converted.definition(),
            converted.canvas());
        Rule projection = graphConverter.project(new ConvertedWorkflow(
            23,
            7,
            draft.name(),
            draft.description(),
            enabled,
            draft.recordType(),
            draft.executionMode(),
            1,
            1,
            converted.definition(),
            converted.canvas()));
        Workflow workflow = new Workflow();
        workflow.setId(101);
        workflow.setWorkspaceId(7);
        workflow.setLegacyRuleId(23);
        workflow.setName(projection.getName());
        workflow.setDescription(projection.getDescription());
        workflow.setEnabled(enabled);
        workflow.setRuntimeOwner("legacy");
        workflow.setArchivedAt(null);
        workflow.setDraftRevision(4);
        workflow.setDraftRecordType(projection.getRecordType());
        workflow.setDraftExecutionMode(projection.getExecutionMode());
        workflow.setDraftRunAsUserId(projection.getRunAsUserId());
        workflow.setDraftDefinitionJson(draft.definitionJson());
        workflow.setDraftCanvasJson(draft.canvasJson());
        workflow.setActiveVersionId(202L);
        workflow.setCreatedById(1);
        workflow.setUpdatedById(2);
        WorkflowVersion version = new WorkflowVersion();
        version.setId(202L);
        version.setWorkspaceId(7);
        version.setWorkflowId(101);
        version.setVersionNumber(1);
        version.setName(projection.getName());
        version.setDescription(projection.getDescription());
        version.setRecordType(projection.getRecordType());
        version.setTriggerType(projection.getTriggerType());
        version.setTriggerConfig(projection.getTriggerConfig());
        version.setConditionJson(projection.getConditionJson());
        version.setActionsJson(projection.getActionsJson());
        version.setExecutionMode(projection.getExecutionMode());
        version.setRunAsUserId(projection.getRunAsUserId());
        version.setCreatedById(projection.getCreatedById());
        version.setPublishedById(2);
        version.setDefinitionJson(draft.definitionJson());
        version.setCanvasJson(draft.canvasJson());
        version.setDefinitionHash(draft.definitionHash());
        return new PersistedAggregate(projection, workflow, version);
    }

    private void rebuildCanonicalAggregate(PersistedAggregate aggregate) {
        ConvertedWorkflow converted = graphConverter.convert(aggregate.rule());
        CanonicalDraft draft = canonicalizer.canonicalizeDraft(
            converted.name(),
            converted.description(),
            converted.recordType(),
            converted.executionMode(),
            converted.definition(),
            converted.canvas());
        aggregate.workflow().setDraftDefinitionJson(draft.definitionJson());
        aggregate.workflow().setDraftCanvasJson(draft.canvasJson());
        aggregate.version().setDefinitionJson(draft.definitionJson());
        aggregate.version().setCanvasJson(draft.canvasJson());
        aggregate.version().setDefinitionHash(draft.definitionHash());
    }

    private void customizePublishedGraph(PersistedAggregate aggregate) {
        RuleTrigger trigger = definitionCodec.parse(
            aggregate.rule().getTriggerConfig(), RuleTrigger.class);
        RuleAction action = definitionCodec.parse(
            aggregate.rule().getActionsJson(), RuleAction[].class)[0];
        WorkflowDefinition definition =
            new WorkflowDefinition(
                1,
                "custom-trigger",
                List.of(
                    new WorkflowNode.Trigger("custom-trigger", trigger),
                    new WorkflowNode.Action("custom-action", action),
                    new WorkflowNode.End("custom-end")),
                List.of(
                    new WorkflowEdge(
                        "custom-edge-1",
                        "custom-trigger",
                        "custom-action",
                        WorkflowEdge.Outcome.NEXT),
                    new WorkflowEdge(
                        "custom-edge-2",
                        "custom-action",
                        "custom-end",
                        WorkflowEdge.Outcome.NEXT)));
        WorkflowCanvas canvas =
            new WorkflowCanvas(
                Map.of(
                    "custom-trigger",
                    new WorkflowCanvas.Position(BigDecimal.TEN, BigDecimal.ONE),
                    "custom-action",
                    new WorkflowCanvas.Position(
                        BigDecimal.valueOf(320), BigDecimal.valueOf(80)),
                    "custom-end",
                    new WorkflowCanvas.Position(BigDecimal.valueOf(640), BigDecimal.ONE)),
                new WorkflowCanvas.Viewport(
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ONE));
        CanonicalDraft draft = canonicalizer.canonicalizeDraft(
            aggregate.version().getName(),
            aggregate.version().getDescription(),
            aggregate.version().getRecordType(),
            aggregate.version().getExecutionMode(),
            definition,
            canvas);
        aggregate.version().setDefinitionJson(draft.definitionJson());
        aggregate.version().setCanvasJson(draft.canvasJson());
        aggregate.version().setDefinitionHash(draft.definitionHash());
    }

    private RuleRequest request(
            String name, boolean enabled, String mode, String event) {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of(event));
        RuleAction action = new RuleAction();
        action.setType("notify");
        action.setTitle("Notify owner");
        RuleRequest request = new RuleRequest();
        request.setName(name);
        request.setDescription("Description");
        request.setEnabled(enabled);
        request.setRecordType("deal");
        request.setTrigger(trigger);
        request.setActions(List.of(action));
        request.setExecutionMode(mode);
        return request;
    }

    private Rule rule(
            int id,
            int workspaceId,
            Integer createdById,
            Integer runAsUserId,
            boolean enabled,
            String mode,
            String name,
            String event) {
        RuleRequest request = request(name, enabled, mode, event);
        Rule rule = new Rule();
        rule.setId(id);
        rule.setWorkspaceId(workspaceId);
        rule.setName(name);
        rule.setDescription(request.getDescription());
        rule.setEnabled(enabled);
        rule.setRecordType("deal");
        rule.setTriggerType("entity_change");
        rule.setTriggerConfig(definitionCodec.serialize(request.getTrigger()));
        rule.setConditionJson(null);
        rule.setActionsJson(definitionCodec.serialize(request.getActions()));
        rule.setExecutionMode(mode);
        rule.setRunAsUserId(runAsUserId);
        rule.setCreatedById(createdById);
        return rule;
    }

    private static LockedPrincipals lockedPrincipals(
            int actorId, Collection<Integer> discoveredIds) {
        TreeSet<Integer> ids = new TreeSet<>(discoveredIds);
        ids.add(actorId);
        Set<Integer> locked = Set.copyOf(ids);
        return new LockedPrincipals(locked, locked);
    }

    private record PersistedAggregate(
        Rule rule, Workflow workflow, WorkflowVersion version) { }
}
