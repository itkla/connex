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
    private final DataSubjectDisclosurePlacementRead placementRead;

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

    public <T> T withSnapshot(int orgId, int actorId, int workspaceId, int personId,
            List<Integer> workspaceIds, Function<DataSubjectDisclosureDto, T> work) {
        var placement = tenantWorkScope.unroutedRead(() -> placementRead.resolve(orgId, workspaceId));
        return tenantWorkScope.withPreparedWorkspace(workspaceId, placement.orgId(), placement.catalog(), placement.scopes(),
            () -> withTenantContext(workspaceId, placement.orgId(), actorId, placement.catalog(),
                () -> readTransaction.withSnapshot(workspaceId, personId, workspaceIds, work)));
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
        TenantContext.State previousTenant = tenantContext.snapshot();
        tenantContext.set(workspaceId, orgId, actorId, "org_admin", catalog);
        try {
            return work.get();
        } finally {
            tenantContext.restore(previousTenant);
        }
    }

    private static ConflictException changedSubjectLink() {
        return new ConflictException(
            "The subject workspace changed before the data-subject request could be recorded");
    }
}
