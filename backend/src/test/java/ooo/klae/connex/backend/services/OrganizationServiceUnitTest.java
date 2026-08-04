package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.OrganizationIdentityDto;
import ooo.klae.connex.backend.dto.OrganizationLayoutAuthorityMemberDto;
import ooo.klae.connex.backend.dto.OrganizationLayoutDto;
import ooo.klae.connex.backend.dto.OrganizationLayoutWorkspaceMemberDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/** Lock-order, pagination, and non-disclosure contract for organization settings. */
@ExtendWith(MockitoExtension.class)
class OrganizationServiceUnitTest {
    private static final int ORG_ID = 3;
    private static final int ACTOR_ID = 7;

    @Mock private OrganizationMapper organizationMapper;
    @Mock private OrgMemberMapper orgMemberMapper;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private UserMapper userMapper;
    @Mock private OrgMemberService orgMemberService;
    @Mock private SessionSecurityService sessionSecurityService;
    @Mock private AuditService auditService;

    @InjectMocks private OrganizationService service;

    @Test
    void renameLocksActorOrganizationAndMembershipBeforeWriteAndScopedAudit() {
        Organization before = organization("Old Org");
        Organization after = organization("New Org");
        after.setIdentityVersion(1L);
        when(userMapper.lockByIdForShare(ACTOR_ID)).thenReturn(ACTOR_ID);
        when(organizationMapper.lockActiveIdentity(ORG_ID)).thenReturn(before);
        when(organizationMapper.updateName(ORG_ID, "New Org")).thenReturn(1);
        when(organizationMapper.getActiveById(ORG_ID)).thenReturn(after);

        OrganizationIdentityDto result = service.rename(
            ORG_ID, ACTOR_ID, " New Org ", " Old Org ", 0L);

        assertEquals("New Org", result.name());
        assertEquals("immutable", result.slug());
        assertEquals(1L, result.identityVersion());
        InOrder order = inOrder(
            orgMemberService,
            sessionSecurityService,
            userMapper,
            organizationMapper,
            auditService);
        order.verify(orgMemberService).requireOrgAdmin(ORG_ID, ACTOR_ID);
        order.verify(sessionSecurityService).requireRecentAuthentication(ACTOR_ID);
        order.verify(userMapper).lockByIdForShare(ACTOR_ID);
        order.verify(organizationMapper).lockActiveIdentity(ORG_ID);
        order.verify(orgMemberService).requireOrgAdminForUpdate(ORG_ID, ACTOR_ID);
        order.verify(organizationMapper).updateName(ORG_ID, "New Org");
        order.verify(auditService).singleChange("name", "Old Org", "New Org");
        order.verify(auditService).recordScoped(
            eq("org.rename"),
            eq("organization"),
            eq(ORG_ID),
            eq(null),
            eq(ORG_ID),
            eq("New Org"),
            eq("Renamed organization"),
            any());
        order.verify(organizationMapper).getActiveById(ORG_ID);
    }

