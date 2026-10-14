package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.OwnershipOffer;
import ooo.klae.connex.backend.mappers.OwnershipOfferMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import ooo.klae.connex.backend.services.TenantLifecycleAccess.Route;

/** Account reservations fence new references while existing offers are erased one catalog at a time. */
@Service
@RequiredArgsConstructor
public class OwnershipOfferAccountCleanupService {
    private final OwnershipOfferMapper mapper;
    private final TenantLifecycleControlMapper controlMapper;
    private final TenantWorkScope workScope;
    private final TenantLifecycleAccess lifecycleAccess;
    private final OwnershipOfferAccountCleanupTransaction transaction;

    /**
     * Erases offers only after all guarded tenant catalog erasures succeed. The account reservation
     * fences new references throughout route discovery, closure and notice resolution.
     */
    public void eraseAfterCatalogs(int userId) {
        List<OwnershipOffer> discovered = workScope.unroutedRead(() -> mapper.discoverAccount(userId));
        Map<Route, List<OwnershipOffer>> groups = new LinkedHashMap<>();
        List<OwnershipOffer> control = new ArrayList<>();
        for (OwnershipOffer offer : discovered) {
            Integer workspaceId = offer.getDeliveryWorkspaceId();
            if (workspaceId == null) {
                control.add(offer);
            } else {
                var workspace = workScope.unroutedRead(() -> controlMapper.findWorkspaceOrCleanupInOrg(offer.getOrgId(), workspaceId));
                if (workspace == null) {
                    control.add(offer);
                } else {
                    Route route = lifecycleAccess.capture(workspace, offer.getOrgId());
                    groups.computeIfAbsent(route, ignored -> new ArrayList<>()).add(offer);
                }
            }
        }
        if (!control.isEmpty()) {
            workScope.unrouted(() -> { transaction.erase(userId, control); return true; });
        }
        for (Map.Entry<Route, List<OwnershipOffer>> group : groups.entrySet()) {
            lifecycleAccess.withRoute(group.getKey(), userId,
                () -> { transaction.erase(userId, group.getValue()); return true; });
        }
    }
}
