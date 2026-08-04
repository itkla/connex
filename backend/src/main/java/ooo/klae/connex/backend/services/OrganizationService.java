package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.OrganizationIdentityDto;
import ooo.klae.connex.backend.dto.OrganizationLayoutAuthorityMemberDto;
import ooo.klae.connex.backend.dto.OrganizationLayoutDto;
import ooo.klae.connex.backend.dto.OrganizationLayoutWorkspaceDto;
import ooo.klae.connex.backend.dto.OrganizationLayoutWorkspaceMemberDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/** Locked organization identity mutations and bounded organization-layout projection. */
@Service
@RequiredArgsConstructor
public class OrganizationService {
    private static final int NAME_MAX = 128;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int WORKSPACE_MEMBER_LIMIT = 100;

    private final OrganizationMapper organizationMapper;
    private final OrgMemberMapper orgMemberMapper;
    private final WorkspaceMapper workspaceMapper;
    private final UserMapper userMapper;
    private final OrgMemberService orgMemberService;
    private final SessionSecurityService sessionSecurityService;
    private final AuditService auditService;

    /**
     * Renames an organization without changing its id or slug. Current org-admin authority is
     * revalidated after locking the actor and organization roots.
     *
     * @param orgId organization to rename
     * @param actorId authenticated actor
     * @param nameRaw required display name
     * @param expectedNameRaw display name observed before editing
     * @param expectedIdentityVersion identity version observed before editing
     * @return canonical persisted organization identity
     */
    @Transactional
    public OrganizationIdentityDto rename(
            int orgId,
            int actorId,
            String nameRaw,
            String expectedNameRaw,
            long expectedIdentityVersion) {
        orgMemberService.requireOrgAdmin(orgId, actorId);
        sessionSecurityService.requireRecentAuthentication(actorId);
        String name = normalizeName(nameRaw);
        String expectedName = normalizeName(expectedNameRaw);
        if (userMapper.lockByIdForShare(actorId) == null) {
            throw orgAdminRequired();
        }
        Organization before = organizationMapper.lockActiveIdentity(orgId);
        if (before == null) {
            throw orgAdminRequired();
        }
        orgMemberService.requireOrgAdminForUpdate(orgId, actorId);
        if (before.getIdentityVersion() != expectedIdentityVersion
                || !Objects.equals(before.getName(), expectedName)) {
            throw new ConflictException("Organization settings changed; refresh and retry");
        }
        if (!Objects.equals(before.getName(), name)) {
            if (organizationMapper.updateName(orgId, name) == 0) {
                throw orgAdminRequired();
            }
            auditService.recordScoped(
                "org.rename",
                "organization",
                orgId,
                null,
                orgId,
                name,
                "Renamed organization",
                auditService.singleChange("name", before.getName(), name));
        }
        Organization updated = organizationMapper.getActiveById(orgId);
        if (updated == null) {
            throw orgAdminRequired();
        }
        return identity(updated);
    }

