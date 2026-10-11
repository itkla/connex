package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.PrivilegedAccountService;
import ooo.klae.connex.backend.services.PrivilegedMfaStanding;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * Drives the confinement acknowledgement through the real HTTP stack and real transactions (#1534).
 * With attestation enforced, a role edit that makes members newly privileged in an organization
 * while none of their passkeys is approved there answers {@code 409} and rolls back until the
 * request acknowledges it. Only that organization's own facts decide the answer.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PrivilegedMfaConfinementAcknowledgementIntegrationTest {
    private static final String PASSWORD = "ConfinementProof9!";
    private static final String CODE = "PRIVILEGED_MFA_CONFINEMENT_ACKNOWLEDGEMENT_REQUIRED";
    private static final String MESSAGE = "This change leaves members without a passkey approved in this"
            + " organization; they will be confined until one is approved";
    private static final String UNPRIVILEGED = Permission.PERSON_CREATE.name();
    private static final String PRIVILEGED = Permission.TAG_MANAGE.name();
    private static final ThreadLocal<ConnectionProbe> CONNECTION_PROBE = new ThreadLocal<>();

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PrivilegedMfaProperties privilegedMfaProperties;
    @Autowired private PrivilegedAccountService privilegedAccountService;
    @MockitoSpyBean private UserMapper userMapper;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private OrgMemberMapper orgMemberMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private TenantContext tenantContext;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Integer> workspaceIds = new ArrayList<>();
    private final List<Integer> organizationIds = new ArrayList<>();
    private final List<Integer> userIds = new ArrayList<>();

    private MockMvc mockMvc;
    private String configuredAttestationEnforced;
    private String passwordHash;
    private Organization organization;
    private Workspace workspace;
    private User owner;
    private MockHttpSession ownerSession;

    @BeforeEach
    void setUp() throws Exception {
        UserMapper real = sqlSessionTemplate.getMapper(UserMapper.class);
        doAnswer(invocation -> {
            recordConnection();
            return real.lockById(invocation.getArgument(0));
        }).when(userMapper).lockById(anyInt());
        configuredAttestationEnforced = privilegedMfaProperties.getAttestationEnforced();
        privilegedMfaProperties.setAttestationEnforced("true");
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        passwordHash = passwordEncoder.encode(PASSWORD);
        organization = newOrganization();
        workspace = newWorkspace(organization);
        owner = newUser();
        workspaceMapper.addMember(workspace.getId(), owner.getId(), "owner");
        orgMemberMapper.addMember(organization.getId(), owner.getId(), "owner");
        int ownerPasskey = passkey(owner);
        cover(ownerPasskey, organization);
        ownerSession = steppedUpSession(owner, ownerPasskey);
    }

    @AfterEach
    void tearDown() {
        privilegedMfaProperties.setAttestationEnforced(configuredAttestationEnforced);
        for (int workspaceId : workspaceIds) {
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspaceId);
        }
        for (int organizationId : organizationIds) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organizationId);
        }
        for (int userId : userIds) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
    }

    /**
     * Promoting a member who has no passkey answers {@code 409} with the code, the message and how
     * many members it affects. The refused change leaves the role and the audit trail as they were,
     * and the acknowledged retry applies it.
     */
    @Test
    void promotingAMemberWithoutAPasskeyNeedsAcknowledgementAndRollsBackUntilThen() throws Exception {
        User member = workspaceMember();

        changeRole(member, Map.of("role", "admin"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(CODE))
                .andExpect(jsonPath("$.message").value(MESSAGE))
                .andExpect(jsonPath("$.affectedCount").value(1));
        assertEquals("member", workspaceRole(member));
        assertEquals(0, workspaceAudits("workspace.member.role", member));

        changeRole(member, Map.of("role", "admin", "acknowledgeConfinement", true))
                .andExpect(status().isOk());
        assertEquals("admin", workspaceRole(member));
        assertEquals(1, workspaceAudits("workspace.member.role", member));
    }

    /**
     * Another organization's confinement changes nothing: a member it already confines answers
     * exactly as one it does not, whether or not this organization approved their passkey.
     */
    @Test
    void anotherOrganizationsConfinementChangesNothing() throws Exception {
        Organization other = newOrganization();
        User confinedElsewhere = workspaceMember();
        passkey(confinedElsewhere);
        orgMemberMapper.addMember(other.getId(), confinedElsewhere.getId(), "admin");
        User unconfined = workspaceMember();
        passkey(unconfined);
        assertEquals(PrivilegedMfaStanding.ATTESTATION_REQUIRED,
                privilegedAccountService.standing(confinedElsewhere.getId()));
        assertEquals(PrivilegedMfaStanding.UNPRIVILEGED, privilegedAccountService.standing(unconfined.getId()));

        assertEquals(refusalOf(unconfined), refusalOf(confinedElsewhere));

        User approvedHereConfinedElsewhere = workspaceMember();
        cover(passkey(approvedHereConfinedElsewhere), organization);
        orgMemberMapper.addMember(other.getId(), approvedHereConfinedElsewhere.getId(), "admin");
        User approvedHere = workspaceMember();
        cover(passkey(approvedHere), organization);

        changeRole(approvedHereConfinedElsewhere, Map.of("role", "admin")).andExpect(status().isOk());
        changeRole(approvedHere, Map.of("role", "admin")).andExpect(status().isOk());
    }

    @Test
    void anApprovalOnlyInAnotherOrganizationDoesNotCountHere() throws Exception {
        User member = workspaceMember();
        cover(passkey(member), newOrganization());

        changeRole(member, Map.of("role", "admin"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.affectedCount").value(1));
        assertEquals("member", workspaceRole(member));
    }

    /**
     * With approvals split across passkeys, one approved here answers here. No single passkey
     * covers both organizations, so the member ends up confined, but that depends on the other
     * organization, which this one is not told about.
     */
    @Test
    void splitApprovalsAnswerFromThisOrganizationsApprovalAlone() throws Exception {
        Organization other = newOrganization();
        User member = workspaceMember();
        cover(passkey(member), organization);
        cover(passkey(member), other);
        orgMemberMapper.addMember(other.getId(), member.getId(), "admin");
        assertEquals(PrivilegedMfaStanding.SATISFIED, privilegedAccountService.standing(member.getId()));

        changeRole(member, Map.of("role", "admin")).andExpect(status().isOk());

        assertEquals(PrivilegedMfaStanding.ATTESTATION_REQUIRED, privilegedAccountService.standing(member.getId()));
    }

    @Test
    void breakGlassAssuranceCountsWithoutAnApproval() throws Exception {
        User member = workspaceMember();
        jdbcTemplate.update(
                "UPDATE webauthn_credential SET privileged_assurance = 'BREAK_GLASS',"
                        + " privileged_assured_at = UTC_TIMESTAMP(3) WHERE id = ?",
                passkey(member));

        changeRole(member, Map.of("role", "admin")).andExpect(status().isOk());
    }

    /**
     * A built-in role clears any custom overlay, so moving an owner whose overlay grants nothing
     * privileged to built-in admin makes them privileged, and they count although it looks like a
     * demotion. The refusal keeps the overlay.
     */
    @Test
    void anApparentDemotionThatClearsAnUnprivilegedOverlayCounts() throws Exception {
        User overlaidOwner = newUser();
        workspaceMapper.addMember(workspace.getId(), overlaidOwner.getId(), "owner");
        int overlay = customRole(List.of(UNPRIVILEGED));
        workspaceMapper.setMemberCustomRole(workspace.getId(), overlaidOwner.getId(), overlay);
        assertEquals(PrivilegedMfaStanding.UNPRIVILEGED, privilegedAccountService.standing(overlaidOwner.getId()));

        changeRole(overlaidOwner, Map.of("role", "admin"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.affectedCount").value(1));

        assertEquals("owner", workspaceRole(overlaidOwner));
        assertEquals(overlay, workspaceRoleId(overlaidOwner));
    }

    @Test
    void assigningAPrivilegedCustomRoleNeedsAcknowledgementAndKeepsTheAssignmentUntilThen() throws Exception {
        User member = workspaceMember();
        int privileged = customRole(List.of(UNPRIVILEGED, PRIVILEGED));

        changeRole(member, Map.of("roleId", privileged))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.affectedCount").value(1));
        assertNull(workspaceRoleId(member));
        assertEquals(0, workspaceAudits("workspace.member.role", member));

        changeRole(member, Map.of("roleId", privileged, "acknowledgeConfinement", true))
                .andExpect(status().isOk());
        assertEquals(privileged, workspaceRoleId(member));
    }

    /**
     * A role edit counts each active assignee it makes newly privileged; a pending one never
     * counts. A refused edit leaves the role's name, permissions and audit trail as they were.
     */
    @Test
    void aRoleEditCountsActiveAssigneesOnlyAndRollsBackUntilAcknowledged() throws Exception {
        int role = customRole(List.of(UNPRIVILEGED));
        String name = roleName(role);
        User active = workspaceMember();
        workspaceMapper.setMemberCustomRole(workspace.getId(), active.getId(), role);
        User pending = pendingMember();
        workspaceMapper.setMemberCustomRole(workspace.getId(), pending.getId(), role);

        updateRole(role, Map.of("name", "Taggers", "permissions", List.of(UNPRIVILEGED, PRIVILEGED)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(CODE))
                .andExpect(jsonPath("$.affectedCount").value(1));
        assertEquals(name, roleName(role));
        assertEquals(List.of(UNPRIVILEGED), permissions(role));
        assertEquals(0, workspaceAudits("workspace.role.update", null));

        updateRole(role, Map.of("name", "Taggers", "permissions", List.of(UNPRIVILEGED, PRIVILEGED),
                        "acknowledgeConfinement", true))
                .andExpect(status().isOk());
        assertEquals("Taggers", roleName(role));
        assertEquals(List.of(UNPRIVILEGED, PRIVILEGED), permissions(role));
    }

    @Test
    void aRoleEditWhoseOnlyAssigneeIsPendingNeedsNoAcknowledgement() throws Exception {
        int role = customRole(List.of(UNPRIVILEGED));
        User pending = pendingMember();
        workspaceMapper.setMemberCustomRole(workspace.getId(), pending.getId(), role);

        updateRole(role, Map.of("name", "Invited", "permissions", List.of(UNPRIVILEGED, PRIVILEGED)))
                .andExpect(status().isOk());
    }

    /**
     * Adding an organization administrator, by email or by id, counts the same way. A refused add
     * leaves no membership and no success audit.
     */
    @Test
    void anOrganizationAddNeedsAcknowledgementAndLeavesNoMembershipUntilThen() throws Exception {
        User byEmail = workspaceMember();
        User byId = workspaceMember();

        addToOrganization(Map.of("email", byEmail.getEmail(), "orgRole", "admin"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(CODE))
                .andExpect(jsonPath("$.affectedCount").value(1));
        setOrganizationRole(byId, Map.of("orgRole", "admin"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.affectedCount").value(1));
        assertNull(organizationRole(byEmail));
        assertNull(organizationRole(byId));
        assertEquals(0, organizationAudits(byEmail) + organizationAudits(byId));

        addToOrganization(Map.of("email", byEmail.getEmail(), "orgRole", "admin", "acknowledgeConfinement", true))
                .andExpect(status().isNoContent());
        setOrganizationRole(byId, Map.of("orgRole", "admin", "acknowledgeConfinement", true))
                .andExpect(status().isNoContent());
        assertEquals("admin", organizationRole(byEmail));
        assertEquals("admin", organizationRole(byId));
    }

    @Test
    void anAccountAlreadyPrivilegedHereIsNotNewlyPrivilegedByAnOrganizationAdd() throws Exception {
        User administrator = newUser();
        workspaceMapper.addMember(workspace.getId(), administrator.getId(), "admin");

        setOrganizationRole(administrator, Map.of("orgRole", "admin")).andExpect(status().isNoContent());
    }

    /** While attestation is not enforced, nothing is counted and nothing needs acknowledging. */
    @Test
    void nothingNeedsAcknowledgingWhileAttestationIsOff() throws Exception {
        privilegedMfaProperties.setAttestationEnforced("false");
        User member = workspaceMember();
        User organizationAdmin = workspaceMember();
        int role = customRole(List.of(UNPRIVILEGED));
        User assignee = workspaceMember();
        workspaceMapper.setMemberCustomRole(workspace.getId(), assignee.getId(), role);

        changeRole(member, Map.of("role", "admin")).andExpect(status().isOk());
        setOrganizationRole(organizationAdmin, Map.of("orgRole", "admin")).andExpect(status().isNoContent());
        updateRole(role, Map.of("name", "Taggers", "permissions", List.of(UNPRIVILEGED, PRIVILEGED)))
                .andExpect(status().isOk());
    }

    /**
     * The check reads current state: an approval committed while an edit waits on the workspace
     * counts, although the edit read other state before it waited. At the default repeatable-read
     * isolation the edit's snapshot would predate the approval and refuse the edit. Each entry
     * point declares its own isolation, so each is raced.
     */
    @Test
    void anApprovalCommittedWhileAnEditWaitsOnTheWorkspaceCounts() throws Exception {
        User promoted = workspaceMember();
        int promotedPasskey = passkey(promoted);
        assertEquals(200, statusWhileApproving(promotedPasskey,
                () -> changeRole(promoted, Map.of("role", "admin"))));

        User assigned = workspaceMember();
        int assignedPasskey = passkey(assigned);
        int privileged = customRole(List.of(UNPRIVILEGED, PRIVILEGED));
        assertEquals(200, statusWhileApproving(assignedPasskey,
                () -> changeRole(assigned, Map.of("roleId", privileged))));

        User assignee = workspaceMember();
        int assigneePasskey = passkey(assignee);
        int role = customRole(List.of(UNPRIVILEGED));
        workspaceMapper.setMemberCustomRole(workspace.getId(), assignee.getId(), role);
        assertEquals(200, statusWhileApproving(assigneePasskey,
                () -> updateRole(role, Map.of("name", "Taggers", "permissions", List.of(UNPRIVILEGED, PRIVILEGED)))));
    }

    private ResultActions changeRole(User member, Map<String, ?> body) throws Exception {
        return mockMvc.perform(patch("/api/workspaces/{id}/members/{userId}", workspace.getId(), member.getId())
                .session(ownerSession).header("X-Workspace-Id", workspace.getId())
                .with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions updateRole(int roleId, Map<String, ?> body) throws Exception {
        return mockMvc.perform(put("/api/workspaces/{id}/roles/{roleId}", workspace.getId(), roleId)
                .session(ownerSession).header("X-Workspace-Id", workspace.getId())
                .with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions addToOrganization(Map<String, ?> body) throws Exception {
        return mockMvc.perform(post("/api/orgs/{id}/members", organization.getId())
                .session(ownerSession).with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions setOrganizationRole(User member, Map<String, ?> body) throws Exception {
        return mockMvc.perform(put("/api/orgs/{id}/members/{userId}", organization.getId(), member.getId())
                .session(ownerSession).with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode refusalOf(User member) throws Exception {
        String body = changeRole(member, Map.of("role", "admin"))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /**
     * Runs the edit while another transaction holds the workspace and approves the passkey, and
     * commits the approval only once the edit is really waiting on that workspace.
     */
    private int statusWhileApproving(int credentialRowId, Callable<ResultActions> edit) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        jdbcTemplate.queryForObject(
                                "SELECT id FROM workspace WHERE id = ? FOR UPDATE", Integer.class, workspace.getId());
                        cover(credentialRowId, organization);
                        held.countDown();
                        await(release);
                    }));
            assertTrue(held.await(30, TimeUnit.SECONDS), "the approval was never held");
            ConnectionProbe probe = new ConnectionProbe(new AtomicLong(), new CountDownLatch(1));
            Future<Integer> contender = executor.submit(() -> {
                CONNECTION_PROBE.set(probe);
                try {
                    return edit.call().andReturn().getResponse().getStatus();
                } finally {
                    CONNECTION_PROBE.remove();
                    probe.known().countDown();
                }
            });
            assertTrue(probe.known().await(30, TimeUnit.SECONDS), "the edit never reached its first lock");
            MySqlLockWaitProbe.awaitExclusiveRecordLock(
                    jdbcTemplate, probe.connectionId().get(), "workspace", Integer.toString(workspace.getId()));
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            return contender.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    /** Reports the connection of the edit's own transaction, at its first account lock. */
    private void recordConnection() {
        ConnectionProbe probe = CONNECTION_PROBE.get();
        if (probe != null && probe.connectionId().get() == 0
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            Long connectionId = jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class);
            probe.connectionId().set(connectionId == null ? 0 : connectionId);
            probe.known().countDown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The held approval was never released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private MockHttpSession steppedUpSession(User user, int credentialRowId) throws Exception {
        MockHttpSession session = assertInstanceOf(MockHttpSession.class, mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", user.getUsername(), "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false));
        long now = System.currentTimeMillis();
        session.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR, now);
        session.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_USER_ATTR, user.getId());
        session.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_PROOF_ATTR,
                user.getId() + ":" + now + ":" + credentialRowId);
        return session;
    }

    private int passkey(User user) {
        List<String> handles = jdbcTemplate.queryForList(
                "SELECT id FROM webauthn_user_entity WHERE user_id = ?", String.class, user.getId());
        String handle = handles.isEmpty() ? "confinement-" + UUID.randomUUID() : handles.get(0);
        if (handles.isEmpty()) {
            jdbcTemplate.update(
                    "INSERT INTO webauthn_user_entity (id, user_id, name, display_name) VALUES (?, ?, ?, ?)",
                    handle, user.getId(), user.getUsername(), user.getDisplayName());
        }
        byte[] credentialId = ("confinement-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        jdbcTemplate.update(
                "INSERT INTO webauthn_credential (credential_id, user_entity_user_id, public_key) VALUES (?, ?, ?)",
                credentialId, handle, new byte[] {1});
        Integer rowId = jdbcTemplate.queryForObject(
                "SELECT id FROM webauthn_credential WHERE credential_id = ?", Integer.class, credentialId);
        assertNotNull(rowId);
        return rowId;
    }

    private void cover(int credentialRowId, Organization coveredIn) {
        jdbcTemplate.update(
                "INSERT INTO privileged_credential_attestation (credential_row_id, org_id, source)"
                        + " VALUES (?, ?, 'FOUNDER')",
                credentialRowId, coveredIn.getId());
    }

    private int customRole(List<String> permissions) {
        tenantContext.set(workspace.getId(), organization.getId(), owner.getId(), "owner", null);
        try {
            WorkspaceRole role = new WorkspaceRole();
            role.setWorkspaceId(workspace.getId());
            role.setName("Confinement " + UUID.randomUUID().toString().substring(0, 8));
            roleMapper.insertRole(role);
            roleMapper.insertPermissions(workspace.getId(), role.getId(), permissions);
            return role.getId();
        } finally {
            tenantContext.clear();
        }
    }

    private String workspaceRole(User member) {
        return jdbcTemplate.queryForObject(
                "SELECT role FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
                String.class, workspace.getId(), member.getId());
    }

    private Integer workspaceRoleId(User member) {
        return jdbcTemplate.queryForObject(
                "SELECT role_id FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
                Integer.class, workspace.getId(), member.getId());
    }

    private String roleName(int roleId) {
        return jdbcTemplate.queryForObject("SELECT name FROM workspace_role WHERE id = ?", String.class, roleId);
    }

    private List<String> permissions(int roleId) {
        return jdbcTemplate.queryForList(
                "SELECT permission FROM workspace_role_permission WHERE workspace_role_id = ? ORDER BY permission",
                String.class, roleId);
    }

    private String organizationRole(User member) {
        List<String> roles = jdbcTemplate.queryForList(
                "SELECT org_role FROM org_member WHERE org_id = ? AND user_id = ?",
                String.class, organization.getId(), member.getId());
        return roles.isEmpty() ? null : roles.get(0);
    }

    private int workspaceAudits(String action, User target) {
        Integer count = target == null
                ? jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM audit_log WHERE action = ? AND entity_id = ?",
                        Integer.class, action, workspace.getId())
                : jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM audit_log WHERE action = ? AND entity_id = ? AND target_label = ?",
                        Integer.class, action, workspace.getId(), target.getDisplayName());
        assertNotNull(count);
        return count;
    }

    private int organizationAudits(User target) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'org.member.set' AND entity_id = ?"
                        + " AND target_label = ?",
                Integer.class, organization.getId(), target.getDisplayName());
        assertNotNull(count);
        return count;
    }

    private User workspaceMember() {
        User member = newUser();
        workspaceMapper.addMember(workspace.getId(), member.getId(), "member");
        return member;
    }

    private User pendingMember() {
        User member = newUser();
        workspaceMapper.addPendingMember(workspace.getId(), member.getId(), "member");
        return member;
    }

    private Organization newOrganization() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization created = new Organization();
        created.setName("Confinement Org " + suffix);
        created.setSlug("confinement-org-" + suffix);
        organizationMapper.insert(created);
        organizationIds.add(created.getId());
        return created;
    }

    private Workspace newWorkspace(Organization parent) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Workspace created = new Workspace();
        created.setOrgId(parent.getId());
        created.setName("Confinement Workspace " + suffix);
        created.setSlug("confinement-ws-" + suffix);
        created.setTimezone("UTC");
        workspaceMapper.insert(created);
        workspaceIds.add(created.getId());
        return created;
    }

    private User newUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User created = new User();
        created.setUsername("confine_" + suffix);
        created.setDisplayName("Confine " + suffix);
        created.setEmail("confine-" + suffix + "@example.com");
        created.setEmailVerified(true);
        created.setPasswordHash(passwordHash);
        created.setTimezone("UTC");
        userMapper.insert(created);
        userIds.add(created.getId());
        return userMapper.getUserById(created.getId());
    }

    /**
     * Where the raced edit reports the connection its own transaction runs on.
     *
     * @param connectionId the MySQL connection id, or zero until the edit reaches a lock
     * @param known released once the edit reports, or finishes without reaching a lock
     */
    private record ConnectionProbe(AtomicLong connectionId, CountDownLatch known) {
    }
}
