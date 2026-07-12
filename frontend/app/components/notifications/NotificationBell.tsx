"use client";

import { BellIcon, CheckCircleIcon, CheckIcon, EyeIcon, XMarkIcon } from "@heroicons/react/24/outline";
import { CheckCheck } from "lucide-react";
import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { DropdownMenu } from "radix-ui";

import {
    completeTask,
    dismissNotification,
    getNotifications,
    markAllNotificationsRead,
    markNotificationRead,
} from "@/app/lib/api";
import { type Notification } from "@/app/lib/types";
import { formatRelativeTime } from "@/app/lib/utils";
import { toastError } from "@/app/lib/toast";
import { useNotifications } from "@/app/hooks/useNotifications";
import { notificationContent, notificationIcon, notificationSeverityStyle } from "@/app/components/notifications/notificationContent";
import { useNotificationWorkspaceActions } from "@/app/components/notifications/useNotificationWorkspaceActions";
import { Checkbox } from "@/components/ui/checkbox";
import { cn } from "@/lib/utils";
import {
    emitAllNotificationsRead,
    emitNotificationStateChanged,
    onNotificationStateChanged,
} from "@/app/components/notifications/notificationEvents";

export default function NotificationBell() {
    const t = useTranslations("Notifications");
    const locale = useLocale();
    const { recipientId, unread, refreshUnread } = useNotifications();
    const { executeInNotificationWorkspace, openNotification } = useNotificationWorkspaceActions();
    const [items, setItems] = useState<Notification[]>([]);
    const [completing, setCompleting] = useState<Set<number>>(new Set());
    const [loading, setLoading] = useState(false);
    const loadGenerationRef = useRef(0);
    const openRef = useRef(false);
    const loadedStateVersionRef = useRef(0);
    const requiredStateVersionRef = useRef(0);

    const load = useCallback(async (open: boolean) => {
        if (!open) return;
        const generation = ++loadGenerationRef.current;
        setLoading(true);
        try {
            const page = await getNotifications({ state: "unread", page: 1, size: 8 });
            if (loadGenerationRef.current === generation) {
                if (page.stateVersion < requiredStateVersionRef.current) return;
                loadedStateVersionRef.current = page.stateVersion;
                setItems(page.items);
            }
        } catch {
            if (loadGenerationRef.current === generation) toastError(t("loadError"));
        } finally {
            if (loadGenerationRef.current === generation) setLoading(false);
        }
    }, [t]);

    useEffect(
        () => onNotificationStateChanged(recipientId, ({ stateVersion, forceRefresh }) => {
            if (!openRef.current
                || (!forceRefresh && stateVersion <= loadedStateVersionRef.current)) return;
            requiredStateVersionRef.current = Math.max(requiredStateVersionRef.current, stateVersion);
            void load(true);
        }),
        [load, recipientId],
    );

    async function openItem(item: Notification) {
        try {
            if (!await openNotification(item)) toastError(t("actionError"));
        } catch {
            toastError(t("actionError"));
        }
    }

    async function resolve(item: Notification) {
        try {
            const updated = await markNotificationRead(item.id);
            if (updated.stateVersion != null) {
                emitNotificationStateChanged(recipientId, updated.stateVersion);
            }
            setItems((current) => current.filter((entry) => entry.id !== item.id));
            await refreshUnread();
        } catch {
            toastError(t("actionError"));
        }
    }

    async function completeFromInbox(item: Notification) {
        const taskId = item.sourceId;
        if (item.sourceType !== "task" || taskId == null) return;
        setCompleting((current) => new Set(current).add(item.id));
        try {
            const completed = await executeInNotificationWorkspace(item, async () => {
                await completeTask(taskId);
                setItems((current) => current.filter((entry) => entry.id !== item.id));
                await refreshUnread();
            });
            if (!completed) toastError(t("completeError"));
        } catch {
            toastError(t("completeError"));
        } finally {
            setCompleting((current) => {
                const next = new Set(current);
                next.delete(item.id);
                return next;
            });
        }
    }

    async function dismiss(item: Notification) {
        try {
            const updated = await dismissNotification(item.id);
            if (updated.stateVersion != null) {
                emitNotificationStateChanged(recipientId, updated.stateVersion);
            }
            setItems((current) => current.filter((entry) => entry.id !== item.id));
            await refreshUnread();
        } catch {
            toastError(t("actionError"));
        }
    }

    async function readAll() {
        try {
            const result = await markAllNotificationsRead();
            emitAllNotificationsRead(recipientId, result);
        } catch {
            toastError(t("actionError"));
        }
    }

    return (
        <DropdownMenu.Root onOpenChange={(open) => {
            openRef.current = open;
            void load(open);
        }}>
            <DropdownMenu.Trigger asChild>
                <button
                    type="button"
                    aria-label={unread > 0 ? `${t("bellLabel")} — ${t("unreadCount", { count: unread })}` : t("bellLabel")}
                    className="relative inline-flex size-9 items-center justify-center rounded-md text-muted-foreground transition hover:bg-sidebar-accent hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand"
                >
                    <BellIcon className="size-5" />
                    {unread > 0 ? (
                        <span aria-hidden="true" className="absolute -right-1 -top-1 min-w-5 rounded-full bg-destructive px-1 text-center text-[10px] font-semibold leading-5 text-white">
                            {unread > 99 ? "99+" : unread}
                        </span>
                    ) : null}
                </button>
            </DropdownMenu.Trigger>
            <DropdownMenu.Portal>
                <DropdownMenu.Content
                    side="bottom"
                    align="start"
                    sideOffset={8}
                    className="z-50 w-[min(20rem,calc(100vw-2rem))] origin-(--radix-dropdown-menu-content-transform-origin) rounded-xl border border-border bg-popover p-2 text-popover-foreground shadow-xl duration-150 data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95 data-closed:animate-out data-closed:fade-out-0 data-closed:zoom-out-95"
                >
                    <div className="flex items-center justify-between px-2 py-1.5">
                        <div>
                            <p className="text-sm font-semibold">{t("title")}</p>
                            <p className="text-xs text-muted-foreground">{t("unreadCount", { count: unread })}</p>
                        </div>
                        {unread > 0 ? (
                            <button
                                type="button"
                                onClick={() => void readAll()}
                                className="inline-flex items-center gap-1 text-xs font-medium text-brand hover:text-brand-dark"
                            >
                                <CheckCheck className="size-3.5" />
                                {t("markAllRead")}
                            </button>
                        ) : null}
                    </div>
                    <div className="mt-1 max-h-96 overflow-y-auto">
                        {loading ? (
                            <div className="p-1">
                                {Array.from({ length: 3 }).map((_, i) => (
                                    <div key={i} className="flex items-start gap-3 px-1.5 py-2">
                                        <span className="size-8 shrink-0 rounded-full bg-muted motion-safe:animate-pulse" />
                                        <div className="flex-1 space-y-2 py-1">
                                            <div className="h-3 w-1/2 rounded bg-muted motion-safe:animate-pulse" />
                                            <div className="h-3 w-4/5 rounded bg-muted motion-safe:animate-pulse" />
                                        </div>
                                    </div>
                                ))}
                            </div>
                        ) : items.length === 0 ? (
                            <div className="flex flex-col items-center gap-2 px-2 py-10 text-center">
                                <span className="flex size-10 items-center justify-center rounded-full bg-muted text-muted-foreground">
                                    <CheckCircleIcon className="size-5" />
                                </span>
                                <p className="text-sm font-medium">{t("empty")}</p>
                            </div>
                        ) : items.map((item) => {
                            const content = notificationContent(item, t, locale);
                            const Icon = notificationIcon(item);
                            const style = notificationSeverityStyle(item.severity);
                            const isTask = item.sourceType === "task" && item.sourceId != null;
                            return (
                                <div
                                    key={item.id}
                                    className="group relative flex items-start gap-2 rounded-lg p-2 transition-colors hover:bg-muted"
                                >
                                    <DropdownMenu.Item
                                        onSelect={() => void openItem(item)}
                                        className="flex min-w-0 flex-1 cursor-pointer items-start gap-3 rounded-md text-left outline-none data-[highlighted]:bg-muted"
                                    >
                                        <span
                                            className={cn(
                                                "flex size-8 shrink-0 items-center justify-center rounded-full",
                                                item.readAt ? "bg-foreground/5 text-muted-foreground" : style.chip,
                                            )}
                                        >
                                            <Icon className="size-4" />
                                        </span>
                                        <div className="min-w-0 flex-1">
                                            <div className="flex items-center gap-2">
                                                <p className={cn("truncate text-sm", item.readAt ? "font-medium" : "font-semibold")}>
                                                    {content.title}
                                                </p>
                                                {!item.readAt ? <span className={cn("size-1.5 shrink-0 rounded-full", style.dot)} /> : null}
                                            </div>
                                            <p className="mt-0.5 line-clamp-2 text-xs text-muted-foreground">{content.body}</p>
                                            <p className="mt-1 flex items-center gap-1.5 text-[11px] text-muted-foreground">
                                                {formatRelativeTime(item.triggeredAt, locale)}
                                                {item.workspaceName ? (
                                                    <span className="rounded-full bg-muted px-1.5 py-0.5 font-medium">
                                                        {item.workspaceName}
                                                    </span>
                                                ) : null}
                                            </p>
                                        </div>
                                    </DropdownMenu.Item>
                                    {isTask ? (
                                        <div className="flex shrink-0 items-center self-center pr-1">
                                            <Checkbox
                                                checked={completing.has(item.id)}
                                                disabled={completing.has(item.id)}
                                                onCheckedChange={(value) => {
                                                    if (value === true) void completeFromInbox(item);
                                                }}
                                                aria-label={t("completeTask")}
                                                className="size-[18px] rounded-full border-border transition data-[state=checked]:border-brand data-[state=checked]:bg-brand data-[state=checked]:text-white"
                                            />
                                        </div>
                                    ) : (
                                        <div className="flex shrink-0 items-center gap-0.5 self-center opacity-0 transition-opacity focus-within:opacity-100 group-hover:opacity-100">
                                            {!item.readAt ? (
                                                <button
                                                    type="button"
                                                    aria-label={t("markRead")}
                                                    onClick={() => void resolve(item)}
                                                    className="rounded p-1 text-muted-foreground transition-colors hover:bg-background hover:text-brand"
                                                >
                                                    <CheckIcon className="size-4" />
                                                </button>
                                            ) : null}
                                            <button
                                                type="button"
                                                aria-label={t("dismiss")}
                                                onClick={() => void dismiss(item)}
                                                className="rounded p-1 text-muted-foreground transition-colors hover:bg-background hover:text-foreground"
                                            >
                                                <XMarkIcon className="size-4" />
                                            </button>
                                        </div>
                                    )}
                                </div>
                            );
                        })}
                    </div>
                    <div className="mt-1 border-t border-border pt-2">
                        <DropdownMenu.Item asChild>
                            <Link
                                href="/notifications"
                                className="flex items-center justify-center gap-2 rounded-md py-2 text-sm font-medium text-brand outline-none transition-colors hover:bg-muted"
                            >
                                <EyeIcon className="size-4" />
                                {t("viewAll")}
                            </Link>
                        </DropdownMenu.Item>
                    </div>
                </DropdownMenu.Content>
            </DropdownMenu.Portal>
        </DropdownMenu.Root>
    );
}
