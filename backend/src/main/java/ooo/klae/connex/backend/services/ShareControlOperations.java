package ooo.klae.connex.backend.services;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.Permission;

/** Executes sharing authorization, workspace metadata, and audit work on the control catalog. */
@Component
@RequiredArgsConstructor
public class ShareControlOperations {
    private final WorkspaceService workspaceService;
    private final WorkspaceMapper workspaceMapper;
    private final AuditService auditService;

    /**
     * Authorizes share management and resolves the owning organization.
     *
     * @return immutable actor, workspace, and organization scope
     */
    @Transactional(readOnly = true)
    public ShareAccess requireAccess() {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        workspaceService.requirePermission(workspaceId, actorId, Permission.SHARE_MANAGE);
        return new ShareAccess(workspaceId, workspaceService.getOrgId(workspaceId), actorId);
    }

    /**
     * Authorizes a share listing and captures its organization workspace snapshot.
     *
     * @return immutable access scope and organization workspace snapshot
     */
    @Transactional(readOnly = true)
    public ShareListControl prepareList() {
        ShareAccess access = requireAccess();
        return new ShareListControl(
            access, workspaceSnapshot(access.workspaceId(), access.orgId()));
    }

    /**
     * Validates target membership and same-organization scope against one immutable snapshot.
     *
     * @param workspaceId owning workspace
     * @param orgId owning organization
     * @param targetWorkspaceId target workspace
     * @param actorId acting user
     * @return immutable organization workspace snapshot
     */
    @Transactional(readOnly = true)
    public WorkspaceSnapshot prepareTarget(
            int workspaceId, int orgId, int targetWorkspaceId, int actorId) {
        workspaceService.requireMember(targetWorkspaceId, actorId);
        WorkspaceSnapshot snapshot = workspaceSnapshot(workspaceId, orgId);
        if (!snapshot.names().containsKey(targetWorkspaceId)) {
            throw new ForbiddenException("A record cannot be shared across organizations");
        }
        return snapshot;
    }

    /** Records a successful share with explicit control-plane scope. */
    @Transactional
    public void recordShare(
            String entityType, int entityId, int workspaceId, int orgId, int targetWorkspaceId) {
        auditService.recordScoped("workspace.share", entityType, entityId, workspaceId, orgId, null,
            "Shared with workspace " + targetWorkspaceId, null);
    }

    /** Records a successful unshare with explicit control-plane scope. */
    @Transactional
    public void recordUnshare(
            String entityType, int entityId, int workspaceId, int orgId, int targetWorkspaceId) {
        auditService.recordScoped("workspace.unshare", entityType, entityId, workspaceId, orgId, null,
            "Stopped sharing with workspace " + targetWorkspaceId, null);
    }

    private WorkspaceSnapshot workspaceSnapshot(int workspaceId, int orgId) {
        List<Workspace> workspaces = workspaceMapper.findByOrgId(orgId);
        List<Integer> ids = workspaces.stream().map(Workspace::getId).toList();
        Map<Integer, String> names = new LinkedHashMap<>();
        for (Workspace workspace : workspaces) {
            names.put(workspace.getId(), workspace.getName());
        }
        if (!names.containsKey(workspaceId)) {
            throw new ResourceNotFoundException("Workspace not found: " + workspaceId);
        }
        return new WorkspaceSnapshot(ids, names);
    }

    public record WorkspaceSnapshot(List<Integer> ids, Map<Integer, String> names) {
        public WorkspaceSnapshot {
            ids = List.copyOf(ids);
            names = Map.copyOf(names);
        }
    }

    public record ShareAccess(int workspaceId, int orgId, int actorId) {}

    public record ShareListControl(ShareAccess access, WorkspaceSnapshot snapshot) {}
}
