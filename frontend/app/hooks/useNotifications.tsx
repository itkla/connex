"use client";

import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";

import { useLocale, useTranslations } from "next-intl";

import { notificationContent, safeNotificationUrl } from "@/app/components/notifications/notificationContent";
import { notificationSnoozeDelayMs } from "@/app/components/notifications/notificationSnooze";
import { useNotificationWorkspaceActions } from "@/app/components/notifications/useNotificationWorkspaceActions";
import {
    emitNotificationStateChanged,
    onAllNotificationsRead,
    onNotificationStateChanged,
} from "@/app/components/notifications/notificationEvents";
import { getNotificationCounts } from "@/app/lib/api";
import {
    createNotificationSocket,
    type RealtimeNotificationFrame,
} from "@/app/lib/realtime";
import { toastError, toastInfo, toastWarn, type ToastOptions } from "@/app/lib/toast";
import { type Notification } from "@/app/lib/types";

const POLL_INTERVAL_MS = 45_000;
const POLL_SAFETY_INTERVAL_MS = 300_000;
const SEEN_LIMIT = 200;

type NotificationContextValue = {
    recipientId: number;
    unread: number;
    snoozed: number;
    quietHoursActive: boolean;
    refreshUnread: () => Promise<void>;
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

    const t = useTranslations("Notifications");
    const locale = useLocale();
    const { openNotification } = useNotificationWorkspaceActions();

    const refreshUnread = useCallback(async () => {
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
                    const counts = await getNotificationCounts({ signal: controller.signal });
                    const roundTripMs = performance.now() - requestStartedAt;
                    if (generation === mutationGenerationRef.current) {
                        if (counts.stateVersion < observedStateVersionRef.current && staleRetryAvailable) {
                            staleRetryAvailable = false;
                            pendingRef.current = true;
                        } else if (counts.stateVersion >= observedStateVersionRef.current) {
                            const previousVersion = observedStateVersionRef.current;
                            const previousUnread = unreadRef.current;
                            const snoozeExpiryDue = snoozeExpiryDueRef.current;
                            snoozeExpiryDueRef.current = false;
                            observedStateVersionRef.current = counts.stateVersion;
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
    }, [recipientId]);

    useEffect(() => {
        const invalidateRequest = () => {
            mutationGenerationRef.current += 1;
            pendingRef.current = false;
            requestRef.current?.abort();
        };
        const stopAllRead = onAllNotificationsRead(
            recipientId,
            ({ stateVersion, unread: nextUnread }) => {
                invalidateRequest();
                if (stateVersion >= observedStateVersionRef.current) {
                    observedStateVersionRef.current = stateVersion;
                    unreadRef.current = nextUnread;
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
    }, [refreshUnread]);

    useEffect(() => {
        const period = connected ? POLL_SAFETY_INTERVAL_MS : POLL_INTERVAL_MS;
        const interval = window.setInterval(() => void refreshUnread(), period);
        return () => window.clearInterval(interval);
    }, [connected, refreshUnread]);

    useEffect(() => {
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
    }, [refreshUnread]);

    return (
        <NotificationContext.Provider value={{ recipientId, unread, snoozed, quietHoursActive, refreshUnread }}>
            {children}
        </NotificationContext.Provider>
    );
}

export function useNotifications() {
    const value = useContext(NotificationContext);
    if (!value) throw new Error("useNotifications must be used within NotificationProvider");
    return value;
}
