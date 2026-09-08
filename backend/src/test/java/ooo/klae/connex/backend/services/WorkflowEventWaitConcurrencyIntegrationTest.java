package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowEventWait;
import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowStepRun;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.dto.WorkflowWaitConfig;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.WorkflowEventWaitMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.NodeType;

/** Verifies task completion and deadline resolution serialize on real MySQL row locks. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WorkflowEventWaitConcurrencyIntegrationTest extends AbstractServiceTest {

    private static final String DEFINITION =
        "{\"schemaVersion\":2,\"entryNodeId\":\"trigger\",\"nodes\":[],\"edges\":[]}";
    private static final String CANVAS =
        "{\"positions\":{},\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}";

    @Autowired private WorkflowMapper workflowMapper;
    @Autowired private WorkflowVersionMapper versionMapper;
    @Autowired private WorkflowRunMapper runMapper;
    @Autowired private WorkflowEventWaitMapper eventWaitMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private TaskMapper taskMapperSpy;

    private Workflow workflow;
    private WorkflowVersion version;
    private WorkflowRun run;
    private Task task;

    @Test
    void completionCommittedAtDeadlineWinsAfterResolverWaitsForTaskLock() throws Exception {
        Fixture fixture = fixture();
        TaskMapper transactionTaskMapper = sqlSessionTemplate.getMapper(TaskMapper.class);
        CountDownLatch completionWritten = new CountDownLatch(1);
        CountDownLatch releaseCompletion = new CountDownLatch(1);
        CountDownLatch resolverReachedTaskLock = new CountDownLatch(1);
        CountDownLatch duplicateResolverStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            resolverReachedTaskLock.countDown();
            return transactionTaskMapper.getTaskByIdForUpdate(
                workspace.getId(), task.getId());
        }).when(taskMapperSpy).getTaskByIdForUpdate(workspace.getId(), task.getId());
        ExecutorService executor = Executors.newFixedThreadPool(3);

        try {
            Future<?> completion = executor.submit(() -> readCommitted().executeWithoutResult(
                status -> {
                    transactionTaskMapper.getTaskByIdForUpdate(workspace.getId(), task.getId());
                    jdbcTemplate.update(
                        "UPDATE task SET completed = TRUE, status = 'done' "
                            + "WHERE workspace_id = ? AND id = ?",
                        workspace.getId(), task.getId());
                    jdbcTemplate.update(
                        "INSERT INTO task_completion_event "
                            + "(workspace_id, task_id, occurred_at) VALUES (?, ?, ?)",
                        workspace.getId(), task.getId(), fixture.deadline());
                    completionWritten.countDown();
                    await(releaseCompletion);
                }));
            assertTrue(completionWritten.await(10, TimeUnit.SECONDS));

            Future<Boolean> resolution = executor.submit(() -> Boolean.TRUE.equals(
                readCommitted().execute(status -> fixture.service().resume(
                    workspace.getId(), run.getId(), fixture.leaseOwner()))));
            assertTrue(resolverReachedTaskLock.await(10, TimeUnit.SECONDS));
            Future<Boolean> duplicateResolution = executor.submit(() -> {
                duplicateResolverStarted.countDown();
                return Boolean.TRUE.equals(readCommitted().execute(status ->
                    fixture.service().resume(
                        workspace.getId(), run.getId(), fixture.leaseOwner())));
            });
            assertTrue(duplicateResolverStarted.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> resolution.get(1, TimeUnit.SECONDS));
            assertThrows(
                TimeoutException.class,
                () -> duplicateResolution.get(1, TimeUnit.SECONDS));

            releaseCompletion.countDown();
            completion.get(20, TimeUnit.SECONDS);
            assertTrue(resolution.get(20, TimeUnit.SECONDS));
            assertFalse(duplicateResolution.get(20, TimeUnit.SECONDS));
        } finally {
            releaseCompletion.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        WorkflowEventWait resolved = eventWaitMapper.getByRun(
            workspace.getId(), run.getId()).getFirst();
        assertEquals("completed", resolved.getResolution());
        assertEquals(fixture.deadline(), jdbcTemplate.queryForObject(
            "SELECT occurred_at FROM task_completion_event "
                + "WHERE workspace_id = ? AND id = ?",
            LocalDateTime.class,
            workspace.getId(), resolved.getMatchedEventId()));
        assertEquals(1, count(
            "SELECT COUNT(*) FROM task_completion_event "
                + "WHERE workspace_id = ? AND task_id = ?",
            workspace.getId(), task.getId()));
        assertEquals("completed", jdbcTemplate.queryForObject(
            "SELECT selected_outcome FROM workflow_step_run "
                + "WHERE workspace_id = ? AND workflow_run_id = ? AND node_id = 'wait-task'",
            String.class,
            workspace.getId(), run.getId()));
        assertEquals("end", jdbcTemplate.queryForObject(
            "SELECT current_node_id FROM workflow_run WHERE workspace_id = ? AND id = ?",
            String.class,
            workspace.getId(), run.getId()));
        assertNull(jdbcTemplate.queryForObject(
            "SELECT wait_kind FROM workflow_run WHERE workspace_id = ? AND id = ?",
            String.class,
            workspace.getId(), run.getId()));
    }

    private Fixture fixture() {
        task = newTask(currentUser, null, null);
        workflow = workflow();
        workflowMapper.insert(workflow);
        version = version(workflow);
        versionMapper.insert(version);
        LocalDateTime deadline = runMapper.currentTimestamp(workspace.getId(), workflow.getId());
        if (deadline == null) {
            throw new IllegalStateException("Database time is unavailable");
        }
        String leaseOwner = UUID.randomUUID().toString();
        run = run(workflow, version, deadline);
        runMapper.insertRun(run);
        jdbcTemplate.update(
            "UPDATE workflow_run SET status = 'running', wait_kind = 'event', "
                + "resume_at = ?, lease_owner = ?, lease_until = ? "
                + "WHERE workspace_id = ? AND id = ?",
            deadline,
            leaseOwner,
            deadline.plusMinutes(5),
            workspace.getId(),
            run.getId());
        WorkflowStepRun source = sourceStep(deadline);
        runMapper.insertStep(source);
        WorkflowStepRun waiting = waitingStep(deadline);
        runMapper.insertStep(waiting);
        WorkflowEventWait wait = new WorkflowEventWait();
        wait.setWorkspaceId(workspace.getId());
        wait.setWorkflowRunId(run.getId());
        wait.setWorkflowStepRunId(waiting.getId());
        wait.setSourceStepRunId(source.getId());
        wait.setNodeId("wait-task");
        wait.setSourceTaskId(task.getId());
        wait.setEventType("task.completed");
        wait.setTimeoutAt(deadline);
        eventWaitMapper.insertWait(wait);
        return new Fixture(service(compiled()), deadline, leaseOwner);
    }

    private WorkflowEventWaitResumeService service(CompiledWorkflow compiled) {
        WorkflowTraversalService traversal = mock(WorkflowTraversalService.class);
        WorkflowExecutionPrincipalService principals =
            mock(WorkflowExecutionPrincipalService.class);
        WorkflowRecordPolicyService policies = mock(WorkflowRecordPolicyService.class);
        WorkspaceService workspaces = mock(WorkspaceService.class);
        WorkspaceService.LockedPermissionSnapshot authorization =
            new WorkspaceService.LockedPermissionSnapshot(
                Map.of(currentUser.getId(), Set.of()),
                Map.of(currentUser.getId(), Set.of()));
        WorkflowExecutionPrincipal principal = new WorkflowExecutionPrincipal(
            currentUser, "owner", currentUser.getId(), currentUser.getId());
        when(workspaces.lockAndRequirePermissionsSnapshot(eq(workspace.getId()), any()))
            .thenReturn(authorization);
        when(principals.resolveLocked(
            eq(workspace.getId()), any(WorkflowVersion.class), eq(authorization)))
            .thenReturn(principal);
        when(traversal.compiled(any(WorkflowRun.class))).thenReturn(compiled);
        return new WorkflowEventWaitResumeService(
            runMapper,
            versionMapper,
            eventWaitMapper,
            taskMapperSpy,
            traversal,
            principals,
            policies,
            workspaces);
    }

    private Workflow workflow() {
        Workflow bean = new Workflow();
        bean.setWorkspaceId(workspace.getId());
        bean.setName("Wait concurrency " + unique());
        bean.setEnabled(false);
        bean.setRuntimeOwner("legacy");
        bean.setDraftRevision(0);
        bean.setDraftRecordType("deal");
        bean.setDraftExecutionMode("user");
        bean.setDraftRunAsUserId(currentUser.getId());
        bean.setDraftDefinitionJson(DEFINITION);
        bean.setDraftCanvasJson(CANVAS);
        bean.setCreatedById(currentUser.getId());
        bean.setUpdatedById(currentUser.getId());
        return bean;
    }

    private WorkflowVersion version(Workflow owner) {
        WorkflowVersion bean = new WorkflowVersion();
        bean.setWorkspaceId(workspace.getId());
        bean.setWorkflowId(owner.getId());
        bean.setVersionNumber(1);
        bean.setName(owner.getName());
        bean.setRecordType("deal");
        bean.setTriggerType("manual");
        bean.setTriggerConfig("{}");
        bean.setActionsJson("[]");
        bean.setExecutionMode("user");
        bean.setRunAsUserId(currentUser.getId());
        bean.setCreatedById(currentUser.getId());
        bean.setPublishedById(currentUser.getId());
        bean.setDefinitionJson(DEFINITION);
        bean.setCanvasJson(CANVAS);
        bean.setDefinitionHash(new byte[32]);
        return bean;
    }

    private WorkflowRun run(
            Workflow owner,
            WorkflowVersion pinnedVersion,
            LocalDateTime startedAt) {
        WorkflowRun bean = new WorkflowRun();
        bean.setWorkspaceId(workspace.getId());
        bean.setWorkflowId(owner.getId());
        bean.setWorkflowVersionId(pinnedVersion.getId());
        bean.setStatus("queued");
        bean.setTriggerType("manual");
        bean.setTriggerEvent("manual");
        bean.setTriggerKey("wait-" + unique());
        bean.setRecordType("deal");
        bean.setRecordId(1);
        bean.setDedupeKey("wait-" + unique());
        bean.setExecutionMode("user");
        bean.setActorUserId(currentUser.getId());
        bean.setAttributionUserId(currentUser.getId());
        bean.setLaunchInputsJson("{}");
        bean.setCurrentNodeId("wait-task");
        bean.setStartedAt(startedAt.minusMinutes(1));
        return bean;
    }

    private WorkflowStepRun sourceStep(LocalDateTime deadline) {
        WorkflowStepRun step = new WorkflowStepRun();
        step.setWorkspaceId(workspace.getId());
        step.setWorkflowRunId(run.getId());
        step.setSequenceNumber(0);
        step.setNodeId("create-task");
        step.setNodeType("action");
        step.setStatus("succeeded");
        step.setAttemptCount(1);
        step.setRetrySafety("transactional");
        step.setSelectedOutcome("next");
        step.setSelectedEdgeId("task-wait");
        step.setNextNodeId("wait-task");
        step.setActionOutputsJson("{\"taskId\":" + task.getId() + "}");
        step.setStartedAt(deadline.minusSeconds(2));
        step.setFinishedAt(deadline.minusSeconds(1));
        return step;
    }

    private WorkflowStepRun waitingStep(LocalDateTime deadline) {
        WorkflowStepRun step = new WorkflowStepRun();
        step.setWorkspaceId(workspace.getId());
        step.setWorkflowRunId(run.getId());
        step.setSequenceNumber(1);
        step.setNodeId("wait-task");
        step.setNodeType("wait");
        step.setStatus("waiting");
        step.setAttemptCount(1);
        step.setRetrySafety("none");
        step.setStartedAt(deadline.minusSeconds(1));
        return step;
    }

    private static CompiledWorkflow compiled() {
        WorkflowNode.Wait wait = new WorkflowNode.Wait(
            "wait-task",
            new WorkflowWaitConfig(
                "event",
                "task.completed",
                new WorkflowWaitConfig.Source("create-task", "taskId"),
                300));
        WorkflowNode.End end = new WorkflowNode.End("end");
        WorkflowEdge completed = new WorkflowEdge(
            "completed-edge", "wait-task", "end", WorkflowEdge.Outcome.COMPLETED);
        WorkflowEdge timeout = new WorkflowEdge(
            "timeout-edge", "wait-task", "end", WorkflowEdge.Outcome.TIMEOUT);
        return new CompiledWorkflow(
            2,
            "wait-task",
            Map.of("wait-task", wait, "end", end),
            Map.of("wait-task", NodeType.WAIT, "end", NodeType.END),
            Map.of("wait-task", Map.of(
                WorkflowEdge.Outcome.COMPLETED, completed,
                WorkflowEdge.Outcome.TIMEOUT, timeout)),
            List.of("wait-task", "end"),
            List.of(),
            null,
            null,
            null);
    }

    private TransactionTemplate readCommitted() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }

    private int count(String sql, Object... arguments) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        return value == null ? 0 : value;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test coordination");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test coordination was interrupted", exception);
        }
    }

    @AfterEach
    void cleanUpRows() {
        if (run != null) {
            jdbcTemplate.update(
                "DELETE FROM workflow_event_wait WHERE workspace_id = ? AND workflow_run_id = ?",
                workspace.getId(), run.getId());
        }
        if (task != null) {
            jdbcTemplate.update(
                "DELETE FROM task_completion_event WHERE workspace_id = ? AND task_id = ?",
                workspace.getId(), task.getId());
        }
        if (run != null) {
            jdbcTemplate.update(
                "DELETE FROM workflow_step_run WHERE workspace_id = ? AND workflow_run_id = ?",
                workspace.getId(), run.getId());
            jdbcTemplate.update(
                "DELETE FROM workflow_run WHERE workspace_id = ? AND id = ?",
                workspace.getId(), run.getId());
        }
        if (version != null) {
            jdbcTemplate.update(
                "DELETE FROM workflow_version WHERE workspace_id = ? AND id = ?",
                workspace.getId(), version.getId());
        }
        if (workflow != null) {
            jdbcTemplate.update(
                "DELETE FROM workflow WHERE workspace_id = ? AND id = ?",
                workspace.getId(), workflow.getId());
        }
        if (task != null) {
            jdbcTemplate.update(
                "DELETE FROM task WHERE workspace_id = ? AND id = ?",
                workspace.getId(), task.getId());
        }
        if (currentUser != null) {
            workspaceMapper.removeMember(workspace.getId(), currentUser.getId());
            userMapper.delete(currentUser.getId());
        }
    }

    private record Fixture(
        WorkflowEventWaitResumeService service,
        LocalDateTime deadline,
        String leaseOwner
    ) { }
}
