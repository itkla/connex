package ooo.klae.connex.backend.services;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope;
import ooo.klae.connex.backend.services.ShareWorkspaceControlAccess.OrganizationWorkspaces;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantWorkspaceScopeSnapshot;

/**
 * Releases control-read resources before workspace transactions in both modes, including under empty transaction
 * synchronization. Each prepared kind uses one existing control statement; record visibility shares
 * the unfiltered sharing statement. No routing scope or thread state is installed by these reads.
 */
@Component
public class TenantWorkspaceScopeControlOperations {
    private final WorkspaceMapper workspaceMapper;
    private final Function<Integer, Integer> lifecycleOrgResolver;
    private final TenantCatalogResolver catalogResolver;

    /** Creates preparation with the trusted lifecycle resolver used by cleanup. */
    @Autowired
    public TenantWorkspaceScopeControlOperations(WorkspaceMapper workspaceMapper,
            TenantLifecycleControlMapper lifecycleMapper, TenantCatalogResolver catalogResolver) {
        this.workspaceMapper = workspaceMapper;
        this.lifecycleOrgResolver = lifecycleMapper::findWorkspaceOrgIdForLifecycle;
        this.catalogResolver = catalogResolver;
    }

    /** Creates ordinary-route preparation for focused tests and already validated control reads. */
    public TenantWorkspaceScopeControlOperations(WorkspaceMapper workspaceMapper, TenantCatalogResolver catalogResolver) {
        this.workspaceMapper = workspaceMapper;
        this.lifecycleOrgResolver = workspaceMapper::getOrgId;
        this.catalogResolver = catalogResolver;
    }

    /** Resolves placement and both scope kinds in one short control transaction. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TenantWorkspaceScopeSnapshot forWorkspace(
            int workspaceId, boolean lifecycle, WorkspaceScope knownActiveScope) {
        Integer orgId = lifecycle
            ? lifecycleOrgResolver.apply(workspaceId)
            : workspaceMapper.getOrgId(workspaceId);
        if (orgId == null) {
            throw new IllegalStateException("Workspace " + workspaceId + " does not exist");
        }
        return load(workspaceId, orgId, catalogResolver.resolveCatalog(orgId), knownActiveScope);
    }

    /** Captures a trusted lifecycle identity even after its workspace root has been removed. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TenantWorkspaceScopeSnapshot forLifecycleWorkspace(int workspaceId, int orgId) {
        return load(workspaceId, orgId, catalogResolver.resolveCatalog(orgId), null);
    }

    /** Loads scopes for an already validated route without resolving its placement again. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TenantWorkspaceScopeSnapshot load(
            int workspaceId, int orgId, String catalog, WorkspaceScope knownActiveScope) {
        LinkedHashMap<Integer, String> orderedNames = new LinkedHashMap<>();
        for (Workspace row : workspaceMapper.findOrganizationWorkspacesForShare(workspaceId)) {
            if (row.getOrgId() != orgId) {
                throw new IllegalStateException("Workspace scope does not match the resolved tenant context");
            }
            orderedNames.putIfAbsent(row.getId(), row.getName());
        }
        Optional<OrganizationWorkspaces> sharing = orderedNames.containsKey(workspaceId)
            ? Optional.of(new OrganizationWorkspaces(orgId, orderedNames))
            : Optional.empty();
        WorkspaceScope active = knownActiveScope;
        if (active == null) {
            List<Integer> ids = workspaceMapper.findByOrgId(orgId).stream()
                .map(Workspace::getId).sorted().toList();
            active = new WorkspaceScope(orgId, ids,
                ids.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]")));
        }
        return new TenantWorkspaceScopeSnapshot(workspaceId, orgId, catalog, sharing, active);
    }
}
