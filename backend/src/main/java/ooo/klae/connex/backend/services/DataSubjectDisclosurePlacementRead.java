package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;

/** Releases placement-discovery sessions and connections before opening a disclosure snapshot. */
@Component
@RequiredArgsConstructor
public class DataSubjectDisclosurePlacementRead {
    private final WorkspaceMapper workspaceMapper;
    private final TenantCatalogResolver catalogResolver;

    /** Runs through a separate control-read proxy, including when the caller has empty synchronization. */
    @Transactional(readOnly = true)
    public Placement resolve(int expectedOrgId, int workspaceId) {
        Integer orgId = workspaceMapper.getOrgId(workspaceId);
        if (orgId == null) {
            throw new IllegalStateException("Workspace " + workspaceId + " does not exist");
        }
        if (orgId != expectedOrgId) {
            throw new ResourceNotFoundException("Linked subject workspace not found: " + workspaceId);
        }
        return new Placement(orgId, catalogResolver.resolveCatalog(orgId));
    }

    /** Immutable route only; no session, connection, or callback escapes the control read. */
    public record Placement(int orgId, String catalog) {}
}
