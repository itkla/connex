package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Routes subject-person validation and disclosure reads to the subject workspace's tenant catalog. */
@Component
@RequiredArgsConstructor
public class DataSubjectDisclosureAccess {
    private final TenantWorkScope tenantWorkScope;
    private final TenantContext tenantContext;
    private final DataSubjectDisclosureReadTransaction readTransaction;

    public boolean subjectPersonExists(int orgId, int actorId, int workspaceId, int personId) {
        try {
            return inSubjectWorkspace(orgId, actorId, workspaceId,
                () -> readTransaction.subjectPersonExists(workspaceId, personId));
        } catch (ResourceNotFoundException exception) {
            return false;
        }
    }

    public <T> T withLockedSubjectPerson(
            int orgId,
            int actorId,
            int workspaceId,
            int personId,
            Function<Supplier<T>, T> controlTransaction,
            Supplier<T> work) {
        AtomicBoolean routeResolved = new AtomicBoolean();
        try {
            return tenantWorkScope.withWorkspacePlacement(
                workspaceId,
                (resolvedOrgId, catalog) -> {
                    routeResolved.set(true);
                    if (resolvedOrgId != orgId) {
                        throw changedSubjectLink();
                    }
                    return withTenantContext(
                        workspaceId,
                        resolvedOrgId,
                        actorId,
                        catalog,
                        () -> readTransaction.withLockedSubjectPerson(
                            workspaceId,
                            personId,
                            controlTransaction,
                            work));
                });
        } catch (IllegalStateException exception) {
            if (!routeResolved.get()) {
                throw changedSubjectLink();
            }
            throw exception;
        }
    }

    public DataSubjectDisclosureDto assemble(int orgId, int actorId, int workspaceId, int personId,
            List<Integer> workspaceIds) {
        return inSubjectWorkspace(orgId, actorId, workspaceId,
            () -> readTransaction.assemble(workspaceId, personId, workspaceIds));
    }

    private <T> T inSubjectWorkspace(int expectedOrgId, int actorId, int workspaceId, Supplier<T> work) {
        return tenantWorkScope.withWorkspacePlacement(workspaceId, (resolvedOrgId, catalog) -> {
            if (resolvedOrgId != expectedOrgId) {
                throw new ResourceNotFoundException("Linked subject workspace not found: " + workspaceId);
            }
            return withTenantContext(workspaceId, resolvedOrgId, actorId, catalog, work);
        });
    }

    private <T> T withTenantContext(int workspaceId, int orgId, int actorId, String catalog, Supplier<T> work) {
        boolean hadTenant = tenantContext.isResolved();
        Integer previousWorkspace = hadTenant ? tenantContext.getWorkspaceId() : null;
        Integer previousOrg = hadTenant ? tenantContext.getOrgId() : null;
        Integer previousUser = hadTenant ? tenantContext.getUserId() : null;
        String previousRole = hadTenant ? tenantContext.getRole() : null;
        String previousCatalog = hadTenant ? tenantContext.getScopeCatalog() : null;
        tenantContext.set(workspaceId, orgId, actorId, "org_admin", catalog);
        try {
            return work.get();
        } finally {
            if (hadTenant) {
                tenantContext.set(previousWorkspace, previousOrg, previousUser, previousRole, previousCatalog);
            } else {
                tenantContext.clear();
            }
        }
    }

    private static ConflictException changedSubjectLink() {
        return new ConflictException(
            "The subject workspace changed before the data-subject request could be recorded");
    }
}
