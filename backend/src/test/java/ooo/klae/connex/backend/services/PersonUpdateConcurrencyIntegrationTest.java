package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.StaleRecordException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import ooo.klae.connex.backend.tenant.TenantContext;

/** Exercises committed competing contact writes and the atomic SQL version fence. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PersonUpdateConcurrencyIntegrationTest {
    @Autowired private PersonService personService;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private TenantContext tenantContext;
    @Autowired private JdbcTemplate jdbcTemplate;
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

    private Organization organization;
    private Workspace workspace;
    private User currentUser;
    private Person person;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Contact updates " + unique);
        organization.setSlug("contact-updates-" + unique);
        organizationMapper.insert(organization);
        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Contact updates " + unique);
        workspace.setSlug("contact-updates-" + unique);
        workspaceMapper.insert(workspace);
        currentUser = user("contact-updates-actor-" + unique);
        workspaceMapper.addMember(workspace.getId(), currentUser.getId(), "owner");
        person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setOwnerId(currentUser.getId());
        person.setName("Contact updates " + unique);
        person.setTitle("Original title");
        person.setPhone("Original phone");
        personMapper.insert(person);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        tenantContext.clear();
        if (workspace != null) {
            jdbcTemplate.update("DELETE FROM person WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        if (currentUser != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", currentUser.getId());
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @Test
    void stalePhoneEditPreservesTheSecondWritersTitleAndReturnsAConflictBeforeRelationValidation() {
        Person stale = current();
        stale.setExpectedVersion(stale.getVersion());
        stale.setPhone("Draft phone");
        Company invalidCompany = new Company();
        invalidCompany.setId(-1);
        stale.setCompany(invalidCompany);
        Person secondWriter = current();
        secondWriter.setTitle("New title");
        asCurrentUser(() -> personService.update(person.getId(), secondWriter));
        long currentVersion = current().getVersion();
        org.mockito.Mockito.clearInvocations(auditService, ruleTriggers, notificationChanges);

        asCurrentUser(() -> {
            StaleRecordException error = assertThrows(StaleRecordException.class,
                () -> personService.update(person.getId(), stale));
            assertEquals(currentVersion, error.getCurrentVersion());
        });

        Person stored = current();
        assertEquals("New title", stored.getTitle());
        assertEquals("Original phone", stored.getPhone());
        assertEquals(currentVersion, stored.getVersion());
        verify(auditService, never()).record(anyString(), anyString(), anyInt(), any(), anyString(), any());
        verify(ruleTriggers, never()).publish(anyInt(), anyString(), anyInt(), anyString());
        verify(notificationChanges, never()).publish(anyInt(), anyString(), anyInt());
    }

    @Test
    void zeroRowAtomicRefusalNeverAuditsOrPublishesSuccess() {
        Person edit = current();
        edit.setExpectedVersion(edit.getVersion());
        doReturn(0).when(personMapper).update(any(Person.class));
        asCurrentUser(() -> assertThrows(StaleRecordException.class,
            () -> personService.update(person.getId(), edit)));
        verify(auditService, never()).record(anyString(), anyString(), anyInt(), any(), anyString(), any());
        verify(ruleTriggers, never()).publish(anyInt(), anyString(), anyInt(), anyString());
    }

    @Test
    void aMissingContactRemainsNotFound() {
        Person edit = current();
        edit.setExpectedVersion(edit.getVersion());
        jdbcTemplate.update("DELETE FROM person WHERE workspace_id = ? AND id = ?", workspace.getId(), person.getId());
        asCurrentUser(() -> assertThrows(ResourceNotFoundException.class,
            () -> personService.update(person.getId(), edit)));
    }

    @Test
    void successfulFullAndTargetedWritesReturnTheVersionThatCanSeedTheNextEdit() {
        asCurrentUser(() -> {
            Person ownerSaved = personService.updateOwner(person.getId(), null);
            assertEquals(current().getVersion(), ownerSaved.getVersion());
            ownerSaved.setExpectedVersion(ownerSaved.getVersion());
            ownerSaved.setPhone("Current phone");
            Person saved = personService.update(person.getId(), ownerSaved);
            assertEquals(current().getVersion(), saved.getVersion());
            assertTrue(saved.getVersion() > ownerSaved.getExpectedVersion());
            assertEquals("Current phone", saved.getPhone());
        });
    }

    @Test
    void omittedExpectedVersionRetainsLegacyFullEditBehavior() {
        Person legacy = current();
        Person secondWriter = current();
        secondWriter.setTitle("New title");
        personMapper.update(secondWriter);
        asCurrentUser(() -> personService.update(person.getId(), legacy));
        assertEquals("Original title", current().getTitle());
    }

    @Test
    void twoWritersWithTheSameSnapshotHaveExactlyOneAtomicWinner() throws Exception {
        Person first = current();
        Person second = current();
        first.setExpectedVersion(first.getVersion());
        second.setExpectedVersion(second.getVersion());
        first.setTitle("First title");
        second.setPhone("Second phone");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> firstWrite = executor.submit(() -> conditionalWrite(first, ready, start));
            Future<Integer> secondWrite = executor.submit(() -> conditionalWrite(second, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int firstCount = firstWrite.get(20, TimeUnit.SECONDS);
            int secondCount = secondWrite.get(20, TimeUnit.SECONDS);
            assertEquals(1, firstCount + secondCount);
            Person stored = current();
            assertEquals(firstCount == 1 ? "First title" : "Original title", stored.getTitle());
            assertEquals(secondCount == 1 ? "Second phone" : "Original phone", stored.getPhone());
            assertEquals(first.getVersion() + 1, stored.getVersion());
        } finally {
            start.countDown();
        }
    }

    private int conditionalWrite(Person edit, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        java.util.concurrent.atomic.AtomicInteger changed = new java.util.concurrent.atomic.AtomicInteger();
        asCurrentUser(() -> changed.set(personMapper.update(edit)));
        return changed.get();
    }

    private Person current() {
        return personMapper.getPersonById(workspace.getId(), "[" + workspace.getId() + "]", person.getId());
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
