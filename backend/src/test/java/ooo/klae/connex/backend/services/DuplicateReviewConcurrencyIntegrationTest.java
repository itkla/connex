package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.DuplicateReviewDecisionRequest;
import ooo.klae.connex.backend.dto.DuplicateReviewItemDto;
import ooo.klae.connex.backend.dto.DuplicateReviewQuery;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DuplicateReviewConcurrencyIntegrationTest {

    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private PersonService personService;
    @Autowired private DuplicateReviewService duplicateReviewService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TenantContext tenantContext;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private DuplicateDecisionLockService duplicateDecisionLockService;

    private Organization organization;
    private Workspace workspace;
    private User firstActor;
    private User secondActor;
    private Person first;
    private Person second;

    @BeforeEach
    void setUp() {
        String suffix = suffix();
        organization = new Organization();
        organization.setName("Duplicate concurrency " + suffix);
        organization.setSlug("duplicate-concurrency-" + suffix);
        organizationMapper.insert(organization);
        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Duplicate concurrency " + suffix);
        workspace.setSlug("duplicate-concurrency-" + suffix);
        workspaceMapper.insert(workspace);
        firstActor = newCustomRoleActor("first");
        secondActor = newCustomRoleActor("second");
        withContext(firstActor, () -> {
            first = personService.create(person("Concurrency First", "race@example.com"));
            second = personService.create(person("Concurrency Second", "race@example.com"));
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        tenantContext.clear();
        if (workspace != null) {
            int workspaceId = workspace.getId();
            jdbcTemplate.update(
                "DELETE FROM duplicate_review_decision WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM identity_collision WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM person_identity WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM person WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update(
                "DELETE wrp FROM workspace_role_permission wrp"
                    + " JOIN workspace_role wr ON wr.id = wrp.workspace_role_id"
                    + " WHERE wr.workspace_id = ?",
                workspaceId);
            jdbcTemplate.update("DELETE FROM workspace_role WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspaceId);
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
    void concurrentDismissalsConvergeOnOneDecisionWithoutDeadlock() throws Exception {
        String fingerprint = currentFingerprint();
        DuplicateReviewDecisionRequest request = request(fingerprint);
        CountDownLatch firstAcquiredMutex = new CountDownLatch(1);
        CountDownLatch releaseFirstMutex = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger mutexAcquisitions = new AtomicInteger();
        doAnswer(invocation -> {
            int orgId = (int) invocation.callRealMethod();
            if (mutexAcquisitions.incrementAndGet() == 1) {
                firstAcquiredMutex.countDown();
                await(releaseFirstMutex);
            }
            return orgId;
        }).when(duplicateDecisionLockService).lockCurrentOrganization();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> firstResult = executor.submit(
                () -> inNewTransaction(firstActor, () ->
                    duplicateReviewService.dismiss(request).state()));
            assertTrue(firstAcquiredMutex.await(5, TimeUnit.SECONDS));
            Future<String> secondResult = executor.submit(
                () -> {
                    secondStarted.countDown();
                    return inNewTransaction(secondActor, () ->
                        duplicateReviewService.dismiss(request).state());
                });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertEquals(1, mutexAcquisitions.get());
            assertFalse(firstResult.isDone());
            assertFalse(secondResult.isDone());
            releaseFirstMutex.countDown();

            assertEquals("dismissed", firstResult.get(10, TimeUnit.SECONDS));
            assertEquals("dismissed", secondResult.get(10, TimeUnit.SECONDS));
        } finally {
            releaseFirstMutex.countDown();
        }
        assertEquals(1, jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM duplicate_review_decision
            WHERE workspace_id = ?
              AND evidence_fingerprint = ?
              AND state = 'dismissed'
              AND is_current = TRUE
            """,
            Integer.class,
            workspace.getId(), fingerprint));
        DuplicateReviewQuery dismissedQuery = new DuplicateReviewQuery();
        dismissedQuery.setRecordType("person");
        dismissedQuery.setKind("email");
        dismissedQuery.setState("dismissed");
        PageResponse<DuplicateReviewItemDto> dismissed = withContext(
            firstActor,
            () -> duplicateReviewService.list(dismissedQuery));
        assertEquals(1, dismissed.total());
        assertEquals(fingerprint, dismissed.items().getFirst().evidenceFingerprint());
    }

    @Test
    void dismissalRacingIdentityEditNeverLeavesCurrentEvidenceHidden() throws Exception {
        String originalFingerprint = currentFingerprint();
        jdbcTemplate.update(
            """
            INSERT INTO person_identity (
              workspace_id, person_id, kind, `value`, normalized_value,
              source_system, source_channel, acquired_at)
            VALUES (?, ?, 'email', ?, ?, 'csv_import', 'person.email', CURRENT_TIMESTAMP)
            """,
            workspace.getId(), second.getId(), "changed-race@example.com",
            "changed-race@example.com");
        CountDownLatch firstAcquiredMutex = new CountDownLatch(1);
        CountDownLatch releaseFirstMutex = new CountDownLatch(1);
        CountDownLatch editStarted = new CountDownLatch(1);
        AtomicInteger mutexAcquisitions = new AtomicInteger();
        doAnswer(invocation -> {
            int orgId = (int) invocation.callRealMethod();
            if (mutexAcquisitions.incrementAndGet() == 1) {
                firstAcquiredMutex.countDown();
                await(releaseFirstMutex);
            }
            return orgId;
        }).when(duplicateDecisionLockService).lockCurrentOrganization();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> dismissal = executor.submit(
                () -> inNewTransaction(firstActor, () -> {
                    try {
                        return duplicateReviewService.dismiss(
                            request(originalFingerprint)).state();
                    } catch (ConflictException exception) {
                        return "conflict";
                    }
                }));
            assertTrue(firstAcquiredMutex.await(5, TimeUnit.SECONDS));
            Future<String> edit = executor.submit(
                () -> {
                    editStarted.countDown();
                    return inNewTransaction(secondActor, () -> {
                        personService.update(
                            first.getId(),
                            person("Concurrency First", "changed-race@example.com"));
                        return "updated";
                    });
                });
            assertTrue(editStarted.await(5, TimeUnit.SECONDS));
            assertEquals(1, mutexAcquisitions.get());
            assertFalse(dismissal.isDone());
            assertFalse(edit.isDone());
            releaseFirstMutex.countDown();

            assertTrue(List.of("dismissed", "conflict").contains(
                dismissal.get(10, TimeUnit.SECONDS)));
            assertEquals("updated", edit.get(10, TimeUnit.SECONDS));
        } finally {
            releaseFirstMutex.countDown();
        }

        String changedFingerprint = DuplicateReviewService.evidenceFingerprint(
            "person", "email", "changed-race@example.com");
        assertEquals(1, jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM duplicate_review_decision
            WHERE workspace_id = ?
              AND is_current = TRUE
              AND state = 'open'
              AND evidence_fingerprint = ?
            """,
            Integer.class,
            workspace.getId(), changedFingerprint));
        assertEquals(0, jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM duplicate_review_decision
            WHERE workspace_id = ?
              AND is_current = TRUE
              AND evidence_fingerprint = ?
              AND state = 'dismissed'
            """,
            Integer.class,
            workspace.getId(), originalFingerprint));
        DuplicateReviewQuery openQuery = new DuplicateReviewQuery();
        openQuery.setRecordType("person");
        openQuery.setKind("email");
        PageResponse<DuplicateReviewItemDto> visible = withContext(
            firstActor,
            () -> duplicateReviewService.list(openQuery));
        assertEquals(1, visible.total());
        assertEquals(changedFingerprint, visible.items().getFirst().evidenceFingerprint());
        assertEquals(
            ooo.klae.connex.backend.dto.DuplicateMatchKind.EMAIL,
            visible.items().getFirst().evidence().kind());
    }

    private <T> T inNewTransaction(User actor, Callable<T> work) {
        return withContext(actor, () -> {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            return Objects.requireNonNull(transaction.execute(status -> call(work)));
        });
    }

    private <T> T withContext(User actor, Callable<T> work) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(
            SessionSecurityService.AUTHENTICATED_AT_ATTR, System.currentTimeMillis());
        request.getSession().setAttribute(
            SessionSecurityService.AUTHENTICATED_USER_ATTR, actor.getId());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        tenantContext.set(
            workspace.getId(), organization.getId(), actor.getId(), "member", null);
        try {
            return work.call();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Duplicate review concurrent work failed", exception);
        } finally {
            SecurityContextHolder.clearContext();
            RequestContextHolder.resetRequestAttributes();
            tenantContext.clear();
        }
    }

    private static <T> T call(Callable<T> work) {
        try {
            return work.call();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Duplicate review concurrent work failed", exception);
        }
    }

    private User newCustomRoleActor(String label) {
        String suffix = suffix();
        User actor = new User();
        actor.setUsername("duplicate_concurrency_" + label + "_" + suffix);
        actor.setDisplayName("Duplicate concurrency " + label + " " + suffix);
        actor.setEmail(label + "_" + suffix + "@example.com");
        actor.setPasswordHash("fixture");
        actor.setTimezone("UTC");
        userMapper.insert(actor);
        workspaceMapper.addMember(workspace.getId(), actor.getId(), "member");
        WorkspaceRole customRole = new WorkspaceRole();
        customRole.setWorkspaceId(workspace.getId());
        customRole.setName("Duplicate concurrency " + label + " " + suffix);
        roleMapper.insertRole(customRole);
        roleMapper.insertPermissions(
            workspace.getId(),
            customRole.getId(),
            List.of("PERSON_CREATE", "PERSON_UPDATE", "REPORT_READ"));
        workspaceMapper.setMemberCustomRole(
            workspace.getId(), actor.getId(), customRole.getId());
        return actor;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Organization mutex test gate did not resume");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Organization mutex test gate was interrupted", exception);
        }
    }

    private DuplicateReviewDecisionRequest request(String fingerprint) {
        return new DuplicateReviewDecisionRequest(
            "person", "email", first.getId(), second.getId(), fingerprint, null);
    }

    private String currentFingerprint() {
        return java.util.Objects.requireNonNull(jdbcTemplate.queryForObject(
            """
            SELECT evidence_fingerprint
            FROM duplicate_review_decision
            WHERE workspace_id = ? AND is_current = TRUE
            ORDER BY id DESC
            LIMIT 1
            """,
            String.class,
            workspace.getId()));
    }

    private static Person person(String name, String email) {
        Person person = new Person();
        person.setName(name);
        person.setEmail(email);
        person.setTitle("Concurrency");
        return person;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
