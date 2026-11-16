package ooo.klae.connex.backend.services;

import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Opens a membership-provisioning transaction after pinning the target workspace's catalog.
 * Token-based joins and SSO provisioning can start without that workspace in the request context,
 * so beginning their transaction first would bind the connection to the wrong catalog.
 */
@Component
@RequiredArgsConstructor
public class FreshMembershipTransaction {
    private final TenantWorkScope tenantWorkScope;
    private final TransactionTemplate transactionTemplate;

    /**
     * Runs one complete membership-provisioning unit in the target workspace's catalog.
     *
     * @param <T> the provisioning result type
     * @param workspaceId the workspace receiving the member
     * @param work the complete transactional provisioning unit
     * @return the non-null provisioning result
     */
    public <T> T execute(int workspaceId, Supplier<T> work) {
        T result = tenantWorkScope.inWorkspace(
            workspaceId,
            () -> OwnershipOfferReadCommittedTransactions.from(transactionTemplate).execute(status -> work.get()));
        return Objects.requireNonNull(result, "fresh-membership transaction result");
    }

    /**
     * Prepares the membership target before its transaction in either mode. An enclosing actual
     * single-database transaction retains lazy scope reads. The callback may return null, as the
     * existing membership primitives permit.
     */
    public <T> T executeWithPreparedScopes(int workspaceId, Supplier<T> work) {
        return tenantWorkScope.inWorkspace(workspaceId, () -> inTransaction(work));
    }

    private <T> T inTransaction(Supplier<T> work) {
        return OwnershipOfferReadCommittedTransactions.from(transactionTemplate).execute(status -> work.get());
    }
}
