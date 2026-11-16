package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.StaleRecordException;
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
 * Races two single-field deal updates (#1958). The first is paused just before it locks the deal,
 * after its unlocked existence check has opened its read view; the second then commits. The first
 * must audit the transition it really makes, from the value the second committed, not from its
 * snapshot of the value before either ran.
 *
 * <p>The bean overrides match {@code OwnerChangeConcurrencyIntegrationTest}'s exactly, field names
 * included, so the concurrency classes can share one cached application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DealUpdateConcurrencyIntegrationTest {

    @Autowired private DealService dealService;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private TenantContext tenantContext;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
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

    private final CountDownLatch readViewOpen = new CountDownLatch(1);
    private final CountDownLatch concurrentChangeCommitted = new CountDownLatch(1);
    private final AtomicBoolean paused = new AtomicBoolean();

    private Organization organization;
    private Workspace workspace;
    private User currentUser;
    private Deal deal;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Deal updates " + unique);
        organization.setSlug("deal-updates-" + unique);
        organizationMapper.insert(organization);

        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Deal updates " + unique);
        workspace.setSlug("deal-updates-" + unique);
        workspaceMapper.insert(workspace);

        currentUser = user("deal-updates-actor-" + unique);
        workspaceMapper.addMember(workspace.getId(), currentUser.getId(), "owner");

        Pipeline pipeline = new Pipeline();
        pipeline.setWorkspaceId(workspace.getId());
        pipeline.setName("Deal updates pipeline " + unique);
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
        deal.setName("Deal updates " + unique);
        deal.setValue(new BigDecimal("1000.00"));
        deal.setCurrency("JPY");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setPosition(0);
        dealMapper.insert(deal);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        tenantContext.clear();
        if (workspace != null) {
            jdbcTemplate.update("DELETE FROM deal WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM stage WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM pipeline WHERE workspace_id = ?", workspace.getId());
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
    void aRescheduleRacingAnotherAuditsFromTheCommittedDate() throws Exception {
        AtomicReference<Deal> returned = new AtomicReference<>();
        pauseBeforeTheDealLock();

        runWhilePaused(
            () -> returned.set(dealService.reschedule(deal.getId(), "2026-12-03")),
            () -> asCurrentUser(() -> dealService.reschedule(deal.getId(), "2026-11-02")));

        verify(auditService).singleChange("expectedCloseDate", null, "2026-11-02");
        verify(auditService).singleChange("expectedCloseDate", "2026-11-02", "2026-12-03");
        assertEquals("2026-12-03", returned.get().getExpectedCloseDate());
        verify(dealMapper, never()).getDealByIdForUpdate(anyInt(), anyInt());
    }

    /**
     * A deal deleted while its reschedule waited is refused under the lock. Before #1958 the
     * reschedule updated no row yet still audited and published, and answered 200.
     */
    @Test
    void aDealDeletedWhileItsRescheduleWaitedIsRefusedBeforeAnyWrite() throws Exception {
        int workspaceId = workspace.getId();
        pauseBeforeTheDealLock();

        ExecutionException refused = assertThrows(ExecutionException.class, () -> runWhilePaused(
            () -> dealService.reschedule(deal.getId(), "2026-12-03"),
            () -> jdbcTemplate.update(
                "DELETE FROM deal WHERE workspace_id = ? AND id = ?", workspaceId, deal.getId())));

        assertTrue(paused.get());
        assertInstanceOf(ResourceNotFoundException.class, refused.getCause());
        verify(dealMapper, never()).updateExpectedCloseDate(anyInt(), anyInt(), anyString());
        verify(auditService, never()).singleChange(eq("expectedCloseDate"), any(), any());
        verify(ruleTriggers, never()).publish(anyInt(), anyString(), anyInt(), anyString());
    }

    @Test
    void aRiskExclusionRacingTheSameChangeAuditsFromTheCommittedValue() throws Exception {
        AtomicReference<Deal> returned = new AtomicReference<>();
        pauseBeforeTheDealLock();

        runWhilePaused(
            () -> returned.set(dealService.updateRiskExcluded(deal.getId(), true)),
            () -> asCurrentUser(() -> dealService.updateRiskExcluded(deal.getId(), true)));

        verify(auditService).singleChange("riskExcluded", false, true);
        verify(auditService).singleChange("riskExcluded", true, true);
        assertTrue(returned.get().isRiskExcluded());
        verify(dealMapper, never()).getDealByIdForUpdate(anyInt(), anyInt());
    }

    @Test
    void staleFullEditRefusesBeforeValueOutcomeAuditOrEvents() {
        Deal stale = dealMapper.getDealById(workspace.getId(), deal.getId());
        stale.setExpectedVersion(stale.getVersion());
        stale.setName("Stale draft name");
        stale.setCompanyId(-1);
        dealMapper.updateValueAndSource(workspace.getId(), deal.getId(), new BigDecimal("99999"), "manual");
        long currentVersion = dealMapper.getDealById(workspace.getId(), deal.getId()).getVersion();
        asCurrentUser(() -> {
            StaleRecordException error = assertThrows(StaleRecordException.class,
                () -> dealService.update(deal.getId(), stale));
            assertEquals(currentVersion, error.getCurrentVersion());
        });
        Deal stored = dealMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals(deal.getName(), stored.getName());
        assertEquals(0, new BigDecimal("99999").compareTo(stored.getValue()));
        assertEquals(currentVersion, stored.getVersion());
        verify(ruleTriggers, never()).publish(anyInt(), anyString(), anyInt(), anyString());
        verify(notificationChanges, never()).publish(anyInt(), anyString(), anyInt());
    }

    @Test
    void staleCurrencyWithNewLineItemsReturnsStaleRecordBeforeDomainValidation() {
        Deal stale = dealMapper.getDealById(workspace.getId(), deal.getId());
        stale.setExpectedVersion(stale.getVersion());
        jdbcTemplate.update("UPDATE deal SET currency = 'USD' WHERE workspace_id = ? AND id = ?",
            workspace.getId(), deal.getId());
        jdbcTemplate.update("INSERT INTO deal_line_item (workspace_id, deal_id, name, currency) VALUES (?, ?, ?, ?)",
            workspace.getId(), deal.getId(), "New line", "USD");
        asCurrentUser(() -> assertThrows(StaleRecordException.class,
            () -> dealService.update(deal.getId(), stale)));
        assertEquals("USD", dealMapper.getDealById(workspace.getId(), deal.getId()).getCurrency());
        verify(ruleTriggers, never()).publish(anyInt(), anyString(), anyInt(), anyString());
    }

    @Test
    void successfulFullEditReturnsTheFinalVersionAfterDependentValueWrites() {
        when(referenceService.hydrateDeals(anyInt(), anyList())).thenAnswer(invocation -> invocation.getArgument(1));
        Deal edit = dealMapper.getDealById(workspace.getId(), deal.getId());
        edit.setExpectedVersion(edit.getVersion());
        edit.setName("Current edit");
        edit.setValue(new BigDecimal("2000"));
        AtomicReference<Deal> returned = new AtomicReference<>();
        asCurrentUser(() -> returned.set(dealService.update(deal.getId(), edit)));
        Deal stored = dealMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals(stored.getVersion(), returned.get().getVersion());
        assertTrue(stored.getVersion() > edit.getExpectedVersion());
        assertEquals(0, new BigDecimal("2000").compareTo(stored.getValue()));
        assertEquals("Current edit", stored.getName());
    }

    @Test
    void closeAndReopenResponsesCanSeedTheNextConditionalEdit() {
        when(referenceService.hydrateDeals(anyInt(), anyList())).thenAnswer(invocation -> invocation.getArgument(1));
        asCurrentUser(() -> {
            Deal closed = dealService.close(deal.getId(), true, null, new BigDecimal("333"));
            assertEquals(dealMapper.getDealById(workspace.getId(), deal.getId()).getVersion(), closed.getVersion());
            closed.setExpectedVersion(closed.getVersion());
            closed.setName("Edited after close");
            dealService.update(deal.getId(), closed);
            Deal reopened = dealService.reopen(deal.getId());
            assertEquals(dealMapper.getDealById(workspace.getId(), deal.getId()).getVersion(), reopened.getVersion());
            reopened.setExpectedVersion(reopened.getVersion());
            reopened.setName("Edited after reopen");
            Deal saved = dealService.update(deal.getId(), reopened);
            assertEquals("Edited after reopen", saved.getName());
        });
    }

    @Test
    void omittedExpectedVersionRetainsLegacyFullEditBehavior() {
        when(referenceService.hydrateDeals(anyInt(), anyList())).thenAnswer(invocation -> invocation.getArgument(1));
        Deal legacy = dealMapper.getDealById(workspace.getId(), deal.getId());
        dealMapper.updateValueAndSource(workspace.getId(), deal.getId(), new BigDecimal("99999"), "manual");
        asCurrentUser(() -> dealService.update(deal.getId(), legacy));
        Deal stored = dealMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals(0, legacy.getValue().compareTo(stored.getValue()));
    }

    @Test
    void twoWritersWithTheSameSnapshotHaveExactlyOneAtomicWinner() throws Exception {
        Deal first = dealMapper.getDealById(workspace.getId(), deal.getId());
        Deal second = dealMapper.getDealById(workspace.getId(), deal.getId());
        first.setExpectedVersion(first.getVersion());
        second.setExpectedVersion(second.getVersion());
        first.setName("First edit");
        second.setName("Second edit");
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
            Deal stored = dealMapper.getDealById(workspace.getId(), deal.getId());
            assertEquals(firstCount == 1 ? first.getName() : second.getName(), stored.getName());
            assertEquals(first.getVersion() + 1, stored.getVersion());
        } finally {
            start.countDown();
        }
    }

    private int conditionalWrite(Deal edit, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        java.util.concurrent.atomic.AtomicInteger changed = new java.util.concurrent.atomic.AtomicInteger();
        asCurrentUser(() -> changed.set(dealMapper.update(edit)));
        return changed.get();
    }

    /**
     * Pauses the first update just before it locks the deal, after asserting its unlocked check
     * already ran; every later lock, the concurrent update's and the first one's own re-read, passes.
     */
    private void pauseBeforeTheDealLock() {
        int workspaceId = workspace.getId();
        when(referenceService.hydrateDeals(anyInt(), anyList())).thenAnswer(invocation -> invocation.getArgument(1));
        DealMapper realDealMapper = sqlSessionTemplate.getMapper(DealMapper.class);
        doAnswer(invocation -> {
            if (paused.compareAndSet(false, true)) {
                verify(dealMapper).getDealById(workspaceId, deal.getId());
                readViewOpen.countDown();
                assertTrue(concurrentChangeCommitted.await(30, TimeUnit.SECONDS));
            }
            return realDealMapper.getDealByPrimaryKeyForUpdate(workspaceId, deal.getId());
        }).when(dealMapper).getDealByPrimaryKeyForUpdate(workspaceId, deal.getId());
    }

    /** Runs the change on another thread and commits {@code concurrentCommit} while it is paused. */
    private void runWhilePaused(Runnable change, Runnable concurrentCommit) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> firstChange = executor.submit(() -> asCurrentUser(change));
            if (!readViewOpen.await(10, TimeUnit.SECONDS)) {
                firstChange.get(0, TimeUnit.SECONDS);
                throw new AssertionError("The first update never reached its pause");
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
