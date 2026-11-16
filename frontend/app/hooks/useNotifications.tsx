"use client";

import {
    createContext,
    useCallback,
    useContext,
    useEffect,
    useRef,
    useState,
    useSyncExternalStore,
} from "react";

import { usePathname, useSearchParams } from "next/navigation";
import { useLocale, useTranslations } from "next-intl";

import { notificationContent, safeNotificationUrl } from "@/app/components/notifications/notificationContent";
import { notificationSnoozeDelayMs } from "@/app/components/notifications/notificationSnooze";
import {
    excludeExpiredInvitationCounts,
    needsInvitationCountRefresh,
    notificationCountQueryUpperBound,
    notificationInvitationDeadline,
} from "@/app/components/notifications/notificationExpiry";
import { useLiveNow } from "@/app/hooks/useNow";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { useNotificationWorkspaceActions } from "@/app/components/notifications/useNotificationWorkspaceActions";
import {
    emitNotificationStateChanged,
    onAllNotificationsRead,
    onNotificationStateChanged,
} from "@/app/components/notifications/notificationEvents";
import {
    getNotificationCounts,
    currentPrivilegedMfaReadiness,
    privilegedMfaConfinementFor,
    subscribePrivilegedMfaConfinement,
} from "@/app/lib/api";
import {
    createNotificationSocket,
    type RealtimeNotificationFrame,
} from "@/app/lib/realtime";
import { toastError, toastInfo, toastWarn, type ToastOptions } from "@/app/lib/toast";
import { parseMysqlDateTime } from "@/app/lib/utils";
import { type Notification, type NotificationCounts } from "@/app/lib/types";

const POLL_INTERVAL_MS = 45_000;
const POLL_SAFETY_INTERVAL_MS = 300_000;
const SEEN_LIMIT = 200;

type NotificationContextValue = {
    recipientId: number;
    unread: number;
    snoozed: number;
    quietHoursActive: boolean;
    refreshUnread: () => Promise<void>;
    expireInvitations: (items: Notification[]) => void;
};

const MAX_TIMEOUT_DELAY_MS = 2_147_483_647;

const NotificationContext = createContext<NotificationContextValue | null>(null);

/**
 * Owns the workspace-independent unread count (the inbox is recipient-scoped
 * across every workspace) and its realtime lifecycle: a STOMP socket pushes new
 * notifications for an instant badge update and toast, while a poll runs as a
 * fallback — every 45s when the socket is down, and a slow 5-minute safety net
 * while it is connected.
 */
