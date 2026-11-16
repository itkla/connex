import { isNotificationSnoozedAt } from "@/app/components/notifications/notificationSnooze";
import type { Notification, NotificationCounts } from "@/app/lib/types";
import { parseMysqlDateTime } from "@/app/lib/utils";

/** The canonical pending-membership deadline, when this notice has one. */
export function notificationInvitationDeadline(notification: Notification): number | null {
    if (notification.type !== "workspace.join" || !notification.invitationExpiresAt) return null;
    const deadline = parseMysqlDateTime(notification.invitationExpiresAt);
    return Number.isFinite(deadline) ? deadline : null;
}

/** Bounds the server's whole-second query clock using the count request's duration. */
export function notificationCountQueryUpperBound(counts: NotificationCounts, roundTripMs: number): number {
    const asOf = parseMysqlDateTime(counts.asOf);
    if (!Number.isFinite(asOf) || !Number.isFinite(roundTripMs)) return Number.POSITIVE_INFINITY;
    return asOf + Math.ceil(Math.max(0, roundTripMs) / 1_000) * 1_000;
}

/** Whether cached state or a request spanning expiry requires a fresh count snapshot. */
export function needsInvitationCountRefresh(
    counts: NotificationCounts,
    expired: Iterable<Notification>,
    queryUpperBound = Number.NEGATIVE_INFINITY,
): boolean {
    const asOf = parseMysqlDateTime(counts.asOf);
    for (const notification of expired) {
        const deadline = notificationInvitationDeadline(notification);
        const unknownState = notification.stateVersion != null && notification.stateVersion !== counts.stateVersion;
        const counted = !notification.dismissedAt && !notification.resolvedAt
            && (isNotificationSnoozedAt(notification, counts.asOf) || !notification.readAt);
        if (deadline != null && deadline > asOf
                && (unknownState || (deadline <= queryUpperBound && counted))) return true;
    }
    return false;
}

/**
 * Removes expired invitations using states that agree with the server count snapshot.
 * Matching snapshot states take precedence over newer surface caches.
 */
export function excludeExpiredInvitationCounts(
    counts: NotificationCounts,
    expired: Iterable<Notification>,
    snapshotStates: Iterable<Notification> = [],
    queryUpperBound = Number.NEGATIVE_INFINITY,
): NotificationCounts {
    const asOf = parseMysqlDateTime(counts.asOf);
    const states = new Map<number, Notification>();
    for (const notification of expired) states.set(notification.id, notification);
    for (const notification of snapshotStates) {
        if (notification.stateVersion === counts.stateVersion) states.set(notification.id, notification);
    }
    let unread = counts.unread;
    let snoozed = counts.snoozed;
    for (const notification of states.values()) {
        const deadline = notificationInvitationDeadline(notification);
        if (deadline == null || deadline <= asOf || deadline <= queryUpperBound
                || notification.dismissedAt || notification.resolvedAt) continue;
        if (notification.stateVersion != null && notification.stateVersion !== counts.stateVersion) continue;
        if (isNotificationSnoozedAt(notification, counts.asOf)) snoozed -= 1;
        else if (!notification.readAt) unread -= 1;
    }
    return { ...counts, unread: Math.max(0, unread), snoozed: Math.max(0, snoozed) };
}
