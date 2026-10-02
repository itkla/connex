package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * Races an owner change against a concurrent commit to the same record (#1948). The change is paused
 * after its unlocked existence check has opened its read view, and before it holds any lock.
 *
 * <ul>
 *   <li>When the concurrent commit is the same change, the paused one must audit the transition it
 *       really makes, from the owner the other committed, and must not report a second
 *       {@code owner_changed}. From its snapshot it would audit the original owner and fire the
 *       record's automations twice. An unassignment, which locks no membership, behaves the
 *       same.</li>
 *   <li>When the concurrent commit archives or deletes the record, the paused change must be refused
 *       under its lock, before it writes, audits or triggers anything.</li>
 * </ul>
 *
 * <p>The bean overrides match {@code DealCollaboratorConcurrencyIntegrationTest}'s exactly, field
 * names included, so the two classes can share one cached application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OwnerChangeConcurrencyIntegrationTest {
    private static final int MYSQL_LOCK_NOWAIT = 3572;

    @Autowired private DealService dealService;
    @Autowired private PersonService personService;
    @Autowired private CompanyService companyService;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private TenantContext tenantContext;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
    @MockitoSpyBean private WorkspaceMapper workspaceMapper;
    @MockitoSpyBean private NotificationMapper notificationMapper;
    @MockitoSpyBean private DealMapper dealMapper;
    @MockitoSpyBean private PersonMapper personMapper;
    @MockitoSpyBean private CompanyMapper companyMapper;
    @MockitoBean private AuditService auditService;
    @MockitoBean private NotificationChangePublisher notificationChanges;
    @MockitoBean private NotificationStateVersionService notificationStateVersionService;
    @MockitoBean private ReferenceService referenceService;
    @MockitoBean private RuleTriggerPublisher ruleTriggers;
    @MockitoBean private SessionSecurityService sessionSecurityService;

    private final CountDownLatch readViewOpen = new CountDownLatch(1);
    private final CountDownLatch concurrentChangeCommitted = new CountDownLatch(1);
    private final AtomicBoolean paused = new AtomicBoolean();

    private Organization organization;
    private Workspace workspace;
    private User currentUser;
    private User newOwner;
    private Deal deal;
    private Person person;
    private Company company;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Owner changes " + unique);
        organization.setSlug("owner-changes-" + unique);
        organizationMapper.insert(organization);

        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Owner changes " + unique);
        workspace.setSlug("owner-changes-" + unique);
        workspaceMapper.insert(workspace);

        currentUser = user("owner-changes-actor-" + unique);
        newOwner = user("owner-changes-new-owner-" + unique);
        workspaceMapper.addMember(workspace.getId(), currentUser.getId(), "owner");
        workspaceMapper.addMember(workspace.getId(), newOwner.getId(), "member");

        Pipeline pipeline = new Pipeline();
        pipeline.setWorkspaceId(workspace.getId());
        pipeline.setName("Owner changes pipeline " + unique);
        pipelineMapper.insertPipeline(pipeline);
        Stage stage = new Stage();
        stage.setWorkspaceId(workspace.getId());
        stage.setPipeline(pipeline);
        stage.setName("Open " + unique);
        stage.setPosition(0);
        pipelineMapper.insertStage(stage);

        deal = new Deal();
        deal.setWorkspaceId(workspace.getId());
        deal.setOwnerId(currentUser.getId());
        deal.setName("Owner changes " + unique);
        deal.setValue(new BigDecimal("1000.00"));
        deal.setCurrency("JPY");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setPosition(0);
        dealMapper.insert(deal);

        person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setOwnerId(currentUser.getId());
        person.setName("Owner changes " + unique);
        person.setEmail("owner-changes-" + unique + "@example.test");
        personMapper.insert(person);

        company = new Company();
        company.setWorkspaceId(workspace.getId());
        company.setOwnerId(currentUser.getId());
        company.setName("Owner changes " + unique);
        companyMapper.insert(company);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        tenantContext.clear();
        if (workspace != null) {
            jdbcTemplate.update("DELETE FROM deal_collaborator WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM deal WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM person WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM company WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM stage WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM pipeline WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        for (User user : new User[] {newOwner, currentUser}) {
            if (user != null) {
                jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", user.getId());
            }
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @Test
    void aDealOwnerChangeRacingTheSameChangeAuditsAndTriggersFromTheLockedRow() throws Exception {
        int workspaceId = workspace.getId();
        int ownerId = newOwner.getId();
        when(referenceService.hydrateDeals(anyInt(), anyList())).thenAnswer(invocation -> invocation.getArgument(1));
        pauseInTheNewOwnersMembershipLock(() -> verify(dealMapper).getDealById(workspaceId, deal.getId()));
        AtomicReference<Deal> returned = new AtomicReference<>();

        changeOwnerWhileTheSameChangeCommits(() -> returned.set(dealService.updateOwner(deal.getId(), ownerId)));

        assertEquals(Integer.valueOf(ownerId), committedOwner("deal", deal.getId()));
        assertEquals(Integer.valueOf(ownerId), returned.get().getOwnerId());
        verify(dealMapper, never()).getDealByIdForUpdate(anyInt(), anyInt());
        verify(auditService).singleChange("ownerId", currentUser.getId(), ownerId);
        verify(auditService).singleChange("ownerId", ownerId, ownerId);
        verify(ruleTriggers, times(1)).publish(workspaceId, "deal", deal.getId(), "deal.owner_changed");
    }

    @Test
    void aContactOwnerChangeRacingTheSameChangeAuditsAndTriggersFromTheLockedRow() throws Exception {
        int workspaceId = workspace.getId();
        int ownerId = newOwner.getId();
        pauseInTheNewOwnersMembershipLock(() -> verify(personMapper).existsOwned(workspaceId, person.getId()));
        AtomicReference<Person> returned = new AtomicReference<>();

        changeOwnerWhileTheSameChangeCommits(() -> returned.set(personService.updateOwner(person.getId(), ownerId)));

        assertEquals(Integer.valueOf(ownerId), committedOwner("person", person.getId()));
        assertEquals(Integer.valueOf(ownerId), returned.get().getOwnerId());
        verify(auditService).singleChange("ownerId", currentUser.getId(), ownerId);
        verify(auditService).singleChange("ownerId", ownerId, ownerId);
        verify(ruleTriggers, times(1)).publish(workspaceId, "person", person.getId(), "person.owner_changed");
    }

    @Test
    void aCompanyOwnerChangeRacingTheSameChangeAuditsAndTriggersFromTheLockedRow() throws Exception {
        int workspaceId = workspace.getId();
        int ownerId = newOwner.getId();
        pauseInTheNewOwnersMembershipLock(() -> verify(companyMapper).existsOwned(workspaceId, company.getId()));
        AtomicReference<Company> returned = new AtomicReference<>();

        changeOwnerWhileTheSameChangeCommits(() -> returned.set(companyService.updateOwner(company.getId(), ownerId)));

        assertEquals(Integer.valueOf(ownerId), committedOwner("company", company.getId()));
        assertEquals(Integer.valueOf(ownerId), returned.get().getOwnerId());
        verify(auditService).singleChange("ownerId", currentUser.getId(), ownerId);
        verify(auditService).singleChange("ownerId", ownerId, ownerId);
        verify(ruleTriggers, times(1)).publish(
            workspaceId, "company", company.getId(), "company.owner_changed");
    }

    /**
     * The locking read holds the contact row from the audited old owner through the write, so no
     * change can land between them. Under {@code READ_COMMITTED} a plain read after the membership
     * lock would already see committed owners; the lock is what the locking read adds (#1961).
     */
    @Test
    void aContactOwnerChangeHoldsTheRowFromItsReadToItsWrite() {
        int workspaceId = workspace.getId();
        AtomicReference<Integer> probe = new AtomicReference<>();
        PersonMapper realPersonMapper = sqlSessionTemplate.getMapper(PersonMapper.class);
        doAnswer(invocation -> {
            Person locked = realPersonMapper.getOwnedPersonByIdForUpdate(workspaceId, person.getId());
            probe.set(lockErrorFromAnotherConnection("person", person.getId()));
            return locked;
        }).when(personMapper).getOwnedPersonByIdForUpdate(workspaceId, person.getId());

        asCurrentUser(() -> personService.updateOwner(person.getId(), newOwner.getId()));

        assertEquals(Integer.valueOf(MYSQL_LOCK_NOWAIT), probe.get());
    }

    /** The same for a company. */
    @Test
    void aCompanyOwnerChangeHoldsTheRowFromItsReadToItsWrite() {
        int workspaceId = workspace.getId();
        AtomicReference<Integer> probe = new AtomicReference<>();
        CompanyMapper realCompanyMapper = sqlSessionTemplate.getMapper(CompanyMapper.class);
        doAnswer(invocation -> {
            Company locked = realCompanyMapper.getOwnedCompanyByIdForUpdate(workspaceId, company.getId());
            probe.set(lockErrorFromAnotherConnection("company", company.getId()));
            return locked;
        }).when(companyMapper).getOwnedCompanyByIdForUpdate(workspaceId, company.getId());

        asCurrentUser(() -> companyService.updateOwner(company.getId(), newOwner.getId()));

        assertEquals(Integer.valueOf(MYSQL_LOCK_NOWAIT), probe.get());
    }

    /**
     * A contact archived while the owner change waited is refused under the lock, before anything is
     * written, audited or triggered.
     */
    @Test
    void aContactArchivedWhileTheOwnerChangeWaitedIsRefusedBeforeAnyWrite() throws Exception {
        int workspaceId = workspace.getId();
        int ownerId = newOwner.getId();
        pauseInTheNewOwnersMembershipLock(() -> verify(personMapper).existsOwned(workspaceId, person.getId()));

        ExecutionException refused = assertThrows(ExecutionException.class, () -> runWhilePaused(
            () -> personService.updateOwner(person.getId(), ownerId),
            () -> archive("person", person.getId())));

        assertTrue(paused.get());
        assertInstanceOf(ResourceNotFoundException.class, refused.getCause());
        assertTrue(archived("person", person.getId()));
        assertEquals(Integer.valueOf(currentUser.getId()), committedOwner("person", person.getId()));
        verify(personMapper, never()).updateOwner(anyInt(), anyInt(), any());
        verify(auditService, never()).singleChange(eq("ownerId"), any(), any());
        verify(ruleTriggers, never()).publish(anyInt(), anyString(), anyInt(), anyString());
    }

    /** The same refusal for a company archived while its owner change waited. */
    @Test
    void aCompanyArchivedWhileTheOwnerChangeWaitedIsRefusedBeforeAnyWrite() throws Exception {
        int workspaceId = workspace.getId();
        int ownerId = newOwner.getId();
        pauseInTheNewOwnersMembershipLock(() -> verify(companyMapper).existsOwned(workspaceId, company.getId()));

        ExecutionException refused = assertThrows(ExecutionException.class, () -> runWhilePaused(
            () -> companyService.updateOwner(company.getId(), ownerId),
            () -> archive("company", company.getId())));

        assertTrue(paused.get());
        assertInstanceOf(ResourceNotFoundException.class, refused.getCause());
        assertTrue(archived("company", company.getId()));
        assertEquals(Integer.valueOf(currentUser.getId()), committedOwner("company", company.getId()));
        verify(companyMapper, never()).updateOwner(anyInt(), anyInt(), any());
        verify(auditService, never()).singleChange(eq("ownerId"), any(), any());
        verify(ruleTriggers, never()).publish(anyInt(), anyString(), anyInt(), anyString());
    }

    /**
     * A deal deleted while its owner change waited is refused under the lock. Before #1948 the change
     * updated no row yet still committed its audit and its trigger, and answered 200.
     */
    @Test
    void aDealDeletedWhileTheOwnerChangeWaitedIsRefusedBeforeAnyWrite() throws Exception {
        int workspaceId = workspace.getId();
        int ownerId = newOwner.getId();
        when(referenceService.hydrateDeals(anyInt(), anyList())).thenAnswer(invocation -> invocation.getArgument(1));
        pauseInTheNewOwnersMembershipLock(() -> verify(dealMapper).getDealById(workspaceId, deal.getId()));

        ExecutionException refused = assertThrows(ExecutionException.class, () -> runWhilePaused(
            () -> dealService.updateOwner(deal.getId(), ownerId),
            () -> jdbcTemplate.update(
                "DELETE FROM deal WHERE workspace_id = ? AND id = ?", workspaceId, deal.getId())));

        assertTrue(paused.get());
        assertInstanceOf(ResourceNotFoundException.class, refused.getCause());
        verify(dealMapper, never()).updateOwner(anyInt(), anyInt(), any());
        verify(auditService, never()).singleChange(eq("ownerId"), any(), any());
        verify(ruleTriggers, never()).publish(anyInt(), anyString(), anyInt(), anyString());
    }

    /**
     * Unassigning takes no membership lock, so the first change is paused just before it locks the
     * contact row instead, still after its unlocked check opened its read view.
     */
    @Test
    void anUnassignmentRacingTheSameUnassignmentAuditsAndTriggersFromTheLockedRow() throws Exception {
        int workspaceId = workspace.getId();
        PersonMapper realPersonMapper = sqlSessionTemplate.getMapper(PersonMapper.class);
        doAnswer(invocation -> {
            if (paused.compareAndSet(false, true)) {
                verify(personMapper).existsOwned(workspaceId, person.getId());
                readViewOpen.countDown();
                assertTrue(concurrentChangeCommitted.await(30, TimeUnit.SECONDS));
            }
            return realPersonMapper.getOwnedPersonByIdForUpdate(workspaceId, person.getId());
        }).when(personMapper).getOwnedPersonByIdForUpdate(workspaceId, person.getId());

        changeOwnerWhileTheSameChangeCommits(() -> personService.updateOwner(person.getId(), null));

        assertNull(committedOwner("person", person.getId()));
        verify(auditService).singleChange("ownerId", currentUser.getId(), null);
        verify(auditService).singleChange("ownerId", null, null);
        verify(ruleTriggers, times(1)).publish(workspaceId, "person", person.getId(), "person.owner_changed");
        verify(workspaceMapper, never()).lockActiveMembership(eq(workspaceId), anyInt());
    }

    /**
     * Pauses the first change inside its new owner's membership lock, before it takes that lock and
     * after asserting its unlocked check already ran; the second change's identical call passes.
     */
    private void pauseInTheNewOwnersMembershipLock(Runnable assertReadViewOpen) {
        int workspaceId = workspace.getId();
        int ownerId = newOwner.getId();
        WorkspaceMapper realWorkspaceMapper = sqlSessionTemplate.getMapper(WorkspaceMapper.class);
        doAnswer(invocation -> {
            if (paused.compareAndSet(false, true)) {
                assertReadViewOpen.run();
                readViewOpen.countDown();
                assertTrue(concurrentChangeCommitted.await(30, TimeUnit.SECONDS));
            }
            return realWorkspaceMapper.lockActiveMembership(workspaceId, ownerId);
        }).when(workspaceMapper).lockActiveMembership(workspaceId, ownerId);
    }

    /** Runs the change on another thread, commits the same change on this one while it is paused. */
    private void changeOwnerWhileTheSameChangeCommits(Runnable change) throws Exception {
        runWhilePaused(change, () -> asCurrentUser(change));
    }

    /** Runs the change on another thread and commits {@code concurrentCommit} while it is paused. */
    private void runWhilePaused(Runnable change, Runnable concurrentCommit) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> firstChange = executor.submit(() -> asCurrentUser(change));
            if (!readViewOpen.await(10, TimeUnit.SECONDS)) {
                firstChange.get(0, TimeUnit.SECONDS);
                throw new AssertionError("The first owner change never reached its pause");
            }
            try {
                concurrentCommit.run();
            } finally {
                concurrentChangeCommitted.countDown();
            }
            firstChange.get(20, TimeUnit.SECONDS);
        } finally {
            concurrentChangeCommitted.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private void asCurrentUser(Runnable change) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(currentUser, null, currentUser.getAuthorities()));
        tenantContext.set(workspace.getId(), organization.getId(), currentUser.getId(), "owner", null);
        try {
            change.run();
        } finally {
            SecurityContextHolder.clearContext();
            tenantContext.clear();
        }
    }

    private void archive(String table, int id) {
        jdbcTemplate.update(
            "UPDATE " + table + " SET archived_at = UTC_TIMESTAMP() WHERE workspace_id = ? AND id = ?",
            workspace.getId(), id);
    }

    /** Tries to lock the row from an independent connection without waiting, and reports the error. */
    private Integer lockErrorFromAnotherConnection(String table, int id) throws SQLException {
        try (Connection other = dataSource.getConnection(); Statement statement = other.createStatement()) {
            other.setAutoCommit(false);
            try {
                statement.executeQuery("SELECT id FROM " + table + " WHERE id = " + id + " FOR UPDATE NOWAIT")
                    .close();
                return null;
            } catch (SQLException refused) {
                return refused.getErrorCode();
            } finally {
                other.rollback();
            }
        }
    }

    private boolean archived(String table, int id) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
            "SELECT archived_at IS NOT NULL FROM " + table + " WHERE workspace_id = ? AND id = ?",
            Boolean.class, workspace.getId(), id));
    }

    private Integer committedOwner(String table, int id) {
        return jdbcTemplate.queryForObject(
            "SELECT owner_id FROM " + table + " WHERE workspace_id = ? AND id = ?",
            Integer.class, workspace.getId(), id);
    }

    private User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setDisplayName(username);
        user.setEmail(username + "@example.com");
        user.setPasswordHash("hash-" + username);
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }
}
