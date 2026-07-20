package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;

@ExtendWith(MockitoExtension.class)
class LegacyWorkflowBackfillTransactionTest {

    @Mock private RuleMapper ruleMapper;
    @Mock private WorkflowMapper workflowMapper;
    @Mock private WorkflowVersionMapper workflowVersionMapper;

    private RuleDefinitionCodec definitionCodec;
    private LegacyWorkflowBackfillTransaction backfill;

    @BeforeEach
    void setUp() {
        definitionCodec = new RuleDefinitionCodec(new ObjectMapper());
        backfill = new LegacyWorkflowBackfillTransaction(
            ruleMapper,
            workflowMapper,
            workflowVersionMapper,
            new LegacyWorkflowGraphConverter(definitionCodec),
            new WorkflowDraftCanonicalizer(),
            definitionCodec);
    }

    @Test
    void freshBackfillPersistsExactProjectionCanonicalHashAndSafeActivationOrder() throws Exception {
        Rule rule = rule("user", true);
        when(ruleMapper.getByWorkspaceForUpdate(7)).thenReturn(List.of(rule));
        doAnswer(invocation -> {
            invocation.<Workflow>getArgument(0).setId(101);
            return null;
        }).when(workflowMapper).insert(any(Workflow.class));
        doAnswer(invocation -> {
            invocation.<WorkflowVersion>getArgument(0).setId(202L);
            return null;
        }).when(workflowVersionMapper).insert(any(WorkflowVersion.class));
        when(workflowMapper.updateActiveVersion(7, 101, 202L, null)).thenReturn(1);
        when(workflowMapper.updateLifecycle(7, 101, true, null)).thenReturn(1);
        completeCounts(1, 1, 0);

        backfill.backfillWorkspace("cnx_a", 7);

        ArgumentCaptor<Workflow> workflowCaptor = ArgumentCaptor.forClass(Workflow.class);
        ArgumentCaptor<WorkflowVersion> versionCaptor = ArgumentCaptor.forClass(WorkflowVersion.class);
        verify(workflowMapper).insert(workflowCaptor.capture());
        verify(workflowVersionMapper).insert(versionCaptor.capture());
        Workflow workflow = workflowCaptor.getValue();
        WorkflowVersion version = versionCaptor.getValue();

        assertEquals(7, workflow.getWorkspaceId());
        assertEquals(23, workflow.getLegacyRuleId());
        assertEquals("Legacy rule", workflow.getName());
        assertEquals("Description", workflow.getDescription());
        assertFalse(workflow.isEnabled());
        assertEquals(1, workflow.getDraftRevision());
        assertEquals("deal", workflow.getDraftRecordType());
        assertEquals("user", workflow.getDraftExecutionMode());
        assertEquals(41, workflow.getDraftRunAsUserId());
        assertEquals(41, workflow.getCreatedById());
        assertNull(workflow.getUpdatedById());
        assertNull(workflow.getActiveVersionId());

        assertEquals(7, version.getWorkspaceId());
        assertEquals(101, version.getWorkflowId());
        assertEquals(1, version.getVersionNumber());
        assertEquals("Legacy rule", version.getName());
        assertEquals("Description", version.getDescription());
        assertEquals("deal", version.getRecordType());
        assertEquals("entity_change", version.getTriggerType());
        assertEquals(rule.getTriggerConfig(), version.getTriggerConfig());
        assertNull(version.getConditionJson());
        assertEquals(rule.getActionsJson(), version.getActionsJson());
        assertEquals("user", version.getExecutionMode());
        assertEquals(41, version.getRunAsUserId());
        assertEquals(41, version.getCreatedById());
        assertNull(version.getPublishedById());
        assertEquals(workflow.getDraftDefinitionJson(), version.getDefinitionJson());
        assertEquals(workflow.getDraftCanvasJson(), version.getCanvasJson());
        assertEquals(
            "{\"positions\":{\"action-1\":{\"x\":240,\"y\":0},\"end\":{\"x\":480,\"y\":0},"
                + "\"trigger\":{\"x\":0,\"y\":0}},\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}",
            version.getCanvasJson());
        String hashInput = "{\"definition\":" + version.getDefinitionJson()
            + ",\"canvas\":" + version.getCanvasJson() + "}";
        byte[] expectedHash = MessageDigest.getInstance("SHA-256")
            .digest(hashInput.getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(expectedHash, version.getDefinitionHash());
        assertEquals(
            "f30d86a62791f871742d5d04099607944e461fa41e93cddde21d27347dbf05aa",
            HexFormat.of().formatHex(version.getDefinitionHash()));

        InOrder writes = inOrder(workflowMapper, workflowVersionMapper);
        writes.verify(workflowMapper).insert(workflow);
        writes.verify(workflowVersionMapper).insert(version);
        writes.verify(workflowMapper).updateActiveVersion(7, 101, 202L, null);
        writes.verify(workflowMapper).updateLifecycle(7, 101, true, null);
    }

    @Test
    void systemBackfillAlwaysUsesNullRunAsIdentity() {
        Rule rule = rule("system", false);
        rule.setRunAsUserId(999);
        when(ruleMapper.getByWorkspaceForUpdate(7)).thenReturn(List.of(rule));
        doAnswer(invocation -> {
            invocation.<Workflow>getArgument(0).setId(101);
            return null;
        }).when(workflowMapper).insert(any(Workflow.class));
        doAnswer(invocation -> {
            invocation.<WorkflowVersion>getArgument(0).setId(202L);
            return null;
        }).when(workflowVersionMapper).insert(any(WorkflowVersion.class));
        when(workflowMapper.updateActiveVersion(7, 101, 202L, null)).thenReturn(1);
        completeCounts(1, 1, 0);

        backfill.backfillWorkspace(null, 7);

        ArgumentCaptor<WorkflowVersion> version = ArgumentCaptor.forClass(WorkflowVersion.class);
        verify(workflowVersionMapper).insert(version.capture());
        assertEquals("system", version.getValue().getExecutionMode());
        assertNull(version.getValue().getRunAsUserId());
        verify(workflowMapper, never()).updateLifecycle(eq(7), eq(101), eq(true), any());
    }

    @Test
    void rerunVerifiesEquivalentActiveVersionWithoutWrites() {
        Rule rule = rule("user", true);
        PersistedPair pair = pair(rule, 4);
        when(ruleMapper.getByWorkspaceForUpdate(7)).thenReturn(List.of(rule));
        when(workflowMapper.getByLegacyRuleId(7, 23)).thenReturn(pair.workflow());
        when(workflowVersionMapper.getById(7, 101, 204L)).thenReturn(pair.version());
        completeCounts(1, 1, 0);

        backfill.backfillWorkspace("cnx_a", 7);

        verify(workflowMapper, never()).insert(any());
        verify(workflowVersionMapper, never()).insert(any());
        verify(workflowMapper, never()).updateActiveVersion(
            eq(7), eq(101), eq(204L), any());
        verify(workflowMapper, never()).updateLifecycle(eq(7), eq(101), eq(true), any());
    }

    @Test
    void allowsLaterVersionsAndDraftDivergenceWhenActiveSnapshotStillMatches() {
        Rule rule = rule("user", true);
        PersistedPair pair = pair(rule, 7);
        pair.workflow().setName("Unpublished rename");
        pair.workflow().setDescription("Unpublished description");
        pair.workflow().setDraftRevision(12);
        pair.workflow().setDraftRecordType("company");
        pair.workflow().setDraftExecutionMode("system");
        pair.workflow().setDraftRunAsUserId(null);
        pair.workflow().setDraftDefinitionJson("{\"schemaVersion\":1,\"entryNodeId\":null,\"nodes\":[],\"edges\":[]}");
        pair.workflow().setDraftCanvasJson("{\"positions\":{},\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}");
        pair.version().setTriggerConfig(
            "{\"events\":[\"deal.won\"],\"type\":\"entity_change\"}");
        pair.version().setActionsJson(
            "[{\"title\":\"Notify owner\",\"type\":\"notify\"}]");
        pair.version().setPublishedById(88);
        when(ruleMapper.getByWorkspaceForUpdate(7)).thenReturn(List.of(rule));
        when(workflowMapper.getByLegacyRuleId(7, 23)).thenReturn(pair.workflow());
        when(workflowVersionMapper.getById(7, 101, 207L)).thenReturn(pair.version());
        completeCounts(1, 1, 0);

        backfill.backfillWorkspace("cnx_a", 7);

        verify(workflowVersionMapper, never()).listByWorkflow(anyInt(), anyInt());
        verify(workflowMapper, never()).insert(any());
        verify(workflowVersionMapper, never()).insert(any());
    }

    @Test
    void missingOrMismatchedActiveVersionFailsClosedWithoutContentInMessage() {
        Rule rule = rule("user", true);
        PersistedPair pair = pair(rule, 3);
        pair.version().setExecutionMode("system");
        pair.version().setRunAsUserId(null);
        when(ruleMapper.getByWorkspaceForUpdate(7)).thenReturn(List.of(rule));
        when(workflowMapper.getByLegacyRuleId(7, 23)).thenReturn(pair.workflow());
        when(workflowVersionMapper.getById(7, 101, 203L)).thenReturn(pair.version());

        IllegalStateException mismatch = assertThrows(
            IllegalStateException.class,
            () -> backfill.backfillWorkspace("cnx_a", 7));

        assertEquals(
            "Legacy workflow backfill failed for catalog=cnx_a workspace=7 rule=23",
            mismatch.getMessage());
        verify(workflowMapper, never()).insert(any());
        verify(workflowVersionMapper, never()).insert(any());

        pair.workflow().setActiveVersionId(null);
        IllegalStateException missing = assertThrows(
            IllegalStateException.class,
            () -> backfill.backfillWorkspace("cnx_a", 7));
        assertEquals(mismatch.getMessage(), missing.getMessage());
    }

    @Test
    void emptyWorkspaceIsANoOpAndStillProvesCompleteness() {
        when(ruleMapper.getByWorkspaceForUpdate(7)).thenReturn(List.of());
        completeCounts(0, 0, 0);

        backfill.backfillWorkspace(null, 7);

        verify(workflowMapper, never()).getByLegacyRuleId(eq(7), anyInt());
        verify(workflowMapper, never()).insert(any());
        verify(workflowVersionMapper, never()).insert(any());
    }

    @Test
    void incompleteWorkspaceFailsClosedWithTheFirstUnpairedRuleId() {
        Rule rule = rule("user", true);
        PersistedPair pair = pair(rule, 1);
        when(ruleMapper.getByWorkspaceForUpdate(7)).thenReturn(List.of(rule));
        when(workflowMapper.getByLegacyRuleId(7, 23)).thenReturn(pair.workflow());
        when(workflowVersionMapper.getById(7, 101, 201L)).thenReturn(pair.version());
        completeCounts(2, 1, 1);
        when(workflowMapper.firstUnpairedLegacyRuleId(7)).thenReturn(29);

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> backfill.backfillWorkspace(null, 7));

        assertEquals(
            "Legacy workflow backfill failed for catalog=(default) workspace=7 rule=29",
            exception.getMessage());
    }

