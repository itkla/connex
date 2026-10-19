package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceInvite;
import ooo.klae.connex.backend.mappers.InviteMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;

/** Distinct principals and physical MySQL waits prove audited assignment roots survive to commit. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MembershipRootOrderConcurrencyIntegrationTest extends AbstractServiceTest {
    @MockitoSpyBean private WorkspaceMapper roots;
    @MockitoSpyBean private UserMapper accountsMapper;
    @MockitoSpyBean private PersonMapper persons;
    @MockitoSpyBean private TenantLifecycleControlMapper lifecycleMapper;
    @Autowired private OrganizationMapper organizations;
    @Autowired private OrgMemberMapper organizationMembers;
    @Autowired private InviteMapper invites;
    @Autowired private AuditIntegrityService integrity;
    @Autowired private PersonService personService;
    @Autowired private RoleService roleService;
    @Autowired private InviteService inviteService;
    @Autowired private WorkspaceService workspaces;
    @Autowired private TenantLifecycleControlOperations lifecycle;
    @Autowired private SqlSessionTemplate sql;
    @Autowired private JdbcTemplate jdbc;
    private final List<Integer> accountIds = new ArrayList<>();
    private final Map<Integer, Integer> approvedCredentials = new HashMap<>();
    private int organizationId;
    private int workspaceId;
    private User assignee;
    private User administrator;
    private Person person;

    @Override
    protected User newUser() {
        User user = super.newUser();
        accountIds.add(user.getId());
        return user;
    }

    @BeforeEach
    void committedIsolatedFixtures() {
        Organization organization = new Organization();
        organization.setName("Membership root " + unique());
        organization.setSlug("membership-root-" + unique());
        organizations.insert(organization);
        organizationId = organization.getId();
        Workspace isolated = new Workspace();
        isolated.setOrgId(organizationId);
        isolated.setName("Membership root workspace");
        isolated.setSlug("membership-root-" + unique());
        roots.insert(isolated);
        workspace = isolated;
        workspaceId = isolated.getId();
        roots.addMember(workspaceId, currentUser.getId(), "owner");
        assignee = newUser();
        administrator = newUser();
        roots.updateMemberRole(workspaceId, assignee.getId(), "owner");
        roots.updateMemberRole(workspaceId, administrator.getId(), "owner");
        for (User member : List.of(currentUser, assignee, administrator)) {
            organizationMembers.addMember(organizationId, member.getId(), "owner");
            authenticateAs(member, workspaceId);
            approvedCredentials.put(member.getId(), approveForStepUp(member));
        }
        authenticateAs(currentUser, workspaceId);
        person = newPerson(null);
        jdbc.update("UPDATE person SET owner_id = ? WHERE id = ?", currentUser.getId(), person.getId());
    }

    @AfterEach
    void deleteCommittedFixtures() {
        reset(roots, accountsMapper, persons, lifecycleMapper);
        jdbc.update("DELETE FROM person WHERE workspace_id = ?", workspaceId);
        jdbc.update("DELETE FROM tenant_operation_lease WHERE workspace_id = ?", workspaceId);
        jdbc.update("DELETE FROM tenant_cleanup_tombstone WHERE workspace_id = ?", workspaceId);
        jdbc.update("DELETE FROM workspace WHERE id = ?", workspaceId);
        jdbc.update("DELETE FROM organization WHERE id = ?", organizationId);
        accountIds.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
        accountIds.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "invite", "teardown", "removeActor"})
    void assignmentSerializesBeforeTheContendingRootAndCommitsAValidAudit(String contender) throws Exception {
        WorkspaceInvite invite = new WorkspaceInvite();
        if ("invite".equals(contender)) {
            roots.removeMember(workspaceId, administrator.getId());
            invite.setWorkspaceId(workspaceId);
            invite.setEmail(administrator.getEmail());
            invite.setRole("member");
            invite.setToken("membership-root-" + unique());
            invite.setInvitedById(assignee.getId());
            invites.insert(invite);
        }
        CountDownLatch aggregateHeld = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CountDownLatch contenderAtRoot = new CountDownLatch(1);
        AtomicLong writerConnection = new AtomicLong();
        AtomicLong contenderConnection = new AtomicLong();
        PersonMapper realPersons = sql.getMapper(PersonMapper.class);
        WorkspaceMapper realRoots = sql.getMapper(WorkspaceMapper.class);
        UserMapper realAccounts = sql.getMapper(UserMapper.class);
        TenantLifecycleControlMapper realLifecycle = sql.getMapper(TenantLifecycleControlMapper.class);
        doAnswer(call -> {
            Person locked = realPersons.getOwnedPersonByIdForUpdate(workspaceId, person.getId());
            writerConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            aggregateHeld.countDown();
            assertTrue(releaseWriter.await(30, TimeUnit.SECONDS));
            return locked;
        }).when(persons).getOwnedPersonByIdForUpdate(workspaceId, person.getId());
        Runnable reachedRoot = () -> {
            contenderConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            contenderAtRoot.countDown();
        };
        switch (contender) {
            case "role" -> doAnswer(call -> {
                reachedRoot.run();
                return realRoots.lockWorkspace(workspaceId);
            }).when(roots).lockWorkspace(workspaceId);
            case "invite" -> doAnswer(call -> {
                reachedRoot.run();
                return realRoots.lockActiveIdentity(workspaceId);
            }).when(roots).lockActiveIdentity(workspaceId);
            case "teardown" -> doAnswer(call -> {
                reachedRoot.run();
                return realLifecycle.lockWorkspaceInOrg(workspaceId);
            }).when(lifecycleMapper).lockWorkspaceInOrg(workspaceId);
            case "removeActor" -> doAnswer(call -> {
                reachedRoot.run();
                return realAccounts.lockById(currentUser.getId());
            }).when(accountsMapper).lockById(currentUser.getId());
            default -> throw new IllegalArgumentException(contender);
        }
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<?> writer = workers.submit(() -> as(currentUser,
                () -> personService.updateOwner(person.getId(), assignee.getId())));
            try {
                assertTrue(aggregateHeld.await(30, TimeUnit.SECONDS));
                Future<?> other = workers.submit(() -> as(
                    "role".equals(contender) ? assignee : administrator,
                    () -> {
                        switch (contender) {
                            case "role" -> assertNotNull(roleService.createRole(
                                workspaceId, assignee.getId(), "Contended role", List.of("PERSON_UPDATE")));
                            case "invite" -> inviteService.acceptInvite(invite.getToken(), administrator);
                            case "teardown" -> {
                                var acquired = lifecycle.acquireWorkspaceTeardown(
                                    organizationId, workspaceId, administrator.getId());
                                assertEquals(assignee.getId(), jdbc.queryForObject(
                                    "SELECT owner_id FROM person WHERE id = ?", Integer.class, person.getId()));
                                assertEquals(1, committedAudits().size());
                                jdbc.update("DELETE FROM person WHERE id = ?", person.getId());
                                lifecycle.deleteWorkspaceRoot(
                                    organizationId, workspaceId, administrator.getId(), acquired.lease());
                            }
                            case "removeActor" -> workspaces.removeMember(
                                workspaceId, administrator.getId(), currentUser.getId());
                            default -> throw new IllegalArgumentException(contender);
                        }
                    }));
                assertTrue(contenderAtRoot.await(30, TimeUnit.SECONDS));
                assertNotEquals(writerConnection.get(), contenderConnection.get());
                boolean account = "removeActor".equals(contender);
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, contenderConnection.get(),
                    account ? "app_user" : "workspace",
                    Integer.toString(account ? currentUser.getId() : workspaceId));
                assertEquals(currentUser.getId(), jdbc.queryForObject(
                    "SELECT owner_id FROM person WHERE id = ?", Integer.class, person.getId()));
                assertTrue(committedAudits().isEmpty());
                releaseWriter.countDown();
                completed(writer);
                completed(other);
            } finally {
                releaseWriter.countDown();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS));
            }
        }
        List<AuditLog> committed = committedAudits();
        List<AuditLog> assignments = committed.stream()
            .filter(entry -> "person.updateOwner".equals(entry.getAction())).toList();
        assertEquals(1, assignments.size());
        assertEquals(currentUser.getId(), assignments.getFirst().getIntegrityActorId());
        assertTrue(committed.stream().allMatch(integrity::hasValidIntegrity));
        String expectedAction = switch (contender) {
            case "role" -> "workspace.role.create";
            case "invite" -> "workspace.invite.accept";
            case "removeActor" -> "workspace.member.remove";
            default -> "person.updateOwner";
        };
        assertEquals(1, committed.stream().filter(entry -> expectedAction.equals(entry.getAction())).count());
        for (int index = 1; index < committed.size(); index++) {
            assertEquals(committed.get(index - 1).getRowHash(), committed.get(index).getPrevHash());
            assertEquals(committed.get(index - 1).getChainIndex() + 1, committed.get(index).getChainIndex());
        }
        switch (contender) {
            case "role" -> assertTrue(roleService.listRoles(workspaceId, currentUser.getId()).stream()
                .anyMatch(role -> "Contended role".equals(role.getName())));
            case "invite" -> {
                assertEquals("accepted", invites.findByToken(invite.getToken()).getStatus());
                assertTrue(roots.isMember(workspaceId, administrator.getId()));
            }
            case "teardown" -> assertNull(roots.getActiveById(workspaceId));
            case "removeActor" -> assertNull(roots.getMember(workspaceId, currentUser.getId()));
            default -> throw new IllegalArgumentException(contender);
        }
        if (!"teardown".equals(contender)) {
            assertEquals(assignee.getId(), jdbc.queryForObject(
                "SELECT owner_id FROM person WHERE id = ?", Integer.class, person.getId()));
        }
    }

    private List<AuditLog> committedAudits() {
        return jdbc.query("""
            SELECT audit_log.*,
                   untrusted_client_asserted_correlation_id AS untrusted_client_asserted_correlation_hmac
            FROM audit_log WHERE integrity_workspace_id = ? ORDER BY chain_index
            """, org.springframework.jdbc.core.BeanPropertyRowMapper.newInstance(AuditLog.class), workspaceId);
    }

    private void as(User user, Runnable action) {
        authenticateAs(user, workspaceId);
        var attributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        var session = attributes.getRequest().getSession();
        session.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_PROOF_ATTR,
            user.getId() + ":" + session.getAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR)
                + ":" + approvedCredentials.get(user.getId()));
        try {
            action.run();
        } finally {
            clearAuthentication();
        }
    }

    private static void completed(Future<?> future) throws Exception {
        try {
            future.get(15, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sqlFailure) {
                    assertNotEquals(1213, sqlFailure.getErrorCode());
                    assertNotEquals(1205, sqlFailure.getErrorCode());
                }
            }
            throw failure;
        }
    }
}
