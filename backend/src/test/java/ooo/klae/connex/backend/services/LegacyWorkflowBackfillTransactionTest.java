package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
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

import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.WorkflowCanvas;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

@ExtendWith(MockitoExtension.class)
class LegacyWorkflowBackfillTransactionTest {

    @Mock private RuleMapper ruleMapper;
    @Mock private WorkflowMapper workflowMapper;
    @Mock private WorkflowVersionMapper workflowVersionMapper;
    @Mock private UserMapper userMapper;
    @Mock private WorkspaceMapper workspaceMapper;

    private RuleDefinitionCodec definitionCodec;
    private LegacyWorkflowBackfillTransaction backfill;
    private Map<Integer, List<Rule>> workspaceRules;

    @BeforeEach
    void setUp() {
        definitionCodec = new RuleDefinitionCodec(new ObjectMapper());
        workspaceRules = new HashMap<>();
        backfill = new LegacyWorkflowBackfillTransaction(
            ruleMapper,
            workflowMapper,
            workflowVersionMapper,
            userMapper,
            workspaceMapper,
            new LegacyWorkflowGraphConverter(definitionCodec),
            new WorkflowDraftCanonicalizer(),
            definitionCodec);
        lenient().when(ruleMapper.getByWorkspace(anyInt())).thenAnswer(invocation ->
            workspaceRules.getOrDefault(invocation.getArgument(0), List.of()));
        lenient().when(ruleMapper.getByWorkspaceForUpdate(anyInt())).thenAnswer(invocation ->
            workspaceRules.getOrDefault(invocation.getArgument(0), List.of()));
        lenient().when(ruleMapper.getByIdForUpdate(anyInt(), anyInt())).thenAnswer(invocation ->
            workspaceRules.getOrDefault(
                    invocation.<Integer>getArgument(0), List.of()).stream()
                .filter(rule -> rule.getId() == invocation.<Integer>getArgument(1))
                .findFirst()
                .orElse(null));
        lenient().when(workflowMapper.getByLegacyRuleIdForUpdate(anyInt(), anyInt()))
            .thenAnswer(invocation -> workflowMapper.getByLegacyRuleId(
                invocation.getArgument(0), invocation.getArgument(1)));
        lenient().when(workflowVersionMapper.getByIdForUpdate(anyInt(), anyInt(), anyLong()))
            .thenAnswer(invocation -> workflowVersionMapper.getById(
                invocation.getArgument(0),
                invocation.getArgument(1),
                invocation.getArgument(2)));
        lenient().when(userMapper.lockById(anyInt())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(workspaceMapper.lockWorkspace(anyInt()))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void freshBackfillPersistsExactProjectionCanonicalHashAndSafeActivationOrder() throws Exception {
        Rule rule = rule("user", true);
        rules(7, rule);
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
        assertEquals(999, workflow.getDraftRunAsUserId());
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
        assertEquals(999, version.getRunAsUserId());
        assertEquals(41, version.getCreatedById());
        assertNull(version.getPublishedById());
        assertEquals(workflow.getDraftDefinitionJson(), version.getDefinitionJson());
        assertEquals(workflow.getDraftCanvasJson(), version.getCanvasJson());
        assertEquals(expectedDefinition(), version.getDefinitionJson());
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
    void freshBackfillDisablesEnabledUserRuleWithRedactedRunAsIdentity() {
        Rule rule = rule("user", true);
        rule.setRunAsUserId(null);
        rules(7, rule);
        allowFreshInsert();
        when(ruleMapper.updateEnabled(7, 23, false)).thenReturn(1);
        completeCounts(1, 1, 0);

        backfill.backfillWorkspace("cnx_a", 7);

        ArgumentCaptor<Workflow> workflow = ArgumentCaptor.forClass(Workflow.class);
        ArgumentCaptor<WorkflowVersion> version = ArgumentCaptor.forClass(WorkflowVersion.class);
        verify(ruleMapper).updateEnabled(7, 23, false);
        verify(workflowMapper).insert(workflow.capture());
        verify(workflowVersionMapper).insert(version.capture());
        assertFalse(rule.isEnabled());
        assertFalse(workflow.getValue().isEnabled());
        assertNull(workflow.getValue().getDraftRunAsUserId());
        assertNull(version.getValue().getRunAsUserId());
        verify(workflowMapper, never()).updateLifecycle(7, 101, true, null);
        InOrder writes = inOrder(ruleMapper, workflowMapper, workflowVersionMapper);
        writes.verify(ruleMapper).updateEnabled(7, 23, false);
        writes.verify(workflowMapper).insert(workflow.getValue());
        writes.verify(workflowVersionMapper).insert(version.getValue());
    }

    @Test
    void freshBackfillDisablesEnabledSystemRuleWithRedactedCreatorIdentity() {
        Rule rule = rule("system", true);
        rule.setCreatedById(null);
        rules(7, rule);
        allowFreshInsert();
        when(ruleMapper.updateEnabled(7, 23, false)).thenReturn(1);
        completeCounts(1, 1, 0);

        backfill.backfillWorkspace("cnx_a", 7);

        ArgumentCaptor<Workflow> workflow = ArgumentCaptor.forClass(Workflow.class);
        ArgumentCaptor<WorkflowVersion> version = ArgumentCaptor.forClass(WorkflowVersion.class);
        verify(ruleMapper).updateEnabled(7, 23, false);
        verify(workflowMapper).insert(workflow.capture());
        verify(workflowVersionMapper).insert(version.capture());
        assertFalse(rule.isEnabled());
        assertFalse(workflow.getValue().isEnabled());
        assertNull(workflow.getValue().getCreatedById());
        assertNull(version.getValue().getCreatedById());
        verify(workflowMapper, never()).updateLifecycle(7, 101, true, null);
        InOrder writes = inOrder(ruleMapper, workflowMapper, workflowVersionMapper);
        writes.verify(ruleMapper).updateEnabled(7, 23, false);
        writes.verify(workflowMapper).insert(workflow.getValue());
        writes.verify(workflowVersionMapper).insert(version.getValue());
    }

    @Test
    void freshEnabledUserWithRunAsAndRedactedCreatorRemainsEnabled() {
        Rule rule = rule("user", true);
        rule.setCreatedById(null);
        rules(7, rule);
        allowFreshInsert();
        when(workflowMapper.updateLifecycle(7, 101, true, null)).thenReturn(1);
        completeCounts(1, 1, 0);

        backfill.backfillWorkspace("cnx_a", 7);

        verify(ruleMapper, never()).updateEnabled(7, 23, false);
        verify(workflowMapper).updateLifecycle(7, 101, true, null);
    }

    @Test
    void freshEnabledSystemWithCreatorAndNoRunAsRemainsEnabled() {
        Rule rule = rule("system", true);
        rules(7, rule);
        allowFreshInsert();
        when(workflowMapper.updateLifecycle(7, 101, true, null)).thenReturn(1);
        completeCounts(1, 1, 0);

        backfill.backfillWorkspace("cnx_a", 7);

        verify(ruleMapper, never()).updateEnabled(7, 23, false);
        verify(workflowMapper).updateLifecycle(7, 101, true, null);
    }

    @Test
    void redactedOperationalIdentityRepairFailsClosedWhenRuleDisableLosesRace() {
        Rule rule = rule("user", true);
        rule.setRunAsUserId(null);
        rules(7, rule);

        assertThrows(
            IllegalStateException.class,
            () -> backfill.backfillWorkspace("cnx_a", 7));

        verify(ruleMapper).updateEnabled(7, 23, false);
        verify(workflowMapper, never()).insert(any());
        verify(workflowVersionMapper, never()).insert(any());
    }

    @Test
    void existingEnabledPairWithRedactedOperationalIdentityIsDisabledWithoutNewVersion() {
        Rule rule = rule("user", true);
        rule.setRunAsUserId(null);
        PersistedPair pair = pair(rule, 4);
        rules(7, rule);
        when(workflowMapper.getByLegacyRuleId(7, 23)).thenReturn(pair.workflow());
        when(workflowVersionMapper.getById(7, 101, 204L)).thenReturn(pair.version());
        when(ruleMapper.updateEnabled(7, 23, false)).thenReturn(1);
        when(workflowMapper.disableForOffboarding(7, 101)).thenReturn(1);
        completeCounts(1, 1, 0);

        backfill.backfillWorkspace("cnx_a", 7);

        assertFalse(rule.isEnabled());
        assertFalse(pair.workflow().isEnabled());
        verify(ruleMapper).updateEnabled(7, 23, false);
        verify(workflowMapper).disableForOffboarding(7, 101);
        verify(workflowMapper, never()).insert(any());
        verify(workflowVersionMapper, never()).insert(any());
    }

    @Test
    void systemBackfillRejectsPersistedRunAsIdentity() {
        Rule rule = rule("system", false);
        rule.setRunAsUserId(999);
        rules(7, rule);

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> backfill.backfillWorkspace(null, 7));

        assertEquals(
            "Legacy workflow backfill failed for catalog=(default) workspace=7 rule=23",
            exception.getMessage());
        verify(workflowMapper, never()).insert(any());
        verify(workflowVersionMapper, never()).insert(any());
    }

    @Test
    void freshProjectionAcceptsSemanticJsonAndBlankDescriptionButRejectsMetadataNormalization() {
        Rule formatted = rule("user", false);
        formatted.setDescription("   ");
        formatted.setTriggerConfig(
            "{ \"events\" : [ \"deal.won\" ], \"type\" : \"entity_change\" }");
        formatted.setActionsJson(
            "[ { \"title\" : \"Notify owner\", \"type\" : \"notify\" } ]");
        rules(7, formatted);
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

        ArgumentCaptor<Workflow> workflow = ArgumentCaptor.forClass(Workflow.class);
        verify(workflowMapper).insert(workflow.capture());
        assertEquals("   ", workflow.getValue().getDescription());
        ArgumentCaptor<WorkflowVersion> version = ArgumentCaptor.forClass(WorkflowVersion.class);
        verify(workflowVersionMapper).insert(version.capture());
        assertEquals("   ", version.getValue().getDescription());
        assertEquals(
            "{\"cadence\":null,\"events\":[\"deal.won\"],\"targetStageId\":null,"
                + "\"throttleMinutes\":null,\"type\":\"entity_change\"}",
            version.getValue().getTriggerConfig());

        Rule changedName = rule("user", false);
        changedName.setName(" Legacy rule ");
        changedName.setWorkspaceId(8);
        rules(8, changedName);

        assertThrows(
            IllegalStateException.class,
            () -> backfill.backfillWorkspace(null, 8));
    }

    @Test
    void rerunVerifiesEquivalentActiveVersionWithoutWrites() {
        Rule rule = rule("user", true);
        PersistedPair pair = pair(rule, 4);
        rules(7, rule);
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
    void rerunAcceptsDisabledOffboardingRedactionAndMissingAttributionRoots() {
        Rule rule = rule("user", false);
        PersistedPair pair = pair(rule, 4);
        rule.setCreatedById(null);
        rule.setRunAsUserId(null);
        pair.workflow().setCreatedById(null);
        pair.workflow().setDraftRunAsUserId(null);
        pair.version().setPublishedById(88);
        rules(7, rule);
        when(workflowMapper.getByLegacyRuleId(7, 23)).thenReturn(pair.workflow());
        when(workflowVersionMapper.getById(7, 101, 204L)).thenReturn(pair.version());
        when(userMapper.lockById(41)).thenReturn(null);
        when(userMapper.lockById(88)).thenReturn(null);
        when(userMapper.lockById(999)).thenReturn(null);
        completeCounts(1, 1, 0);

        backfill.backfillWorkspace("cnx_a", 7);

        verify(workflowMapper, never()).insert(any());
        verify(workflowVersionMapper, never()).insert(any());
        verify(workflowMapper, never()).updateActiveVersion(eq(7), eq(101), any(), any());
    }

    @Test
    void enabledUserBackfillRejectsAMissingOperationalRunAsRoot() {
        Rule rule = rule("user", true);
        rules(7, rule);
        when(userMapper.lockById(999)).thenReturn(null);

        assertThrows(
            IllegalStateException.class,
            () -> backfill.backfillWorkspace("cnx_a", 7));

        verify(workflowMapper, never()).insert(any());
        verify(workflowVersionMapper, never()).insert(any());
    }

    @Test
    void locksSortedPrincipalsWorkspaceWorkflowVersionAndRuleBeforeVerification() {
        Rule rule = rule("user", true);
        PersistedPair pair = pair(rule, 4);
        pair.version().setPublishedById(88);
        rules(7, rule);
        when(workflowMapper.getByLegacyRuleId(7, 23)).thenReturn(pair.workflow());
        when(workflowVersionMapper.getById(7, 101, 204L)).thenReturn(pair.version());
        completeCounts(1, 1, 0);

        backfill.backfillWorkspace("cnx_a", 7);

        InOrder order = inOrder(
            userMapper, workspaceMapper, workflowMapper, workflowVersionMapper, ruleMapper);
        order.verify(userMapper).lockById(41);
        order.verify(userMapper).lockById(88);
        order.verify(userMapper).lockById(999);
        order.verify(workspaceMapper).lockWorkspace(7);
        order.verify(workflowMapper).getByLegacyRuleIdForUpdate(7, 23);
        order.verify(workflowVersionMapper).getByIdForUpdate(7, 101, 204L);
        order.verify(ruleMapper).getByIdForUpdate(7, 23);
        order.verify(ruleMapper).getByWorkspaceForUpdate(7);
    }

    @Test
    void concurrentCompletedPairIsRefreshedAndVerifiedInsteadOfInsertedAgain() {
        Rule rule = rule("user", true);
        PersistedPair pair = pair(rule, 1);
        rules(7, rule);
        when(workflowMapper.getByLegacyRuleId(7, 23)).thenReturn(null);
        when(workflowMapper.getByLegacyRuleIdForUpdate(7, 23))
            .thenReturn(pair.workflow());
        when(workflowVersionMapper.getByIdForUpdate(7, 101, 201L))
            .thenReturn(pair.version());
        completeCounts(1, 1, 0);

        backfill.backfillWorkspace("cnx_a", 7);

        verify(workflowMapper, never()).insert(any());
        verify(workflowVersionMapper, never()).insert(any());
        verify(workflowMapper, never()).updateActiveVersion(
            eq(7), eq(101), anyLong(), any());
    }

    @Test
    void rerunRejectsCreatorDriftThatLegacyMutationsWouldReject() {
        Rule rule = rule("user", true);
        PersistedPair pair = pair(rule, 1);
        pair.workflow().setCreatedById(88);
        rules(7, rule);
        when(workflowMapper.getByLegacyRuleId(7, 23)).thenReturn(pair.workflow());
        when(workflowVersionMapper.getById(7, 101, 201L)).thenReturn(pair.version());

        assertThrows(
            IllegalStateException.class,
            () -> backfill.backfillWorkspace("cnx_a", 7));

        verify(workflowMapper, never()).insert(any());
        verify(workflowVersionMapper, never()).insert(any());
    }

    @Test
    void rerunAcceptsCanonicalEditorIdsAndCanvasWhenProjectionStillMatches() {
        Rule rule = rule("user", true);
        rule.setDescription("   ");
        PersistedPair pair = pair(rule, 4);
        RuleTrigger trigger = definitionCodec.parse(rule.getTriggerConfig(), RuleTrigger.class);
        RuleAction action = definitionCodec.parse(rule.getActionsJson(), RuleAction[].class)[0];
        WorkflowDefinition definition = new WorkflowDefinition(
            1,
            "eventSource",
            List.of(
                new WorkflowNode.Trigger("eventSource", trigger),
                new WorkflowNode.Action("notifyOwner", action),
                new WorkflowNode.End("complete")),
            List.of(
                new WorkflowEdge(
                    "edgeA", "eventSource", "notifyOwner", WorkflowEdge.Outcome.NEXT),
                new WorkflowEdge(
                    "edgeB", "notifyOwner", "complete", WorkflowEdge.Outcome.NEXT)));
        WorkflowCanvas canvas = new WorkflowCanvas(
            Map.of(
                "eventSource", new WorkflowCanvas.Position(BigDecimal.TEN, BigDecimal.ONE),
                "notifyOwner", new WorkflowCanvas.Position(
                    BigDecimal.valueOf(350), BigDecimal.valueOf(80)),
                "complete", new WorkflowCanvas.Position(
                    BigDecimal.valueOf(700), BigDecimal.TEN)),
            new WorkflowCanvas.Viewport(
                BigDecimal.valueOf(25), BigDecimal.valueOf(-15), new BigDecimal("1.25")));
        WorkflowDraftCanonicalizer.CanonicalDraft canonical = new WorkflowDraftCanonicalizer()
            .canonicalizeDraft(
                pair.version().getName(),
                pair.version().getDescription(),
                pair.version().getRecordType(),
                pair.version().getExecutionMode(),
                definition,
                canvas);
        pair.version().setDefinitionJson(canonical.definitionJson());
        pair.version().setCanvasJson(canonical.canvasJson());
        pair.version().setDefinitionHash(canonical.definitionHash());
        rules(7, rule);
        when(workflowMapper.getByLegacyRuleId(7, 23)).thenReturn(pair.workflow());
        when(workflowVersionMapper.getById(7, 101, 204L)).thenReturn(pair.version());
        completeCounts(1, 1, 0);

        backfill.backfillWorkspace("cnx_a", 7);

        verify(workflowMapper, never()).insert(any());
        verify(workflowVersionMapper, never()).insert(any());
        verify(workflowMapper, never()).updateActiveVersion(eq(7), eq(101), any(), any());
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
        rules(7, rule);
        when(workflowMapper.getByLegacyRuleId(7, 23)).thenReturn(pair.workflow());
        when(workflowVersionMapper.getById(7, 101, 207L)).thenReturn(pair.version());
        completeCounts(1, 1, 0);

        backfill.backfillWorkspace("cnx_a", 7);

        verify(workflowVersionMapper, never()).listByWorkflow(anyInt(), anyInt());
        verify(workflowMapper, never()).insert(any());
        verify(workflowVersionMapper, never()).insert(any());
    }

    @Test
    void rerunRejectsNoncanonicalActiveDefinitionOrCanvasBytes() {
        Rule rule = rule("user", true);
        PersistedPair definitionChanged = pair(rule, 4);
        definitionChanged.version().setDefinitionJson(
            " " + definitionChanged.version().getDefinitionJson());
        rules(7, rule);
        when(workflowMapper.getByLegacyRuleId(7, 23))
            .thenReturn(definitionChanged.workflow());
        when(workflowVersionMapper.getById(7, 101, 204L))
            .thenReturn(definitionChanged.version());

        assertThrows(
            IllegalStateException.class,
            () -> backfill.backfillWorkspace("cnx_a", 7));

        PersistedPair canvasChanged = pair(rule, 5);
        canvasChanged.version().setCanvasJson(
            "{\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1},\"positions\":"
                + canvasChanged.version().getCanvasJson().substring(
                    canvasChanged.version().getCanvasJson().indexOf("{\"action-1\""),
                    canvasChanged.version().getCanvasJson().indexOf(",\"viewport\""))
                + "}");
        when(workflowMapper.getByLegacyRuleId(7, 23)).thenReturn(canvasChanged.workflow());
        when(workflowVersionMapper.getById(7, 101, 205L)).thenReturn(canvasChanged.version());

        assertThrows(
            IllegalStateException.class,
            () -> backfill.backfillWorkspace("cnx_a", 7));
    }

    @Test
    void missingOrMismatchedActiveVersionFailsClosedWithoutContentInMessage() {
        Rule rule = rule("user", true);
        PersistedPair pair = pair(rule, 3);
        pair.version().setExecutionMode("system");
        pair.version().setRunAsUserId(null);
        rules(7, rule);
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
        rules(7);
        completeCounts(0, 0, 0);

        backfill.backfillWorkspace(null, 7);

        verify(workflowMapper, never()).getByLegacyRuleId(eq(7), anyInt());
        verify(workflowMapper, never()).insert(any());
        verify(workflowVersionMapper, never()).insert(any());
    }

    @Test
    void nullRuleCandidateFailsWithTheBoundedBackfillError() {
        workspaceRules.put(7, Arrays.asList((Rule) null));

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> backfill.backfillWorkspace("cnx_a", 7));

        assertEquals(
            "Legacy workflow backfill failed for catalog=cnx_a workspace=7 rule=0",
            exception.getMessage());
    }

    @Test
    void incompleteWorkspaceFailsClosedWithTheFirstUnpairedRuleId() {
        Rule rule = rule("user", true);
        PersistedPair pair = pair(rule, 1);
        rules(7, rule);
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
                draft.name(),
                draft.description(),
                converted.enabled(),
                draft.recordType(),
                draft.executionMode(),
                converted.runAsUserId(),
                converted.createdById(),
                converted.definition(),
                converted.canvas());
        Rule projection = converter.project(normalized);

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
        rule.setRunAsUserId("system".equals(executionMode) ? null : 999);
        rule.setCreatedById(41);
        return rule;
    }

    private void completeCounts(int rules, int linked, int unpaired) {
        when(ruleMapper.countByWorkspace(7)).thenReturn(rules);
        when(workflowMapper.countLegacyRuleLinks(7)).thenReturn(linked);
        when(workflowMapper.countUnpairedLegacyRules(7)).thenReturn(unpaired);
    }

    private void rules(int workspaceId, Rule... rules) {
        workspaceRules.put(workspaceId, List.of(rules));
    }

    private void allowFreshInsert() {
        doAnswer(invocation -> {
            invocation.<Workflow>getArgument(0).setId(101);
            return null;
        }).when(workflowMapper).insert(any(Workflow.class));
        doAnswer(invocation -> {
            invocation.<WorkflowVersion>getArgument(0).setId(202L);
            return null;
        }).when(workflowVersionMapper).insert(any(WorkflowVersion.class));
        when(workflowMapper.updateActiveVersion(7, 101, 202L, null)).thenReturn(1);
    }

    private static String expectedDefinition() {
        return "{\"edges\":[{\"id\":\"action-1--next--end\",\"outcome\":\"next\","
            + "\"sourceNodeId\":\"action-1\",\"targetNodeId\":\"end\"},{\"id\":"
            + "\"trigger--next--action-1\",\"outcome\":\"next\",\"sourceNodeId\":\"trigger\","
            + "\"targetNodeId\":\"action-1\"}],\"entryNodeId\":\"trigger\",\"nodes\":[{"
            + "\"config\":{\"activityType\":null,\"body\":null,\"dueInDays\":null,"
            + "\"severity\":null,\"tagId\":null,\"targetStageId\":null,\"targetUserId\":null,"
            + "\"title\":\"Notify owner\",\"type\":\"notify\"},\"id\":\"action-1\","
            + "\"type\":\"ACTION\"},{\"id\":\"end\",\"type\":\"END\"},{\"config\":{"
            + "\"cadence\":null,\"events\":[\"deal.won\"],\"targetStageId\":null,"
            + "\"throttleMinutes\":null,\"type\":\"entity_change\"},\"id\":\"trigger\","
            + "\"type\":\"TRIGGER\"}],\"schemaVersion\":1}";
    }

    private record PersistedPair(Workflow workflow, WorkflowVersion version) { }
}
