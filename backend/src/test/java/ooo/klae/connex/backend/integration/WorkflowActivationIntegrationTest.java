package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.Filter;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.interceptor.AsyncExecutionInterceptor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowTriggerOutbox;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.CsrfBootstrapDto;
import ooo.klae.connex.backend.dto.WorkflowRecipeInstallDto;
import ooo.klae.connex.backend.dto.WorkflowRecipePreviewDto;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationPushListener;
import ooo.klae.connex.backend.notifications.NotificationSourceChangedListener;
import ooo.klae.connex.backend.services.RuleTriggerListener;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.NullifyReference;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.TableLifecycle;

/**
 * Exercises activation authorization, atomic trigger admission, and the lock order that keeps
 * automation authoring clear of audited CRM writes, all through committed HTTP requests.
 */
@SpringBootTest(properties = {
    "connex.workflows.runtime.max-trigger-fanout=2",
    "connex.workflows.runtime.enabled=false",
    "connex.workflows.runtime.scheduling-enabled=false",
    "connex.rules.scheduling-enabled=false"
})
@UnenrolledPrivilegedFixture
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkflowActivationIntegrationTest {

    private static final String PASSWORD = "Workflow-Activation-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private UserMapper userMapper;
    @MockitoSpyBean private RoleMapper roleMapper;
    @Autowired private RuleMapper ruleMapper;
    @MockitoSpyBean private WorkflowMapper workflowMapper;
    @Autowired private PersonMapper personMapper;
    @Autowired private NoteMapper noteMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private TenantLifecycleMapper lifecycleMapper;
    @Autowired private TenantLifecycleControlMapper lifecycleControlMapper;
    @MockitoSpyBean private WorkspaceMapper workspaceMapper;
    @MockitoSpyBean private WorkflowTriggerOutboxMapper outboxMapper;
    @MockitoSpyBean private RuleTriggerListener triggerListener;

    private final ThreadLocal<String> contender = new ThreadLocal<>();
    private final List<CommittedFixture> pendingCleanup = new ArrayList<>();
    private final AtomicInteger pendingAsyncCallbacks = new AtomicInteger();
    private CommittedFixture fixture;
    private MockMvc mockMvc;
    private Workspace workspace;
    private Member author;
    private Member manager;

    @BeforeAll
    void trackAsyncListenerCompletion() {
        trackAsyncCallbacks(triggerListener);
        trackAsyncCallbacks(context.getBean(NotificationSourceChangedListener.class));
        trackAsyncCallbacks(context.getBean(NotificationPushListener.class));
    }

    /**
     * Counts submissions before the async interceptor dispatches and completes them after the real
     * callback returns, including callbacks it submits. Spring's fallback executor is not a bean.
     */
    private void trackAsyncCallbacks(Object listener) {
        Advised proxy = assertInstanceOf(Advised.class, listener);
        var advisors = proxy.getAdvisors();
        for (int index = 0; index < advisors.length; index++) {
            if (advisors[index] instanceof PointcutAdvisor advisor
                    && advisor.getAdvice() instanceof AsyncExecutionInterceptor) {
                MethodInterceptor submission = invocation -> {
                    pendingAsyncCallbacks.incrementAndGet();
                    try {
                        return invocation.proceed();
                    } catch (Throwable failure) {
                        pendingAsyncCallbacks.decrementAndGet();
                        throw failure;
                    }
                };
                MethodInterceptor completion = invocation -> {
                    try {
                        return invocation.proceed();
                    } finally {
                        pendingAsyncCallbacks.decrementAndGet();
                    }
                };
                proxy.addAdvisor(index, new DefaultPointcutAdvisor(advisor.getPointcut(), submission));
                proxy.addAdvisor(index + 2,
                    new DefaultPointcutAdvisor(advisor.getPointcut(), completion));
                return;
            }
        }
        throw new IllegalStateException("Listener has no async dispatch interceptor");
    }

    @BeforeEach
    void setUp() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        fixture = new CommittedFixture();
        pendingCleanup.add(fixture);
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
        String suffix = unique();
        Organization organization = fixture.organization;
        organization.setName("Workflow admission " + suffix);
        organization.setSlug("workflow-admission-org-" + suffix);
        organizationMapper.insert(organization);
        workspace = fixture.workspace;
        workspace.setOrgId(organization.getId());
        workspace.setName("Workflow admission " + suffix);
        workspace.setSlug("workflow-admission-" + suffix);
        workspaceMapper.insert(workspace);
        author = member(List.of("RULE_MANAGE", "PERSON_UPDATE", "NOTE_CREATE"));
        manager = member(List.of("RULE_MANAGE", "PERSON_UPDATE"));
    }

    @AfterEach
    void deleteCommittedFixture() {
        RequestContextHolder.resetRequestAttributes();
        contender.remove();
        if (fixture != null) {
            deleteCommittedFixture(fixture);
        }
    }

    @AfterAll
    void retryIncompleteFixtureCleanup() {
        assertAll(pendingCleanup.stream()
            .<Executable>map(committed -> () -> deleteCommittedFixture(committed)).toList());
        assertTrue(pendingCleanup.isEmpty());
    }

    private void awaitAsyncCallbacks() {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30))
            .until(() -> pendingAsyncCallbacks.get() == 0);
    }

    private void deleteCommittedFixture(CommittedFixture committed) {
        awaitAsyncCallbacks();
        for (MockHttpSession session : committed.sessions) {
            if (!session.isInvalid()) {
                session.invalidate();
            }
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            int workspaceId = committed.workspace.getId();
            int orgId = committed.organization.getId();
            if (workspaceId > 0) {
                WorkflowMapper realWorkflowMapper = sqlSessionTemplate.getMapper(WorkflowMapper.class);
                for (Workflow workflow : realWorkflowMapper.listByWorkspace(workspaceId, false)) {
                    realWorkflowMapper.updateLifecycle(workspaceId, workflow.getId(), false, null);
                    if ("canonical".equals(workflow.getRuntimeOwner())) {
                        assertNotNull(workflow.getActiveVersionId());
                        assertEquals(1, realWorkflowMapper.compareAndSwapRuntimeOwner(
                            workspaceId, workflow.getId(), workflow.getActiveVersionId(),
                            "canonical", "legacy", committed.users.getFirst().getId()));
                    }
                }
                List<TableLifecycle> tables = TenantLifecycleRegistry.declarations().values().stream()
                    .filter(TableLifecycle::direct)
                    .sorted(Comparator.comparingInt(TableLifecycle::deleteOrder)).toList();
                for (TableLifecycle table : tables) {
                    for (var preparation : table.preparations()) {
                        if (preparation instanceof NullifyReference reference) {
                            lifecycleMapper.nullifyReference(workspaceId, table, reference);
                        }
                    }
                }
                for (TableLifecycle table : tables) {
                    while (lifecycleMapper.deleteDirectBatch(workspaceId, table, 100) > 0) {
                    }
                }
                for (TableLifecycle table : TenantLifecycleRegistry.declarations().values()) {
                    assertEquals(0, lifecycleMapper.countRows(workspaceId, table), table.table());
                }
                lifecycleControlMapper.markWorkspaceTearingDown(orgId, workspaceId);
                lifecycleControlMapper.deleteWorkspace(orgId, workspaceId);
                assertEquals(0, lifecycleControlMapper.countWorkspaces(orgId));
                assertTrue(workspaceMapper.getMembers(workspaceId).isEmpty());
                assertTrue(roleMapper.findRolesByWorkspace(workspaceId).isEmpty());
            }
            if (orgId > 0) {
                lifecycleControlMapper.markOrganizationTearingDown(orgId);
                lifecycleControlMapper.deleteOrganization(orgId);
                assertNull(organizationMapper.getById(orgId));
            }
            for (User user : committed.users) {
                if (user.getId() > 0) {
                    userMapper.delete(user.getId());
                    assertNull(userMapper.getUserById(user.getId()));
                }
            }
        });
        pendingCleanup.remove(committed);
    }

    @Test
    void cleanupBarrierWaitsForDispatchedTriggerCallbacks() throws Exception {
        Person person = person(author.user().getId());
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        CountDownLatch barrierStarted = new CountDownLatch(1);
        CountDownLatch barrierCompleted = new CountDownLatch(1);
        RuleTriggerListener listener = AopTestUtils.getUltimateTargetObject(triggerListener);
        doAnswer(invocation -> {
            listenerEntered.countDown();
            await(releaseListener);
            return invocation.callRealMethod();
        }).when(listener).onTrigger(argThat(event -> event != null
            && event.workspaceId() == workspace.getId()
            && event.entityId() == person.getId()));
        try (var executor = Executors.newSingleThreadExecutor()) {
            try {
                updateOwner(manager, person, manager.user().getId());
                await(listenerEntered);
                var barrier = executor.submit(() -> {
                    barrierStarted.countDown();
                    awaitAsyncCallbacks();
                    barrierCompleted.countDown();
                });
                await(barrierStarted);
                assertFalse(barrierCompleted.await(5, TimeUnit.SECONDS),
                    "Cleanup must wait for the dispatched listener to finish");
                releaseListener.countDown();
                barrier.get(30, TimeUnit.SECONDS);
                assertEquals(0, pendingAsyncCallbacks.get());
            } finally {
                releaseListener.countDown();
            }
        }
    }

    @Test
    void unknownExecutionModeIsRejectedAtTheDraftRequestBoundary() throws Exception {
        int ruleId = createRule(manager, ruleBody(false, "person.owner_changed", "notify"));
        Workflow workflow = workflowMapper.getByLegacyRuleId(workspace.getId(), ruleId);
        assertNotNull(workflow);
        Map<String, Object> body = workflowDraftBody(workflow, "unknown");

        perform(manager, post("/api/workflows").content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Workflow execution mode must be user or system"));
        body.put("expectedRevision", workflow.getDraftRevision());
        perform(manager, put("/api/workflows/{id}/draft", Integer.MAX_VALUE)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Workflow execution mode must be user or system"));

        assertEquals(1, workflowMapper.listByWorkspace(workspace.getId(), false).size());
        assertEquals(workflow.getDraftRevision(),
            workflowMapper.getById(workspace.getId(), workflow.getId()).getDraftRevision());
    }

    @Test
    void systemDraftCreationAndUpdateRequireTheActorsSystemAuthorization() throws Exception {
        int ruleId = createRule(manager, ruleBody(false, "person.owner_changed", "notify"));
        Workflow workflow = workflowMapper.getByLegacyRuleId(workspace.getId(), ruleId);
        assertNotNull(workflow);
        Map<String, Object> body = workflowDraftBody(workflow, "system");

        perform(manager, post("/api/workflows").content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("Requires a built-in admin role in this workspace"));
        body.put("expectedRevision", workflow.getDraftRevision());
        perform(manager, put("/api/workflows/{id}/draft", workflow.getId())
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("Requires a built-in admin role in this workspace"));

        Workflow unchanged = workflowMapper.getById(workspace.getId(), workflow.getId());
        assertEquals("user", unchanged.getDraftExecutionMode());
        assertEquals(workflow.getDraftRevision(), unchanged.getDraftRevision());
        assertEquals(1, workflowMapper.listByWorkspace(workspace.getId(), false).size());
    }

    @Test
    void memberWithoutNoteCreateCannotEnablePrivilegedLegacyRuleOrProduceANote() throws Exception {
        int ruleId = createRule(author, ruleBody(false, "person.owner_changed", "create_note"));
        Workflow workflow = workflowMapper.getByLegacyRuleId(workspace.getId(), ruleId);
        assertNotNull(workflow);
        Person person = person(author.user().getId());

        perform(manager, post("/api/notes").content(objectMapper.writeValueAsString(
                Map.of("content", "Forbidden note", "person", person.getId()))))
            .andExpect(status().isForbidden());
        perform(manager, put("/api/rules/{id}", ruleId)
                .content(ruleBody(true, "person.owner_changed", "create_note")))
            .andExpect(status().isForbidden());
        perform(manager, get("/api/workflows/legacy-rules/{id}", ruleId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.workflowId").value(workflow.getId()));
        perform(manager, post("/api/workflows/{id}/enable", workflow.getId()))
            .andExpect(status().isForbidden());

        assertFalse(workflowMapper.getById(workspace.getId(), workflow.getId()).isEnabled());
        assertFalse(ruleMapper.getById(workspace.getId(), ruleId).isEnabled());
        CountDownLatch listenerCompleted = new CountDownLatch(1);
        RuleTriggerListener listener = AopTestUtils.getUltimateTargetObject(triggerListener);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            listenerCompleted.countDown();
            return null;
        }).when(listener).onTrigger(argThat(event -> event != null
            && event.workspaceId() == workspace.getId()
            && event.entityId() == person.getId()
            && "person.owner_changed".equals(event.event())));
        updateOwner(manager, person, manager.user().getId());
        assertTrue(listenerCompleted.await(30, TimeUnit.SECONDS));
        assertTrue(noteMapper.getNotesByPersonId(workspace.getId(), person.getId()).isEmpty());
        assertTrue(outboxMapper.findEntityTargets(
            workspace.getId(), "person", "person.owner_changed", 3).isEmpty());
    }

    @Test
    void thirdMatchingRuleIsRejectedAndOwnerChangeCommitsTwoDurableTargets() throws Exception {
        createRule(manager, ruleBody(true, "person.owner_changed", "notify"));
        createRule(manager, ruleBody(true, "person.owner_changed", "notify"));

        perform(manager, post("/api/rules")
                .content(ruleBody(true, "person.owner_changed", "notify")))
            .andExpect(status().isConflict());

        assertEquals(2, workflowMapper.listByWorkspace(workspace.getId(), false).size());
        assertEquals(2, ruleMapper.getByWorkspace(workspace.getId()).size());
        Person person = person(author.user().getId());
        updateOwner(manager, person, manager.user().getId());
        ArgumentCaptor<WorkflowTriggerOutbox> inserted =
            ArgumentCaptor.forClass(WorkflowTriggerOutbox.class);
        verify(outboxMapper, times(2)).insert(inserted.capture());
        for (WorkflowTriggerOutbox target : inserted.getAllValues()) {
            WorkflowTriggerOutbox committed = outboxMapper.getById(workspace.getId(), target.getId());
            assertNotNull(committed);
            assertEquals(person.getId(), committed.getRecordId());
            assertEquals("person.owner_changed", committed.getTriggerEvent());
        }
    }

    @Test
    void enableLegacyUpdateAndResumeReadmitCapacityReleasedByPause() throws Exception {
        int firstRule = createRule(manager, ruleBody(true, "person.owner_changed", "notify"));
        createRule(manager, ruleBody(true, "person.owner_changed", "notify"));
        int thirdRule = createRule(manager, ruleBody(false, "person.owner_changed", "notify"));
        int first = workflowMapper.getByLegacyRuleId(workspace.getId(), firstRule).getId();
        int third = workflowMapper.getByLegacyRuleId(workspace.getId(), thirdRule).getId();

        perform(manager, post("/api/workflows/{id}/enable", third))
            .andExpect(status().isConflict());
        perform(manager, put("/api/rules/{id}", thirdRule)
                .content(ruleBody(true, "person.owner_changed", "notify")))
            .andExpect(status().isConflict());
        perform(manager, post("/api/workflows/{id}/enable", first))
            .andExpect(status().isOk());
        perform(manager, post("/api/workflows/{id}/pause", first))
            .andExpect(status().isOk());
        perform(manager, post("/api/workflows/{id}/enable", third))
            .andExpect(status().isOk());
        perform(manager, post("/api/workflows/{id}/resume", first))
            .andExpect(status().isConflict());
        assertNotNull(workflowMapper.getById(workspace.getId(), first).getIntakePausedAt());
        perform(manager, post("/api/workflows/{id}/disable", third))
            .andExpect(status().isOk());
        perform(manager, post("/api/workflows/{id}/resume", first))
            .andExpect(status().isOk());
        assertEquals(2, outboxMapper.findEntityTargets(
            workspace.getId(), "person", "person.owner_changed", 3).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"legacy", "canonical"})
    void enabledPublicationCannotMoveIntoAnExhaustedTrigger(String runtimeOwner) throws Exception {
        createRule(manager, ruleBody(true, "person.owner_changed", "notify"));
        createRule(manager, ruleBody(true, "person.owner_changed", "notify"));
        int movingRule = createRule(manager, ruleBody(true, "person.updated", "notify"));
        Workflow moving = workflowMapper.getByLegacyRuleId(workspace.getId(), movingRule);
        assertNotNull(moving);
        try {
            if ("canonical".equals(runtimeOwner)) {
                assertEquals(1, ruleMapper.updateEnabled(workspace.getId(), movingRule, false));
                assertEquals(1, workflowMapper.compareAndSwapRuntimeOwner(
                    workspace.getId(), moving.getId(), moving.getActiveVersionId(),
                    "legacy", "canonical", manager.user().getId()));
            } else {
                perform(manager, put("/api/rules/{id}", movingRule)
                        .content(ruleBody(true, "person.owner_changed", "notify")))
                    .andExpect(status().isConflict());
            }
            String definition = moving.getDraftDefinitionJson()
                .replace("person.updated", "person.owner_changed");
            String draft = objectMapper.writeValueAsString(Map.of(
                "name", moving.getName(),
                "recordType", "person",
                "executionMode", "user",
                "expectedRevision", moving.getDraftRevision(),
                "definition", objectMapper.readTree(definition),
                "canvas", objectMapper.readTree(moving.getDraftCanvasJson())));
            perform(manager, put("/api/workflows/{id}/draft", moving.getId()).content(draft))
                .andExpect(status().isOk());
            perform(manager, post("/api/workflows/{id}/publish", moving.getId())
                    .content(objectMapper.writeValueAsString(
                        Map.of("expectedRevision", moving.getDraftRevision() + 1))))
                .andExpect(status().isConflict());

            assertEquals(moving.getActiveVersionId(),
                workflowMapper.getById(workspace.getId(), moving.getId()).getActiveVersionId());
            assertEquals(2, outboxMapper.findEntityTargets(
                workspace.getId(), "person", "person.owner_changed", 3).size());
            assertEquals(1, outboxMapper.findEntityTargets(
                workspace.getId(), "person", "person.updated", 3).size());
        } finally {
            if ("canonical".equals(runtimeOwner)) {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    workflowMapper.updateLifecycle(workspace.getId(), moving.getId(), false, null);
                    ruleMapper.updateEnabled(workspace.getId(), movingRule, false);
                });
            }
        }
        assertEquals(ruleMapper.getById(workspace.getId(), movingRule).isEnabled(),
            workflowMapper.getById(workspace.getId(), moving.getId()).isEnabled());
    }

    @Test
    void admissionChecksEveryEventAndScheduleCadenceAcrossRecordTypes() throws Exception {
        createRule(manager, ruleBody(true, "person.owner_changed", "notify"));
        createRule(manager, ruleBody(true, "person.owner_changed", "notify"));
        String multipleEvents = ruleBody(true, "person.updated", "notify")
            .replace("\"person.updated\"", "\"person.updated\",\"person.owner_changed\"");
        perform(manager, post("/api/rules").content(multipleEvents))
            .andExpect(status().isConflict());
        createRule(manager, scheduleBody("person", "daily"));
        createRule(manager, scheduleBody("company", "daily"));
        perform(manager, post("/api/rules").content(scheduleBody("deal", "daily")))
            .andExpect(status().isConflict());
        createRule(manager, scheduleBody("deal", "weekly"));
        assertEquals(2, outboxMapper.findScheduleTargets(workspace.getId(), "daily", 3).size());
    }

    @Test
    void concurrentActivationsSerializeAtAdmissionMutexAndRecountAfterWaiting() throws Exception {
        createRule(author, ruleBody(true, "person.owner_changed", "notify"));
        int firstRule = createRule(author, ruleBody(false, "person.owner_changed", "notify"));
        int secondRule = createRule(manager, ruleBody(false, "person.owner_changed", "notify"));
        int first = workflowMapper.getByLegacyRuleId(workspace.getId(), firstRule).getId();
        int second = workflowMapper.getByLegacyRuleId(workspace.getId(), secondRule).getId();
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        CountDownLatch secondLocked = new CountDownLatch(1);
        WorkflowMapper realWorkflowMapper = sqlSessionTemplate.getMapper(WorkflowMapper.class);
        doAnswer(invocation -> {
            String current = contender.get();
            if ("second".equals(current)) {
                secondAttempted.countDown();
            }
            realWorkflowMapper.acquireTriggerAdmissionMutex(invocation.getArgument(0));
            if ("first".equals(current)) {
                firstLocked.countDown();
                await(releaseFirst);
            } else if ("second".equals(current)) {
                secondLocked.countDown();
            }
            return null;
        }).when(workflowMapper).acquireTriggerAdmissionMutex(workspace.getId());

        var executor = Executors.newFixedThreadPool(2);
        try {
            var firstResult = executor.submit(() -> enableAs("first", author, first));
            assertTrue(firstLocked.await(30, TimeUnit.SECONDS),
                "The first activation must reach the admission upsert and hold its row lock");
            var secondResult = executor.submit(() -> enableAs("second", manager, second));
            assertTrue(secondAttempted.await(30, TimeUnit.SECONDS),
                "The second activation must reach the admission upsert before it blocks");
            assertFalse(secondLocked.await(5, TimeUnit.SECONDS),
                "The admission upsert must block the second activation until the first "
                    + "transaction commits");
            releaseFirst.countDown();

            assertEquals(200, firstResult.get(30, TimeUnit.SECONDS));
            assertEquals(409, secondResult.get(30, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
        assertTrue(workflowMapper.getById(workspace.getId(), first).isEnabled());
        assertFalse(workflowMapper.getById(workspace.getId(), second).isEnabled());
        assertFalse(ruleMapper.getById(workspace.getId(), secondRule).isEnabled());
        assertEquals(2, outboxMapper.findEntityTargets(
            workspace.getId(), "person", "person.owner_changed", 3).size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyRuleUpdateSkipsAdmissionWhenDisablingAndWaitsWhenEnabling(boolean enabled)
            throws Exception {
        String body = ruleBody(!enabled, "person.owner_changed", "notify");
        int ruleId = createRule(author, body);
        int workflowId = workflowMapper.getByLegacyRuleId(workspace.getId(), ruleId).getId();
        createRule(manager, ruleBody(true, "person.owner_changed", "notify"));
        int activationRule = createRule(manager, ruleBody(false, "person.owner_changed", "notify"));
        int activationWorkflow = workflowMapper.getByLegacyRuleId(
            workspace.getId(), activationRule).getId();
        String updateBody = body.replace("\"enabled\":" + !enabled, "\"enabled\":" + enabled);
        CountDownLatch activationHoldsMutex = new CountDownLatch(1);
        CountDownLatch releaseActivation = new CountDownLatch(1);
        CountDownLatch legacyRequestsMutex = new CountDownLatch(1);
        CountDownLatch legacyHoldsMutex = new CountDownLatch(1);
        CountDownLatch legacyCompleted = new CountDownLatch(1);
        WorkflowMapper realWorkflowMapper = sqlSessionTemplate.getMapper(WorkflowMapper.class);
        doAnswer(invocation -> {
            String current = contender.get();
            if ("legacy".equals(current)) {
                legacyRequestsMutex.countDown();
            }
            realWorkflowMapper.acquireTriggerAdmissionMutex(invocation.getArgument(0));
            if ("activation".equals(current)) {
                activationHoldsMutex.countDown();
                await(releaseActivation);
            } else if ("legacy".equals(current)) {
                legacyHoldsMutex.countDown();
            }
            return null;
        }).when(workflowMapper).acquireTriggerAdmissionMutex(workspace.getId());

        var executor = Executors.newFixedThreadPool(2);
        try {
            var activation = executor.submit(() -> enableAs("activation", manager, activationWorkflow));
            assertTrue(activationHoldsMutex.await(30, TimeUnit.SECONDS),
                "Activation must hold the real admission upsert before the legacy update starts");
            var update = executor.submit(() -> {
                contender.set("legacy");
                try {
                    return perform(author, put("/api/rules/{id}", ruleId).content(updateBody))
                        .andReturn().getResponse().getStatus();
                } finally {
                    legacyCompleted.countDown();
                    contender.remove();
                    RequestContextHolder.resetRequestAttributes();
                }
            });
            if (enabled) {
                assertTrue(legacyRequestsMutex.await(30, TimeUnit.SECONDS),
                    "Legacy enable must request admission before checking capacity");
                assertFalse(legacyHoldsMutex.await(5, TimeUnit.SECONDS),
                    "Legacy enable must wait for the activation's admission transaction to commit");
            } else {
                assertTrue(legacyCompleted.await(10, TimeUnit.SECONDS),
                    "Legacy disable must commit while the unrelated activation still holds admission");
                assertEquals(200, update.get(30, TimeUnit.SECONDS));
                assertEquals(1, legacyRequestsMutex.getCount(),
                    "Legacy disable must not request the admission mutex");
                assertFalse(ruleMapper.getById(workspace.getId(), ruleId).isEnabled());
                assertFalse(workflowMapper.getById(workspace.getId(), workflowId).isEnabled());
            }
            releaseActivation.countDown();
            assertEquals(200, activation.get(30, TimeUnit.SECONDS));
            assertEquals(enabled ? 409 : 200, update.get(30, TimeUnit.SECONDS));
        } finally {
            releaseActivation.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
        assertFalse(ruleMapper.getById(workspace.getId(), ruleId).isEnabled());
        assertFalse(workflowMapper.getById(workspace.getId(), workflowId).isEnabled());
        assertTrue(workflowMapper.getById(workspace.getId(), activationWorkflow).isEnabled());
        assertEquals(2, outboxMapper.findEntityTargets(
            workspace.getId(), "person", "person.owner_changed", 3).size());
    }

    /**
     * Parks installation after its first real role lock. With admission already held, enable must
     * wait at the upsert; otherwise it must hold admission and request the shared role before
     * installation resumes, reproducing the former MySQL deadlock instead of a latch timeout.
     */
    @Test
    void recipeInstallAndEnableWithASharedCustomRoleBothCommitWithoutADeadlock() throws Exception {
        Integer sharedRoleId = workspaceMapper.getMemberRoleId(workspace.getId(), author.user().getId());
        assertNotNull(sharedRoleId);
        roleMapper.insertPermissions(workspace.getId(), sharedRoleId, List.of("TASK_CREATE"));
        assertEquals(1, workspaceMapper.setMemberCustomRole(
            workspace.getId(), manager.user().getId(), sharedRoleId));
        int ruleId = createRule(manager, ruleBody(false, "person.owner_changed", "notify"));
        Workflow enabledWorkflow = workflowMapper.getByLegacyRuleId(workspace.getId(), ruleId);
        assertNotNull(enabledWorkflow);
        String recipeKey = "person-job-change-follow-up";
        Map<String, Object> parameters = Map.of(
            "actorUserId", author.user().getId(),
            "targetUserId", author.user().getId(),
            "taskTitle", "Follow up after job change",
            "dueInDays", 7);
        MvcResult previewResult = perform(author, post("/api/workflow-recipes/{key}/preview", recipeKey)
                .content(objectMapper.writeValueAsString(Map.of("parameters", parameters))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.validation.canPublish").value(true))
            .andExpect(jsonPath("$.unresolvedParameters").isEmpty())
            .andReturn();
        WorkflowRecipePreviewDto preview = objectMapper.readValue(
            previewResult.getResponse().getContentAsString(), WorkflowRecipePreviewDto.class);
        assertNotNull(preview);
        assertNotNull(preview.previewHash());
        String installBody = objectMapper.writeValueAsString(Map.of(
            "previewHash", preview.previewHash(), "parameters", parameters));
        CountDownLatch installHoldsRole = new CountDownLatch(1);
        CountDownLatch installHoldsMutex = new CountDownLatch(1);
        CountDownLatch releaseInstall = new CountDownLatch(1);
        CountDownLatch enableRequestsMutex = new CountDownLatch(1);
        CountDownLatch enableHoldsMutex = new CountDownLatch(1);
        CountDownLatch enableRequestsRole = new CountDownLatch(1);
        WorkflowMapper realWorkflowMapper = sqlSessionTemplate.getMapper(WorkflowMapper.class);
        RoleMapper realRoleMapper = sqlSessionTemplate.getMapper(RoleMapper.class);
        doAnswer(invocation -> {
            String current = contender.get();
            if ("enable".equals(current)) {
                enableRequestsMutex.countDown();
            }
            realWorkflowMapper.acquireTriggerAdmissionMutex(invocation.getArgument(0));
            if ("install".equals(current)) {
                installHoldsMutex.countDown();
            } else if ("enable".equals(current)) {
                enableHoldsMutex.countDown();
            }
            return null;
        }).when(workflowMapper).acquireTriggerAdmissionMutex(workspace.getId());
        doAnswer(invocation -> {
            String current = contender.get();
            if ("enable".equals(current)) {
                enableRequestsRole.countDown();
            }
            Integer locked = realRoleMapper.lockRole(invocation.getArgument(0), invocation.getArgument(1));
            assertNotNull(locked);
            if ("install".equals(current) && installHoldsRole.getCount() > 0) {
                installHoldsRole.countDown();
                await(releaseInstall);
            }
            return locked;
        }).when(roleMapper).lockRole(workspace.getId(), sharedRoleId);

        var executor = Executors.newFixedThreadPool(2);
        try {
            var installation = executor.submit(() -> installAs(author, recipeKey, installBody));
            assertTrue(installHoldsRole.await(30, TimeUnit.SECONDS),
                "Installation must retain the shared custom-role row before enable starts");
            boolean admissionBeforeRole = installHoldsMutex.getCount() == 0;
            var activation = executor.submit(() -> enableAs("enable", manager, enabledWorkflow.getId()));
            assertTrue(enableRequestsMutex.await(30, TimeUnit.SECONDS),
                "Enable must reach the admission upsert while installation holds the role");
            if (admissionBeforeRole) {
                assertFalse(enableHoldsMutex.await(5, TimeUnit.SECONDS),
                    "Enable must wait at admission before it can request the shared role");
            } else {
                assertTrue(enableHoldsMutex.await(30, TimeUnit.SECONDS),
                    "The reversed order must let enable acquire admission before installation publishes");
                assertTrue(enableRequestsRole.await(30, TimeUnit.SECONDS),
                    "Enable must request installation's role while retaining admission");
            }
            releaseInstall.countDown();

            MvcResult installedResult = installation.get(30, TimeUnit.SECONDS);
            assertNull(installedResult.getResolvedException());
            assertEquals(200, installedResult.getResponse().getStatus());
            assertEquals(200, activation.get(30, TimeUnit.SECONDS));
            assertTrue(admissionBeforeRole, "Installation must acquire admission in its first lock pass");
            WorkflowRecipeInstallDto installed = objectMapper.readValue(
                installedResult.getResponse().getContentAsString(), WorkflowRecipeInstallDto.class);
            assertNotNull(installed);
            assertNotNull(installed.workflow());
            Workflow committed = workflowMapper.getById(workspace.getId(), installed.workflow().id());
            assertNotNull(committed);
            assertNotNull(committed.getActiveVersionId());
            assertFalse(committed.isEnabled());
            assertEquals("canonical", committed.getRuntimeOwner());
            Workflow activated = workflowMapper.getById(workspace.getId(), enabledWorkflow.getId());
            assertNotNull(activated);
            assertTrue(activated.isEnabled());
            var activatedRule = ruleMapper.getById(workspace.getId(), ruleId);
            assertNotNull(activatedRule);
            assertTrue(activatedRule.isEnabled());
        } finally {
            releaseInstall.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    @Test
    void auditedOwnerUpdateAndConcurrentActivationBothCommitWithoutADeadlock() throws Exception {
        int ruleId = createRule(manager, ruleBody(false, "person.owner_changed", "notify"));
        int workflowId = workflowMapper.getByLegacyRuleId(workspace.getId(), ruleId).getId();
        Person person = person(author.user().getId());
        CountDownLatch activationHoldsMutex = new CountDownLatch(1);
        CountDownLatch releaseActivation = new CountDownLatch(1);
        CountDownLatch ownerRequestedRoot = new CountDownLatch(1);
        CountDownLatch ownerHoldsRoot = new CountDownLatch(1);
        WorkflowMapper realWorkflowMapper = sqlSessionTemplate.getMapper(WorkflowMapper.class);
        WorkspaceMapper realWorkspaceMapper = sqlSessionTemplate.getMapper(WorkspaceMapper.class);
        doAnswer(invocation -> {
            realWorkflowMapper.acquireTriggerAdmissionMutex(invocation.getArgument(0));
            if ("activation".equals(contender.get())) {
                activationHoldsMutex.countDown();
                await(releaseActivation);
            }
            return null;
        }).when(workflowMapper).acquireTriggerAdmissionMutex(workspace.getId());
        doAnswer(invocation -> {
            boolean owner = "owner".equals(contender.get());
            if (owner) {
                ownerRequestedRoot.countDown();
            }
            Integer locked = realWorkspaceMapper.lockWorkspaceForShare(invocation.getArgument(0));
            if (owner) {
                ownerHoldsRoot.countDown();
            }
            return locked;
        }).when(workspaceMapper).lockWorkspaceForShare(workspace.getId());

        var executor = Executors.newFixedThreadPool(2);
        try {
            var activation = executor.submit(() -> enableAs("activation", manager, workflowId));
            assertTrue(activationHoldsMutex.await(30, TimeUnit.SECONDS),
                "Workflow activation must hold the automation admission mutex before the audited "
                    + "owner update starts");
            var ownerChange = executor.submit(() -> updateOwnerAs(
                "owner", author, person, manager.user().getId()));
            assertTrue(ownerRequestedRoot.await(30, TimeUnit.SECONDS),
                "The audited owner update must reach the workspace root while the admission mutex "
                    + "is held");
            assertTrue(ownerHoldsRoot.await(30, TimeUnit.SECONDS),
                "The audited owner update must still take the workspace root FOR SHARE while "
                    + "workflow activation holds the automation admission mutex");
            assertEquals(200, ownerChange.get(30, TimeUnit.SECONDS));
            releaseActivation.countDown();
            assertEquals(200, activation.get(30, TimeUnit.SECONDS));
        } finally {
            releaseActivation.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
        assertTrue(workflowMapper.getById(workspace.getId(), workflowId).isEnabled());
        assertEquals(manager.user().getId(),
            personMapper.getPersonById(workspace.getId(), person.getId()).getOwnerId());
    }

    private int updateOwnerAs(
            String name, Member actor, Person person, int ownerId) throws Exception {
        contender.set(name);
        try {
            return perform(actor, put("/api/persons/{id}/owner", person.getId())
                    .content(objectMapper.writeValueAsString(Map.of("ownerId", ownerId))))
                .andReturn().getResponse().getStatus();
        } finally {
            contender.remove();
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private MvcResult installAs(Member actor, String recipeKey, String body) throws Exception {
        contender.set("install");
        try {
            return perform(actor, post("/api/workflow-recipes/{key}/install", recipeKey).content(body))
                .andReturn();
        } finally {
            contender.remove();
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private int enableAs(String name, Member actor, int workflowId) throws Exception {
        contender.set(name);
        try {
            return perform(actor, post("/api/workflows/{id}/enable", workflowId))
                .andReturn().getResponse().getStatus();
        } finally {
            contender.remove();
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private void updateOwner(Member actor, Person person, int ownerId) throws Exception {
        perform(actor, put("/api/persons/{id}/owner", person.getId())
                .content(objectMapper.writeValueAsString(Map.of("ownerId", ownerId))))
            .andExpect(status().isOk());
        assertEquals(ownerId,
            personMapper.getPersonById(workspace.getId(), person.getId()).getOwnerId());
    }

    private ResultActions perform(Member actor, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("X-Workspace-Id", workspace.getId())
            .session(actor.session()).contentType(MediaType.APPLICATION_JSON)
            .header(actor.csrf().headerName(), actor.csrf().token()));
    }

    private int createRule(Member actor, String body) throws Exception {
        MvcResult result = perform(actor, post("/api/rules").content(body))
            .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").intValue();
    }

    private Map<String, Object> workflowDraftBody(Workflow workflow, String executionMode) throws Exception {
        return new LinkedHashMap<>(Map.of(
            "name", workflow.getName(),
            "recordType", workflow.getDraftRecordType(),
            "executionMode", executionMode,
            "definition", objectMapper.readTree(workflow.getDraftDefinitionJson()),
            "canvas", objectMapper.readTree(workflow.getDraftCanvasJson())));
    }

    private String ruleBody(boolean enabled, String event, String actionType) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
            "name", "Admission rule " + unique(),
            "enabled", enabled,
            "recordType", "person",
            "executionMode", "user",
            "trigger", Map.of("type", "entity_change", "events", List.of(event)),
            "actions", List.of(Map.of("type", actionType, "title", "Admission", "body", "Test note"))));
    }

    private String scheduleBody(String recordType, String cadence) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
            "name", "Schedule admission " + unique(),
            "enabled", true,
            "recordType", recordType,
            "executionMode", "user",
            "trigger", Map.of("type", "schedule", "cadence", cadence),
            "condition", Map.of("match", "all", "conditions",
                List.of(Map.of("type", "field", "field", "name", "op", "contains", "value", "Test"))),
            "actions", List.of(Map.of("type", "notify", "title", "Schedule"))));
    }

    private Person person(int ownerId) {
        Person person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setName("Admission contact " + unique());
        person.setOwnerId(ownerId);
        personMapper.insert(person);
        return person;
    }

    private Member member(List<String> permissions) throws Exception {
        String suffix = unique();
        User user = new User();
        fixture.users.add(user);
        user.setUsername("workflow_admission_" + suffix);
        user.setDisplayName("Workflow admission " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), "member");
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("Workflow author " + suffix);
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(workspace.getId(), role.getId(), permissions);
        workspaceMapper.setMemberCustomRole(workspace.getId(), user.getId(), role.getId());
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("username", user.getUsername(), "password", PASSWORD))))
            .andExpect(status().isOk()).andReturn();
        MockHttpSession session = assertInstanceOf(
            MockHttpSession.class, login.getRequest().getSession(false));
        fixture.sessions.add(session);
        MvcResult bootstrap = mockMvc.perform(get("/api/auth/csrf").session(session))
            .andExpect(status().isOk()).andReturn();
        CsrfBootstrapDto csrf = objectMapper.readValue(
            bootstrap.getResponse().getContentAsString(), CsrfBootstrapDto.class);
        assertNotNull(csrf);
        assertNotNull(csrf.headerName());
        assertNotNull(csrf.token());
        assertNotNull(csrf.requestIdentity());
        return new Member(user, session, csrf);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(30, TimeUnit.SECONDS), "Concurrent activation did not resume");
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 12);
    }

    private record Member(User user, MockHttpSession session, CsrfBootstrapDto csrf) { }

    private static final class CommittedFixture {
        private final Organization organization = new Organization();
        private final Workspace workspace = new Workspace();
        private final List<User> users = new ArrayList<>();
        private final List<MockHttpSession> sessions = new ArrayList<>();
    }
}
