package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.MemberDto;
import ooo.klae.connex.backend.dto.PrivilegedMembershipDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.support.OwnershipOfferTestFixtures;

/** Real SQL coverage for the self-only exits and org-local, authorized, batched member badges. */
@Import(PasskeyApprovalMetadataTest.CounterConfiguration.class)
class PasskeyApprovalMetadataTest extends AbstractServiceTest {
    @Autowired private PrivilegedAccountService privileged;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private OrgMemberService orgService;
    @Autowired private OrganizationMapper organizations;
    @Autowired private OrgMemberMapper orgMembers;
    @Autowired private RoleMapper roles;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private QueryCounter counter;
    @Autowired private org.mybatis.spring.SqlSessionTemplate sqlSession;

    @Test void orgOnlyPrivilegeHasNoWorkspaceExits() {
        User user = newUser();
        Organization org = org();
        orgMembers.addMember(org.getId(), user.getId(), "admin");
        assertEquals(List.of(new PrivilegedMembershipDto(org.getId(), org.getName(), "admin", List.of())),
                privileged.privilegedMemberships(user.getId()));
    }

    @Test void builtInAdminAndOwnerWorkspacesShareOneOrgEntry() {
        User user = newUser();
        Organization org = org();
        Workspace admin = workspace(org), owner = workspace(org);
        workspaceMapper.addMember(admin.getId(), user.getId(), "admin");
        workspaceMapper.addMember(owner.getId(), user.getId(), "owner");
        var memberships = privileged.privilegedMemberships(user.getId());
        assertEquals(1, memberships.size());
        assertNull(memberships.getFirst().orgRole());
        assertEquals(List.of(new PrivilegedMembershipDto.Workspace(admin.getId(), admin.getName()),
                new PrivilegedMembershipDto.Workspace(owner.getId(), owner.getName())), memberships.getFirst().workspaces());
    }

    @Test void customPrivilegeDeduplicatesPermissionJoinsAndCombinesOrgMembership() {
        User user = newUser();
        Organization org = org();
        Workspace custom = workspace(org);
        workspaceMapper.addMember(custom.getId(), user.getId(), "member");
        assign(custom, user, "member", "MEMBER_MANAGE", "ROLE_MANAGE", "AUDIT_READ");
        orgMembers.addMember(org.getId(), user.getId(), "owner");
        var membership = privileged.privilegedMemberships(user.getId()).getFirst();
        assertEquals("owner", membership.orgRole());
        assertEquals(List.of(new PrivilegedMembershipDto.Workspace(custom.getId(), custom.getName())), membership.workspaces());
    }

    @Test void nonPrivilegedCustomRoleOverridesAdminAndInactiveMembershipsAndScopesAreExcluded() {
        User user = newUser();
        Organization org = org(), inactiveOrg = org();
        Workspace overlay = workspace(org), inactive = workspace(org), pending = workspace(org), foreign = workspace(inactiveOrg);
        workspaceMapper.addMember(overlay.getId(), user.getId(), "admin");
        assign(overlay, user, "admin", "PERSON_CREATE");
        workspaceMapper.addMember(inactive.getId(), user.getId(), "owner");
        workspaceMapper.addPendingMember(pending.getId(), user.getId(), "admin");
        workspaceMapper.addMember(foreign.getId(), user.getId(), "owner");
        orgMembers.addMember(inactiveOrg.getId(), user.getId(), "owner");
        jdbc.update("UPDATE workspace SET lifecycle_state = 'tearing_down' WHERE id = ?", inactive.getId());
        jdbc.update("UPDATE organization SET lifecycle_state = 'tearing_down' WHERE id = ?", inactiveOrg.getId());
        assertEquals(List.of(), privileged.privilegedMemberships(user.getId()));
        assertFalse(privileged.isPrivileged(user.getId()));
    }

    @Test void unprivilegedAndOtherAccountsHaveNoSelfExitMetadata() {
        User ordinary = newUser(), privilegedOther = newUser();
        Organization org = org();
        orgMembers.addMember(org.getId(), privilegedOther.getId(), "owner");
        assertEquals(List.of(), privileged.privilegedMemberships(ordinary.getId()));
        assertEquals(org.getId(), privileged.privilegedMemberships(privilegedOther.getId()).getFirst().orgId());
    }

