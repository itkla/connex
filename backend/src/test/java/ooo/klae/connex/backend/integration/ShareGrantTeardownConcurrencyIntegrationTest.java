package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.ShareDto;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.ShareService;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * Drives the teardown race a pipeline grant is exposed to since the grant statements stopped
 * joining the control-plane {@code workspace} table (#811). That join was also an existence check
 * on the target at insert time and V65 had already removed the foreign key, so the organization
 * ceiling is now decided from a snapshot taken before any lock. Unless the target workspace row is
 * held from before that decision until the tenant insert lands, a teardown committing in between
 * leaves a {@code pipeline_share} row addressed to a workspace that is gone.
 *
 * <p>Company and person grants already took the target root through
 * {@code DuplicateDecisionLockService}; this pins that pipeline grants take the same one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ShareGrantTeardownConcurrencyIntegrationTest {

    @Autowired private ShareService shareService;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private TenantContext tenantContext;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Organization organization;
    private Workspace ownerWorkspace;
    private Workspace targetWorkspace;
    private User actor;
    private Pipeline pipeline;

    @BeforeEach
    void createCommittedFixtures() {
        String suffix = unique();
        organization = new Organization();
        organization.setName("Share teardown " + suffix);
        organization.setSlug("share-teardown-" + suffix);
        organizationMapper.insert(organization);
        ownerWorkspace = insertWorkspace("Share teardown owner " + suffix, "owner-" + suffix);
        targetWorkspace = insertWorkspace("Share teardown target " + suffix, "target-" + suffix);
        actor = new User();
        actor.setUsername("share_teardown_" + suffix);
        actor.setDisplayName("Share teardown " + suffix);
        actor.setEmail(suffix + "@example.com");
        actor.setPasswordHash("fixture");
        actor.setTimezone("UTC");
        userMapper.insert(actor);
        workspaceMapper.addMember(ownerWorkspace.getId(), actor.getId(), "owner");
        workspaceMapper.addMember(targetWorkspace.getId(), actor.getId(), "owner");
        authenticate();
        pipeline = new Pipeline();
        pipeline.setName("Share teardown pipeline " + suffix);
        pipeline.setWorkspaceId(ownerWorkspace.getId());
        pipelineMapper.insertPipeline(pipeline);
        assertTrue(ownerWorkspace.getId() < targetWorkspace.getId(),
            "the lock order is ascending by workspace id, so the target must be the later root "
                + "for this drill to contend on it rather than on the owning workspace");
    }

    @AfterEach
    void removeCommittedFixtures() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        tenantContext.clear();
        if (pipeline != null) {
            jdbcTemplate.update("DELETE FROM pipeline_share WHERE pipeline_id = ?", pipeline.getId());
            jdbcTemplate.update("DELETE FROM pipeline WHERE id = ?", pipeline.getId());
        }
        for (Workspace workspace : workspaces()) {
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        if (actor != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", actor.getId());
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    /**
     * The fixture must be able to grant, or the refusal the race test asserts could come from a
     * missing permission or an unshareable record instead of the teardown.
     */
    @Test
    void anUndisturbedPipelineGrantStillSucceeds() {
        runAsActor(() -> shareService.share(
            "pipeline", pipeline.getId(), targetWorkspace.getId(), false));

        List<ShareDto> shares = withActor(
            () -> shareService.listShares("pipeline", pipeline.getId()));
        assertEquals(List.of(targetWorkspace.getId()),
            shares.stream().map(ShareDto::getWorkspaceId).toList());
        assertEquals(1, pipelineShareCount());
    }

    @Test
    void aPipelineGrantRefusesATargetWorkspaceTornDownAfterTheOrganizationSnapshot()
            throws Exception {
        CountDownLatch teardownApplied = new CountDownLatch(1);
        CountDownLatch releaseTeardown = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> teardown = executor.submit(() -> {
                transaction().executeWithoutResult(status -> {
                    assertEquals(1, jdbcTemplate.update(
                        "UPDATE workspace SET lifecycle_state = 'tearing_down' WHERE id = ?",
                        targetWorkspace.getId()));
                    teardownApplied.countDown();
                    await(releaseTeardown);
                });
            });
            assertTrue(teardownApplied.await(30, TimeUnit.SECONDS),
                "the teardown transaction never took the target workspace row");

            Future<?> grant = executor.submit(() -> runAsActor(() -> shareService.share(
                "pipeline", pipeline.getId(), targetWorkspace.getId(), false)));

            assertThrows(TimeoutException.class, () -> grant.get(1, TimeUnit.SECONDS),
                "the grant must wait on the target workspace row the teardown holds: its "
                    + "membership and organization reads are unlocked, so under READ COMMITTED "
                    + "they still see the pre-teardown row and the tenant insert would land");
            releaseTeardown.countDown();
            teardown.get(30, TimeUnit.SECONDS);

            ExecutionException failure = assertThrows(
                ExecutionException.class, () -> grant.get(30, TimeUnit.SECONDS));
            assertInstanceOf(ResourceNotFoundException.class, failure.getCause(),
                "a target that is no longer active must be refused, not granted");
        } finally {
            releaseTeardown.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }

        assertEquals(0, pipelineShareCount(),
            "no share row may address a workspace whose teardown committed before the grant "
                + "revalidated it");
    }

    private Workspace insertWorkspace(String name, String slug) {
        Workspace workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName(name);
        workspace.setSlug(slug);
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private List<Workspace> workspaces() {
        List<Workspace> created = new ArrayList<>();
        if (ownerWorkspace != null) {
            created.add(ownerWorkspace);
        }
        if (targetWorkspace != null) {
            created.add(targetWorkspace);
        }
        return created;
    }

    private int pipelineShareCount() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pipeline_share WHERE pipeline_id = ?",
            Integer.class, pipeline.getId());
        return count == null ? 0 : count;
    }

    private <T> T withActor(Supplier<T> action) {
        authenticate();
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
            RequestContextHolder.resetRequestAttributes();
            tenantContext.clear();
        }
    }

    private void runAsActor(Runnable action) {
        withActor(() -> {
            action.run();
            return Boolean.TRUE;
        });
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        long now = System.currentTimeMillis();
        request.getSession().setAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR, now);
        request.getSession().setAttribute(SessionSecurityService.AUTHENTICATED_USER_ATTR, actor.getId());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        tenantContext.set(ownerWorkspace.getId(), organization.getId(), actor.getId(), "owner", null);
    }

    private TransactionTemplate transaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent operation did not resume");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent operation was interrupted", exception);
        }
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
