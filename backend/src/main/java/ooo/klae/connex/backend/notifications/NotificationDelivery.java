package ooo.klae.connex.backend.notifications;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.dto.NotificationDto;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.PreferenceMapper;

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
 * <p>A single dedupe pre-read serves two purposes. It gates email to the first
 * occurrence, and it classifies the in-app write as <em>created</em> (no prior
 * row), <em>updated</em> (a prior row whose severity shifts or is revived from
 * resolved — the exact condition under which the upsert clears read/dismiss
 * state) or an idempotent no-op. A realtime frame is published only for the
 * first two, so the every-cycle re-dispatch of an unchanged reminder never
 * re-notifies a live client.
 *
 * <p>The pre-read is not transactionally isolated across concurrent reconcile
 * passes: two passes for the same workspace can each observe "new" and both
 * email — and both push a {@code created} frame — once. This is bounded to a
 * single duplicate per brand-new reminder (repeat reminders are always
 * suppressed), absorbed client-side by dedupe-key suppression, and tracked for a
 * claim-based hardening.
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

    /**
     * Delivers a notification across every eligible channel.
     * @param notification the generated notification
     */
    @Transactional
    public void deliver(Notification notification) {
        Notification existing = findExisting(notification);
        boolean firstOccurrence = existing == null;
        boolean changed = firstOccurrence || isVisibleChange(existing, notification);

        for (NotificationDispatcher dispatcher : dispatchers) {
            if (IN_APP.equals(dispatcher.channel())) {
                dispatcher.dispatch(notification);
            }
        }

        if (changed) {
            stateVersionService.markChanged(notification.getRecipientId());
            pushRealtime(notification, existing);
        }

        if (!firstOccurrence) {
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

    private void pushRealtime(Notification notification, Notification existing) {
        boolean created = existing == null;
        boolean updated = !created && isVisibleChange(existing, notification);
        if (!created && !updated) {
            return;
        }
        int id = created ? notification.getId() : existing.getId();
        Notification persisted = notificationMapper.findById(notification.getRecipientId(), id);
        if (persisted == null) {
            return;
        }
        NotificationDto dto = NotificationDto.from(persisted);
        if (created) {
            pushPublisher.created(notification.getRecipientId(), dto, notification.getDedupeKey());
        } else {
            pushPublisher.updated(notification.getRecipientId(), dto, notification.getDedupeKey());
        }
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
}