    @Test void badgeIsTrueOnlyForPrivilegeWithNoPasskeyApprovedHere() {
        Organization org = org();
        Workspace scope = owned(org);
        User missing = member(scope, "admin"), covered = member(scope, "admin"), glass = member(scope, "owner"), ordinary = member(scope, "member");
        cover(covered, org);
        int credential = OwnershipOfferTestFixtures.passkey(jdbc, glass);
        jdbc.update("UPDATE webauthn_credential SET privileged_assurance = 'BREAK_GLASS', privileged_assured_at = UTC_TIMESTAMP(6) WHERE id = ?", credential);
        var members = workspaceService.getMembersWithRoles(scope.getId(), currentUser.getId());
        assertEquals(true, badge(members, missing));
        assertEquals(false, badge(members, covered));
        assertEquals(false, badge(members, glass));
        assertEquals(false, badge(members, ordinary));
        assertNull(badge(members, currentUser));
        assertEquals(PrivilegedMfaStanding.ATTESTATION_REQUIRED, privileged.standing(missing.getId()));
        assertEquals(PrivilegedMfaStanding.SATISFIED, privileged.standing(covered.getId()));
        assertEquals(PrivilegedMfaStanding.SATISFIED, privileged.standing(glass.getId()));
    }

    @Test void anotherOrganizationsCoverageOrPrivilegeNeverDecidesTheBadge() {
        Organization here = org(), elsewhere = org();
        Workspace scope = owned(here), other = workspace(elsewhere);
        User coveredElsewhere = member(scope, "admin"), privilegedElsewhere = member(scope, "member");
        workspaceMapper.addMember(other.getId(), coveredElsewhere.getId(), "admin");
        workspaceMapper.addMember(other.getId(), privilegedElsewhere.getId(), "admin");
        cover(coveredElsewhere, elsewhere);
        cover(privilegedElsewhere, here);
        assertEquals(true, badge(workspaceService.getMembersWithRoles(scope.getId(), currentUser.getId()), coveredElsewhere));
        assertEquals(false, badge(workspaceService.getMembersWithRoles(scope.getId(), currentUser.getId()), privilegedElsewhere));
        cover(coveredElsewhere, here);
        assertEquals(false, badge(workspaceService.getMembersWithRoles(scope.getId(), currentUser.getId()), coveredElsewhere));
        assertEquals(PrivilegedMfaStanding.ATTESTATION_REQUIRED, privileged.standing(privilegedElsewhere.getId()));
    }

    @Test void callerWithoutIssueAuthoritySeesNullOnEveryWorkspaceRow() {
        Organization org = org();
        Workspace scope = owned(org);
        User ordinary = member(scope, "member"), uncoveredAdmin = member(scope, "admin");
        cover(ordinary, org);
        assertTrue(workspaceService.getMembersWithRoles(scope.getId(), ordinary.getId()).stream()
            .allMatch(row -> row.getPasskeyApprovalNeeded() == null));
        assertTrue(workspaceService.getMembersWithRoles(scope.getId(), uncoveredAdmin.getId()).stream()
            .allMatch(row -> row.getPasskeyApprovalNeeded() == null));
    }

    @Test void workspaceAuthorityHonorsOwnerBoundaryGrantCeilingAndCustomRolePermission() {
        Organization org = org();
        Workspace scope = owned(org);
        User admin = member(scope, "admin"), target = member(scope, "member"), owner = member(scope, "owner");
        cover(admin, org);
        assertNull(badge(workspaceService.getMembersWithRoles(scope.getId(), admin.getId()), owner));
        assign(scope, target, "member", "MEMBER_MANAGE");
        assertNull(badge(workspaceService.getMembersWithRoles(scope.getId(), admin.getId()), target));
        assign(scope, admin, "admin", "MEMBER_MANAGE");
        assertNull(badge(workspaceService.getMembersWithRoles(scope.getId(), admin.getId()), target));
        assign(scope, admin, "admin", "ROLE_MANAGE");
        assertNull(badge(workspaceService.getMembersWithRoles(scope.getId(), admin.getId()), target));
        assign(scope, admin, "admin", "ROLE_MANAGE", "MEMBER_MANAGE");
        assertEquals(true, badge(workspaceService.getMembersWithRoles(scope.getId(), admin.getId()), target));
    }

