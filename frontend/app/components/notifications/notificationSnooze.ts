import type { Notification } from "@/app/lib/types";

type SnoozeState = Pick<Notification, "dismissedAt" | "resolvedAt" | "snoozedUntil">;

function parseUtcInstant(value: string): number {
    const normalized = value.includes("T") ? value : `${value.replace(" ", "T")}Z`;
    return Date.parse(normalized);
}

/** Whether a notification remains actively snoozed at the server-provided page snapshot. */
export function isNotificationSnoozedAt(notification: SnoozeState, asOf: string): boolean {
    if (notification.dismissedAt || notification.resolvedAt || !notification.snoozedUntil) return false;
    const snoozedUntil = parseUtcInstant(notification.snoozedUntil);
    const snapshot = parseUtcInstant(asOf);
    return Number.isFinite(snoozedUntil) && Number.isFinite(snapshot) && snoozedUntil > snapshot;
}

/** Milliseconds from a server snapshot until its next snooze expiry, independent of the client clock. */
export function notificationSnoozeDelayMs(nextSnoozeExpiry: string, asOf: string): number | null {
    const expiry = parseUtcInstant(nextSnoozeExpiry);
    const snapshot = parseUtcInstant(asOf);
    if (!Number.isFinite(expiry) || !Number.isFinite(snapshot)) return null;
    return Math.max(0, expiry - snapshot);
}
