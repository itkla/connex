package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.RuleTriggerPublisher;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.TenantContext;
import tools.jackson.databind.ObjectMapper;

/** Exercises assistant write lock ordering against real MySQL row locks. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiAssistantWriteToolConcurrencyIntegrationTest {
    @Autowired private AiAssistantWriteToolService writeToolService;
    @Autowired private AiRestrictionEpoch restrictionEpoch;
    @Autowired private AiChatMapper chatMapper;
    @Autowired private CompanyMapper companyMapper;
    @Autowired private DealMapper dealMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private PersonMapper personMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @Autowired private TagMapper tagMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private TenantContext tenantContext;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DealService dealService;
    @MockitoSpyBean private AiChatMapper chatMapperSpy;
    @MockitoSpyBean private DealMapper dealMapperSpy;
    @MockitoSpyBean private PersonMapper personMapperSpy;
    @MockitoBean private AuditService auditService;
    @MockitoBean private NotificationChangePublisher notificationChanges;
    @MockitoBean private RuleTriggerPublisher ruleTriggers;

    private Organization organization;
    private Workspace workspace;
    private User firstActor;
    private User secondActor;
    private Person person;
    private Tag tag;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Assistant write locks " + unique);
        organization.setSlug("assistant-write-locks-" + unique);
        organizationMapper.insert(organization);

        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Assistant write locks " + unique);
        workspace.setSlug("assistant-write-locks-" + unique);
        workspaceMapper.insert(workspace);

        firstActor = user("assistant-lock-first-" + unique, "First Actor " + unique);
        secondActor = user("assistant-lock-second-" + unique, "Second Actor " + unique);
        workspaceMapper.addMember(workspace.getId(), firstActor.getId(), "owner");
        workspaceMapper.addMember(workspace.getId(), secondActor.getId(), "owner");

        person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setName("Assistant target " + unique);
        personMapper.insert(person);

        tag = new Tag();
        tag.setWorkspaceId(workspace.getId());
        tag.setName("Assistant tag " + unique);
        tagMapper.insert(tag);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        tenantContext.clear();
        if (workspace != null) {
            jdbcTemplate.update("DELETE FROM ai_chat_tool_call WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM ai_chat_turn WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM ai_chat_message WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM ai_chat_session_participant WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM ai_chat_session WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM person_tag WHERE person_id = ?", person.getId());
            jdbcTemplate.update("DELETE FROM deal WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM stage WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM pipeline WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM company WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM person WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM tag WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update(
                    "DELETE wrp FROM workspace_role_permission wrp"
                            + " JOIN workspace_role wr ON wr.id = wrp.workspace_role_id"
                            + " WHERE wr.workspace_id = ?",
                    workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace_role WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        if (firstActor != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", firstActor.getId());
        }
        if (secondActor != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", secondActor.getId());
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @Test
    void concurrentAutoWriteAndRestrictionUpdateDoNotDeadlock() throws Exception {
        authenticate(firstActor);
        ToolFixture proposal = autoTagProposal(firstActor, person.getId());
        clearAuthentication();
        CountDownLatch restrictionLockedPerson = new CountDownLatch(1);
        CountDownLatch releaseRestriction = new CountDownLatch(1);
        CountDownLatch autoPersonLockAttempted = new CountDownLatch(1);
        PersonMapper realPersonMapper = sqlSessionTemplate.getMapper(PersonMapper.class);
        doAnswer(invocation -> {
            autoPersonLockAttempted.countDown();
            return realPersonMapper.getVisiblePersonByIdForUpdate(
                    workspace.getId(), person.getId());
        }).when(personMapperSpy).getVisiblePersonByIdForUpdate(
                workspace.getId(), person.getId());
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Person> restriction = executor.submit(() -> restrictPerson(
                    firstActor, restrictionLockedPerson, releaseRestriction));
            assertTrue(restrictionLockedPerson.await(10, TimeUnit.SECONDS));
            Future<AiAssistantWriteToolService.WriteExecution> auto =
                    executor.submit(() -> executeAuto(firstActor, proposal));
            assertTrue(autoPersonLockAttempted.await(10, TimeUnit.SECONDS));
            releaseRestriction.countDown();

            restriction.get(20, TimeUnit.SECONDS);
            ExecutionException rejected = assertThrows(
                    ExecutionException.class,
                    () -> auto.get(20, TimeUnit.SECONDS));
            assertTrue(hasCause(rejected, AiAssistantLoopException.class));
        } finally {
            releaseRestriction.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        Person restricted = personMapper.getPersonById(workspace.getId(), person.getId());
        assertTrue(restricted.getSuspendedAt() != null);
    }

    @Test
    void autoResultGuardFailureRollsBackTheTenantMutation() throws Exception {
        authenticate(firstActor);
        ToolFixture proposal = autoTagProposal(firstActor, person.getId());
        clearAuthentication();

        authenticate(firstActor);
        try {
            assertThrows(
                    AiAssistantLoopException.class,
                    () -> writeToolService.executeAuto(
                            proposal.turn(),
                            proposal.toolCallId(),
                            result -> {
                                throw new AiAssistantLoopException(
                                        "tool_result_budget_exhausted",
                                        "tool_result_budget_exhausted");
                            }));
        } finally {
            clearAuthentication();
        }

        assertEquals(
                List.of(),
                tagMapper.getTagsByPersonId(workspace.getId(), person.getId()));
    }

    @Test
    void reciprocalConcurrentOwnerAssignmentsDoNotDeadlock() throws Exception {
        Company firstCompany = company("First owner target");
        Company secondCompany = company("Second owner target");
        authenticate(firstActor);
        ToolFixture firstProposal = ownerProposal(
                firstActor, firstCompany.getId(), secondActor.getDisplayName());
        clearAuthentication();
        authenticate(secondActor);
        ToolFixture secondProposal = ownerProposal(
                secondActor, secondCompany.getId(), firstActor.getDisplayName());
        clearAuthentication();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> approveAfterStart(
                    firstActor, firstProposal, ready, start));
            Future<?> second = executor.submit(() -> approveAfterStart(
                    secondActor, secondProposal, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(
                secondActor.getId(),
                companyMapper.getCompanyById(workspace.getId(), firstCompany.getId()).getOwnerId());
        assertEquals(
                firstActor.getId(),
                companyMapper.getCompanyById(workspace.getId(), secondCompany.getId()).getOwnerId());
    }

    @Test
    void undoRefusesWhenAnotherTransactionCreatedTheTagAssociation() throws Exception {
        authenticate(firstActor);
        ToolFixture proposal = autoTagProposal(firstActor, person.getId());
        clearAuthentication();
        assertEquals(1, personMapper.addTag(workspace.getId(), person.getId(), tag.getId()));

        AiAssistantWriteToolService.WriteExecution execution = executeAuto(firstActor, proposal);

        assertFalse(execution.toolCall().undoAvailable());
        authenticate(firstActor);
        try {
            assertThrows(
                    ooo.klae.connex.backend.exceptions.ConflictException.class,
                    () -> writeToolService.undo(proposal.sessionId(), proposal.toolCallId()));
        } finally {
            clearAuthentication();
        }
        assertEquals(
                List.of(tag.getId()),
                tagMapper.getTagsByPersonId(workspace.getId(), person.getId()).stream()
                        .map(Tag::getId)
                .toList());
    }

    /**
     * A permission revoked while an approval is in flight must stop that approval's write.
     *
     * <p>The latch is keyed on {@code getAccessibleSessionById}, which the approval really does call
     * between entering its transaction and taking any lock, so the revocation commits inside the
     * window the defect lived in. Resolving the authority from unlocked rows lets the write through:
     * the pre-lock permission read populates the MyBatis session cache, and the post-lock
     * re-assertion re-issues identical statements that the cache answers with the pre-lock result.
     */
    @Test
    void approvalRefusesWhenTheActorsRolePermissionWasRevokedAfterTheProposal() throws Exception {
        Company company = company("Revoked role company");
        Pipeline pipeline = pipeline("Revoked role pipeline");
        Stage source = stage(pipeline, "Source", 0);
        Stage target = stage(pipeline, "Target", 1);
        Deal deal = deal(pipeline, source, company);
        jdbcTemplate.update(
                "UPDATE deal SET updated_at = updated_at - INTERVAL 5 SECOND WHERE id = ?",
                deal.getId());
        int roleId = customRole(firstActor);
        authenticate(firstActor);
        ToolFixture proposal = stageProposal(firstActor, deal.getId(), target.getName());
        clearAuthentication();
        CountDownLatch approvalStarted = new CountDownLatch(1);
        CountDownLatch revoked = new CountDownLatch(1);
        AtomicBoolean interceptSessionRead = new AtomicBoolean(true);
        AiChatMapper realChatMapper = sqlSessionTemplate.getMapper(AiChatMapper.class);
        doAnswer(invocation -> {
            if (interceptSessionRead.compareAndSet(true, false)) {
                approvalStarted.countDown();
                assertTrue(revoked.await(10, TimeUnit.SECONDS));
            }
            return realChatMapper.getAccessibleSessionById(
                    workspace.getId(), firstActor.getId(), proposal.sessionId());
        }).when(chatMapperSpy).getAccessibleSessionById(
                workspace.getId(), firstActor.getId(), proposal.sessionId());
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> approval = executor.submit(() -> {
                authenticate(firstActor);
                try {
                    writeToolService.approve(proposal.sessionId(), proposal.toolCallId());
                } finally {
                    clearAuthentication();
                }
            });
            assertTrue(approvalStarted.await(10, TimeUnit.SECONDS));
            assertEquals(1, jdbcTemplate.update(
                    "DELETE FROM workspace_role_permission"
                            + " WHERE workspace_role_id = ? AND permission = 'DEAL_UPDATE'",
                    roleId));
            revoked.countDown();

            ExecutionException refused = assertThrows(
                    ExecutionException.class, () -> approval.get(20, TimeUnit.SECONDS));
            Throwable forbidden = causeOfType(refused, ForbiddenException.class);
            assertNotNull(forbidden);
            assertEquals(
                    "Requires the DEAL_UPDATE permission in this workspace",
                    forbidden.getMessage());
        } finally {
            revoked.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(
                source.getId(),
                dealMapper.getDealById(workspace.getId(), deal.getId()).getStageId());
        assertEquals(
                "proposed",
                jdbcTemplate.queryForObject(
                        "SELECT status FROM ai_chat_tool_call WHERE id = ?",
                        String.class,
                        proposal.toolCallId()));
        verifyNoInteractions(auditService);
    }

    @Test
    void stageChangePrelockRejectsAStaleSourceSnapshotBeforeASecondLockPass()
            throws Exception {
        Company company = company("Stage lock company");
        Pipeline pipeline = pipeline("Stage lock pipeline");
        Stage source = stage(pipeline, "Source", 0);
        Stage target = stage(pipeline, "Target", 1);
        Stage concurrentSource = stage(pipeline, "Concurrent source", 2);
        Deal deal = deal(pipeline, source, company);
        CountDownLatch discovered = new CountDownLatch(1);
        CountDownLatch releaseDiscovery = new CountDownLatch(1);
        AtomicBoolean interceptDiscovery = new AtomicBoolean(true);
        DealMapper realDealMapper = sqlSessionTemplate.getMapper(DealMapper.class);
        doAnswer(invocation -> {
            Deal snapshot = realDealMapper.getDealById(workspace.getId(), deal.getId());
            if (interceptDiscovery.compareAndSet(true, false)) {
                discovered.countDown();
                assertTrue(releaseDiscovery.await(10, TimeUnit.SECONDS));
            }
            return snapshot;
        }).when(dealMapperSpy).getDealById(workspace.getId(), deal.getId());
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> prelock = executor.submit(() -> {
                authenticate(firstActor);
                try {
                    new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                            dealService.lockStageChangeRowsForUpdate(
                                    deal.getId(), target.getId()));
                } finally {
                    clearAuthentication();
                }
            });
            assertTrue(discovered.await(10, TimeUnit.SECONDS));
            assertEquals(1, jdbcTemplate.update(
                    "UPDATE deal SET stage_id = ? WHERE workspace_id = ? AND id = ?",
                    concurrentSource.getId(), workspace.getId(), deal.getId()));
            releaseDiscovery.countDown();

            ExecutionException rejected = assertThrows(
                    ExecutionException.class,
                    () -> prelock.get(20, TimeUnit.SECONDS));
            assertTrue(hasCause(
                    rejected,
                    ooo.klae.connex.backend.exceptions.ConflictException.class));
        } finally {
            releaseDiscovery.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private ToolFixture autoTagProposal(User actor, int personId) throws Exception {
        AiChatResourceRegistry resources = new AiChatResourceRegistry();
        resources.register("person", personId);
        long expectedEpoch = restrictionEpoch.current(workspace.getId());
        AiAssistantPreparedWrite write = writeToolService.prepare(
                "add_tag",
                objectMapper.readTree(
                        "{\"handle\":\"r1\",\"tag\":\"" + tag.getName() + "\"}"),
                resources,
                expectedEpoch);
        AiChatSession session = session(actor);
        AiChatMessage message = message(session, actor);
        AiChatTurn turn = new AiChatTurn();
        turn.setWorkspaceId(workspace.getId());
        turn.setSessionId(session.getId());
        turn.setRequestedByUserId(actor.getId());
        turn.setStatus("running");
        chatMapper.insertTurn(turn);
        AiChatToolCall toolCall = toolCall(message, write);
        AiChatQueuedTurn queued = new AiChatQueuedTurn(
                workspace.getId(), actor.getId(), session.getId(), turn.getId(),
                message.getId(), message.getSeq(), expectedEpoch, true, List.of(), List.of());
        return new ToolFixture(session.getId(), toolCall.getId(), queued);
    }

    private ToolFixture stageProposal(User actor, int dealId, String stage) throws Exception {
        AiChatResourceRegistry resources = new AiChatResourceRegistry();
        resources.register("deal", dealId);
        AiAssistantPreparedWrite write = writeToolService.prepare(
                "change_deal_stage",
                objectMapper.readTree(
                        "{\"handle\":\"r1\",\"stage\":\"" + stage + "\"}"),
                resources,
                restrictionEpoch.current(workspace.getId()));
        AiChatSession session = session(actor);
        AiChatMessage message = message(session, actor);
        AiChatToolCall toolCall = toolCall(message, write);
        return new ToolFixture(session.getId(), toolCall.getId(), null);
    }

    /** Replaces a member's built-in authority with a custom role granting every permission. */
    private int customRole(User member) {
        jdbcTemplate.update(
                "INSERT INTO workspace_role (workspace_id, name) VALUES (?, ?)",
                workspace.getId(),
                "Assistant role " + UUID.randomUUID().toString().substring(0, 8));
        Integer roleId = jdbcTemplate.queryForObject(
                "SELECT id FROM workspace_role WHERE workspace_id = ? ORDER BY id DESC LIMIT 1",
                Integer.class,
                workspace.getId());
        assertNotNull(roleId);
        jdbcTemplate.batchUpdate(
                "INSERT INTO workspace_role_permission (workspace_role_id, permission)"
                        + " VALUES (?, ?)",
                Arrays.stream(Permission.values())
                        .map(permission -> new Object[] {roleId, permission.name()})
                        .toList());
        assertEquals(1, jdbcTemplate.update(
                "UPDATE workspace_member SET role_id = ? WHERE workspace_id = ? AND user_id = ?",
                roleId,
                workspace.getId(),
                member.getId()));
        return roleId;
    }

    private ToolFixture ownerProposal(User actor, int companyId, String owner) throws Exception {
        AiChatResourceRegistry resources = new AiChatResourceRegistry();
        resources.register("company", companyId);
        AiAssistantPreparedWrite write = writeToolService.prepare(
                "assign_owner",
                objectMapper.readTree(
                        "{\"handle\":\"r1\",\"owner\":\"" + owner + "\"}"),
                resources,
                restrictionEpoch.current(workspace.getId()));
        AiChatSession session = session(actor);
        AiChatMessage message = message(session, actor);
        AiChatToolCall toolCall = toolCall(message, write);
        return new ToolFixture(session.getId(), toolCall.getId(), null);
    }

    private AiChatSession session(User actor) {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setCreatedByUserId(actor.getId());
        session.setTitle("Assistant proposal");
        session.setVisibility("private");
        session.setStatus("active");
        chatMapper.insertSession(session);
        return session;
    }

    private AiChatMessage message(AiChatSession session, User actor) {
        AiChatMessage message = new AiChatMessage();
        message.setWorkspaceId(workspace.getId());
        message.setSessionId(session.getId());
        message.setSeq(1);
        message.setAuthorKind("user");
        message.setAuthorUserId(actor.getId());
        message.setContent("Assistant write proposal");
        chatMapper.insertMessage(message);
        return message;
    }

    private AiChatToolCall toolCall(
            AiChatMessage message, AiAssistantPreparedWrite write) {
        AiChatToolCall toolCall = new AiChatToolCall();
        toolCall.setWorkspaceId(workspace.getId());
        toolCall.setMessageId(message.getId());
        toolCall.setToolName(write.toolName());
        toolCall.setStatus("proposed");
        toolCall.setArgumentsJson(write.argumentsJson());
        toolCall.setIdempotencyKey("integration-message-" + message.getId());
        chatMapper.insertToolCall(toolCall);
        return toolCall;
    }

    /**
     * Seeds a company whose {@code updated_at} predates any proposal this test will create.
     *
     * The freshness guard refuses a target written in the proposal's own second, and a fixture
     * inserted milliseconds before its proposal always trips that rule. Backdating keeps this
     * test exercising what it exists for — lock ordering — rather than the staleness refusal,
     * which {@code AiAssistantWriteToolServiceTest} covers on its own terms.
     */
    private Company company(String name) {
        Company company = new Company();
        company.setWorkspaceId(workspace.getId());
        company.setName(name + " " + UUID.randomUUID().toString().substring(0, 8));
        companyMapper.insert(company);
        jdbcTemplate.update(
                "UPDATE company SET updated_at = updated_at - INTERVAL 5 SECOND WHERE id = ?",
                company.getId());
        return company;
    }

    private Pipeline pipeline(String name) {
        Pipeline pipeline = new Pipeline();
        pipeline.setWorkspaceId(workspace.getId());
        pipeline.setName(name + " " + UUID.randomUUID().toString().substring(0, 8));
        pipelineMapper.insertPipeline(pipeline);
        return pipeline;
    }

    private Stage stage(Pipeline pipeline, String name, int position) {
        Stage stage = new Stage();
        stage.setWorkspaceId(workspace.getId());
        stage.setPipeline(pipeline);
        stage.setName(name + " " + UUID.randomUUID().toString().substring(0, 8));
        stage.setPosition(position);
        pipelineMapper.insertStage(stage);
        return stage;
    }

    private Deal deal(Pipeline pipeline, Stage stage, Company company) {
        Deal deal = new Deal();
        deal.setWorkspaceId(workspace.getId());
        deal.setOwnerId(firstActor.getId());
        deal.setName("Stage lock deal " + UUID.randomUUID().toString().substring(0, 8));
        deal.setValue(new BigDecimal("1000.00"));
        deal.setCurrency("USD");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setCompanyId(company.getId());
        dealMapper.insert(deal);
        return deal;
    }

    private User user(String username, String displayName) {
        User user = new User();
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setEmail(username + "@example.com");
        user.setPasswordHash("hash-" + username);
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }

    private Person restrictPerson(
            User actor,
            CountDownLatch restrictionLockedPerson,
            CountDownLatch releaseRestriction) {
        authenticate(actor);
        try {
            return new TransactionTemplate(transactionManager).execute(status -> {
                Person locked = personMapper.getOwnedPersonByIdForUpdate(
                        workspace.getId(), person.getId());
                restrictionLockedPerson.countDown();
                try {
                    assertTrue(releaseRestriction.await(30, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Restriction test was interrupted", exception);
                }
                personMapper.updateProcessingRestrictions(
                        workspace.getId(), person.getId(), true, false);
                restrictionEpoch.bump(workspace.getId());
                return locked;
            });
        } finally {
            clearAuthentication();
        }
    }

    private AiAssistantWriteToolService.WriteExecution executeAuto(
            User actor, ToolFixture fixture) {
        authenticate(actor);
        try {
            return writeToolService.executeAuto(
                    fixture.turn(), fixture.toolCallId(), result -> { });
        } finally {
            clearAuthentication();
        }
    }

    private void approveAfterStart(
            User actor,
            ToolFixture fixture,
            CountDownLatch ready,
            CountDownLatch start) {
        ready.countDown();
        try {
            assertTrue(start.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Owner-assignment test was interrupted", exception);
        }
        authenticate(actor);
        try {
            writeToolService.approve(fixture.sessionId(), fixture.toolCallId());
        } finally {
            clearAuthentication();
        }
    }

    private void authenticate(User actor) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        actor, null, actor.getAuthorities()));
        tenantContext.set(
                workspace.getId(), organization.getId(), actor.getId(), "owner", null);
    }

    private void clearAuthentication() {
        SecurityContextHolder.clearContext();
        tenantContext.clear();
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        return causeOfType(error, type) != null;
    }

    private static Throwable causeOfType(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return current;
            }
            current = current.getCause();
        }
        return null;
    }

    private record ToolFixture(
            int sessionId,
            int toolCallId,
            AiChatQueuedTurn turn) {
    }
}
