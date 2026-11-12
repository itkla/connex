package ooo.klae.connex.backend.notifications;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.dto.NotificationDto;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.PreferenceMapper;
import ooo.klae.connex.backend.services.NotificationQuietHoursControlAccess;

/**
 * The single fan-out point for a generated notification. The {@code in_app}
 * channel always delivers (it is the inbox). Any other channel — currently
 * {@code email} — delivers only when the recipient has opted in for that
 * (type, channel) preference AND the notification is new: reconciliation
 * re-dispatches idempotent reminders every cycle, so email is gated to the
 * first occurrence (by {@code dedupe_key}) to avoid repeat sends. The in-app
 * dispatch propagates its failure (it is the load-bearing inbox, and callers such
 * as the rule engine record that failure); secondary channels are isolated so an
 * email outage never blocks in-app delivery. Eligible secondary delivery is queued
 * only after the durable notification transaction commits.
 *
 * <p>During quiet hours the durable in-app write and unread-state version still advance,
 * while detailed realtime frames and first-occurrence email delivery are suppressed.
 *
 * <p>A single dedupe pre-read gates email to the first occurrence and distinguishes
 * created from updated realtime frames. The in-app affected-row count determines
 * whether persistence changed: an existing row reports two for an update and
 * zero or one for a no-op, depending on JDBC found-row configuration. Java input
 * comparisons cannot substitute for that result because persistence can normalize
 * input, such as a missing actor account, without changing the durable row.
 * A generated key distinguishes an insert after a concurrent purge from an
 * unchanged existing row that reports one found row.
 *
 * <p>The in-app affected-row count closes a stale-pre-read race for existing rows:
 * if another transaction changes a notification between the pre-read and upsert,
 * an overwrite still advances the version and pushes an update. Email delivery
 * additionally claims a marker on the persisted dedupe row, so concurrent passes
 * that both observed a brand-new reminder cannot both send it.
 */
@Component
@RequiredArgsConstructor
public class NotificationDelivery {

    private static final Logger log = LoggerFactory.getLogger(NotificationDelivery.class);
    private static final String IN_APP = "in_app";
    private static final String EMAIL = "email";

    private final List<NotificationDispatcher> dispatchers;
    private final NotificationMapper notificationMapper;
    private final PreferenceMapper preferenceMapper;
    private final NotificationPushPublisher pushPublisher;
    private final NotificationStateVersionService stateVersionService;
    private final NotificationQuietHoursControlAccess quietHoursControlAccess;
    private final NotificationQuietHoursBypassPolicy bypassPolicy;
    private final Clock clock;

    /**
     * Delivers a notification across every eligible channel.
     * @param notification the generated notification
     * @return whether delivery observed a new or materially changed notification
     */
    @Transactional
    public boolean deliver(Notification notification) {
        Notification existing = findExisting(notification);
        boolean firstOccurrence = existing == null;
        notification.setId(0);

        int inAppRows = 0;
        for (NotificationDispatcher dispatcher : dispatchers) {
            if (IN_APP.equals(dispatcher.channel())) {
                inAppRows = Math.max(inAppRows, dispatcher.dispatch(notification));
            }
        }

        boolean inserted = inAppRows == 1 && notification.getId() > 0
            && (existing == null || notification.getId() != existing.getId());
        boolean persistedChange = inserted || inAppRows > 1;
        boolean quietHoursActive = persistedChange && quietHoursActive(notification);
        boolean recipientCanAccess = true;
        if (persistedChange) {
            recipientCanAccess = pushRealtime(
                notification, existing, persistedChange, quietHoursActive);
        }

        if (!persistedChange || !firstOccurrence || quietHoursActive || !recipientCanAccess) {
            return persistedChange;
        }
        for (NotificationDispatcher dispatcher : dispatchers) {
            if (IN_APP.equals(dispatcher.channel())) {
                continue;
            }
            if (preferenceMapper.isEnabledOptIn(
                    notification.getRecipientId(), notification.getType(), dispatcher.channel())) {
                if (EMAIL.equals(dispatcher.channel()) && !claimEmailDelivery(notification)) {
                    continue;
                }
                dispatchAfterCommit(dispatcher, notification);
            }
        }
        return persistedChange;
    }

    private boolean claimEmailDelivery(Notification notification) {
        String dedupeKey = notification.getDedupeKey();
        return dedupeKey == null || notificationMapper.claimEmailDelivery(
            notification.getWorkspaceId(), notification.getRecipientId(), dedupeKey) == 1;
    }

    private Notification findExisting(Notification notification) {
        if (notification.getDedupeKey() == null || notification.getDedupeKey().isBlank()) {
            return null;
        }
        return notificationMapper.findByDedupe(
                notification.getWorkspaceId(), notification.getRecipientId(), notification.getDedupeKey());
    }

    private boolean pushRealtime(
            Notification notification,
            Notification existing,
            boolean persistedChange,
            boolean quietHoursActive) {
        boolean created = existing == null;
        boolean updated = !created && persistedChange;
        if (!created && !updated) {
            return true;
        }
        int id = created || notification.getId() > 0 ? notification.getId() : existing.getId();
        Notification persisted = notificationMapper.findById(notification.getRecipientId(), id);
        if (persisted == null) {
            stateVersionService.markChanged(notification.getRecipientId());
            return false;
        }
        if (quietHoursActive) {
            stateVersionService.markChanged(notification.getRecipientId());
            return true;
        }
        stateVersionService.markChangedWithDetailedPush(notification.getRecipientId());
        NotificationDto dto = NotificationDto.from(persisted);
        if (created) {
            pushPublisher.created(notification.getRecipientId(), dto, notification.getDedupeKey());
        } else {
            pushPublisher.updated(notification.getRecipientId(), dto, notification.getDedupeKey());
        }
        return true;
    }

    private void safeDispatch(NotificationDispatcher dispatcher, Notification notification) {
        try {
            dispatcher.dispatch(notification);
        } catch (RuntimeException e) {
            log.warn("Notification dispatch on channel {} failed for recipient {}: {}",
                    dispatcher.channel(), notification.getRecipientId(), e.getMessage());
        }
    }

    private void dispatchAfterCommit(NotificationDispatcher dispatcher, Notification notification) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            safeDispatch(dispatcher, notification);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safeDispatch(dispatcher, notification);
            }
        });
    }

    private boolean quietHoursActive(Notification notification) {
        if (bypassPolicy.bypasses(notification)) {
            return false;
        }
        try {
            return quietHoursControlAccess.evaluateForUser(
                notification.getRecipientId(), clock.instant()).active();
        } catch (RuntimeException exception) {
            log.warn("Quiet-hours evaluation failed for recipient {}: {}",
                notification.getRecipientId(), exception.getMessage());
            return false;
        }
    }
}
