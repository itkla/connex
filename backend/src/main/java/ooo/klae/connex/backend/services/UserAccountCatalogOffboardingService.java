package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.connectedaccounts.ConnectedAccountProviders;
import ooo.klae.connex.backend.connectedaccounts.capture.ProviderCapturePurgeService;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Routes account-deletion guards and erasure through every distinct tenant catalog.
 */
@Service
@RequiredArgsConstructor
public class UserAccountCatalogOffboardingService {
    private final PlacementRegistry placementRegistry;
    private final UserOffboardingService userOffboardingService;
    private final ProviderCapturePurgeService providerCapturePurgeService;
    private final TenantWorkScope tenantWorkScope;
    private final PlatformTransactionManager transactionManager;

    /** Refuses account deletion if any tenant catalog retains authored content. */
    public void assertNoAuthoredContent(int userId) {
        for (String catalog : placementRegistry.activeCatalogs()) {
            tenantWorkScope.withCatalog(
                catalog,
                () -> {
                    new TransactionTemplate(transactionManager).executeWithoutResult(
                        status -> userOffboardingService.assertNoAuthoredContent(userId));
                    return null;
                });
        }
    }

    /** Erases user references once per distinct tenant catalog. */
    public void eraseReferences(int userId) {
        for (String catalog : placementRegistry.activeCatalogs()) {
            tenantWorkScope.withCatalog(
                catalog,
                () -> {
                    new TransactionTemplate(transactionManager).executeWithoutResult(
                        status -> {
                            providerCapturePurgeService.purgeAccountCatalog(
                                userId, ConnectedAccountProviders.GOOGLE);
                            providerCapturePurgeService.purgeAccountCatalog(
                                userId, ConnectedAccountProviders.MICROSOFT);
                            providerCapturePurgeService.clearAccountReferencesInCatalog(
                                userId);
                            userOffboardingService.assertNoAuthoredContent(userId);
                            userOffboardingService.eraseOrgDataReferences(userId);
                        });
                    return null;
                });
        }
    }
}
