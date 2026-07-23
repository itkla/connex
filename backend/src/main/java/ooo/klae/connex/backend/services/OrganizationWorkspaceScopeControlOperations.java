package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/** Loads organization workspace scopes in one control-catalog snapshot. */
@Component
@RequiredArgsConstructor
public class OrganizationWorkspaceScopeControlOperations {
    private final WorkspaceMapper workspaceMapper;

    /**
     * Resolves the complete organization workspace scope for one workspace.
     *
     * @param workspaceId workspace anchoring the organization
     * @return sorted workspace IDs and their JSON representation
     */
    @Transactional(readOnly = true)
    public WorkspaceScope getForWorkspace(int workspaceId) {
        Integer orgId = workspaceMapper.getOrgId(workspaceId);
        if (orgId == null) {
            throw new IllegalStateException("Workspace " + workspaceId + " does not exist");
        }
        List<Integer> workspaceIds = workspaceMapper.findByOrgId(orgId).stream()
            .map(Workspace::getId)
            .sorted()
            .toList();
        if (!workspaceIds.contains(workspaceId)) {
            throw new IllegalStateException("Workspace " + workspaceId + " is missing from organization " + orgId);
        }
        String workspaceIdsJson = workspaceIds.stream()
            .map(String::valueOf)
            .collect(Collectors.joining(",", "[", "]"));
        return new WorkspaceScope(orgId, workspaceIds, workspaceIdsJson);
    }

    /** Complete control-derived workspace scope for one organization. */
    public record WorkspaceScope(int orgId, List<Integer> workspaceIds, String workspaceIdsJson) {
        public WorkspaceScope {
            workspaceIds = List.copyOf(workspaceIds);
        }
    }
}
