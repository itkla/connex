package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonLifecycleStage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.PersonLifecycleRequest;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * The lifecycle pass ledger under a first-response clock racing the close of the pass it would
 * belong to (#559, increment 6 of {@code docs/LEAD_LIFECYCLE.md}). Both writes take the contact's
 * row lock, so either order is a legal outcome; what must never happen is a forked ledger or a
 * clock copied onto a pass that had already ended.
 *
 * <p>The two threads have to see each other's commits, so this test runs outside the suite's
 * rollback transaction and therefore owns its organization and workspace. The shared default
 * workspace {@code AbstractServiceTest} hands out is not usable here: committed fixture rows in it
 * survive the test and are then counted by every other service test that reads that workspace.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LeadPassAttributionConcurrencyIntegrationTest {

    @Autowired private PersonLifecycleService lifecycleService;
    @Autowired private LeadResponseSlaService slaService;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private CompanyMapper companyMapper;
    @Autowired private PersonMapper personMapper;
    @Autowired private TenantContext tenantContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private AuditService auditService;
    @MockitoBean private RuleTriggerPublisher ruleTriggers;
    @MockitoBean private NotificationChangePublisher notificationChanges;

    private Organization organization;
    private Workspace workspace;
    private User owner;
    private Company company;
    private Person person;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Lead pass " + unique);
        organization.setSlug("lead-pass-" + unique);
        organizationMapper.insert(organization);

        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Lead pass " + unique);
        workspace.setSlug("lead-pass-" + unique);
        workspaceMapper.insert(workspace);

        owner = new User();
        owner.setUsername("lead-pass-owner-" + unique);
        owner.setDisplayName("Lead pass owner " + unique);
        owner.setEmail("lead-pass-owner-" + unique + "@example.com");
        owner.setPasswordHash("hash-" + unique);
        owner.setTimezone("UTC");
        userMapper.insert(owner);
        workspaceMapper.addMember(workspace.getId(), owner.getId(), "owner");

        company = new Company();
        company.setWorkspaceId(workspace.getId());
        company.setName("Lead pass company " + unique);
        companyMapper.insert(company);

        person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setOwnerId(owner.getId());
        person.setName("Lead pass contact " + unique);
        person.setEmail("lead-pass-contact-" + unique + "@example.com");
        person.setCompany(company);
        personMapper.insert(person);

        asOwner(() -> lifecycleService.updateLifecycle(person.getId(), entry()));
    }

    @AfterEach
    void cleanUp() {
        clearContext();
        if (workspace != null) {
            jdbcTemplate.update(
                "DELETE FROM person_lifecycle_pass WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update(
                "DELETE FROM person_lifecycle_history WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM person WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM company WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update(
                "DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        if (owner != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", owner.getId());
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @Test
    void aClockStartingWhileThePassClosesNeverAttachesToTheWrongPass() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> clock = executor.submit(() -> afterStart(ready, start,
                () -> asOwner(() -> slaService.startFirstResponseClock(person.getId(), 4))));
            Future<?> withdrawal = executor.submit(() -> afterStart(ready, start,
                () -> asOwner(() -> lifecycleService.withdrawFromLifecycle(person.getId(), "closing"))));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            clock.get(30, TimeUnit.SECONDS);
            withdrawal.get(30, TimeUnit.SECONDS);
        } finally {
            start.countDown();
        }

        List<Map<String, Object>> passes = jdbcTemplate.queryForList(
            "SELECT entered_at, ended_at, first_response_started_at "
                + "FROM person_lifecycle_pass WHERE workspace_id = ? AND person_id = ?",
            workspace.getId(), person.getId());
        assertEquals(1, passes.size(), "the race must not fork the ledger into two passes");
        Map<String, Object> pass = passes.getFirst();
        assertNotNull(pass.get("ended_at"), "the withdrawal must have closed the pass");

        LocalDateTime passStartedAt = (LocalDateTime) pass.get("first_response_started_at");
        LocalDateTime liveStartedAt = jdbcTemplate.queryForObject(
            "SELECT first_response_started_at FROM person WHERE workspace_id = ? AND id = ?",
            LocalDateTime.class, workspace.getId(), person.getId());
        assertEquals(1, (passStartedAt == null ? 0 : 1) + (liveStartedAt == null ? 0 : 1),
            "the clock belongs either to the pass it started within or to no pass at all, "
                + "never to both and never to neither");
        if (passStartedAt != null) {
            assertFalse(
                passStartedAt.isBefore((LocalDateTime) pass.get("entered_at")),
                "a clock may only be attached to a pass it started within");
        }
    }

    private static void afterStart(CountDownLatch ready, CountDownLatch start, Runnable work) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent lifecycle work did not start");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
        work.run();
    }

    private void asOwner(Runnable work) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(owner, null, owner.getAuthorities()));
        tenantContext.set(
            workspace.getId(), organization.getId(), owner.getId(), "owner", null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        long now = System.currentTimeMillis();
        request.getSession().setAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR, now);
        request.getSession().setAttribute(
            SessionSecurityService.AUTHENTICATED_USER_ATTR, owner.getId());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            work.run();
        } finally {
            clearContext();
        }
    }

    private void clearContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        tenantContext.clear();
    }

    private static PersonLifecycleRequest entry() {
        PersonLifecycleRequest request = new PersonLifecycleRequest();
        request.setStage(PersonLifecycleStage.NEW);
        return request;
    }
}
