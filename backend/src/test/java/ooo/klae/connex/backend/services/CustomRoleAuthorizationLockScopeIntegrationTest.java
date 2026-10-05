package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.tenant.Permission;

/**
 * Checks against a real database that one member's locked authorization does not wait on another
 * custom role's rows, under both isolation levels and in either lock order (#1578). The permission
 * read selects the role's rows by equality; a range on {@code permission} let MySQL run it as a
 * full primary-key scan of {@code workspace_role_permission} on a small table, so one member's
 * authorization waited on every other custom role's locked rows. Whether a run reaches that plan
 * depends on the shared table's size, so this case guards the behaviour while
 * {@code WorkflowPrincipalMapperXmlTest} pins the statement shape and the custom-role cases of
 * {@code AttachmentUploadSecurityIntegrationTest} reproduce the old wait end to end. Each case
 * commits its members and roles and deletes them afterwards.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CustomRoleAuthorizationLockScopeIntegrationTest extends AbstractServiceTest {
    private static final int SECOND_MEMBER_LOCK_WAIT_SECONDS = 5;
    private static final int ROLE_SIZE = 8;

    @Autowired private WorkspaceService workspaceService;
    @Autowired private RoleMapper roleMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Integer> committedUsers = new ArrayList<>();
    private final List<Integer> committedRoles = new ArrayList<>();

    /**
     * While one member's locked authorization is still open, a member with a different custom role
     * authorizes inside a short lock wait instead of queueing behind the first member's role rows. The
     * holder takes either the lower or the higher role id, so the other member's lookup either starts
     * or ends next to the held rows.
     */
    @ParameterizedTest
    @CsvSource({"READ_COMMITTED,false", "READ_COMMITTED,true", "REPEATABLE_READ,false", "REPEATABLE_READ,true"})
    void membersWithDifferentCustomRolesAuthorizeWithoutWaitingOnEachOther(
            Isolation isolation, boolean holderHasTheHigherRole) throws InterruptedException {
        List<Permission> grantable = List.copyOf(Permission.grantableSet());
        List<Permission> lowerGrants = grantable.subList(0, ROLE_SIZE);
        List<Permission> higherGrants = grantable.subList(ROLE_SIZE, 2 * ROLE_SIZE);
        User lower = customRoleMember(lowerGrants);
        User higher = customRoleMember(higherGrants);
        User holder = holderHasTheHigherRole ? higher : lower;
        User other = holderHasTheHigherRole ? lower : higher;
        Set<Permission> holderGrants = EnumSet.copyOf(holderHasTheHigherRole ? higherGrants : lowerGrants);
        Set<Permission> otherGrants = EnumSet.copyOf(holderHasTheHigherRole ? lowerGrants : higherGrants);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            transaction(isolation).executeWithoutResult(status -> {
                assertEquals(holderGrants,
                        workspaceService.lockedMemberPermissionsFor(workspace.getId(), holder.getId()));
                Future<Set<Permission>> authorization =
                        executor.submit(() -> authorizeOnItsOwnConnection(other, isolation));
                assertEquals(otherGrants, await(authorization));
                status.setRollbackOnly();
            });
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /** A permission no custom role may grant confers nothing even when its row is stored on the role. */
    @ParameterizedTest
    @EnumSource(value = Permission.class, names = {"SSO_MANAGE", "WORKSPACE_DELETE"})
    void anInertPermissionStoredOnACustomRoleConfersNothing(Permission inert) {
        User member = customRoleMember(List.of(Permission.CAMPAIGN_VIEW));
        assertEquals(1, jdbcTemplate.update(
                "INSERT INTO workspace_role_permission (workspace_role_id, permission) VALUES (?, ?)",
                committedRoles.getLast(), inert.name()));

        Set<Permission> permissions = new TransactionTemplate(transactionManager).execute(status ->
                workspaceService.lockedMemberPermissionsFor(workspace.getId(), member.getId()));

        assertEquals(EnumSet.of(Permission.CAMPAIGN_VIEW), permissions);
    }

    /** Deletes the committed members, including the owner the base class made, and then their roles. */
    @AfterEach
    void deleteCommittedFixtures() {
        if (currentUser != null) {
            committedUsers.add(currentUser.getId());
        }
        for (int userId : committedUsers) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
        for (int roleId : committedRoles) {
            jdbcTemplate.update("DELETE FROM workspace_role WHERE id = ?", roleId);
        }
    }

    private User customRoleMember(List<Permission> permissions) {
        User member = newUser();
        committedUsers.add(member.getId());
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("Lock scope " + unique());
        roleMapper.insertRole(role);
        committedRoles.add(role.getId());
        roleMapper.insertPermissions(workspace.getId(), role.getId(),
                permissions.stream().map(Permission::name).toList());
        assertEquals(1, workspaceMapper.setMemberCustomRole(workspace.getId(), member.getId(), role.getId()));
        return member;
    }

    private Set<Permission> authorizeOnItsOwnConnection(User member, Isolation isolation) {
        authenticateAs(member, workspace.getId());
        try {
            return transaction(isolation).execute(status -> {
                long previousLockWait = Objects.requireNonNull(jdbcTemplate.queryForObject(
                        "SELECT @@SESSION.innodb_lock_wait_timeout", Long.class));
                jdbcTemplate.execute(
                        "SET SESSION innodb_lock_wait_timeout = " + SECOND_MEMBER_LOCK_WAIT_SECONDS);
                try {
                    return workspaceService.lockedMemberPermissionsFor(workspace.getId(), member.getId());
                } finally {
                    jdbcTemplate.execute("SET SESSION innodb_lock_wait_timeout = " + previousLockWait);
                }
            });
        } finally {
            clearAuthentication();
        }
    }

    private TransactionTemplate transaction(Isolation isolation) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(isolation.value());
        return template;
    }

    private static Set<Permission> await(Future<Set<Permission>> authorization) {
        try {
            return authorization.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof PessimisticLockingFailureException) {
                fail("A member with a different custom role waited on the first member's authorization locks",
                        failure.getCause());
            }
            throw new AssertionError(failure.getCause());
        } catch (TimeoutException failure) {
            throw new AssertionError("The second member's authorization did not finish", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting the second member's authorization", failure);
        }
    }
}
