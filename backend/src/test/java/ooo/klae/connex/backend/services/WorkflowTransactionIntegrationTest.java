package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.WorkflowCanvas;
import ooo.klae.connex.backend.dto.WorkflowCreateRequest;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowDraftRequest;
import ooo.klae.connex.backend.dto.WorkflowDto;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.dto.WorkflowPublishRequest;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WorkflowTransactionIntegrationTest extends AbstractServiceTest {

    @Autowired private WorkflowService workflowService;
    @Autowired private WorkflowVersionMapper workflowVersionMapper;
    @Autowired private RuleMapper ruleMapper;
    @Autowired private ObjectMapper objectMapper;
    @MockitoSpyBean private WorkflowMapper workflowMapperSpy;
    @MockitoSpyBean private WorkflowPrincipalLockService workflowPrincipalLockServiceSpy;

    private final List<Integer> workflowIds = new ArrayList<>();

    @Test
    void firstPublishPointerFailureRollsBackRuleAndVersion() {
        WorkflowDto created = workflowService.create(createRequest("Rollback workflow"));
        workflowIds.add(created.id());
        int initialRuleCount = ruleMapper.countByWorkspace(workspace.getId());
        doReturn(0).when(workflowMapperSpy).assignFirstPublication(
            eq(workspace.getId()),
            eq(created.id()),
            anyInt(),
            anyLong(),
            eq(currentUser.getId()),
            eq(0));

        assertThrows(
            IllegalStateException.class,
            () -> workflowService.publish(created.id(), publishRequest(0)));

        Workflow persisted = workflowMapperSpy.getById(workspace.getId(), created.id());
        assertNull(persisted.getLegacyRuleId());
        assertNull(persisted.getActiveVersionId());
        assertEquals(0, persisted.getDraftRevision());
        assertTrue(workflowVersionMapper.listByWorkflow(
            workspace.getId(), created.id()).isEmpty());
        assertEquals(initialRuleCount, ruleMapper.countByWorkspace(workspace.getId()));
    }

    @Test
    void concurrentDraftSavesAllowOneCasWinner() throws Exception {
        WorkflowDto created = workflowService.create(createRequest("Concurrent workflow"));
        workflowIds.add(created.id());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> first = executor.submit(() -> saveAfterRelease(
                created.id(), "First editor", ready, start));
            Future<Boolean> second = executor.submit(() -> saveAfterRelease(
                created.id(), "Second editor", ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            int successes = (first.get(20, TimeUnit.SECONDS) ? 1 : 0)
                + (second.get(20, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        WorkflowDto persisted = workflowService.getById(created.id());
        assertEquals(1, persisted.draftRevision());
        assertTrue(Set.of("First editor", "Second editor").contains(persisted.name()));
        assertNull(persisted.activeVersionId());
        assertTrue(workflowVersionMapper.listByWorkflow(
            workspace.getId(), created.id()).isEmpty());
    }

    @Test
    void concurrentFirstPublicationCreatesOneVersionAndOneRule() throws Exception {
        WorkflowDto created = workflowService.create(createRequest("Concurrent publication"));
        workflowIds.add(created.id());
        int initialRuleCount = ruleMapper.countByWorkspace(workspace.getId());
        CountDownLatch bothDiscovered = new CountDownLatch(2);
        doAnswer(invocation -> {
            bothDiscovered.countDown();
            assertTrue(bothDiscovered.await(10, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(workflowPrincipalLockServiceSpy).lockUserMutation(
            eq(workspace.getId()),
            eq(currentUser.getId()),
            eq(Set.of(currentUser.getId())),
            eq(Set.of(currentUser.getId())));
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> first = executor.submit(() -> publish(created.id()));
            Future<Boolean> second = executor.submit(() -> publish(created.id()));
            int successes = (first.get(20, TimeUnit.SECONDS) ? 1 : 0)
                + (second.get(20, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        Workflow persisted = workflowMapperSpy.getById(workspace.getId(), created.id());
        assertNotNull(persisted.getLegacyRuleId());
        assertNotNull(persisted.getActiveVersionId());
        assertNotNull(ruleMapper.getById(workspace.getId(), persisted.getLegacyRuleId()));
        assertEquals(initialRuleCount + 1, ruleMapper.countByWorkspace(workspace.getId()));
        List<WorkflowVersion> versions = workflowVersionMapper.listByWorkflow(
            workspace.getId(), created.id());
        assertEquals(1, versions.size());
        assertEquals(1, versions.getFirst().getVersionNumber());
        assertEquals(persisted.getActiveVersionId().longValue(), versions.getFirst().getId());
    }

    private boolean saveAfterRelease(
            int workflowId,
            String name,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                currentUser, null, currentUser.getAuthorities()));
        Integer orgId = workspaceMapper.getOrgId(workspace.getId());
        tenantContext.set(
            workspace.getId(),
            orgId == null ? workspace.getId() : orgId,
            currentUser.getId(),
            "owner",
            null);
        ready.countDown();
        try {
            assertTrue(start.await(10, TimeUnit.SECONDS));
            workflowService.saveDraft(workflowId, draftRequest(name, 0));
            return true;
        } catch (ConflictException exception) {
            return false;
        } finally {
            SecurityContextHolder.clearContext();
            tenantContext.clear();
        }
    }

    private boolean publish(int workflowId) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                currentUser, null, currentUser.getAuthorities()));
        Integer orgId = workspaceMapper.getOrgId(workspace.getId());
        tenantContext.set(
            workspace.getId(),
            orgId == null ? workspace.getId() : orgId,
            currentUser.getId(),
            "owner",
            null);
        try {
            workflowService.publish(workflowId, publishRequest(0));
            return true;
        } catch (ConflictException exception) {
            return false;
        } finally {
            SecurityContextHolder.clearContext();
            tenantContext.clear();
        }
    }

    private WorkflowCreateRequest createRequest(String name) {
        WorkflowCreateRequest request = new WorkflowCreateRequest();
        request.setName(name);
        request.setRecordType("deal");
        request.setExecutionMode("user");
        request.setDefinition(objectMapper.valueToTree(definition()));
        request.setCanvas(objectMapper.valueToTree(canvas()));
        return request;
    }

    private WorkflowDraftRequest draftRequest(String name, int expectedRevision) {
        WorkflowDraftRequest request = new WorkflowDraftRequest();
        request.setExpectedRevision(expectedRevision);
        request.setName(name);
        request.setRecordType("deal");
        request.setExecutionMode("user");
        request.setDefinition(objectMapper.valueToTree(definition()));
        request.setCanvas(objectMapper.valueToTree(canvas()));
        return request;
    }

    private static WorkflowPublishRequest publishRequest(int expectedRevision) {
        WorkflowPublishRequest request = new WorkflowPublishRequest();
        request.setExpectedRevision(expectedRevision);
        return request;
    }

    private static WorkflowDefinition definition() {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of("deal.won"));
        RuleAction action = new RuleAction();
        action.setType("notify");
        action.setTitle("Notify owner");
        return new WorkflowDefinition(
            1,
            "trigger",
            List.of(
                new WorkflowNode.Trigger("trigger", trigger),
                new WorkflowNode.Action("action", action),
                new WorkflowNode.End("end")),
            List.of(
                new WorkflowEdge(
                    "trigger-action", "trigger", "action", WorkflowEdge.Outcome.NEXT),
                new WorkflowEdge(
                    "action-end", "action", "end", WorkflowEdge.Outcome.NEXT)));
    }

    private static WorkflowCanvas canvas() {
        return new WorkflowCanvas(
            Map.of(
                "trigger", new WorkflowCanvas.Position(BigDecimal.ZERO, BigDecimal.ZERO),
                "action", new WorkflowCanvas.Position(BigDecimal.valueOf(300), BigDecimal.ZERO),
                "end", new WorkflowCanvas.Position(BigDecimal.valueOf(600), BigDecimal.ZERO)),
            new WorkflowCanvas.Viewport(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE));
    }

    @AfterEach
    void deleteCreatedWorkflowsAndPrincipal() {
        for (int workflowId : workflowIds) {
            Workflow workflow = workflowMapperSpy.getById(workspace.getId(), workflowId);
            if (workflow == null) {
                continue;
            }
            Integer ruleId = workflow.getLegacyRuleId();
            Long versionId = workflow.getActiveVersionId();
            if (ruleId != null && versionId != null) {
                workflowMapperSpy.unlinkLegacyRuleForDeletion(
                    workspace.getId(),
                    workflowId,
                    currentUser.getId(),
                    ruleId,
                    versionId,
                    workflow.getDraftRevision());
            }
            workflowMapperSpy.delete(workspace.getId(), workflowId);
            if (ruleId != null) {
                ruleMapper.delete(workspace.getId(), ruleId);
            }
        }
        ruleMapper.getByWorkspace(workspace.getId()).stream()
            .filter(rule -> Integer.valueOf(currentUser.getId()).equals(rule.getCreatedById()))
            .forEach(rule -> ruleMapper.delete(workspace.getId(), rule.getId()));
        workspaceMapper.removeMember(workspace.getId(), currentUser.getId());
        userMapper.delete(currentUser.getId());
    }
}
