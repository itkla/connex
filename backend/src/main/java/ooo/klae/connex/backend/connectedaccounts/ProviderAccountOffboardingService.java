package ooo.klae.connex.backend.connectedaccounts;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Pre-transaction account-erasure orchestration for every provider generation.
 */
@Service
@RequiredArgsConstructor
public class ProviderAccountOffboardingService {
    private final ProviderConnectionMapper connectionMapper;
    private final ProviderConnectionLifecycleService lifecycleService;
    private final ProviderConnectionMutation connectionMutation;
    private final TenantWorkScope tenantWorkScope;

    /** Completes every provider purge before the control user row can cascade away. */
    public void purgeBeforeAccountDeletion(int userId) {
        List<ProviderConnection> connections = tenantWorkScope.unrouted(
            () -> connectionMapper.getByUserId(userId));
        List<ProviderConnection> pending = connections.stream()
            .map(connection -> tenantWorkScope.unrouted(
                () -> connectionMutation.beginDisconnect(
                    userId, connection.getProvider())))
            .toList();
        for (ProviderConnection connection : pending) {
            if (connection != null && !lifecycleService.process(connection)) {
                throw new ConflictException(
                    "Provider-captured data purge is incomplete; retry account deletion");
            }
        }
    }
}
