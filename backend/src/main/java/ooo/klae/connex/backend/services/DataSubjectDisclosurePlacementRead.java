package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantWorkspaceScopeSnapshot;

/** Releases placement-discovery sessions and connections before opening a disclosure snapshot. */
@Component
public class DataSubjectDisclosurePlacementRead {
    private final WorkspaceMapper workspaceMapper;
    private final TenantCatalogResolver catalogResolver;
    private final TenantWorkspaceScopeControlOperations scopeControlOperations;

    /** Creates the short control preparation boundary. */
    @Autowired
    public DataSubjectDisclosurePlacementRead(WorkspaceMapper workspaceMapper,
            TenantCatalogResolver catalogResolver, TenantWorkspaceScopeControlOperations scopeControlOperations) {
        this.workspaceMapper = workspaceMapper;
        this.catalogResolver = catalogResolver;
        this.scopeControlOperations = scopeControlOperations;
    }

    /** Creates control preparation for focused single-database tests. */
    public DataSubjectDisclosurePlacementRead(WorkspaceMapper workspaceMapper, TenantCatalogResolver catalogResolver) {
        this(workspaceMapper, catalogResolver,
            new TenantWorkspaceScopeControlOperations(workspaceMapper, catalogResolver));
    }

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
        String catalog = catalogResolver.resolveCatalog(orgId);
        TenantWorkspaceScopeSnapshot scopes = catalogResolver.isCatalogPerPlacement()
            ? scopeControlOperations.load(workspaceId, orgId, catalog, null)
            : null;
        return new Placement(orgId, catalog, scopes);
    }

    /** Immutable route only; no session, connection, or callback escapes the control read. */
    public record Placement(int orgId, String catalog, TenantWorkspaceScopeSnapshot scopes) {
        public Placement(int orgId, String catalog) {
            this(orgId, catalog, null);
        }
    }
}
