package ooo.klae.connex.backend.notifications;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.NotificationQuietHours;
import ooo.klae.connex.backend.dto.NotificationDto;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.NotificationQuietHoursMapper;
import ooo.klae.connex.backend.mappers.PreferenceMapper;
import ooo.klae.connex.backend.services.NotificationQuietHoursEvaluator;

/**
 * The single fan-out point for a generated notification. The {@code in_app}
 * channel always delivers (it is the inbox). Any other channel — currently
 * {@code email} — delivers only when the recipient has opted in for that
 * (type, channel) preference AND the notification is new: reconciliation
 * re-dispatches idempotent reminders every cycle, so email is gated to the
 * first occurrence (by {@code dedupe_key}) to avoid repeat sends. The in-app
 * dispatch propagates its failure (it is the load-bearing inbox, and callers such
 * as the rule engine record that failure); secondary channels are isolated so an
 * email outage never blocks in-app delivery.
 *
 * <p>During quiet hours the durable in-app write and unread-state version still advance,
 * while detailed realtime frames and first-occurrence email delivery are suppressed.
 *
 * <p>A single dedupe pre-read serves two purposes. It gates email to the first
 * occurrence, and it classifies the in-app write as <em>created</em> (no prior
 * row), <em>updated</em> (a prior row whose severity shifts or is revived from
 * resolved — the exact condition under which the upsert clears read/dismiss
 * state) or an idempotent no-op. A realtime frame is published only for the
 * first two, so the every-cycle re-dispatch of an unchanged reminder never
 * re-notifies a live client.
 *
 * <p>The in-app affected-row count closes a stale-pre-read race for existing rows:
 * if another transaction changes a notification between the pre-read and upsert,
 * an overwrite still advances the version and pushes an update. Two concurrent
 * passes can each observe a brand-new reminder and both email and push it once;
 * that bounded first-occurrence duplicate is absorbed client-side by dedupe-key
 * suppression and tracked for claim-based hardening.
 */
@Component
@RequiredArgsConstructor
public class NotificationDelivery {

    private static final Logger log = LoggerFactory.getLogger(NotificationDelivery.class);
    private static final String IN_APP = "in_app";

    private final List<NotificationDispatcher> dispatchers;
    private final NotificationMapper notificationMapper;
    private final PreferenceMapper preferenceMapper;
    private final NotificationPushPublisher pushPublisher;
    private final NotificationStateVersionService stateVersionService;
    private final NotificationQuietHoursMapper quietHoursMapper;
    private final NotificationQuietHoursEvaluator quietHoursEvaluator;
    private final NotificationQuietHoursBypassPolicy bypassPolicy;
    private final Clock clock;

    /**
     * Delivers a notification across every eligible channel.
     * @param notification the generated notification
     */
    @Transactional
    public void deliver(Notification notification) {
        Notification existing = findExisting(notification);
        boolean firstOccurrence = existing == null;
        boolean changed = firstOccurrence || isVisibleChange(existing, notification);

        int inAppRows = 0;
        for (NotificationDispatcher dispatcher : dispatchers) {
            if (IN_APP.equals(dispatcher.channel())) {
                inAppRows = Math.max(inAppRows, dispatcher.dispatch(notification));
            }
        }

        boolean persistedChange = changed || (!firstOccurrence && inAppRows > 1);
        boolean quietHoursActive = persistedChange && quietHoursActive(notification);
        boolean recipientCanAccess = true;
        if (persistedChange) {
            recipientCanAccess = pushRealtime(
                notification, existing, persistedChange, quietHoursActive);
        }

        if (!firstOccurrence || quietHoursActive || !recipientCanAccess) {
            return;
        }
        for (NotificationDispatcher dispatcher : dispatchers) {
            if (IN_APP.equals(dispatcher.channel())) {
                continue;
            }
            if (preferenceMapper.isEnabledOptIn(
                    notification.getRecipientId(), notification.getType(), dispatcher.channel())) {
                safeDispatch(dispatcher, notification);
            }
        }
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
        int id = created ? notification.getId() : existing.getId();
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

    /**
     * A re-delivered reminder is materially changed when its severity shifts or a
     * previously resolved row is revived — the same condition under which the in-app
     * upsert clears read/dismiss/snooze state, so a live client should refresh.
     */
    private boolean isVisibleChange(Notification existing, Notification incoming) {
        return !Objects.equals(existing.getSeverity(), incoming.getSeverity())
                || existing.getResolvedAt() != null
                || !Objects.equals(existing.getType(), incoming.getType())
                || !Objects.equals(existing.getCategory(), incoming.getCategory())
                || existing.getTemplateVersion() != incoming.getTemplateVersion()
                || !Objects.equals(existing.getTitle(), incoming.getTitle())
                || !Objects.equals(existing.getBody(), incoming.getBody())
                || !Objects.equals(existing.getActorId(), incoming.getActorId())
                || !Objects.equals(existing.getActorLabel(), incoming.getActorLabel())
                || !Objects.equals(existing.getSourceType(), incoming.getSourceType())
                || !Objects.equals(existing.getSourceId(), incoming.getSourceId())
                || !Objects.equals(existing.getSourceLabel(), incoming.getSourceLabel())
                || !Objects.equals(existing.getContextType(), incoming.getContextType())
                || !Objects.equals(existing.getContextId(), incoming.getContextId())
                || !Objects.equals(existing.getContextLabel(), incoming.getContextLabel())
                || !Objects.equals(existing.getActionUrl(), incoming.getActionUrl())
                || !Objects.equals(existing.getData(), incoming.getData());
    }

    private void safeDispatch(NotificationDispatcher dispatcher, Notification notification) {
        try {
            dispatcher.dispatch(notification);
        } catch (RuntimeException e) {
            log.warn("Notification dispatch on channel {} failed for recipient {}: {}",
                    dispatcher.channel(), notification.getRecipientId(), e.getMessage());
        }
    }

    private boolean quietHoursActive(Notification notification) {
        if (bypassPolicy.bypasses(notification)) {
            return false;
        }
        try {
            NotificationQuietHours quietHours = quietHoursMapper.findByUserId(notification.getRecipientId());
            return quietHours != null && quietHoursEvaluator.evaluate(quietHours, clock.instant()).active();
        } catch (RuntimeException exception) {
            log.warn("Quiet-hours evaluation failed for recipient {}: {}",
                notification.getRecipientId(), exception.getMessage());
            return true;
        }
    }
}
