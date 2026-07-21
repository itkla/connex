import type { Notification } from "@/app/lib/types";

type SnoozeState = Pick<Notification, "dismissedAt" | "resolvedAt" | "snoozedUntil">;

/** Whether a notification remains actively snoozed at the server-provided page snapshot. */
export function isNotificationSnoozedAt(notification: SnoozeState, asOf: string): boolean {
    if (notification.dismissedAt || notification.resolvedAt || !notification.snoozedUntil) return false;
    const snoozedUntil = Date.parse(notification.snoozedUntil);
    const snapshot = Date.parse(asOf);
    return Number.isFinite(snoozedUntil) && Number.isFinite(snapshot) && snoozedUntil > snapshot;
}
