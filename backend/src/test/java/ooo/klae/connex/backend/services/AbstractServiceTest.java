package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.support.TestEntityFixtures;
import ooo.klae.connex.backend.support.OwnershipOfferTestFixtures;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.ObjectDeletionQueueMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.UserObjectDeletionQueueMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import ooo.klae.connex.backend.storage.ObjectDeletionTask;
import ooo.klae.connex.backend.storage.ObjectDeletionTransactionExecutor;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
@Import(AbstractServiceTest.ApprovalMutationTestConfiguration.class)
public abstract class AbstractServiceTest {
    private static final Duration OBJECT_DELETION_DRAIN_SAFETY_MARGIN = Duration.ofMinutes(10);
    private static final int OBJECT_DELETION_DRAIN_LIMIT = 10;

    @TestConfiguration(proxyBeanMethods = false)
    static class ApprovalMutationTestConfiguration {
        @Bean
        @Primary
        ApprovalMutationRetryService testApprovalMutationRetryService(
                PlatformTransactionManager transactionManager) {
            return new JoiningApprovalMutationRetryService(transactionManager);
        }
    }

    static final class JoiningApprovalMutationRetryService
            extends ApprovalMutationRetryService {
        private final PlatformTransactionManager transactionManager;

        JoiningApprovalMutationRetryService(PlatformTransactionManager transactionManager) {
            super(transactionManager);
            this.transactionManager = transactionManager;
        }

        @Override
        public <T> T execute(Supplier<T> mutation) {
            Objects.requireNonNull(mutation, "mutation");
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    TransactionTemplate template = new TransactionTemplate(transactionManager);
                    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
                    template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                    T result = template.execute(status -> mutation.get());
                    return Objects.requireNonNull(result, "Approval mutation returned no result");
                } catch (ApprovalRecipientSetChangedException exception) {
                    if (attempt == MAX_ATTEMPTS) {
                        throw new ConflictException("Approval changed; refresh and try again");
                    }
                }
            }
            throw new IllegalStateException("Approval mutation retry loop did not terminate");
        }
    }

    @Autowired protected UserMapper userMapper;
    @Autowired protected CompanyMapper companyMapper;
    @Autowired protected PipelineMapper pipelineMapper;
    @Autowired protected TagMapper tagMapper;
    @Autowired protected PersonMapper personMapper;
    @Autowired protected DealMapper dealMapper;
    @Autowired protected ActivityMapper activityMapper;
    @Autowired protected NoteMapper noteMapper;
    @Autowired protected TaskMapper taskMapper;
    @Autowired protected WorkspaceMapper workspaceMapper;
    @Autowired protected NotificationMapper notificationMapper;
    @Autowired protected TenantContext tenantContext;
    @Autowired private PlatformTransactionManager fixtureTransactionManager;
    @Autowired private JdbcTemplate approvalFixtureJdbc;
    @Autowired private ObjectDeletionQueueMapper fixtureDeletionQueueMapper;
    @Autowired private UserObjectDeletionQueueMapper fixtureUserDeletionQueueMapper;
    @Autowired private ObjectDeletionTransactionExecutor fixtureDeletionTransactionExecutor;
    @Autowired private TenantWorkScope fixtureTenantWorkScope;
    @Autowired private Clock fixtureClock;

    protected Workspace workspace;
    protected User currentUser;

    @BeforeEach
    protected void setUpWorkspaceAndAuthentication() {
        workspace = resolveDefaultWorkspace();
        currentUser = newUser();
        workspaceMapper.updateMemberRole(workspace.getId(), currentUser.getId(), "owner");
        authenticateAs(currentUser, workspace.getId());
        approveForStepUp(currentUser);
    }

    /**
     * Resolves the shared default workspace, creating it in its own committed transaction when the
     * schema has none yet. Control-catalog reads suspend the caller's transaction
     * ({@code OrganizationWorkspaceScopeControlAccess} runs them {@code NOT_SUPPORTED}), so a
     * workspace inserted inside the rolled-back test transaction is invisible to the code under
     * test and every route that resolves an organization scope fails. Resolving it before the test
     * transaction issues any read also keeps the committed row inside that transaction's snapshot.
     *
     * @return the default workspace, visible to reads on any connection
     */
    private Workspace resolveDefaultWorkspace() {
        TransactionTemplate template = new TransactionTemplate(fixtureTransactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return Objects.requireNonNull(template.execute(status -> {
            Workspace existing = workspaceMapper.getDefaultWorkspace();
            if (existing != null) {
                return existing;
            }
            Workspace created = new Workspace();
            created.setName("Test Workspace");
            created.setSlug("default");
            workspaceMapper.insert(created);
            Workspace reloaded = workspaceMapper.getDefaultWorkspace();
            return reloaded == null ? created : reloaded;
        }), "default workspace");
    }

    protected void authenticateAs(User user, int workspaceId) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())
        );
        int orgId = workspaceServiceOrgId(workspaceId);
        String role = workspaceMapper.getRole(workspaceId, user.getId());
        MockHttpServletRequest request = new MockHttpServletRequest();
        long now = System.currentTimeMillis();
        request.getSession().setAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR, now);
        request.getSession().setAttribute(SessionSecurityService.AUTHENTICATED_USER_ATTR, user.getId());
        request.getSession().setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR, now);
        request.getSession().setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_USER_ATTR, user.getId());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        tenantContext.set(workspaceId, orgId, user.getId(), role == null ? "member" : role, null);
    }

    /**
     * Models an approved administrator whose approval is incidental to the test, wherever the
     * test makes them privileged. Authentication alone leaves subjects' approval state untouched.
     * The credential joins the caller's transaction, as the step-up mapper reads do; nontransactional
     * fixtures commit it with their account and delete the account in their existing teardown.
     *
     * @param user the incidental actor authenticated in the current request
     * @return the approved credential row id
     */
    protected int approveForStepUp(User user) {
        int credential = OwnershipOfferTestFixtures.approvedPasskey(approvalFixtureJdbc, user);
        var attributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        var session = attributes.getRequest().getSession();
        session.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_PROOF_ATTR,
            user.getId() + ":" + session.getAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR)
                + ":" + credential);
        return credential;
    }

    @AfterEach
    protected void clearAuthentication() {
        SecurityContextHolder.clearContext();
        clearRequestContext();
    }

    /**
     * Drains one bounded batch per queue after rollback, when cancelled upload tombstones are
     * visible again and no test transaction holds their locks. One indexed selection per queue
     * keeps an empty drain cheap while repeated tests and runs advance both passes without sleeping.
     * A row becomes eligible only once its next attempt is at least ten minutes overdue. Thus a
     * fixture such as ManagedObjectRollbackIntegrationTest is never raced within its tombstone
     * delay plus this margin; a test stalled beyond that interval is accepted as out of scope.
     * The cutoff is rechecked under the executor's row lock, preserving the margin for a
     * concurrently rescheduled row. The real executor deletes orphaned bytes and reschedules the
     * second pass, which a later drain completes after its backoff and the same margin.
     *
     * <p>The shared default workspace's tenant queue uses its resolved route, and user-profile
     * tombstones use the unrouted control-plane queue even when the default workspace is absent.
     * Each task failure is left for a later drain so quota-lock contention with another test
     * cannot fail this test's teardown. Ten tasks per queue bound each call's lock exposure; drains
     * per run far outnumber leaked rows, keeping the backlog bounded as tasks recover.
     */
    @AfterTransaction
    protected void drainLeakedObjectDeletions() {
        LocalDateTime cutoff = LocalDateTime.ofInstant(fixtureClock.instant(), ZoneOffset.UTC)
            .minus(OBJECT_DELETION_DRAIN_SAFETY_MARGIN);
        drainLeakedUserObjectDeletions(cutoff, true);
        Workspace defaultWorkspace = fixtureTenantWorkScope.unroutedRead(
            workspaceMapper::getDefaultWorkspace);
        if (defaultWorkspace == null) {
            return;
        }
        drainLeakedObjectDeletions(defaultWorkspace.getId(), cutoff, true);
    }

    /**
     * Drains a bounded batch in an explicitly selected workspace without suppressing task failures.
     * Tests use an isolated workspace and explicit cutoff to exercise both passes without sleeps
     * or exposing their rows to another fork's default-workspace hook.
     *
     * @param workspaceId the workspace owning the test's deletion rows
     * @param cutoff the latest next-attempt time eligible for this pass, rechecked under row lock
     */
    protected void drainLeakedObjectDeletions(int workspaceId, LocalDateTime cutoff) {
        drainLeakedObjectDeletions(workspaceId, cutoff, false);
    }

    private void drainLeakedObjectDeletions(
            int workspaceId, LocalDateTime cutoff, boolean continueAfterTaskFailure) {
        fixtureTenantWorkScope.inWorkspace(workspaceId, () -> {
            for (ObjectDeletionTask task : fixtureDeletionQueueMapper.findDue(
                    workspaceId, cutoff, OBJECT_DELETION_DRAIN_LIMIT)) {
                try {
                    fixtureDeletionTransactionExecutor.retryTenant(task, cutoff);
                } catch (RuntimeException exception) {
                    if (!continueAfterTaskFailure) {
                        throw exception;
                    }
                }
            }
        });
    }

    /**
     * Drains a bounded control-plane user-object batch without suppressing task failures.
     * Tests supply an explicit cutoff and isolate selection of future-dated fixture rows so
     * neither this drain nor another fork's hook can claim unrelated fixtures.
     *
     * @param cutoff the latest next-attempt time eligible for this pass, rechecked under row lock
     */
    protected void drainLeakedUserObjectDeletions(LocalDateTime cutoff) {
        drainLeakedUserObjectDeletions(cutoff, false);
    }

    private void drainLeakedUserObjectDeletions(
            LocalDateTime cutoff, boolean continueAfterTaskFailure) {
        fixtureTenantWorkScope.unrouted(() -> {
            for (ObjectDeletionTask task : fixtureUserDeletionQueueMapper.findDue(
                    cutoff, OBJECT_DELETION_DRAIN_LIMIT)) {
                try {
                    fixtureDeletionTransactionExecutor.retryUser(task, cutoff);
                } catch (RuntimeException exception) {
                    if (!continueAfterTaskFailure) {
                        throw exception;
                    }
                }
            }
            return null;
        });
    }

    protected void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
        tenantContext.clear();
    }

    private int workspaceServiceOrgId(int workspaceId) {
        Integer orgId = workspaceMapper.getOrgId(workspaceId);
        return orgId == null ? workspaceId : orgId;
    }

    protected static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    protected User newUser() {
        return TestEntityFixtures.newUser(userMapper, workspaceMapper, workspace);
    }

    protected User newPendingMember() {
        String s = unique();
        User user = new User();
        user.setUsername("user_" + s);
        user.setDisplayName("User " + s);
        user.setEmail(s + "@example.com");
        user.setPasswordHash("hash_" + s);
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addPendingMember(workspace.getId(), user.getId(), "member");
        return user;
    }

    protected Notification newNotification(int workspaceId, int recipientId) {
        Notification notification = new Notification();
        notification.setWorkspaceId(workspaceId);
        notification.setRecipientId(recipientId);
        notification.setType("task.assigned");
        notification.setCategory("tasks");
        notification.setSeverity("info");
        notification.setTemplateVersion(1);
        notification.setTitle("Notification " + unique());
        notification.setDedupeKey("fixture-" + unique());
        notification.setTriggeredAt("2026-07-11 00:00:00");
        notificationMapper.upsert(notification);
        return notification;
    }

    protected Company newCompany() {
        return TestEntityFixtures.newCompany(companyMapper, workspace);
    }

    protected Pipeline newPipeline() {
        return TestEntityFixtures.newPipeline(pipelineMapper, workspace);
    }

    protected Stage newStage(Pipeline pipeline, int position) {
        return TestEntityFixtures.newStage(pipelineMapper, workspace, pipeline, position);
    }

    protected Tag newTag() {
        return TestEntityFixtures.newTag(tagMapper, workspace);
    }

    protected Person newPerson(Company company) {
        return TestEntityFixtures.newPerson(personMapper, workspace, company);
    }

    protected Deal newDeal(Pipeline pipeline, Stage stage, Company company) {
        Deal deal = new Deal();
        deal.setName("Deal " + unique());
        deal.setWorkspaceId(workspace.getId());
        deal.setOwnerId(currentUser == null ? null : currentUser.getId());
        deal.setValue(new BigDecimal("1000.00"));
        deal.setCurrency("JPY");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setCompanyId(company.getId());
        dealMapper.insert(deal);
        return deal;
    }

    protected Activity newActivity(User createdBy, Person person, Deal deal) {
        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setType("call");
        activity.setSubject("subj_" + unique());
        activity.setNotes("notes_" + unique());
        activity.setPerson(person);
        activity.setDeal(deal);
        activity.setCreatedBy(createdBy);
        activity.setTimestamp("2024-06-01 10:00:00");
        activityMapper.insert(activity);
        return activity;
    }

    protected Note newNote(User author, Person person, Deal deal) {
        Note note = new Note();
        note.setWorkspaceId(workspace.getId());
        note.setContent("note_" + unique());
        note.setAuthor(author);
        note.setPerson(person);
        note.setDeal(deal);
        noteMapper.insert(note);
        return note;
    }

    protected Task newTask(User assignedTo, Person person, Deal deal) {
        Task task = new Task();
        task.setDescription("task_" + unique());
        task.setWorkspaceId(workspace.getId());
        task.setCompleted(false);
        task.setStatus("todo");
        task.setDueDate("2024-12-31");
        task.setAssignedTo(assignedTo);
        task.setPerson(person);
        task.setDeal(deal);
        taskMapper.insert(task);
        return task;
    }
}
