package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.EntityReference;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.EntityReferenceMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;
import ooo.klae.connex.backend.support.TestEntityFixtures;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Exercises the scope-read/read-view rule in {@code docs/backend/LOCKING.md} with production
 * boundaries and mapper statements. Entry preparation must not establish the later tenant
 * transaction's InnoDB read view; the retained transaction-first fallback deliberately still does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "connex.tenancy.routing.mode=single-database")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RecordVisibilityScopeReadViewIntegrationTest {
    @Autowired private RecordVisibilityScopeControlAccess scopes;
    @Autowired private TenantWorkScope workScope;
    @Autowired private TenantContext tenantContext;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrganizationMapper organizations;
    @Autowired private WorkspaceMapper workspaces;
    @Autowired private UserMapper users;
    @Autowired private PipelineMapper pipelines;
    @MockitoSpyBean private DealMapper deals;
    @Autowired private DealService dealService;
    @Autowired private SqlSessionTemplate sqlSessions;
    @Autowired private EntityReferenceMapper references;
    @Autowired private CompanyMapper companies;
    @Autowired private ShareMapper shares;

    private Organization organization;
    private Workspace viewer;
    private Workspace owner;
    private User actor;
    private Pipeline pipeline;
    private Deal deal;
    private Company company;

    @BeforeEach
    void seedCommittedDealAndSharedCompany() {
        tenantContext.clear();
        String unique = UUID.randomUUID().toString().replace("-", "");
        organization = new Organization();
        organization.setName("Scope read view " + unique);
        organization.setSlug("scope-read-view-" + unique);
        organizations.insert(organization);
        viewer = workspace("viewer-" + unique);
        owner = workspace("owner-" + unique);
        actor = TestEntityFixtures.newUser(users, workspaces, viewer);
        workspaces.addMember(owner.getId(), actor.getId(), "owner");
        company = TestEntityFixtures.newCompany(companies, owner);
        String workspaceIds = "[" + viewer.getId() + "," + owner.getId() + "]";
        assertEquals(1, shares.shareCompany(company.getId(), owner.getId(), viewer.getId(),
            actor.getId(), false, workspaceIds));
        pipeline = TestEntityFixtures.newPipeline(pipelines, viewer);
        var stage = TestEntityFixtures.newStage(pipelines, viewer, pipeline, 0);
        deal = new Deal();
        deal.setWorkspaceId(viewer.getId());
        deal.setName("Scope read view deal");
        deal.setCurrency("JPY");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deals.insert(deal);
        assertNotNull(companies.getCompanyById(viewer.getId(),
                scopes.getForWorkspace(viewer.getId()).workspaceIdsJson(), company.getId()));
    }

    @AfterEach
    void deleteCommittedFixtures() {
        tenantContext.clear();
        SecurityContextHolder.clearContext();
        if (viewer != null) {
            jdbc.update("DELETE FROM entity_reference WHERE workspace_id = ?", viewer.getId());
            jdbc.update("DELETE FROM deal WHERE workspace_id = ?", viewer.getId());
            jdbc.update("DELETE FROM stage WHERE workspace_id = ?", viewer.getId());
            jdbc.update("DELETE FROM pipeline WHERE workspace_id = ?", viewer.getId());
        }
        if (owner != null) {
            jdbc.update("DELETE FROM company WHERE workspace_id = ?", owner.getId());
        }
        for (Workspace workspace : new Workspace[] {viewer, owner}) {
            if (workspace != null) {
                jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
                jdbc.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
            }
        }
        if (actor != null) {
            jdbc.update("DELETE FROM app_user WHERE id = ?", actor.getId());
        }
        if (organization != null) {
            jdbc.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @Test
    void preparedWorkspaceEntryObservesTheShareRevokedWhileWaitingForTheDealLock() throws Exception {
        assertNull(companyVisibleAfterContendedDealLock(true));
    }

    /** Characterizes the retained transaction-first lazy fallback documented in LOCKING.md. */
    @Test
    void transactionFirstLazyFallbackRetainsThePreLockShareReadView() throws Exception {
        Company visible = companyVisibleAfterContendedDealLock(false);
        assertNotNull(visible);
        assertEquals(company.getId(), visible.getId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preparedDealValueMutationRedactsACompanyRevokedWhileWaiting(boolean changeValue) throws Exception {
        BigDecimal original = new BigDecimal("100.00");
        BigDecimal requested = changeValue ? new BigDecimal("125.00") : original;
        String reason = "Discuss [" + company.getName() + "](company:" + company.getId() + ")";
        jdbc.update("UPDATE deal SET value = ?, closed_reason = ? WHERE workspace_id = ? AND id = ?",
            original, reason, viewer.getId(), deal.getId());
        var reference = new EntityReference();
        reference.setWorkspaceId(viewer.getId());
        reference.setSourceType(ReferenceService.SOURCE_DEAL);
        reference.setSourceId(deal.getId());
        reference.setRefType("company");
        reference.setRefId(company.getId());
        reference.setLabel(company.getName());
        references.insert(reference);
        assertEquals(1, references.findBySource(viewer.getId(), ReferenceService.SOURCE_DEAL, deal.getId()).size());
        try {
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
            Deal before = workScope.inWorkspace(viewer.getId(), () -> {
                tenantContext.set(viewer.getId(), organization.getId(), actor.getId(), "member", null);
                return dealService.getDealById(deal.getId());
            });
            assertEquals(reason, before.getClosedReason());
            assertEquals(company.getId(), before.getReferences().getFirst().getRefId());
        } finally {
            tenantContext.clear();
            SecurityContextHolder.clearContext();
        }

        var contenderConnection = new AtomicLong();
        var contenderReady = new CountDownLatch(1);
        var contender = new AtomicReference<Future<Deal>>();
        DealMapper realDeals = sqlSessions.getMapper(DealMapper.class);
        doAnswer(invocation -> {
            assertDefaultRepeatableRead();
            contenderConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            Integer previousTimeout = jdbc.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
            jdbc.execute("SET SESSION innodb_lock_wait_timeout = 15");
            try {
                contenderReady.countDown();
                return realDeals.getDealByIdForUpdate(viewer.getId(), deal.getId());
            } finally {
                jdbc.execute("SET SESSION innodb_lock_wait_timeout = " + previousTimeout);
            }
        }).when(deals).getDealByIdForUpdate(eq(viewer.getId()), eq(deal.getId()));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            defaultIsolationTransaction().executeWithoutResult(status -> {
                assertNotNull(deals.getDealByPrimaryKeyForUpdate(viewer.getId(), deal.getId()));
                contender.set(executor.submit(() -> {
                    try {
                        SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
                        return workScope.inWorkspace(viewer.getId(), () -> {
                            tenantContext.set(viewer.getId(), organization.getId(), actor.getId(), "member", null);
                            assertNotNull(tenantContext.getWorkspaceScopes());
                            return dealService.updateValue(deal.getId(), requested);
                        });
                    } finally {
                        tenantContext.clear();
                        SecurityContextHolder.clearContext();
                    }
                }));
                awaitContender(contenderReady);
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, contenderConnection.get(),
                    "deal", Integer.toString(deal.getId()));
                assertEquals(1, shares.unshareCompany(company.getId(), owner.getId(), viewer.getId()));
            });
            Deal returned = contender.get().get(20, TimeUnit.SECONDS);
            assertEquals(0, requested.compareTo(returned.getValue()));
            assertTrue(returned.getReferences().isEmpty());
            assertFalse(returned.getClosedReason().contains(company.getName()));
            assertFalse(returned.getClosedReason().contains("company:" + company.getId()));
            assertEquals(reason, deals.getDealById(viewer.getId(), deal.getId()).getClosedReason());
            assertEquals(1, references.findBySource(viewer.getId(), ReferenceService.SOURCE_DEAL, deal.getId()).size());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private Company companyVisibleAfterContendedDealLock(boolean preparedEntry) throws Exception {
        var contenderConnection = new AtomicLong();
        var contenderReady = new CountDownLatch(1);
        var contender = new AtomicReference<Future<Company>>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            defaultIsolationTransaction().executeWithoutResult(status -> {
                assertNotNull(deals.getDealByPrimaryKeyForUpdate(viewer.getId(), deal.getId()));
                contender.set(executor.submit(() -> {
                    try {
                        Supplier<Company> mutation = () -> defaultIsolationTransaction().execute(waiter -> {
                            assertDefaultRepeatableRead();
                            contenderConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                            contenderReady.countDown();
                            assertTrue(scopes.getForWorkspace(viewer.getId()).workspaceIds().contains(owner.getId()));
                            assertNotNull(deals.getDealByPrimaryKeyForUpdate(viewer.getId(), deal.getId()));
                            return companies.getCompanyById(viewer.getId(),
                scopes.getForWorkspace(viewer.getId()).workspaceIdsJson(), company.getId());
                        });
                        return preparedEntry ? workScope.inWorkspace(viewer.getId(), mutation) : mutation.get();
                    } finally {
                        tenantContext.clear();
                    }
                }));
                awaitContender(contenderReady);
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, contenderConnection.get(),
                    "deal", Integer.toString(deal.getId()));
                assertEquals(1, shares.unshareCompany(company.getId(), owner.getId(), viewer.getId()));
            });
            Company visible = contender.get().get(20, TimeUnit.SECONDS);
            assertNull(companies.getCompanyById(viewer.getId(),
                scopes.getForWorkspace(viewer.getId()).workspaceIdsJson(), company.getId()));
            return visible;
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private TransactionTemplate defaultIsolationTransaction() {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setTimeout(20);
        return transaction;
    }

    private void assertDefaultRepeatableRead() {
        assertNull(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
        try {
            assertEquals(Connection.TRANSACTION_REPEATABLE_READ,
                DataSourceUtils.getConnection(dataSource).getTransactionIsolation());
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void awaitContender(CountDownLatch ready) {
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS), "The contender did not enter its transaction");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the contender", exception);
        }
    }

    private Workspace workspace(String slug) {
        var workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName(slug);
        workspace.setSlug(slug);
        workspaces.insert(workspace);
        return workspace;
    }
}
