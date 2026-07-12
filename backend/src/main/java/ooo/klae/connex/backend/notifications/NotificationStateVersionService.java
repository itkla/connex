package ooo.klae.connex.backend.notifications;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.NotificationMapper;

/**
 * Advances recipient notification-state versions after all notification rows in a transaction
 * have been mutated and queues generic realtime invalidations for mutations without a detailed
 * push. Recipients are deduplicated and ordered so a transaction acquires each state row at most
 * once and concurrent multi-recipient flows use a stable lock order.
 */
@Component
@RequiredArgsConstructor
public class NotificationStateVersionService {
    private static final Object RESOURCE_KEY = NotificationStateVersionService.class;

    private final NotificationMapper notificationMapper;
    private final NotificationPushPublisher pushPublisher;

    /**
     * Records a recipient whose durable notification view changed in the current transaction.
     * @param recipientId the affected recipient
     */
    public void markChanged(int recipientId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            notificationMapper.bumpStateVersions(List.of(recipientId));
            pushPublisher.invalidated(recipientId);
            return;
        }
        recipientVersions().add(recipientId, true);
    }

    /**
     * Records a changed recipient whose producer also queues a detailed realtime frame.
     * @param recipientId the affected recipient
     */
    public void markChangedWithDetailedPush(int recipientId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            notificationMapper.bumpStateVersions(List.of(recipientId));
            return;
        }
        recipientVersions().add(recipientId, false);
    }

    /**
     * Advances one recipient immediately after an inbox mutation and returns the resulting version.
     * @param recipientId the affected recipient
     * @return the advanced state version
     */
    public long bumpNow(int recipientId) {
        Object resource = TransactionSynchronizationManager.getResource(RESOURCE_KEY);
        if (resource instanceof RecipientVersions versions) {
            versions.remove(recipientId);
        }
        notificationMapper.bumpStateVersions(List.of(recipientId));
        long stateVersion = notificationMapper.getStateVersion(recipientId);
        pushPublisher.invalidated(recipientId);
        return stateVersion;
    }

    private RecipientVersions recipientVersions() {
        Object resource = TransactionSynchronizationManager.getResource(RESOURCE_KEY);
        if (resource instanceof RecipientVersions versions) {
            return versions;
        }
        RecipientVersions versions = new RecipientVersions();
        TransactionSynchronizationManager.bindResource(RESOURCE_KEY, versions);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                List<Integer> recipientIds = versions.snapshot();
                if (!recipientIds.isEmpty()) {
                    notificationMapper.bumpStateVersions(recipientIds);
                }
                versions.invalidationSnapshot().forEach(pushPublisher::invalidated);
            }

            @Override
            public void afterCompletion(int status) {
                if (TransactionSynchronizationManager.getResource(RESOURCE_KEY) == versions) {
                    TransactionSynchronizationManager.unbindResource(RESOURCE_KEY);
                }
            }
        });
        return versions;
    }

    private static final class RecipientVersions {
        private final Set<Integer> recipientIds = new TreeSet<>();
        private final Set<Integer> invalidationRecipientIds = new TreeSet<>();

        void add(int recipientId, boolean invalidationRequired) {
            recipientIds.add(recipientId);
            if (invalidationRequired) {
                invalidationRecipientIds.add(recipientId);
            }
        }

        void remove(int recipientId) {
            recipientIds.remove(recipientId);
            invalidationRecipientIds.remove(recipientId);
        }

        List<Integer> snapshot() {
            return List.copyOf(recipientIds);
        }

        List<Integer> invalidationSnapshot() {
            return List.copyOf(invalidationRecipientIds);
        }
    }
}
