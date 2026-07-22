import type { NotificationMarkAllResult } from "@/app/lib/types";

const ALL_NOTIFICATIONS_READ_EVENT = "connex:notifications-all-read";
const NOTIFICATION_STATE_CHANGED_EVENT = "connex:notification-state-changed";
const NOTIFICATION_CHANNEL = "connex:notifications";

export type AllNotificationsReadDetail = NotificationMarkAllResult & {
    recipientId: number;
};

export type NotificationStateChangedDetail = {
    recipientId: number;
    stateVersion: number;
    forceRefresh?: boolean;
};

type NotificationBroadcastMessage =
    | { kind: "all-read"; detail: AllNotificationsReadDetail }
    | { kind: "state-changed"; detail: NotificationStateChangedDetail };

let replayableStateChange: NotificationStateChangedDetail | null = null;
let broadcastChannel: BroadcastChannel | null = null;

declare global {
    interface WindowEventMap {
        "connex:notifications-all-read": CustomEvent<AllNotificationsReadDetail>;
        "connex:notification-state-changed": CustomEvent<NotificationStateChangedDetail>;
    }
}

function isObject(value: unknown): value is object {
    return typeof value === "object" && value !== null;
}

function isNonNegativeInteger(value: unknown): value is number {
    return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

function property(value: object, key: string): unknown {
    return Reflect.get(value, key);
}

function parseStateChangedDetail(value: unknown): NotificationStateChangedDetail | null {
    if (!isObject(value)) return null;
    const recipientId = property(value, "recipientId");
    const stateVersion = property(value, "stateVersion");
    const forceRefresh = property(value, "forceRefresh");
    if (!isNonNegativeInteger(recipientId) || !isNonNegativeInteger(stateVersion)) return null;
    if (forceRefresh !== undefined && typeof forceRefresh !== "boolean") return null;
    return { recipientId, stateVersion, forceRefresh: forceRefresh === true };
}

function parseAllReadDetail(value: unknown): AllNotificationsReadDetail | null {
    if (!isObject(value)) return null;
    const state = parseStateChangedDetail(value);
    const unread = property(value, "unread");
    const snoozed = property(value, "snoozed");
    const cutoffId = property(value, "cutoffId");
    const readAt = property(value, "readAt");
    const asOf = property(value, "asOf");
    const nextSnoozeExpiry = property(value, "nextSnoozeExpiry");
    const quietHoursActive = property(value, "quietHoursActive");
    const nextQuietHoursTransition = property(value, "nextQuietHoursTransition");
    if (!state
        || !isNonNegativeInteger(unread)
        || !isNonNegativeInteger(snoozed)
        || !isNonNegativeInteger(cutoffId)
        || typeof readAt !== "string"
        || typeof asOf !== "string"
        || typeof quietHoursActive !== "boolean"
        || (nextSnoozeExpiry !== undefined
            && nextSnoozeExpiry !== null
            && typeof nextSnoozeExpiry !== "string")
        || (nextQuietHoursTransition !== undefined
            && nextQuietHoursTransition !== null
            && typeof nextQuietHoursTransition !== "string")) {
        return null;
    }
    return {
        ...state,
        unread,
        snoozed,
        cutoffId,
        readAt,
        asOf,
        quietHoursActive,
        ...(typeof nextSnoozeExpiry === "string" ? { nextSnoozeExpiry } : {}),
        ...(typeof nextQuietHoursTransition === "string" ? { nextQuietHoursTransition } : {}),
    };
}

function parseBroadcastMessage(value: unknown): NotificationBroadcastMessage | null {
    if (!isObject(value)) return null;
    const kind = property(value, "kind");
    const detail = property(value, "detail");
    if (kind === "all-read") {
        const parsed = parseAllReadDetail(detail);
        return parsed ? { kind, detail: parsed } : null;
    }
    if (kind === "state-changed") {
        const parsed = parseStateChangedDetail(detail);
        return parsed ? { kind, detail: parsed } : null;
    }
    return null;
}

function isOlderThanReplay(detail: NotificationStateChangedDetail): boolean {
    return replayableStateChange?.recipientId === detail.recipientId
        && replayableStateChange.stateVersion > detail.stateVersion;
}

function dispatchStateChanged(detail: NotificationStateChangedDetail): boolean {
    if (replayableStateChange?.recipientId === detail.recipientId
        && (replayableStateChange.stateVersion > detail.stateVersion
            || (replayableStateChange.stateVersion === detail.stateVersion && !detail.forceRefresh))) {
        return false;
    }
    replayableStateChange = detail;
    window.dispatchEvent(new CustomEvent(NOTIFICATION_STATE_CHANGED_EVENT, { detail }));
    return true;
}

function dispatchAllRead(detail: AllNotificationsReadDetail): void {
    if (isOlderThanReplay(detail)) return;
    window.dispatchEvent(new CustomEvent(ALL_NOTIFICATIONS_READ_EVENT, { detail }));
    dispatchStateChanged(detail);
}

function notificationBroadcastChannel(): BroadcastChannel | null {
    if (typeof window === "undefined" || typeof BroadcastChannel === "undefined") return null;
    if (broadcastChannel) return broadcastChannel;
    broadcastChannel = new BroadcastChannel(NOTIFICATION_CHANNEL);
    broadcastChannel.addEventListener("message", (event: MessageEvent<unknown>) => {
        const message = parseBroadcastMessage(event.data);
        if (!message) return;
        if (message.kind === "all-read") dispatchAllRead(message.detail);
        else dispatchStateChanged(message.detail);
    });
    return broadcastChannel;
}

export function emitAllNotificationsRead(
    recipientId: number,
    result: NotificationMarkAllResult,
): void {
    if (typeof window === "undefined") return;
    const detail = { recipientId, ...result };
    dispatchAllRead(detail);
    notificationBroadcastChannel()?.postMessage({ kind: "all-read", detail } satisfies NotificationBroadcastMessage);
}

export function onAllNotificationsRead(
    recipientId: number,
    handler: (detail: AllNotificationsReadDetail) => void,
): () => void {
    if (typeof window === "undefined") return () => {};
    notificationBroadcastChannel();
    const listener = (event: CustomEvent<AllNotificationsReadDetail>) => {
        if (event.detail.recipientId === recipientId) handler(event.detail);
    };
    window.addEventListener(ALL_NOTIFICATIONS_READ_EVENT, listener);
    return () => window.removeEventListener(ALL_NOTIFICATIONS_READ_EVENT, listener);
}

export function emitNotificationStateChanged(
    recipientId: number,
    stateVersion: number,
    forceRefresh = false,
): void {
    if (typeof window === "undefined") return;
    const detail = { recipientId, stateVersion, forceRefresh };
    if (dispatchStateChanged(detail)) {
        notificationBroadcastChannel()?.postMessage(
            { kind: "state-changed", detail } satisfies NotificationBroadcastMessage,
        );
    }
}

export function onNotificationStateChanged(
    recipientId: number,
    handler: (detail: NotificationStateChangedDetail) => void,
    options: { replay?: boolean } = {},
): () => void {
    if (typeof window === "undefined") return () => {};
    notificationBroadcastChannel();
    const listener = (event: CustomEvent<NotificationStateChangedDetail>) => {
        if (event.detail.recipientId === recipientId) handler(event.detail);
    };
    window.addEventListener(NOTIFICATION_STATE_CHANGED_EVENT, listener);
    if (options.replay && replayableStateChange?.recipientId === recipientId) {
        handler(replayableStateChange);
    }
    return () => window.removeEventListener(NOTIFICATION_STATE_CHANGED_EVENT, listener);
}
