package ooo.klae.connex.backend.services;

import java.util.TreeSet;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.OwnershipOfferMapper;
import ooo.klae.connex.backend.notifications.NotificationPushPublisher;

/** Advances delivered-offer views without the ordinary upsert's late recipient-account FK lock. */
@Component
@RequiredArgsConstructor
public class OwnershipOfferNotificationStateVersionService {
    private static final Object RESOURCE_KEY = OwnershipOfferNotificationStateVersionService.class;
    private final OwnershipOfferMapper mapper;
    private final NotificationPushPublisher pushPublisher;

    /** Returns whether this transaction will advance the existing delivered-notice state row. */
    public boolean hasQueuedChange(int recipientId) {
        Object resource = TransactionSynchronizationManager.getResource(RESOURCE_KEY);
        return resource instanceof RecipientVersions versions && versions.recipients().contains(recipientId);
    }

    /**
     * Consumes a queued closure bump for an immediate inbox response while retaining the
     * existing-row-only write and its missing-row guard. The caller publishes invalidation.
     */
    public boolean bumpQueuedChangeNow(int recipientId) {
        Object resource = TransactionSynchronizationManager.getResource(RESOURCE_KEY);
        if (!(resource instanceof RecipientVersions versions) || !versions.recipients().contains(recipientId)) {
            return false;
        }
        bumpExisting(recipientId);
        versions.recipients().remove(recipientId);
        return true;
    }

    /**
     * Queues sorted, deduplicated updates while the caller retains offer X. Delivery commits its
     * marker, notice and recipient-state row together, so closure updates only existing rows and
     * fails closed if that guarantee has been broken. It never creates a new account reference.
     */
    public void markChanged(int recipientId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Ownership notice closure requires a transaction");
        }
        Object existing = TransactionSynchronizationManager.getResource(RESOURCE_KEY);
        if (existing instanceof RecipientVersions versions) {
            versions.recipients().add(recipientId);
            return;
        }
        RecipientVersions versions = new RecipientVersions(new TreeSet<>());
        versions.recipients().add(recipientId);
        TransactionSynchronizationManager.bindResource(RESOURCE_KEY, versions);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void suspend() {
                if (TransactionSynchronizationManager.getResource(RESOURCE_KEY) == versions) {
                    TransactionSynchronizationManager.unbindResource(RESOURCE_KEY);
                }
            }
            @Override public void resume() {
                if (!TransactionSynchronizationManager.hasResource(RESOURCE_KEY)) {
                    TransactionSynchronizationManager.bindResource(RESOURCE_KEY, versions);
                } else if (TransactionSynchronizationManager.getResource(RESOURCE_KEY) != versions) {
                    throw new IllegalStateException("Ownership notice state belongs to another transaction");
                }
            }
            @Override public void beforeCommit(boolean readOnly) {
                for (int id : versions.recipients()) {
                    bumpExisting(id);
                }
                versions.recipients().forEach(pushPublisher::invalidated);
            }
            @Override public void afterCompletion(int status) {
                if (TransactionSynchronizationManager.getResource(RESOURCE_KEY) == versions) {
                    TransactionSynchronizationManager.unbindResource(RESOURCE_KEY);
                }
            }
        });
    }

    private void bumpExisting(int recipientId) {
        if (mapper.bumpExistingNoticeStateVersion(recipientId) != 1) {
            throw new IllegalStateException("Delivered ownership notice has no recipient state");
        }
    }

    private record RecipientVersions(TreeSet<Integer> recipients) {}
}