    @Test void reservedAndPendingTargetsAreNotApprovalCandidates() {
        Organization org = org();
        Workspace scope = owned(org);
        User reserved = member(scope, "admin"), pending = newUser();
        workspaceMapper.addPendingMember(scope.getId(), pending.getId(), "admin");
        userMapper.reserveAccountDeletion(reserved.getId(), "metadata-test");
        var members = workspaceService.getMembersWithRoles(scope.getId(), currentUser.getId());
        assertNull(badge(members, reserved));
        assertNull(badge(members, pending));
        userMapper.reserveAccountDeletion(currentUser.getId(), "metadata-test");
        assertTrue(workspaceService.getMembersWithRoles(scope.getId(), currentUser.getId()).stream()
            .allMatch(row -> row.getPasskeyApprovalNeeded() == null));
    }

    @Test void organizationOwnerSeesLocalBadgesButAdminAndForeignOwnerDoNot() {
        Organization org = org(), foreign = org();
        cover(currentUser, org);
        orgMembers.addMember(org.getId(), currentUser.getId(), "owner");
        User target = newUser();
        orgMembers.addMember(org.getId(), target.getId(), "admin");
        cover(target, foreign);
        assertEquals(true, orgService.listMembers(org.getId(), currentUser.getId()).stream()
            .filter(row -> row.getId() == target.getId()).findFirst().orElseThrow().getPasskeyApprovalNeeded());
        cover(target, org);
        assertEquals(false, orgService.listMembers(org.getId(), currentUser.getId()).stream()
            .filter(row -> row.getId() == target.getId()).findFirst().orElseThrow().getPasskeyApprovalNeeded());
        assertTrue(orgService.listMembers(org.getId(), target.getId()).stream()
            .allMatch(row -> row.getPasskeyApprovalNeeded() == null));
        orgMembers.addMember(foreign.getId(), target.getId(), "owner");
        assertThrows(ForbiddenException.class, () -> orgService.listMembers(foreign.getId(), currentUser.getId()));
    }

    @Test void workspaceAndOrgListsHaveConstantPhysicalQueryCountsWithManyCustomRoles() {
        Organization org = org();
        Workspace scope = owned(org);
        orgMembers.addMember(org.getId(), currentUser.getId(), "owner");
        User first = member(scope, "admin");
        orgMembers.addMember(org.getId(), first.getId(), "admin");
        assign(scope, first, "member", "MEMBER_MANAGE");
        int workspaceCount = measured(() -> workspaceService.getMembersWithRoles(scope.getId(), currentUser.getId()));
        int orgCount = measured(() -> orgService.listMembers(org.getId(), currentUser.getId()));
        for (int i = 0; i < 20; i++) {
            User target = member(scope, "admin");
            orgMembers.addMember(org.getId(), target.getId(), "admin");
            assign(scope, target, "member", "MEMBER_MANAGE", "ROLE_MANAGE");
        }
        assertTrue(workspaceCount > 0);
        assertTrue(orgCount > 0);
        assertEquals(workspaceCount, measured(() -> workspaceService.getMembersWithRoles(scope.getId(), currentUser.getId())));
        assertEquals(orgCount, measured(() -> orgService.listMembers(org.getId(), currentUser.getId())));
    }

    @Test void orgOnlyPrivilegeMakesAnOrdinaryWorkspaceMemberNeedApprovalHere() {
        Organization org = org();
        Workspace scope = owned(org);
        User target = member(scope, "member");
        orgMembers.addMember(org.getId(), target.getId(), "admin");
        assertEquals(true, badge(workspaceService.getMembersWithRoles(scope.getId(), currentUser.getId()), target));
    }

    @Test void metadataDoesNotDependOnEitherEnforcementSwitch() {
        var properties = new ooo.klae.connex.backend.config.PrivilegedMfaProperties();
        var service = new PrivilegedAccountService(userMapper, properties);
        Organization org = org();
        Workspace scope = owned(org);
        User target = member(scope, "admin");
        for (String enforced : List.of("true", "false")) {
            for (String attestation : List.of("true", "false")) {
                properties.setEnforced(enforced);
                properties.setAttestationEnforced(attestation);
                assertEquals(Set.of(target.getId()), service.passkeyApprovalNeeded(org.getId(), List.of(target.getId())));
                assertEquals(org.getId(), service.privilegedMemberships(target.getId()).getFirst().orgId());
            }
        }
    }

