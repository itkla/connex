package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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
import ooo.klae.connex.backend.dto.UserDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
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
 * Verifies a deal-collaborator replacement cannot write back a member removed while it runs (#1793).
 * The tenant-only insert no longer joins {@code workspace_member}, so the replacement relies on locking
 * each requested membership {@code FOR UPDATE} before it writes: a removal holding the member's locks
 * makes the replacement wait, and once the removal commits the replacement is refused.
 *
 * <p>The bean overrides match {@code OwnerChangeConcurrencyIntegrationTest}'s exactly, field names
 * included, so both classes share one cached application context; the person and company mapper
 * spies serve that class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DealCollaboratorConcurrencyIntegrationTest {

    @Autowired private DealService dealService;
    @Autowired private WorkspaceService workspaceService;
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

    private Organization organization;
    private Workspace workspace;
    private User currentUser;
    private User targetMember;
    private Deal deal;
    private final List<User> additionalUsers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Deal collaborators " + unique);
        organization.setSlug("deal-collaborators-" + unique);
        organizationMapper.insert(organization);

        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Deal collaborators " + unique);
        workspace.setSlug("deal-collaborators-" + unique);
        workspaceMapper.insert(workspace);

        currentUser = user("deal-collaborators-owner-" + unique);
        targetMember = user("deal-collaborators-target-" + unique);
        workspaceMapper.addMember(workspace.getId(), currentUser.getId(), "owner");
        workspaceMapper.addMember(workspace.getId(), targetMember.getId(), "member");

        Pipeline pipeline = new Pipeline();
        pipeline.setWorkspaceId(workspace.getId());
        pipeline.setName("Deal collaborators pipeline " + unique);
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
        deal.setName("Deal collaborators " + unique);
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
            jdbcTemplate.update("DELETE FROM deal_collaborator WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM deal WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM stage WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM pipeline WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        for (User additional : additionalUsers) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", additional.getId());
        }
        if (targetMember != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", targetMember.getId());
        }
        if (currentUser != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", currentUser.getId());
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @Test
    void aMemberRemovedWhileTheReplacementWaitsIsRefusedAndNeverWrittenBack() throws Exception {
        int workspaceId = workspace.getId();
        int targetUserId = targetMember.getId();
        CountDownLatch removalLocked = new CountDownLatch(1);
        CountDownLatch releaseRemoval = new CountDownLatch(1);
        CountDownLatch replacementStarted = new CountDownLatch(1);
        NotificationMapper realNotificationMapper = sqlSessionTemplate.getMapper(NotificationMapper.class);
        WorkspaceMapper realWorkspaceMapper = sqlSessionTemplate.getMapper(WorkspaceMapper.class);
        doAnswer(invocation -> {
            List<Integer> locked = realNotificationMapper.lockRecipientMemberships(targetUserId);
            removalLocked.countDown();
            assertTrue(releaseRemoval.await(30, TimeUnit.SECONDS));
            return locked;
        }).when(notificationMapper).lockRecipientMemberships(targetUserId);
        doAnswer(invocation -> {
            replacementStarted.countDown();
            return realWorkspaceMapper.lockActiveMembership(workspaceId, targetUserId);
        }).when(workspaceMapper).lockActiveMembership(eq(workspaceId), eq(targetUserId));
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> removal = executor.submit(() -> removeTargetMember(workspaceId));
            assertTrue(removalLocked.await(10, TimeUnit.SECONDS));
            Future<List<UserDto>> replacement = executor.submit(() -> addTargetAsCollaborator(workspaceId));
            assertTrue(replacementStarted.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> replacement.get(1, TimeUnit.SECONDS));
            releaseRemoval.countDown();

            removal.get(20, TimeUnit.SECONDS);
            ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> replacement.get(20, TimeUnit.SECONDS));
            assertTrue(hasCause(failure, ForbiddenException.class));
        } finally {
            releaseRemoval.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM deal_collaborator WHERE workspace_id = ? AND user_id = ?",
            Integer.class, workspaceId, targetUserId));
        assertNull(workspaceMapper.getMember(workspaceId, targetUserId));
    }

    /**
     * The removal pauses after it has deleted the member's collaborator rows, so a row the replacement
     * wrote back would survive: the zero-row check here can only pass because the replacement waits on
     * the membership lock and is then refused.
     */
    @Test
    void aMemberRemovedAfterTheirCollaboratorRowsAreDeletedIsNeverWrittenBack() throws Exception {
        int workspaceId = workspace.getId();
        int targetUserId = targetMember.getId();
        jdbcTemplate.update("INSERT INTO deal_collaborator (workspace_id, deal_id, user_id) VALUES (?, ?, ?)",
            workspaceId, deal.getId(), targetUserId);
        CountDownLatch removalCleanedUp = new CountDownLatch(1);
        CountDownLatch releaseRemoval = new CountDownLatch(1);
        DealMapper realDealMapper = sqlSessionTemplate.getMapper(DealMapper.class);
        doAnswer(invocation -> {
            realDealMapper.removeCollaboratorFromWorkspace(workspaceId, targetUserId);
            removalCleanedUp.countDown();
            assertTrue(releaseRemoval.await(30, TimeUnit.SECONDS));
            return null;
        }).when(dealMapper).removeCollaboratorFromWorkspace(workspaceId, targetUserId);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> removal = executor.submit(() -> removeTargetMember(workspaceId));
            assertTrue(removalCleanedUp.await(10, TimeUnit.SECONDS));
            Future<List<UserDto>> replacement = executor.submit(() -> addTargetAsCollaborator(workspaceId));
            assertThrows(TimeoutException.class, () -> replacement.get(1, TimeUnit.SECONDS));
            releaseRemoval.countDown();

            removal.get(20, TimeUnit.SECONDS);
            ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> replacement.get(20, TimeUnit.SECONDS));
            assertTrue(hasCause(failure, ForbiddenException.class));
        } finally {
            releaseRemoval.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM deal_collaborator WHERE workspace_id = ? AND user_id = ?",
            Integer.class, workspaceId, targetUserId));
    }

    /**
     * The membership locks must still be held when the tenant-only insert runs, through the real service
     * path: at that moment an independent connection cannot take the member's row. A lock released before
     * the insert, for example by a separate transaction around the membership check, would let it through.
     */
    @Test
    void membershipLocksAreStillHeldWhenTheCollaboratorInsertRuns() {
        int workspaceId = workspace.getId();
        AtomicReference<Integer> probeError = new AtomicReference<>();
        DealMapper realDealMapper = sqlSessionTemplate.getMapper(DealMapper.class);
        doAnswer(invocation -> {
            probeError.set(lockErrorFromAnotherConnection(workspaceId, targetMember.getId()));
            return realDealMapper.insertCollaborators(
                workspaceId, deal.getId(), invocation.getArgument(2));
        }).when(dealMapper).insertCollaborators(eq(workspaceId), eq(deal.getId()), anyList());

        addTargetAsCollaborator(workspaceId);

        assertEquals(Integer.valueOf(3572), probeError.get());
    }

    /**
     * An owner change commits after a replacement has opened its read view but before it holds any lock,
     * deleting the new owner's collaborator row. The replacement's audited and returned lists must be
     * current reads: from its snapshot they would still list the new owner as a collaborator (#1942).
     */
    @Test
    void aReplacementRacingAnOwnerChangeReturnsAndAuditsTheCommittedCollaborators() throws Exception {
        int workspaceId = workspace.getId();
        int newOwnerId = targetMember.getId();
        User kept = member("deal-collaborators-kept-");
        insertCollaborators(workspaceId, List.of(newOwnerId, kept.getId()));
        when(referenceService.hydrateDeals(anyInt(), anyList())).thenAnswer(invocation -> invocation.getArgument(1));

        List<UserDto> returned = replaceWhileAnotherWriteCommits(workspaceId, List.of(kept.getId()), () -> {
            changeOwner(workspaceId, newOwnerId);
            assertEquals(Integer.valueOf(newOwnerId), jdbcTemplate.queryForObject(
                "SELECT owner_id FROM deal WHERE workspace_id = ? AND id = ?",
                Integer.class, workspaceId, deal.getId()));
            assertEquals(List.of(kept.getId()), committedCollaborators(workspaceId));
        });

        assertEquals(List.of(kept.getId()), returned.stream().map(UserDto::getId).toList());
        assertEquals(List.of(kept.getId()), committedCollaborators(workspaceId));
        verify(auditService).singleChange("collaboratorIds", List.of(kept.getId()), List.of(kept.getId()));
    }

    /**
     * Another replacement commits while this one has opened its read view but holds no lock. The audit's
     * {@code before} must be the rows this replacement actually replaced, the other's committed set, and
     * its {@code after} and response exactly what it commits: from its snapshot both lists would still
     * hold the rows the other replacement removed (#1942).
     */
    @Test
    void aReplacementRacingAnotherReplacementAuditsTheRowsItActuallyReplaced() throws Exception {
        int workspaceId = workspace.getId();
        User kept = member("deal-collaborators-kept-");
        User committed = member("deal-collaborators-committed-");
        User requested = member("deal-collaborators-requested-");
        insertCollaborators(workspaceId, List.of(targetMember.getId(), kept.getId()));

        List<UserDto> returned = replaceWhileAnotherWriteCommits(workspaceId, List.of(requested.getId()), () -> {
            addCollaborators(workspaceId, List.of(committed.getId()));
            assertEquals(List.of(committed.getId()), committedCollaborators(workspaceId));
        });

        assertEquals(List.of(requested.getId()), returned.stream().map(UserDto::getId).toList());
        assertEquals(List.of(requested.getId()), committedCollaborators(workspaceId));
        verify(auditService).singleChange(
            "collaboratorIds", List.of(committed.getId()), List.of(requested.getId()));
    }

    /**
     * Pins the lock order the replacement shares with {@code updateOwner} and offboarding: each requested
     * membership in ascending user id, then the deal row, then its collaborator rows.
     */
    @Test
    void replacementLocksMembershipsInAscendingOrderBeforeTheDealAndItsCollaborators() {
        int workspaceId = workspace.getId();
        User second = user("deal-collaborators-second-" + UUID.randomUUID().toString().substring(0, 8));
        additionalUsers.add(second);
        workspaceMapper.addMember(workspaceId, second.getId(), "member");
        int lower = Math.min(targetMember.getId(), second.getId());
        int higher = Math.max(targetMember.getId(), second.getId());

        List<UserDto> collaborators = addCollaborators(workspaceId, List.of(higher, lower));

        assertEquals(2, collaborators.size());
        InOrder order = inOrder(workspaceMapper, dealMapper);
        order.verify(workspaceMapper).lockActiveMembership(workspaceId, lower);
        order.verify(workspaceMapper).lockActiveMembership(workspaceId, higher);
        order.verify(dealMapper).getDealByIdForUpdate(workspaceId, deal.getId());
        order.verify(dealMapper).getCollaboratorIdsForUpdate(workspaceId, deal.getId());
        order.verify(dealMapper).clearCollaborators(workspaceId, deal.getId());
        order.verify(dealMapper).insertCollaborators(eq(workspaceId), eq(deal.getId()), anyList());
        order.verify(dealMapper).getCollaboratorIdsForUpdate(workspaceId, deal.getId());
    }

    private Integer lockErrorFromAnotherConnection(int workspaceId, int userId) throws SQLException {
        try (Connection other = dataSource.getConnection(); Statement statement = other.createStatement()) {
            other.setAutoCommit(false);
            try {
                statement.executeQuery("SELECT user_id FROM workspace_member WHERE workspace_id = " + workspaceId
                    + " AND user_id = " + userId + " FOR UPDATE NOWAIT").close();
                return null;
            } catch (SQLException refused) {
                return refused.getErrorCode();
            } finally {
                other.rollback();
            }
        }
    }

    private void removeTargetMember(int workspaceId) {
        authenticate(workspaceId);
        try {
            workspaceService.removeMember(workspaceId, currentUser.getId(), targetMember.getId());
        } finally {
            clearAuthentication();
        }
    }

    private List<UserDto> addTargetAsCollaborator(int workspaceId) {
        return addCollaborators(workspaceId, List.of(targetMember.getId()));
    }

    /**
     * Runs a replacement on another thread and pauses it inside its first membership lock: its unlocked
     * existence check has opened its read view, but it holds no lock. {@code concurrentWrite} runs and
     * commits on this thread in that window, and the replacement then resumes.
     */
    private List<UserDto> replaceWhileAnotherWriteCommits(
            int workspaceId, List<Integer> requestedIds, Runnable concurrentWrite) throws Exception {
        int firstLockedId = requestedIds.stream().min(Integer::compare).orElseThrow();
        CountDownLatch readViewOpen = new CountDownLatch(1);
        CountDownLatch writeCommitted = new CountDownLatch(1);
        WorkspaceMapper realWorkspaceMapper = sqlSessionTemplate.getMapper(WorkspaceMapper.class);
        doAnswer(invocation -> {
            verify(dealMapper).getDealById(workspaceId, deal.getId());
            readViewOpen.countDown();
            assertTrue(writeCommitted.await(30, TimeUnit.SECONDS));
            return realWorkspaceMapper.lockActiveMembership(workspaceId, firstLockedId);
        }).when(workspaceMapper).lockActiveMembership(workspaceId, firstLockedId);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<List<UserDto>> replacement = executor.submit(() -> addCollaborators(workspaceId, requestedIds));
            awaitOrSurface(readViewOpen, replacement);
            try {
                concurrentWrite.run();
            } finally {
                writeCommitted.countDown();
            }
            return replacement.get(20, TimeUnit.SECONDS);
        } finally {
            writeCommitted.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static void awaitOrSurface(CountDownLatch latch, Future<?> task) throws Exception {
        if (!latch.await(10, TimeUnit.SECONDS)) {
            task.get(0, TimeUnit.SECONDS);
            fail("The paused replacement never reached its first membership lock");
        }
    }

    private User member(String usernamePrefix) {
        User member = user(usernamePrefix + UUID.randomUUID().toString().substring(0, 8));
        additionalUsers.add(member);
        workspaceMapper.addMember(workspace.getId(), member.getId(), "member");
        return member;
    }

    private void insertCollaborators(int workspaceId, List<Integer> userIds) {
        for (int userId : userIds) {
            jdbcTemplate.update("INSERT INTO deal_collaborator (workspace_id, deal_id, user_id) VALUES (?, ?, ?)",
                workspaceId, deal.getId(), userId);
        }
    }

    private void changeOwner(int workspaceId, int ownerId) {
        authenticate(workspaceId);
        try {
            dealService.updateOwner(deal.getId(), ownerId);
        } finally {
            clearAuthentication();
        }
    }

    private List<Integer> committedCollaborators(int workspaceId) {
        return jdbcTemplate.queryForList(
            "SELECT user_id FROM deal_collaborator WHERE workspace_id = ? AND deal_id = ? ORDER BY user_id",
            Integer.class, workspaceId, deal.getId());
    }

    private List<UserDto> addCollaborators(int workspaceId, List<Integer> userIds) {
        authenticate(workspaceId);
        try {
            return dealService.replaceCollaborators(deal.getId(), userIds);
        } finally {
            clearAuthentication();
        }
    }

    private void authenticate(int workspaceId) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(currentUser, null, currentUser.getAuthorities()));
        tenantContext.set(workspaceId, organization.getId(), currentUser.getId(), "owner", null);
    }

    private void clearAuthentication() {
        SecurityContextHolder.clearContext();
        tenantContext.clear();
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

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