    @Test
    void renameRefusesWhenLockedOrgAuthorityWasRevoked() {
        when(userMapper.lockByIdForShare(ACTOR_ID)).thenReturn(ACTOR_ID);
        when(organizationMapper.lockActiveIdentity(ORG_ID))
            .thenReturn(organization("Old Org"));
        org.mockito.Mockito.doThrow(new ForbiddenException("revoked"))
            .when(orgMemberService).requireOrgAdminForUpdate(ORG_ID, ACTOR_ID);

        assertThrows(ForbiddenException.class, () -> service.rename(
            ORG_ID, ACTOR_ID, "New Org", "Old Org", 0L));

        verify(organizationMapper, never()).updateName(any(Integer.class), any());
        verify(auditService, never()).recordScoped(
            any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void renameRejectsAStaleExpectedNameAfterLocking() {
        when(userMapper.lockByIdForShare(ACTOR_ID)).thenReturn(ACTOR_ID);
        when(organizationMapper.lockActiveIdentity(ORG_ID))
            .thenReturn(organization("Newer Org"));

        assertThrows(ConflictException.class, () -> service.rename(
            ORG_ID, ACTOR_ID, "My Rename", "Old Org", 0L));

        verify(orgMemberService).requireOrgAdminForUpdate(ORG_ID, ACTOR_ID);
        verify(organizationMapper, never()).updateName(any(Integer.class), any());
        verify(auditService, never()).recordScoped(
            any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void renameRejectsAStaleIdentityVersionEvenWhenTheNameMatches() {
        Organization before = organization("Current Org");
        before.setIdentityVersion(2L);
        when(userMapper.lockByIdForShare(ACTOR_ID)).thenReturn(ACTOR_ID);
        when(organizationMapper.lockActiveIdentity(ORG_ID)).thenReturn(before);

        assertThrows(ConflictException.class, () -> service.rename(
            ORG_ID, ACTOR_ID, "My Rename", "Current Org", 1L));

        verify(orgMemberService).requireOrgAdminForUpdate(ORG_ID, ACTOR_ID);
        verify(organizationMapper, never()).updateName(any(Integer.class), any());
    }

    @Test
    void layoutUsesFixedQuerySetAndRepresentsRestrictedRostersWithoutMemberMetadata() {
        Workspace visible = workspace(11, "Visible");
        Workspace restricted = workspace(12, "Restricted");
        OrganizationLayoutAuthorityMemberDto authority = authority(ACTOR_ID);
        OrganizationLayoutWorkspaceMemberDto membership = membership(11, ACTOR_ID);
        when(organizationMapper.getActiveById(ORG_ID)).thenReturn(organization("Org"));
        when(workspaceMapper.findActiveByOrgIdPage(ORG_ID, 0, 51))
            .thenReturn(List.of(visible, restricted));
        when(orgMemberMapper.findLayoutAuthorityMemberships(ORG_ID, 0, 51))
            .thenReturn(List.of(authority));
        when(workspaceMapper.findLayoutMemberships(
            ORG_ID, ACTOR_ID, List.of(11, 12), 100))
            .thenReturn(List.of(membership));

        OrganizationLayoutDto result = service.getLayout(ORG_ID, ACTOR_ID, 0, 0, 50);

        assertEquals(2, result.workspaces().size());
        assertTrue(result.workspaces().getFirst().rosterVisible());
        assertEquals(ACTOR_ID,
            result.workspaces().getFirst().memberships().getFirst().getUserId());
        assertFalse(result.workspaces().get(1).rosterVisible());
        assertTrue(result.workspaces().get(1).memberships().isEmpty());
        assertFalse(result.workspaces().get(1).membershipsTruncated());
        assertNull(result.nextWorkspaceId());
        verify(workspaceMapper).findLayoutMemberships(
            ORG_ID, ACTOR_ID, List.of(11, 12), 100);
        verify(workspaceMapper, never()).getMembersWithRoles(any(Integer.class));
    }

    @Test
    void layoutReturnsIndependentProgressiveCursorsAtTheBound() {
        when(organizationMapper.getActiveById(ORG_ID)).thenReturn(organization("Org"));
        when(workspaceMapper.findActiveByOrgIdPage(ORG_ID, 0, 3))
            .thenReturn(List.of(workspace(11, "One"), workspace(12, "Two"), workspace(13, "Three")));
        when(orgMemberMapper.findLayoutAuthorityMemberships(ORG_ID, 0, 3))
            .thenReturn(List.of(authority(21), authority(22), authority(23)));
        when(workspaceMapper.findLayoutMemberships(
            ORG_ID, ACTOR_ID, List.of(11, 12), 100)).thenReturn(List.of());

        OrganizationLayoutDto result = service.getLayout(ORG_ID, ACTOR_ID, 0, 0, 2);

        assertEquals(2, result.workspaces().size());
        assertEquals(12, result.nextWorkspaceId());
        assertEquals(2, result.authorityMemberships().size());
        assertEquals(22, result.nextAuthorityMemberId());
    }

    @Test
    void layoutRejectsInvalidCursorsAndLimitsAfterAuthorization() {
        assertThrows(BadRequestException.class, () -> service.getLayout(
            ORG_ID, ACTOR_ID, -1, 0, 50));
        assertThrows(BadRequestException.class, () -> service.getLayout(
            ORG_ID, ACTOR_ID, 0, -1, 50));
        assertThrows(BadRequestException.class, () -> service.getLayout(
            ORG_ID, ACTOR_ID, 0, 0, 0));
        assertThrows(BadRequestException.class, () -> service.getLayout(
            ORG_ID, ACTOR_ID, 0, 0, 101));
        verify(orgMemberService, org.mockito.Mockito.times(4))
            .requireOrgAdmin(ORG_ID, ACTOR_ID);
    }

    private static Organization organization(String name) {
        Organization organization = new Organization();
        organization.setId(ORG_ID);
        organization.setName(name);
        organization.setSlug("immutable");
        organization.setUpdatedAt("2026-08-03 12:00:00");
        return organization;
    }

    private static Workspace workspace(int id, String name) {
        Workspace workspace = new Workspace();
        workspace.setId(id);
        workspace.setOrgId(ORG_ID);
        workspace.setName(name);
        workspace.setSlug("workspace-" + id);
        return workspace;
    }

    private static OrganizationLayoutAuthorityMemberDto authority(int userId) {
        OrganizationLayoutAuthorityMemberDto member = new OrganizationLayoutAuthorityMemberDto();
        member.setUserId(userId);
        member.setDisplayName("Authority " + userId);
        member.setOrgRole("admin");
        return member;
    }

    private static OrganizationLayoutWorkspaceMemberDto membership(int workspaceId, int userId) {
        OrganizationLayoutWorkspaceMemberDto member = new OrganizationLayoutWorkspaceMemberDto();
        member.setWorkspaceId(workspaceId);
        member.setUserId(userId);
        member.setDisplayName("Member " + userId);
        member.setRole("owner");
        member.setStatus("active");
        return member;
    }
}
