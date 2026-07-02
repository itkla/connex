"use client";

import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";

import { useLocale, useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import { type ExternalToast } from "sonner";

import { notificationContent, safeNotificationUrl } from "@/app/components/notifications/notificationContent";
import { getNotificationCounts } from "@/app/lib/api";
import {
    createNotificationSocket,
    type RealtimeNotificationFrame,
} from "@/app/lib/realtime";
import { toastError, toastInfo, toastWarn } from "@/app/lib/toast";
import { type Notification } from "@/app/lib/types";

const POLL_INTERVAL_MS = 45_000;
const POLL_SAFETY_INTERVAL_MS = 300_000;
const SEEN_LIMIT = 200;

type NotificationContextValue = {
    unread: number;
    refreshUnread: () => Promise<void>;
    adjustUnread: (delta: number) => void;
    setUnread: (value: number) => void;
};

const NotificationContext = createContext<NotificationContextValue | null>(null);

/**
 * Owns the workspace-independent unread count (the inbox is recipient-scoped
 * across every workspace) and its realtime lifecycle: a STOMP socket pushes new
 * notifications for an instant badge update and toast, while a poll runs as a
 * fallback — every 45s when the socket is down, and a slow 5-minute safety net
 * while it is connected.
 */
export function NotificationProvider({ children }: { children: React.ReactNode }) {
    const [unread, setUnread] = useState(0);
    const [connected, setConnected] = useState(false);
    const requestRef = useRef<AbortController | null>(null);
    const loadingRef = useRef(false);
    const seenRef = useRef<Set<string>>(new Set());

    const t = useTranslations("Notifications");
    const locale = useLocale();
    const router = useRouter();

    const refreshUnread = useCallback(async () => {
        if (loadingRef.current || document.hidden) return;
        loadingRef.current = true;
        requestRef.current?.abort();
        const controller = new AbortController();
        requestRef.current = controller;
        try {
            const counts = await getNotificationCounts({ signal: controller.signal });
            setUnread(counts.unread);
        } catch (error) {
            if (!(error instanceof DOMException && error.name === "AbortError")) {
                console.error("Failed to refresh notification count", error);
            }
        } finally {
            if (requestRef.current === controller) requestRef.current = null;
            loadingRef.current = false;
        }
    }, []);

    const toastNotification = useCallback(
        (notification: Notification) => {
            const content = notificationContent(notification, t, locale);
            const url = safeNotificationUrl(notification.actionUrl);
            const options: ExternalToast = {
                description: content.body,
                ...(url ? { action: { label: t("viewAction"), onClick: () => router.push(url) } } : {}),
            };
            if (notification.severity === "critical") toastError(content.title, options);
            else if (notification.severity === "warning") toastWarn(content.title, options);
            else toastInfo(content.title, options);
        },
        [t, locale, router],
    );

    const handleFrame = useCallback(
        (frame: RealtimeNotificationFrame) => {
            if (frame.kind === "counts") {
                if (typeof frame.unread === "number") setUnread(frame.unread);
                return;
            }
            if (frame.kind === "updated") {
                void refreshUnread();
                return;
            }
            const notification = frame.notification ?? null;
            const key = frame.dedupeKey ?? (notification ? `${notification.id}:${notification.triggeredAt}` : null);
            if (key) {
                if (seenRef.current.has(key)) return;
                seenRef.current.add(key);
                if (seenRef.current.size > SEEN_LIMIT) {
                    const oldest = seenRef.current.values().next().value;
                    if (oldest !== undefined) seenRef.current.delete(oldest);
                }
            }
            void refreshUnread();
            if (notification) toastNotification(notification);
        },
        [refreshUnread, toastNotification],
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

    const adjustUnread = useCallback((delta: number) => {
        setUnread((value) => Math.max(0, value + delta));
    }, []);

    return (
        <NotificationContext.Provider value={{ unread, refreshUnread, adjustUnread, setUnread }}>
            {children}
        </NotificationContext.Provider>
    );
}

export function useNotifications() {
    const value = useContext(NotificationContext);
    if (!value) throw new Error("useNotifications must be used within NotificationProvider");
    return value;
}
