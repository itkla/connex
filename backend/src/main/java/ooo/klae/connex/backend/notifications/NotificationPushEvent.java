package ooo.klae.connex.backend.notifications;

import ooo.klae.connex.backend.dto.NotificationDto;

/**
 * Signals that a realtime frame should be pushed to a recipient once the
 * surrounding transaction commits.
 */
public record NotificationPushEvent(
        int recipientId,
        String kind,
        NotificationDto notification,
        String dedupeKey
) {}