export function NotificationProvider({
    children,
    recipientId,
}: {
    children: React.ReactNode;
    recipientId: number;
}) {
    const [unread, setUnread] = useState(0);
    const [snoozed, setSnoozed] = useState(0);
    const [quietHoursActive, setQuietHoursActive] = useState(false);
    const [connected, setConnected] = useState(false);
    const requestRef = useRef<AbortController | null>(null);
    const loadingRef = useRef(false);
    const pendingRef = useRef(false);
    const mutationGenerationRef = useRef(0);
    const observedStateVersionRef = useRef(0);
    const unreadRef = useRef(0);
    const snoozeExpiryTimerRef = useRef<number | null>(null);
    const snoozeExpiryDueRef = useRef(false);
    const seenRef = useRef<Set<string>>(new Set());
    const expiredInvitationsRef = useRef(new Map<number, Notification>());
    const expiredSnapshotStatesRef = useRef(new Map<number, Notification>());
    const countsSnapshotRef = useRef<NotificationCounts | null>(null);
    const countsQueryUpperBoundRef = useRef(Number.POSITIVE_INFINITY);

    const t = useTranslations("Notifications");
    const locale = useLocale();
    const pathname = usePathname();
    const searchParams = useSearchParams();
    const { openNotification } = useNotificationWorkspaceActions();

    const privilegedMfaReadsBlocked = useSyncExternalStore(
        subscribePrivilegedMfaConfinement,
        () => currentPrivilegedMfaReadiness() !== "ready" || privilegedMfaConfinementFor(pathname, searchParams.get("mfa")),
        () => true,
    );

    const refreshUnread = useCallback(async () => {
        if (privilegedMfaReadsBlocked) return;
        if (document.hidden) return;
        if (loadingRef.current) {
            pendingRef.current = true;
            return;
        }
        loadingRef.current = true;
        let staleRetryAvailable = true;
        try {
            do {
                pendingRef.current = false;
                requestRef.current?.abort();
                const controller = new AbortController();
                const generation = mutationGenerationRef.current;
                requestRef.current = controller;
                try {
                    const requestStartedAt = performance.now();
                    const snapshot = await getNotificationCounts({ signal: controller.signal });
                    const roundTripMs = performance.now() - requestStartedAt;
                    const queryUpperBound = notificationCountQueryUpperBound(snapshot, roundTripMs);
                    const counts = excludeExpiredInvitationCounts(
                        snapshot, expiredInvitationsRef.current.values(), expiredSnapshotStatesRef.current.values(), queryUpperBound,
                    );
                    if (generation === mutationGenerationRef.current) {
                        if (staleRetryAvailable && (counts.stateVersion < observedStateVersionRef.current
                                || needsInvitationCountRefresh(snapshot, expiredInvitationsRef.current.values(), queryUpperBound))) {
                            staleRetryAvailable = false;
                            pendingRef.current = true;
                        } else if (counts.stateVersion >= observedStateVersionRef.current) {
                            const previousVersion = observedStateVersionRef.current;
                            const previousUnread = unreadRef.current;
                            const snoozeExpiryDue = snoozeExpiryDueRef.current;
                            snoozeExpiryDueRef.current = false;
                            observedStateVersionRef.current = counts.stateVersion;
                            countsSnapshotRef.current = snapshot;
                            countsQueryUpperBoundRef.current = queryUpperBound;
                            for (const [id, item] of expiredInvitationsRef.current) {
                                const deadline = notificationInvitationDeadline(item);
                                if (deadline != null && deadline <= parseMysqlDateTime(snapshot.asOf)) {
                                    expiredInvitationsRef.current.delete(id);
                                    expiredSnapshotStatesRef.current.delete(id);
                                }
                            }
                            unreadRef.current = counts.unread;
                            setUnread(counts.unread);
                            setSnoozed(counts.snoozed);
                            setQuietHoursActive(counts.quietHoursActive);
                            if (snoozeExpiryTimerRef.current != null) {
                                window.clearTimeout(snoozeExpiryTimerRef.current);
                                snoozeExpiryTimerRef.current = null;
                            }
                            if (counts.nextSnoozeExpiry) {
                                const delay = notificationSnoozeDelayMs(
                                    counts.nextSnoozeExpiry,
                                    counts.asOf,
                                    roundTripMs,
                                );
                                if (delay != null) {
                                    snoozeExpiryTimerRef.current = window.setTimeout(
                                        () => {
                                            snoozeExpiryDueRef.current = true;
                                            void refreshUnread();
                                        },
                                        Math.min(MAX_TIMEOUT_DELAY_MS, Math.max(0, delay + 100)),
                                    );
                                }
                            }
                            if (counts.stateVersion > previousVersion) {
                                emitNotificationStateChanged(recipientId, counts.stateVersion);
                            } else if (counts.unread !== previousUnread || snoozeExpiryDue) {
                                emitNotificationStateChanged(recipientId, counts.stateVersion, true);
                            }
                        }
                    }
                } catch (error) {
                    if (!(error instanceof DOMException && error.name === "AbortError")) {
                        console.error("Failed to refresh notification count", error);
                    }
                } finally {
                    if (requestRef.current === controller) requestRef.current = null;
                }
            } while (pendingRef.current && !document.hidden);
        } finally {
            loadingRef.current = false;
        }
    }, [privilegedMfaReadsBlocked, recipientId]);

    const expireInvitations = useCallback((items: Notification[]) => {
        const snapshot = countsSnapshotRef.current;
        const changedItems = items.filter((item) => {
            const previous = expiredInvitationsRef.current.get(item.id);
            return !previous || (previous.stateVersion ?? 0) < (item.stateVersion ?? 0);
        });
        for (const item of items) {
            const previous = expiredInvitationsRef.current.get(item.id);
            if ((previous?.stateVersion ?? 0) <= (item.stateVersion ?? 0)) {
                expiredInvitationsRef.current.set(item.id, item);
            }
            if (snapshot != null && item.stateVersion === snapshot.stateVersion) {
                expiredSnapshotStatesRef.current.set(item.id, item);
            }
        }
        if (!snapshot) return;
        const counts = excludeExpiredInvitationCounts(
            snapshot, expiredInvitationsRef.current.values(), expiredSnapshotStatesRef.current.values(), countsQueryUpperBoundRef.current,
        );
        unreadRef.current = counts.unread;
        setUnread(counts.unread);
        setSnoozed(counts.snoozed);
        if (needsInvitationCountRefresh(snapshot, changedItems, countsQueryUpperBoundRef.current)) void refreshUnread();
    }, [refreshUnread]);

    useEffect(() => {
        const invalidateRequest = () => {
            mutationGenerationRef.current += 1;
            pendingRef.current = false;
            requestRef.current?.abort();
        };
        const stopAllRead = onAllNotificationsRead(
            recipientId,
            (result) => {
                const { stateVersion, unread: nextUnread } = result;
                invalidateRequest();
                if (stateVersion >= observedStateVersionRef.current) {
                    observedStateVersionRef.current = stateVersion;
                    unreadRef.current = nextUnread;
                    countsSnapshotRef.current = result;
                    countsQueryUpperBoundRef.current = Number.POSITIVE_INFINITY;
                    setUnread(nextUnread);
                }
            },
        );
        const stopStateChanged = onNotificationStateChanged(recipientId, ({ stateVersion, forceRefresh }) => {
            if (!forceRefresh && stateVersion <= observedStateVersionRef.current) return;
            observedStateVersionRef.current = Math.max(observedStateVersionRef.current, stateVersion);
            invalidateRequest();
            void refreshUnread();
        });
        return () => {
            stopAllRead();
            stopStateChanged();
        };
    }, [recipientId, refreshUnread]);

    const toastNotification = useCallback(
        (notification: Notification) => {
            const content = notificationContent(notification, t, locale);
            const url = safeNotificationUrl(notification.actionUrl);
            const options: ToastOptions = {
                description: content.body,
                ...(url ? {
                    action: {
                        label: t("viewAction"),
                        onClick: () => {
                            void openNotification(notification)
                                .then((opened) => {
                                    if (!opened) toastError(t("actionError"));
                                })
                                .catch(() => toastError(t("actionError")));
                        },
                    },
                } : {}),
            };
            if (notification.severity === "critical") toastError(content.title, options);
            else if (notification.severity === "warning") toastWarn(content.title, options);
            else toastInfo(content.title, options);
        },
        [t, locale, openNotification],
    );

    const handleFrame = useCallback(
        (frame: RealtimeNotificationFrame) => {
            emitNotificationStateChanged(recipientId, frame.stateVersion);
            const notification = frame.notification ?? null;
            if (frame.kind !== "created") return;
            const key = frame.dedupeKey ?? (notification ? `${notification.id}:${notification.triggeredAt}` : null);
            if (key) {
                if (seenRef.current.has(key)) return;
                seenRef.current.add(key);
                if (seenRef.current.size > SEEN_LIMIT) {
                    const oldest = seenRef.current.values().next().value;
                    if (oldest !== undefined) seenRef.current.delete(oldest);
                }
            }
            if (notification && !document.hidden) toastNotification(notification);
        },
        [recipientId, toastNotification],
    );

    const handleFrameRef = useRef(handleFrame);
    useEffect(() => {
        handleFrameRef.current = handleFrame;
    }, [handleFrame]);

    useEffect(() => {
        if (privilegedMfaReadsBlocked) return;
        const initial = window.setTimeout(() => void refreshUnread(), 0);
        const onVisibilityChange = () => {
            if (document.hidden) requestRef.current?.abort();
            else void refreshUnread();
        };
        document.addEventListener("visibilitychange", onVisibilityChange);
        return () => {
            window.clearTimeout(initial);
            document.removeEventListener("visibilitychange", onVisibilityChange);
            requestRef.current?.abort();
            if (snoozeExpiryTimerRef.current != null) {
                window.clearTimeout(snoozeExpiryTimerRef.current);
                snoozeExpiryTimerRef.current = null;
            }
        };
    }, [privilegedMfaReadsBlocked, refreshUnread]);

    useEffect(() => {
        if (privilegedMfaReadsBlocked) return;
        const period = connected ? POLL_SAFETY_INTERVAL_MS : POLL_INTERVAL_MS;
        const interval = window.setInterval(() => void refreshUnread(), period);
        return () => window.clearInterval(interval);
    }, [connected, privilegedMfaReadsBlocked, refreshUnread]);

    useEffect(() => {
        if (privilegedMfaReadsBlocked) return;
        const socket = createNotificationSocket({
            onFrame: (frame) => handleFrameRef.current(frame),
            onStatusChange: (status) => {
                const isConnected = status === "connected";
                setConnected(isConnected);
                if (isConnected) void refreshUnread();
            },
        });
        socket.activate();
        return () => socket.deactivate();
    }, [privilegedMfaReadsBlocked, refreshUnread]);

    return (
        <NotificationContext.Provider value={{ recipientId, unread, snoozed, quietHoursActive, refreshUnread, expireInvitations }}>
            {children}
        </NotificationContext.Provider>
    );
}