    /**
     * Returns one bounded organization-layout page. Organization authority is required for the
     * organization projection, while each workspace roster is disclosed only when the actor is an
     * active member of that exact workspace.
     *
     * @param orgId organization to project
     * @param actorId authenticated actor
     * @param afterWorkspaceId exclusive workspace-id cursor
     * @param afterAuthorityMemberId exclusive authority-member user-id cursor
     * @param requestedLimit page size for workspace and authority nodes
     * @return authorized organization layout page
     */
    @Transactional(readOnly = true)
    public OrganizationLayoutDto getLayout(
            int orgId,
            int actorId,
            int afterWorkspaceId,
            int afterAuthorityMemberId,
            int requestedLimit) {
        orgMemberService.requireOrgAdmin(orgId, actorId);
        int limit = validatePage(afterWorkspaceId, afterAuthorityMemberId, requestedLimit);
        Organization organization = organizationMapper.getActiveById(orgId);
        if (organization == null) {
            throw orgAdminRequired();
        }

        List<Workspace> workspacePage = workspaceMapper.findActiveByOrgIdPage(
            orgId, afterWorkspaceId, limit + 1);
        boolean workspacesTruncated = workspacePage.size() > limit;
        List<Workspace> workspaces = workspacesTruncated
            ? workspacePage.subList(0, limit)
            : workspacePage;
        Integer nextWorkspaceId = workspacesTruncated
            ? workspaces.getLast().getId()
            : null;

        List<OrganizationLayoutAuthorityMemberDto> authorityPage =
            orgMemberMapper.findLayoutAuthorityMemberships(
                orgId, afterAuthorityMemberId, limit + 1);
        boolean authorityTruncated = authorityPage.size() > limit;
        List<OrganizationLayoutAuthorityMemberDto> authorityMemberships = authorityTruncated
            ? authorityPage.subList(0, limit)
            : authorityPage;
        Integer nextAuthorityMemberId = authorityTruncated
            ? authorityMemberships.getLast().getUserId()
            : null;

        List<Integer> workspaceIds = workspaces.stream().map(Workspace::getId).toList();
        List<OrganizationLayoutWorkspaceMemberDto> visibleMemberships = workspaceIds.isEmpty()
            ? List.of()
            : workspaceMapper.findLayoutMemberships(
                orgId, actorId, workspaceIds, WORKSPACE_MEMBER_LIMIT);
        Map<Integer, List<OrganizationLayoutWorkspaceMemberDto>> membershipsByWorkspace =
            new LinkedHashMap<>();
        Map<Integer, Boolean> truncatedByWorkspace = new LinkedHashMap<>();
        for (OrganizationLayoutWorkspaceMemberDto membership : visibleMemberships) {
            membershipsByWorkspace
                .computeIfAbsent(membership.getWorkspaceId(), ignored -> new ArrayList<>())
                .add(membership);
            truncatedByWorkspace.merge(
                membership.getWorkspaceId(), membership.isRosterTruncated(), Boolean::logicalOr);
        }

        List<OrganizationLayoutWorkspaceDto> workspaceDtos = workspaces.stream()
            .map(workspace -> workspaceLayout(
                workspace,
                membershipsByWorkspace.get(workspace.getId()),
                truncatedByWorkspace.getOrDefault(workspace.getId(), false)))
            .toList();
        return new OrganizationLayoutDto(
            identity(organization),
            authorityMemberships,
            nextAuthorityMemberId,
            workspaceDtos,
            nextWorkspaceId);
    }

    private static OrganizationLayoutWorkspaceDto workspaceLayout(
            Workspace workspace,
            List<OrganizationLayoutWorkspaceMemberDto> memberships,
            boolean membershipsTruncated) {
        boolean rosterVisible = memberships != null;
        return new OrganizationLayoutWorkspaceDto(
            workspace.getId(),
            workspace.getName(),
            workspace.getSlug(),
            workspace.getTimezone(),
            rosterVisible,
            rosterVisible ? memberships : List.of(),
            rosterVisible && membershipsTruncated);
    }

    private static int validatePage(
            int afterWorkspaceId, int afterAuthorityMemberId, int requestedLimit) {
        if (afterWorkspaceId < 0 || afterAuthorityMemberId < 0) {
            throw new BadRequestException("Organization layout cursors must be non-negative");
        }
        if (requestedLimit < 1 || requestedLimit > MAX_PAGE_SIZE) {
            throw new BadRequestException("Organization layout limit must be between 1 and 100");
        }
        return requestedLimit;
    }

    private static String normalizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("Organization name is required");
        }
        String name = raw.trim();
        if (name.length() > NAME_MAX) {
            throw new BadRequestException("Organization name must be 128 characters or fewer");
        }
        return name;
    }

    private static OrganizationIdentityDto identity(Organization organization) {
        return new OrganizationIdentityDto(
            organization.getId(),
            organization.getName(),
            organization.getSlug(),
            organization.getIdentityVersion(),
            organization.getUpdatedAt());
    }

    private static ForbiddenException orgAdminRequired() {
        return new ForbiddenException("Requires an organization administrator role");
    }
}