    @Test void foreignWorkspaceMembershipCannotReadMembersOrAuthorityRoles() {
        Organization here = org(), foreign = org();
        Workspace scope = owned(here), other = workspace(foreign);
        User outsider = newUser();
        workspaceMapper.addMember(other.getId(), outsider.getId(), "owner");
        cover(outsider, foreign);
        assertThrows(ForbiddenException.class, () -> workspaceService.getMembersWithRoles(scope.getId(), outsider.getId()));
        WorkspaceRole foreignRole = assign(other, outsider, "owner", "ROLE_MANAGE");
        assertTrue(roles.findAuthorityRolesByWorkspace(scope.getId()).stream().noneMatch(role -> role.getId() == foreignRole.getId()));
    }

    private int measured(Runnable action) {
        sqlSession.clearCache();
        counter.count.set(0);
        counter.active.set(true);
        counter.statements.clear();
        try {
            action.run();
            assertTrue(counter.statements.stream().allMatch(sql -> sql.stripLeading().startsWith("SELECT")));
            assertTrue(counter.statements.stream().noneMatch(sql -> sql.contains("FOR UPDATE") || sql.contains("FOR SHARE")));
            return counter.count.get();
        } finally { counter.active.remove(); }
    }

    private Organization org() {
        Organization org = new Organization();
        org.setName("Metadata " + unique()); org.setSlug("metadata-" + unique()); organizations.insert(org);
        return org;
    }

    private Workspace workspace(Organization org) {
        Workspace scope = new Workspace(); scope.setOrgId(org.getId());
        scope.setName("Metadata " + unique()); scope.setSlug("metadata-" + unique()); workspaceMapper.insert(scope);
        return scope;
    }

    private Workspace owned(Organization org) {
        Workspace scope = workspace(org); workspaceMapper.addMember(scope.getId(), currentUser.getId(), "owner");
        cover(currentUser, org); authenticateAs(currentUser, scope.getId()); return scope;
    }

    private User member(Workspace scope, String role) {
        User user = newUser(); workspaceMapper.addMember(scope.getId(), user.getId(), role);
        OwnershipOfferTestFixtures.passkey(jdbc, user); return user;
    }

    private WorkspaceRole assign(Workspace scope, User user, String builtInRole, String... permissions) {
        WorkspaceRole role = new WorkspaceRole(); role.setWorkspaceId(scope.getId()); role.setName("Role " + unique());
        roles.insertRole(role); roles.insertPermissions(scope.getId(), role.getId(), List.of(permissions));
        jdbc.update("UPDATE workspace_member SET role = ?, role_id = ? WHERE workspace_id = ? AND user_id = ?",
            builtInRole, role.getId(), scope.getId(), user.getId()); return role;
    }

    private void cover(User user, Organization org) {
        int passkey = OwnershipOfferTestFixtures.passkey(jdbc, user);
        jdbc.update("INSERT INTO privileged_credential_attestation (credential_row_id, org_id, source) VALUES (?, ?, 'GRANDFATHERED')",
            passkey, org.getId());
    }

    private Boolean badge(List<MemberDto> members, User user) {
        return members.stream().filter(row -> row.getId() == user.getId()).findFirst().orElseThrow().getPasskeyApprovalNeeded();
    }

    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
    static final class QueryCounter implements Interceptor {
        final ThreadLocal<Boolean> active = new ThreadLocal<>();
        final AtomicInteger count = new AtomicInteger();
        final List<String> statements = new java.util.ArrayList<>();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            if (Boolean.TRUE.equals(active.get())) {
                count.incrementAndGet();
                statements.add(((StatementHandler) invocation.getTarget()).getBoundSql().getSql());
            }
            return invocation.proceed();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CounterConfiguration {
        @Bean QueryCounter approvalMetadataQueryCounter() { return new QueryCounter(); }
    }
}
