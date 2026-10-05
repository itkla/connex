package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * Races record updates that audit a previous value against concurrent writes to the same record
 * (#1968 for contacts and companies, #1980 for deals and attachments).
 *
 * <ul>
 *   <li>A tag replacement locks the record first and reads the tags it is about to clear under locks
 *       on the association rows. A tag added meanwhile waits on the record row at its foreign-key
 *       check, and a removal still in flight makes the replacement wait on the association row, so the
 *       audit names exactly the tags the replacement removed. A record archived or deleted meanwhile is
 *       refused under the lock, before anything is written.</li>
 *   <li>An evaluation opt-out audits from the contact row it locks, holds that row until it writes,
 *       and returns the committed flags even when a concurrent change already set the same ones.</li>
 * </ul>
 *
 * <p>The bean overrides match {@code OwnerChangeConcurrencyIntegrationTest}'s exactly, field names
 * included, so the concurrency classes can share one cached application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RecordUpdateConcurrencyIntegrationTest {
    private static final int MYSQL_LOCK_NOWAIT = 3572;
    private static final int LOCK_WAIT_PROBE_SECONDS = 8;

    @Autowired private PersonService personService;
    @Autowired private CompanyService companyService;
    @Autowired private DealService dealService;
    @Autowired private AttachmentService attachmentService;
    @Autowired private AttachmentMapper attachmentMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private TagMapper tagMapper;
    @Autowired private TenantContext tenantContext;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
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
    private Company company;
    private Deal deal;
    private Attachment attachment;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Record updates " + unique);
        organization.setSlug("record-updates-" + unique);
        organizationMapper.insert(organization);

        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Record updates " + unique);
        workspace.setSlug("record-updates-" + unique);
        workspaceMapper.insert(workspace);

        currentUser = new User();
        currentUser.setUsername("record-updates-actor-" + unique);
        currentUser.setDisplayName(currentUser.getUsername());
        currentUser.setEmail(currentUser.getUsername() + "@example.com");
        currentUser.setPasswordHash("hash-" + currentUser.getUsername());
        currentUser.setTimezone("UTC");
        userMapper.insert(currentUser);
        workspaceMapper.addMember(workspace.getId(), currentUser.getId(), "owner");

        person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setOwnerId(currentUser.getId());
        person.setName("Record updates " + unique);
        person.setEmail("record-updates-" + unique + "@example.test");
        personMapper.insert(person);

        company = new Company();
        company.setWorkspaceId(workspace.getId());
        company.setOwnerId(currentUser.getId());
        company.setName("Record updates " + unique);
        companyMapper.insert(company);

        Pipeline pipeline = new Pipeline();
        pipeline.setWorkspaceId(workspace.getId());
        pipeline.setName("Record updates pipeline " + unique);
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
        deal.setName("Record updates " + unique);
        deal.setValue(new BigDecimal("1000.00"));
        deal.setCurrency("JPY");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setPosition(0);
        dealMapper.insert(deal);

        attachment = new Attachment();
        attachment.setWorkspaceId(workspace.getId());
        attachment.setEntityType("company");
        attachment.setEntityId(company.getId());
        attachment.setFileName("record-updates-" + unique + ".png");
        attachment.setUrl("/api/attachments/content/record-updates-" + unique);
        attachmentMapper.insert(attachment);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        tenantContext.clear();
        if (workspace != null) {
            jdbcTemplate.update("DELETE FROM attachment WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM deal WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM stage WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM pipeline WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM person WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM company WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM tag WHERE workspace_id = ?", workspace.getId());
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

    /**
     * A tag added while a replacement holds the contact row waits for the replacement to commit, so the
     * replacement's audit names only the tags it removed and the added tag survives it.
     */
    @Test
    void aTagAddedWhileAContactsTagsAreReplacedWaitsForTheReplacement() throws Exception {
        Tag kept = tag("kept");
        Tag replacement = tag("replacement");
        Tag added = tag("added");
        attach("person_tag", "person_id", person.getId(), kept.getId());
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Long> replacementConnection = new AtomicReference<>();
        PersonMapper realPersonMapper = sqlSessionTemplate.getMapper(PersonMapper.class);
        doAnswer(invocation -> {
            Person locked = realPersonMapper.getOwnedPersonByIdForUpdate(workspace.getId(), person.getId());
            replacementConnection.set(currentConnectionId());
            lockHeld.countDown();
            assertTrue(release.await(30, TimeUnit.SECONDS));
            return locked;
        }).when(personMapper).getOwnedPersonByIdForUpdate(workspace.getId(), person.getId());

        replaceWhileAnAdditionWaits(
            () -> personService.replaceTags(person.getId(), List.of(replacement.getId())),
            () -> personService.addTag(person.getId(), added.getId()),
            "person", replacementConnection, lockHeld, release);

        assertEquals(Set.of(replacement.getId(), added.getId()),
            committedTags("person_tag", "person_id", person.getId()));
        verify(auditService).singleChange("tags", List.of("kept"), List.of("replacement"));
    }

    /** The same for a company. */
    @Test
    void aTagAddedWhileACompanysTagsAreReplacedWaitsForTheReplacement() throws Exception {
        Tag kept = tag("kept");
        Tag replacement = tag("replacement");
        Tag added = tag("added");
        attach("company_tag", "company_id", company.getId(), kept.getId());
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Long> replacementConnection = new AtomicReference<>();
        CompanyMapper realCompanyMapper = sqlSessionTemplate.getMapper(CompanyMapper.class);
        doAnswer(invocation -> {
            Company locked = realCompanyMapper.getOwnedCompanyByIdForUpdate(workspace.getId(), company.getId());
            replacementConnection.set(currentConnectionId());
            lockHeld.countDown();
            assertTrue(release.await(30, TimeUnit.SECONDS));
            return locked;
        }).when(companyMapper).getOwnedCompanyByIdForUpdate(workspace.getId(), company.getId());

        replaceWhileAnAdditionWaits(
            () -> companyService.replaceTags(company.getId(), List.of(replacement.getId())),
            () -> companyService.addTag(company.getId(), added.getId()),
            "company", replacementConnection, lockHeld, release);

        assertEquals(Set.of(replacement.getId(), added.getId()),
            committedTags("company_tag", "company_id", company.getId()));
        verify(auditService).singleChange("tags", List.of("kept"), List.of("replacement"));
    }

    /**
     * A replacement that meets a tag removal still in flight waits on the association row, so its
     * audit does not claim the removed tag as one it removed.
     */
    @Test
    void aContactReplacementWaitsForAnInFlightRemovalAndAuditsOnlyTheTagsItRemoved() throws Exception {
        Tag removed = tag("removed");
        Tag kept = tag("kept");
        Tag replacement = tag("replacement");
        attach("person_tag", "person_id", person.getId(), removed.getId());
        attach("person_tag", "person_id", person.getId(), kept.getId());

        replaceWhileARemovalIsInFlight(
            () -> personService.replaceTags(person.getId(), List.of(replacement.getId())),
            "person_tag", "person_id", person.getId(), removed.getId());

        assertEquals(Set.of(replacement.getId()), committedTags("person_tag", "person_id", person.getId()));
        verify(auditService).singleChange("tags", List.of("kept"), List.of("replacement"));
    }

    /** The same for a company. */
    @Test
    void aCompanyReplacementWaitsForAnInFlightRemovalAndAuditsOnlyTheTagsItRemoved() throws Exception {
        Tag removed = tag("removed");
        Tag kept = tag("kept");
        Tag replacement = tag("replacement");
        attach("company_tag", "company_id", company.getId(), removed.getId());
        attach("company_tag", "company_id", company.getId(), kept.getId());

        replaceWhileARemovalIsInFlight(
            () -> companyService.replaceTags(company.getId(), List.of(replacement.getId())),
            "company_tag", "company_id", company.getId(), removed.getId());

        assertEquals(Set.of(replacement.getId()), committedTags("company_tag", "company_id", company.getId()));
        verify(auditService).singleChange("tags", List.of("kept"), List.of("replacement"));
    }

    /** A contact archived while its tag replacement waited is refused under the lock, before any write. */
    @Test
    void aContactArchivedWhileItsTagsWereBeingReplacedIsRefusedBeforeAnyWrite() throws Exception {
        Tag kept = tag("kept");
        Tag replacement = tag("replacement");
        attach("person_tag", "person_id", person.getId(), kept.getId());
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch archivedMeanwhile = new CountDownLatch(1);
        PersonMapper realPersonMapper = sqlSessionTemplate.getMapper(PersonMapper.class);
        doAnswer(invocation -> {
            reached.countDown();
            assertTrue(archivedMeanwhile.await(30, TimeUnit.SECONDS));
            return realPersonMapper.getOwnedPersonByIdForUpdate(workspace.getId(), person.getId());
        }).when(personMapper).getOwnedPersonByIdForUpdate(workspace.getId(), person.getId());

        Throwable refused = refusedAfterConcurrentChange(
            () -> personService.replaceTags(person.getId(), List.of(replacement.getId())),
            () -> archive("person", person.getId()), reached, archivedMeanwhile);

        assertInstanceOf(ResourceNotFoundException.class, refused);
        assertEquals(Set.of(kept.getId()), committedTags("person_tag", "person_id", person.getId()));
        verify(personMapper, never()).clearTags(anyInt(), anyInt());
        verify(auditService, never()).singleChange(eq("tags"), any(), any());
    }

    /** The same refusal for a company. */
    @Test
    void aCompanyArchivedWhileItsTagsWereBeingReplacedIsRefusedBeforeAnyWrite() throws Exception {
        Tag kept = tag("kept");
        Tag replacement = tag("replacement");
        attach("company_tag", "company_id", company.getId(), kept.getId());
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch archivedMeanwhile = new CountDownLatch(1);
        CompanyMapper realCompanyMapper = sqlSessionTemplate.getMapper(CompanyMapper.class);
        doAnswer(invocation -> {
            reached.countDown();
            assertTrue(archivedMeanwhile.await(30, TimeUnit.SECONDS));
            return realCompanyMapper.getOwnedCompanyByIdForUpdate(workspace.getId(), company.getId());
        }).when(companyMapper).getOwnedCompanyByIdForUpdate(workspace.getId(), company.getId());

        Throwable refused = refusedAfterConcurrentChange(
            () -> companyService.replaceTags(company.getId(), List.of(replacement.getId())),
            () -> archive("company", company.getId()), reached, archivedMeanwhile);

        assertInstanceOf(ResourceNotFoundException.class, refused);
        assertEquals(Set.of(kept.getId()), committedTags("company_tag", "company_id", company.getId()));
        verify(companyMapper, never()).clearTags(anyInt(), anyInt());
        verify(auditService, never()).singleChange(eq("tags"), any(), any());
    }

    /**
     * Two identical opt-outs race: the first reads the contact without locking, as any earlier read in
     * its transaction would, then waits just before it locks the contact while the second commits. The
     * first must audit from the committed flags and return them, even though its own write leaves no
     * newer row version and a repeatable-read snapshot would still show the flags from before (#1961's
     * shape).
     */
    @Test
    void anEvaluationOptOutRacingTheSameOptOutAuditsAndReturnsTheCommittedFlags() throws Exception {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch concurrentCommitted = new CountDownLatch(1);
        AtomicBoolean paused = new AtomicBoolean();
        PersonMapper realPersonMapper = sqlSessionTemplate.getMapper(PersonMapper.class);
        doAnswer(invocation -> {
            if (paused.compareAndSet(false, true)) {
                assertTrue(realPersonMapper.existsOwned(workspace.getId(), person.getId()));
                reached.countDown();
                assertTrue(concurrentCommitted.await(30, TimeUnit.SECONDS));
            }
            return realPersonMapper.getOwnedPersonByIdForUpdate(workspace.getId(), person.getId());
        }).when(personMapper).getOwnedPersonByIdForUpdate(workspace.getId(), person.getId());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Person returned;
        try {
            Future<Person> first = executor.submit(
                () -> asCurrentUser(() -> personService.updateEvaluationExclusions(person.getId(), true, null)));
            if (!reached.await(10, TimeUnit.SECONDS)) {
                first.get(0, TimeUnit.SECONDS);
                throw new AssertionError("The first opt-out never reached its pause");
            }
            try {
                asCurrentUser(() -> personService.updateEvaluationExclusions(person.getId(), true, null));
            } finally {
                concurrentCommitted.countDown();
            }
            returned = first.get(20, TimeUnit.SECONDS);
        } finally {
            concurrentCommitted.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertTrue(returned.isRiskExcluded());
        ArgumentCaptor<Object> befores = ArgumentCaptor.forClass(Object.class);
        verify(auditService, times(2)).diff(befores.capture(), any(), any());
        assertEquals(List.of(false, true),
            befores.getAllValues().stream().map(before -> ((Person) before).isRiskExcluded()).toList());
    }

    /** The opt-out holds the contact row exclusively from its audited read to its write. */
    @Test
    void anEvaluationOptOutHoldsTheContactRowFromItsReadToItsWrite() {
        AtomicReference<Integer> probe = new AtomicReference<>();
        PersonMapper realPersonMapper = sqlSessionTemplate.getMapper(PersonMapper.class);
        doAnswer(invocation -> {
            Person locked = realPersonMapper.getOwnedPersonByIdForUpdate(workspace.getId(), person.getId());
            probe.set(lockErrorFromAnotherConnection("person", person.getId()));
            return locked;
        }).when(personMapper).getOwnedPersonByIdForUpdate(workspace.getId(), person.getId());

        asCurrentUser(() -> personService.updateEvaluationExclusions(person.getId(), true, null));

        assertEquals(Integer.valueOf(MYSQL_LOCK_NOWAIT), probe.get());
    }

    /** A tag added while a replacement holds the deal row waits for the replacement to commit (#1980). */
    @Test
    void aTagAddedWhileADealsTagsAreReplacedWaitsForTheReplacement() throws Exception {
        Tag kept = tag("kept");
        Tag replacement = tag("replacement");
        Tag added = tag("added");
        attach("deal_tag", "deal_id", deal.getId(), kept.getId());
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Long> replacementConnection = new AtomicReference<>();
        DealMapper realDealMapper = sqlSessionTemplate.getMapper(DealMapper.class);
        doAnswer(invocation -> {
            Deal locked = realDealMapper.getDealByPrimaryKeyForUpdate(workspace.getId(), deal.getId());
            replacementConnection.set(currentConnectionId());
            lockHeld.countDown();
            assertTrue(release.await(30, TimeUnit.SECONDS));
            return locked;
        }).when(dealMapper).getDealByPrimaryKeyForUpdate(workspace.getId(), deal.getId());

        replaceWhileAnAdditionWaits(
            () -> dealService.replaceTags(deal.getId(), List.of(replacement.getId())),
            () -> dealService.addTag(deal.getId(), added.getId()),
            "deal", replacementConnection, lockHeld, release);

        assertEquals(Set.of(replacement.getId(), added.getId()), committedTags("deal_tag", "deal_id", deal.getId()));
        verify(auditService).singleChange("tags", List.of("kept"), List.of("replacement"));
    }

    /** A deal replacement meeting an in-flight removal audits only the tags it removed (#1980). */
    @Test
    void aDealReplacementWaitsForAnInFlightRemovalAndAuditsOnlyTheTagsItRemoved() throws Exception {
        Tag removed = tag("removed");
        Tag kept = tag("kept");
        Tag replacement = tag("replacement");
        attach("deal_tag", "deal_id", deal.getId(), removed.getId());
        attach("deal_tag", "deal_id", deal.getId(), kept.getId());

        replaceWhileARemovalIsInFlight(
            () -> dealService.replaceTags(deal.getId(), List.of(replacement.getId())),
            "deal_tag", "deal_id", deal.getId(), removed.getId());

        assertEquals(Set.of(replacement.getId()), committedTags("deal_tag", "deal_id", deal.getId()));
        verify(auditService).singleChange("tags", List.of("kept"), List.of("replacement"));
    }

    /** A deal deleted while its tag replacement waited is refused under the lock, before any write. */
    @Test
    void aDealDeletedWhileItsTagsWereBeingReplacedIsRefusedBeforeAnyWrite() throws Exception {
        Tag kept = tag("kept");
        Tag replacement = tag("replacement");
        attach("deal_tag", "deal_id", deal.getId(), kept.getId());
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch deletedMeanwhile = new CountDownLatch(1);
        DealMapper realDealMapper = sqlSessionTemplate.getMapper(DealMapper.class);
        doAnswer(invocation -> {
            reached.countDown();
            assertTrue(deletedMeanwhile.await(30, TimeUnit.SECONDS));
            return realDealMapper.getDealByPrimaryKeyForUpdate(workspace.getId(), deal.getId());
        }).when(dealMapper).getDealByPrimaryKeyForUpdate(workspace.getId(), deal.getId());

        Throwable refused = refusedAfterConcurrentChange(
            () -> dealService.replaceTags(deal.getId(), List.of(replacement.getId())),
            () -> jdbcTemplate.update(
                "DELETE FROM deal WHERE workspace_id = ? AND id = ?", workspace.getId(), deal.getId()),
            reached, deletedMeanwhile);

        assertInstanceOf(ResourceNotFoundException.class, refused);
        verify(dealMapper, never()).clearTags(anyInt(), anyInt());
        verify(auditService, never()).singleChange(eq("tags"), any(), any());
    }

    /**
     * An attachment replacement holds the attachment row from its first statement (#1980). Waiting
     * behind a removal still in flight, it already holds that row, so a tag added meanwhile waits on it
     * at its foreign-key check; and the audit names only the tags the replacement removed.
     */
    @Test
    void anAttachmentReplacementHoldsItsRowWhileItWaitsForAnInFlightRemoval() throws Exception {
        Tag removed = tag("removed");
        Tag kept = tag("kept");
        Tag replacement = tag("replacement");
        Tag added = tag("added");
        attach("attachment_tag", "attachment_id", attachment.getId(), removed.getId());
        attach("attachment_tag", "attachment_id", attachment.getId(), kept.getId());
        CountDownLatch removalHeld = new CountDownLatch(1);
        CountDownLatch commitRemoval = new CountDownLatch(1);
        AtomicReference<Long> removalConnection = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> removal = executor.submit(() -> heldUntil(commitRemoval, removalHeld, removalConnection,
                "DELETE FROM attachment_tag WHERE attachment_id = ? AND tag_id = ?",
                attachment.getId(), removed.getId()));
            if (!removalHeld.await(10, TimeUnit.SECONDS)) {
                removal.get(0, TimeUnit.SECONDS);
                throw new AssertionError("The in-flight removal never held its row");
            }
            Future<List<Tag>> replaced = executor.submit(() -> asCurrentUser(
                () -> attachmentService.replaceTags(attachment.getId(), List.of(replacement.getId()))));
            awaitWaitOn("attachment_tag", removalConnection.get(), replaced, "The attachment tag replacement");
            long replacementConnection = waiterBlockedBy(removalConnection.get(), "attachment_tag");
            Future<Boolean> addition = executor.submit(() -> asCurrentUser(() -> {
                attachmentService.addTag(attachment.getId(), added.getId());
                return true;
            }));
            awaitWaitOn("attachment", replacementConnection, addition, "The concurrent attachment tag addition");
            commitRemoval.countDown();
            removal.get(20, TimeUnit.SECONDS);
            assertEquals(1, replaced.get(20, TimeUnit.SECONDS).size());
            assertTrue(addition.get(20, TimeUnit.SECONDS));
        } finally {
            commitRemoval.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(Set.of(replacement.getId(), added.getId()),
            committedTags("attachment_tag", "attachment_id", attachment.getId()));
        verify(auditService).singleChange("tags", List.of("kept"), List.of("replacement"));
    }

    /**
     * An attachment deleted while its replacement waited on the attachment row is refused under that
     * lock with a not-found, before any write (#1980).
     */
    @Test
    void anAttachmentDeletedWhileItsReplacementWaitedIsRefusedBeforeAnyWrite() throws Exception {
        Tag kept = tag("kept");
        Tag replacement = tag("replacement");
        attach("attachment_tag", "attachment_id", attachment.getId(), kept.getId());
        CountDownLatch deletionHeld = new CountDownLatch(1);
        CountDownLatch commitDeletion = new CountDownLatch(1);
        AtomicReference<Long> deletionConnection = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Throwable refused;
        try {
            Future<?> deletion = executor.submit(() -> heldUntil(commitDeletion, deletionHeld, deletionConnection,
                "DELETE FROM attachment WHERE workspace_id = ? AND id = ?", workspace.getId(), attachment.getId()));
            if (!deletionHeld.await(10, TimeUnit.SECONDS)) {
                deletion.get(0, TimeUnit.SECONDS);
                throw new AssertionError("The deletion never held the attachment row");
            }
            Future<List<Tag>> replaced = executor.submit(() -> asCurrentUser(
                () -> attachmentService.replaceTags(attachment.getId(), List.of(replacement.getId()))));
            awaitWaitOn("attachment", deletionConnection.get(), replaced, "The attachment tag replacement");
            commitDeletion.countDown();
            deletion.get(20, TimeUnit.SECONDS);
            refused = assertThrows(ExecutionException.class, () -> replaced.get(20, TimeUnit.SECONDS)).getCause();
        } finally {
            commitDeletion.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertInstanceOf(ResourceNotFoundException.class, refused);
        verify(auditService, never()).singleChange(eq("tags"), any(), any());
    }

    private void replaceWhileAnAdditionWaits(
            Supplier<List<Tag>> replace,
            Supplier<Boolean> add,
            String recordTable,
            AtomicReference<Long> replacementConnection,
            CountDownLatch lockHeld,
            CountDownLatch release) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<Tag>> replaced = executor.submit(() -> asCurrentUser(replace));
            if (!lockHeld.await(10, TimeUnit.SECONDS)) {
                replaced.get(0, TimeUnit.SECONDS);
                throw new AssertionError("The replacement never took its record lock");
            }
            Future<Boolean> addition = executor.submit(() -> asCurrentUser(add));
            awaitWaitOn(recordTable, replacementConnection.get(), addition, "The concurrent tag addition");
            release.countDown();
            assertEquals(1, replaced.get(20, TimeUnit.SECONDS).size());
            assertTrue(addition.get(20, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private void replaceWhileARemovalIsInFlight(
            Supplier<List<Tag>> replace,
            String associationTable,
            String recordColumn,
            int recordId,
            int removedTagId) throws Exception {
        CountDownLatch removalHeld = new CountDownLatch(1);
        CountDownLatch commitRemoval = new CountDownLatch(1);
        AtomicReference<Long> removalConnection = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> removal = executor.submit(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    removalConnection.set(currentConnectionId());
                    assertEquals(1, jdbcTemplate.update(
                        "DELETE FROM " + associationTable + " WHERE " + recordColumn + " = ? AND tag_id = ?",
                        recordId, removedTagId));
                    removalHeld.countDown();
                    try {
                        assertTrue(commitRemoval.await(30, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }));
            if (!removalHeld.await(10, TimeUnit.SECONDS)) {
                removal.get(0, TimeUnit.SECONDS);
                throw new AssertionError("The in-flight removal never held its row");
            }
            Future<List<Tag>> replaced = executor.submit(() -> asCurrentUser(replace));
            awaitWaitOn(associationTable, removalConnection.get(), replaced, "The tag replacement");
            commitRemoval.countDown();
            removal.get(20, TimeUnit.SECONDS);
            assertEquals(1, replaced.get(20, TimeUnit.SECONDS).size());
        } finally {
            commitRemoval.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private Throwable refusedAfterConcurrentChange(
            Supplier<List<Tag>> replace,
            Runnable concurrentChange,
            CountDownLatch reached,
            CountDownLatch changedMeanwhile) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<List<Tag>> replaced = executor.submit(() -> asCurrentUser(replace));
            if (!reached.await(10, TimeUnit.SECONDS)) {
                replaced.get(0, TimeUnit.SECONDS);
                throw new AssertionError("The replacement never reached its record lock");
            }
            try {
                concurrentChange.run();
            } finally {
                changedMeanwhile.countDown();
            }
            return assertThrows(ExecutionException.class, () -> replaced.get(20, TimeUnit.SECONDS)).getCause();
        } finally {
            changedMeanwhile.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /**
     * Waits until a lock request on {@code table} in this schema is queued behind the transaction on
     * {@code blockingConnection}, and fails at once with the waiter's outcome if it finishes first.
     */
    private void awaitWaitOn(String table, long blockingConnection, Future<?> waiter, String description)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LOCK_WAIT_PROBE_SECONDS);
        while (System.nanoTime() < deadline) {
            if (waiter.isDone()) {
                try {
                    waiter.get();
                    throw new AssertionError(description + " completed without waiting on " + table);
                } catch (ExecutionException failure) {
                    throw new AssertionError(description + " failed before waiting on " + table, failure.getCause());
                }
            }
            Integer waits = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM performance_schema.data_lock_waits waits
                    JOIN performance_schema.data_locks requested
                      ON requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                    JOIN performance_schema.threads blocking
                      ON blocking.THREAD_ID = waits.BLOCKING_THREAD_ID
                    WHERE requested.OBJECT_SCHEMA = DATABASE() AND requested.OBJECT_NAME = ?
                      AND blocking.PROCESSLIST_ID = ?
                    """, Integer.class, table, blockingConnection);
            if (waits != null && waits > 0) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError(description + " never waited on " + table);
    }

    /**
     * Runs {@code statement} in a transaction on this thread, publishes its connection and that it holds
     * the statement's locks, and commits only once {@code commit} is released.
     */
    private void heldUntil(
            CountDownLatch commit,
            CountDownLatch held,
            AtomicReference<Long> connection,
            String statement,
            Object... arguments) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            connection.set(currentConnectionId());
            assertEquals(1, jdbcTemplate.update(statement, arguments));
            held.countDown();
            try {
                assertTrue(commit.await(30, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        });
    }

    /** The connection of the one transaction waiting on {@code table} behind {@code blockingConnection}. */
    private long waiterBlockedBy(long blockingConnection, String table) {
        List<Long> waiters = jdbcTemplate.queryForList("""
                SELECT DISTINCT requesting.PROCESSLIST_ID
                FROM performance_schema.data_lock_waits waits
                JOIN performance_schema.data_locks requested
                  ON requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                JOIN performance_schema.threads requesting
                  ON requesting.THREAD_ID = waits.REQUESTING_THREAD_ID
                JOIN performance_schema.threads blocking
                  ON blocking.THREAD_ID = waits.BLOCKING_THREAD_ID
                WHERE requested.OBJECT_SCHEMA = DATABASE() AND requested.OBJECT_NAME = ?
                  AND blocking.PROCESSLIST_ID = ?
                """, Long.class, table, blockingConnection);
        if (waiters.size() != 1) {
            throw new AssertionError("Expected one waiter on " + table + ", found " + waiters);
        }
        return waiters.getFirst();
    }

    private void archive(String table, int id) {
        jdbcTemplate.update(
            "UPDATE " + table + " SET archived_at = UTC_TIMESTAMP() WHERE workspace_id = ? AND id = ?",
            workspace.getId(), id);
    }

    /** The MySQL connection id of the transaction bound to the calling thread. */
    private long currentConnectionId() {
        Long connectionId = jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class);
        if (connectionId == null) {
            throw new IllegalStateException("No MySQL connection id");
        }
        return connectionId;
    }

    private <T> T asCurrentUser(Supplier<T> change) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(currentUser, null, currentUser.getAuthorities()));
        tenantContext.set(workspace.getId(), organization.getId(), currentUser.getId(), "owner", null);
        try {
            return change.get();
        } finally {
            SecurityContextHolder.clearContext();
            tenantContext.clear();
        }
    }

    private Tag tag(String name) {
        Tag tag = new Tag();
        tag.setWorkspaceId(workspace.getId());
        tag.setName(name);
        tagMapper.insert(tag);
        return tag;
    }

    private void attach(String associationTable, String recordColumn, int recordId, int tagId) {
        jdbcTemplate.update(
            "INSERT INTO " + associationTable + " (" + recordColumn + ", tag_id) VALUES (?, ?)", recordId, tagId);
    }

    private Set<Integer> committedTags(String associationTable, String recordColumn, int recordId) {
        return new HashSet<>(jdbcTemplate.queryForList(
            "SELECT tag_id FROM " + associationTable + " WHERE " + recordColumn + " = ?", Integer.class, recordId));
    }

    /**
     * Tries to share-lock the row from an independent connection without waiting, and reports the
     * error. Only an exclusive lock refuses a shared one, so a refusal proves the row is held for
     * update rather than merely for share.
     */
    private Integer lockErrorFromAnotherConnection(String table, int id) throws SQLException {
        try (Connection other = dataSource.getConnection(); Statement statement = other.createStatement()) {
            other.setAutoCommit(false);
            try {
                statement.executeQuery("SELECT id FROM " + table + " WHERE id = " + id + " FOR SHARE NOWAIT")
                    .close();
                return null;
            } catch (SQLException refused) {
                return refused.getErrorCode();
            } finally {
                other.rollback();
            }
        }
    }
}