export function useNotifications() {
    const value = useContext(NotificationContext);
    if (!value) throw new Error("useNotifications must be used within NotificationProvider");
    return value;
}

/** Hides expired invitations locally and wakes once at the earliest loaded deadline. */
export function useUnexpiredNotifications(items: Notification[], scopeKey = "", stateVersion = 0) {
    const { expireInvitations } = useNotifications();
    const { activeWorkspaceId } = useWorkspace();
    const liveNow = useLiveNow();
    const [expiryNow, setExpiryNow] = useState(0);
    const now = Math.max(liveNow, expiryNow);

    useEffect(() => {
        const deadlines = items.flatMap((item) => {
            const deadline = notificationInvitationDeadline(item);
            return deadline != null && deadline > expiryNow ? [deadline] : [];
        });
        if (deadlines.length === 0) return;
        const delay = Math.min(MAX_TIMEOUT_DELAY_MS, Math.max(0, Math.min(...deadlines) - Date.now()));
        const timer = window.setTimeout(() => {
            const current = Date.now();
            const expired = items.filter((item) => {
                const deadline = notificationInvitationDeadline(item);
                return deadline != null && deadline <= current;
            });
            expireInvitations(expired.map((item) => ({
                ...item, stateVersion: Math.max(stateVersion, item.stateVersion ?? 0),
            })));
            setExpiryNow(current);
        }, delay);
        return () => window.clearTimeout(timer);
    }, [activeWorkspaceId, expireInvitations, expiryNow, items, scopeKey, stateVersion]);

    return items.filter((item) => {
        const deadline = notificationInvitationDeadline(item);
        return deadline == null || deadline > now;
    });
}
