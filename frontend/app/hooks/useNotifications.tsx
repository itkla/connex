"use client";

import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";

import { getNotificationCounts } from "@/app/lib/api";
import { useWorkspace } from "@/app/hooks/useWorkspace";

type NotificationContextValue = {
    unread: number;
    refreshUnread: () => Promise<void>;
    adjustUnread: (delta: number) => void;
    setUnread: (value: number) => void;
};

const NotificationContext = createContext<NotificationContextValue | null>(null);

export function NotificationProvider({ children }: { children: React.ReactNode }) {
    const { activeWorkspaceId } = useWorkspace();
    const [unread, setUnread] = useState(0);
    const requestRef = useRef<AbortController | null>(null);
    const loadingRef = useRef(false);

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

    useEffect(() => {
        const initial = window.setTimeout(() => void refreshUnread(), 0);
        const interval = window.setInterval(() => void refreshUnread(), 45_000);
        const onVisibilityChange = () => {
            if (document.hidden) requestRef.current?.abort();
            else void refreshUnread();
        };
        document.addEventListener("visibilitychange", onVisibilityChange);
        return () => {
            window.clearTimeout(initial);
            window.clearInterval(interval);
            document.removeEventListener("visibilitychange", onVisibilityChange);
            requestRef.current?.abort();
        };
        // activeWorkspaceId is a dependency so the count refreshes when the user switches workspace.
    }, [refreshUnread, activeWorkspaceId]);

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