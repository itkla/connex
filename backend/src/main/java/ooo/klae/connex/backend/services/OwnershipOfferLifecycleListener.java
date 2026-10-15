package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import lombok.RequiredArgsConstructor;

/** Completes organization-fence invalidation without ever taking workspace roots after org X. */
@Component
@RequiredArgsConstructor
public class OwnershipOfferLifecycleListener {
    private final OwnershipOfferService offers;

    /** Each workspace closure routes first and opens a fresh READ COMMITTED transaction. */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void fenced(OwnershipOfferOrganizationFenced event) {
        var previous = org.springframework.security.core.context.SecurityContextHolder.getContext();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        try {
            offers.invalidateFencedOrganization(event.orgId());
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.setContext(previous);
        }
    }
}