    @Test
    void transactionBoundaryIsDeclaredOnTheExternallyInvokedWorkspaceMethod() throws Exception {
        Transactional annotation = LegacyWorkflowBackfillTransaction.class
            .getMethod("backfillWorkspace", String.class, int.class)
            .getAnnotation(Transactional.class);

        assertNotNull(annotation);
    }

    private PersistedPair pair(Rule rule, int versionNumber) {
        LegacyWorkflowGraphConverter converter = new LegacyWorkflowGraphConverter(definitionCodec);
        LegacyWorkflowGraphConverter.ConvertedWorkflow converted = converter.convert(rule);
        WorkflowDraftCanonicalizer.CanonicalDraft draft = new WorkflowDraftCanonicalizer().canonicalizeDraft(
            converted.name(),
            converted.description(),
            converted.recordType(),
            converted.executionMode(),
            converted.definition(),
            converted.canvas());
        LegacyWorkflowGraphConverter.ConvertedWorkflow normalized =
            new LegacyWorkflowGraphConverter.ConvertedWorkflow(
                converted.legacyRuleId(),
                converted.workspaceId(),
                converted.name(),
                converted.description(),
                converted.enabled(),
                converted.recordType(),
                converted.executionMode(),
                converted.runAsUserId(),
                converted.createdById(),
                draft.definition(),
                draft.canvas());
        Rule projection = converter.project(normalized);
        projection.setTriggerType(rule.getTriggerType());

        Workflow workflow = new Workflow();
        workflow.setId(101);
        workflow.setWorkspaceId(7);
        workflow.setLegacyRuleId(23);
        workflow.setName(projection.getName());
        workflow.setDescription(projection.getDescription());
        workflow.setEnabled(projection.isEnabled());
        workflow.setDraftRevision(1);
        workflow.setDraftRecordType(projection.getRecordType());
        workflow.setDraftExecutionMode(projection.getExecutionMode());
        workflow.setDraftRunAsUserId(projection.getRunAsUserId());
        workflow.setDraftDefinitionJson(draft.definitionJson());
        workflow.setDraftCanvasJson(draft.canvasJson());
        workflow.setActiveVersionId(200L + versionNumber);
        workflow.setCreatedById(projection.getCreatedById());

        WorkflowVersion version = new WorkflowVersion();
        version.setId(200L + versionNumber);
        version.setWorkspaceId(7);
        version.setWorkflowId(101);
        version.setVersionNumber(versionNumber);
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
        version.setDefinitionJson(draft.definitionJson());
        version.setCanvasJson(draft.canvasJson());
        version.setDefinitionHash(draft.definitionHash());
        return new PersistedPair(workflow, version);
    }

    private Rule rule(String executionMode, boolean enabled) {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of("deal.won"));
        RuleAction action = new RuleAction();
        action.setType("notify");
        action.setTitle("Notify owner");

        Rule rule = new Rule();
        rule.setId(23);
        rule.setWorkspaceId(7);
        rule.setName("Legacy rule");
        rule.setDescription("Description");
        rule.setEnabled(enabled);
        rule.setRecordType("deal");
        rule.setTriggerType("entity_change");
        rule.setTriggerConfig(definitionCodec.serialize(trigger));
        rule.setConditionJson(null);
        rule.setActionsJson(definitionCodec.serialize(List.of(action)));
        rule.setExecutionMode(executionMode);
        rule.setRunAsUserId(999);
        rule.setCreatedById(41);
        return rule;
    }

    private void completeCounts(int rules, int linked, int unpaired) {
        when(ruleMapper.countByWorkspace(7)).thenReturn(rules);
        when(workflowMapper.countLegacyRuleLinks(7)).thenReturn(linked);
        when(workflowMapper.countUnpairedLegacyRules(7)).thenReturn(unpaired);
    }

    private record PersistedPair(Workflow workflow, WorkflowVersion version) { }
}
