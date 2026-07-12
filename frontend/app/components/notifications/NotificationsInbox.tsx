"use client";

import { ArrowUturnLeftIcon, CheckCircleIcon, XMarkIcon } from "@heroicons/react/24/outline";
import { CheckCheck } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { useEffect, useRef, useState } from "react";

import {
    dismissNotification,
    getNotifications,
    markAllNotificationsRead,
    markNotificationRead,
    markNotificationUnread,
    restoreNotification,
    snoozeNotification,
} from "@/app/lib/api";
import { type Notification, type NotificationState } from "@/app/lib/types";
import { formatRelativeTime } from "@/app/lib/utils";
import { toastError } from "@/app/lib/toast";
import { useNotifications } from "@/app/hooks/useNotifications";
import { notificationContent, notificationIcon, notificationSeverityStyle, safeNotificationUrl } from "@/app/components/notifications/notificationContent";
import {
    emitAllNotificationsRead,
    emitNotificationStateChanged,
    onNotificationStateChanged,
} from "@/app/components/notifications/notificationEvents";
import { SnoozeMenu } from "@/app/components/notifications/SnoozeMenu";
import { useNotificationWorkspaceActions } from "@/app/components/notifications/useNotificationWorkspaceActions";
import { cn } from "@/lib/utils";
import { SegmentedToggle } from "@/app/components/filters";
import Rise from "@/app/components/motion/Rise";
import SectionHeader from "@/app/components/dashboard/SectionHeader";
import { Button } from "@/components/ui/button";
import {
    Pagination,
    PaginationContent,
    PaginationItem,
    PaginationNext,
    PaginationPrevious,
} from "@/components/ui/pagination";

const PAGE_SIZE = 20;

function matchesState(n: Notification, state: NotificationState): boolean {
    const inactive = Boolean(n.dismissedAt || n.resolvedAt);
    if (state === "unread") return !inactive && !n.readAt;
    if (state === "history") return inactive;
    if (state === "active") return !inactive;
    return true;
}

/**
 * Notifications inbox component.
 * @returns The notifications inbox component.
 */
export default function NotificationsInbox() {
    const t = useTranslations("Notifications");
    const locale = useLocale();
    const { recipientId, unread, refreshUnread } = useNotifications();
    const { openInNotificationWorkspace } = useNotificationWorkspaceActions();
    const [state, setState] = useState<NotificationState>("active");
    const [items, setItems] = useState<Notification[]>([]);
    const [page, setPage] = useState(1);
    const [total, setTotal] = useState(0);
    const [loading, setLoading] = useState(true);
    const [refreshKey, setRefreshKey] = useState(0);
    const loadGenerationRef = useRef(0);
    const requestRef = useRef<AbortController | null>(null);
    const serverStateVersionRef = useRef(0);
    const requiredStateVersionRef = useRef(0);

    useEffect(() => {
        const controller = new AbortController();
        const generation = ++loadGenerationRef.current;
        requestRef.current = controller;
        getNotifications({ state, page, size: PAGE_SIZE }, { signal: controller.signal })
            .then((result) => {
                if (loadGenerationRef.current !== generation) return;
                if (result.stateVersion < requiredStateVersionRef.current) return;
                serverStateVersionRef.current = result.stateVersion;
                setItems(result.items);
                setTotal(result.total);
            })
            .catch((error) => {
                if (!(error instanceof DOMException && error.name === "AbortError")) {
                    toastError(t("loadError"));
                }
            })
            .finally(() => {
                if (loadGenerationRef.current === generation) {
                    requestRef.current = null;
                    setLoading(false);
                }
            });
        return () => {
            controller.abort();
        };
    }, [page, state, t, refreshKey]);

    useEffect(
        () => onNotificationStateChanged(recipientId, ({ stateVersion, forceRefresh }) => {
            if (!forceRefresh && stateVersion <= serverStateVersionRef.current) return;
            requiredStateVersionRef.current = Math.max(requiredStateVersionRef.current, stateVersion);
            loadGenerationRef.current += 1;
            requestRef.current?.abort();
            requestRef.current = null;
            setLoading(true);
            setRefreshKey((current) => current + 1);
        }),
        [recipientId],
    );

    async function toggleRead(item: Notification) {
        try {
            const updated = item.readAt
                ? await markNotificationUnread(item.id)
                : await markNotificationRead(item.id);
            if (updated.stateVersion != null) {
                emitNotificationStateChanged(recipientId, updated.stateVersion);
            }
            if (matchesState(updated, state)) {
                setItems((current) => current.map((entry) => entry.id === updated.id ? updated : entry));
            } else {
                setItems((current) => current.filter((entry) => entry.id !== updated.id));
                setTotal((value) => Math.max(0, value - 1));
            }
            await refreshUnread();
        } catch {
            toastError(t("actionError"));
        }
    }

    async function dismiss(item: Notification) {
        try {
            const updated = await dismissNotification(item.id);
            if (updated.stateVersion != null) {
                emitNotificationStateChanged(recipientId, updated.stateVersion);
            }
            setItems((current) => current.filter((entry) => entry.id !== item.id));
            setTotal((value) => Math.max(0, value - 1));
            await refreshUnread();
        } catch {
            toastError(t("actionError"));
        }
    }

    async function snooze(item: Notification, hours: number) {
        try {
            const updated = await snoozeNotification(item.id, hours);
            if (updated.stateVersion != null) {
                emitNotificationStateChanged(recipientId, updated.stateVersion);
            }
            setItems((current) => current.filter((entry) => entry.id !== item.id));
            setTotal((value) => Math.max(0, value - 1));
            await refreshUnread();
        } catch {
            toastError(t("actionError"));
        }
    }

    async function logTouch(item: Notification) {
        const sourceId = item.sourceId;
        if (sourceId == null) return;
        try {
            const opened = await openInNotificationWorkspace(
                item, `/records/contacts/${sourceId}`);
            if (!opened) toastError(t("actionError"));
        } catch {
            toastError(t("actionError"));
        }
    }

    async function restore(item: Notification) {
        try {
            const updated = await restoreNotification(item.id);
            if (updated.stateVersion != null) {
                emitNotificationStateChanged(recipientId, updated.stateVersion);
            }
            if (matchesState(updated, state)) {
                setItems((current) => current.map((entry) => entry.id === updated.id ? updated : entry));
            } else {
                setItems((current) => current.filter((entry) => entry.id !== updated.id));
                setTotal((value) => Math.max(0, value - 1));
            }
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

    async function navigate(item: Notification) {
        const url = safeNotificationUrl(item.actionUrl);
        if (!url) {
            if (!item.readAt) await toggleRead(item);
            return;
        }
        try {
            const opened = await openInNotificationWorkspace(item, url, async () => {
                if (!item.readAt) await toggleRead(item);
            });
            if (!opened) toastError(t("actionError"));
        } catch {
            toastError(t("actionError"));
        }
    }

    const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-7xl flex-col gap-10">
                <Rise>
                    <header className="flex flex-wrap items-end justify-between gap-4 px-4 sm:px-6">
                        <div>
                            <h1 className="text-2xl font-semibold tracking-tight text-foreground">{t("title")}</h1>
                            <p className="mt-1 max-w-2xl text-sm text-muted-foreground">{t("inboxDescription")}</p>
                        </div>
                        <Button variant="outline" disabled={unread === 0} onClick={() => void readAll()}>
                            <CheckCheck />
                            {t("markAllRead")}
                        </Button>
                    </header>
                </Rise>

                <Rise delay={0.06} className="px-4 sm:px-6">
                    <SegmentedToggle<NotificationState>
                        ariaLabel={t("filterAria")}
                        value={state}
                        onChange={(value) => {
                            setLoading(true);
                            setState(value);
                            setPage(1);
                        }}
                        options={[
                            { value: "active", label: t("filter_active") },
                            { value: "unread", label: t("filter_unread") },
                            { value: "history", label: t("filter_history") },
                            { value: "all", label: t("filter_all") },
                        ]}
                    />
                </Rise>

                <Rise delay={0.12}>
                    <section>
                        <SectionHeader title={t("inbox")} />
                        <div className="overflow-hidden rounded-2xl border border-border bg-card">
                {loading ? (
                    <div className="divide-y divide-border">
                        {Array.from({ length: 6 }).map((_, i) => (
                            <div key={i} className="flex gap-4 px-5 py-4">
                                <span className="size-9 shrink-0 rounded-full bg-muted motion-safe:animate-pulse" />
                                <div className="flex-1 space-y-2 py-1">
                                    <div className="h-3.5 w-1/3 rounded bg-muted motion-safe:animate-pulse" />
                                    <div className="h-3 w-3/4 rounded bg-muted motion-safe:animate-pulse" />
                                </div>
                            </div>
                        ))}
                    </div>
                ) : items.length === 0 ? (
                    <div className="flex flex-col items-center gap-3 px-6 py-16 text-center">
                        <span className="flex size-12 items-center justify-center rounded-full bg-muted text-muted-foreground">
                            <CheckCircleIcon className="size-6" />
                        </span>
                        <div>
                            <p className="font-medium">{t("empty")}</p>
                            <p className="mt-1 text-sm text-muted-foreground">{t("emptyHint")}</p>
                        </div>
                    </div>
                ) : (
                    <div className="divide-y divide-border">
                        {items.map((item) => {
                            const content = notificationContent(item, t, locale);
                            const Icon = notificationIcon(item);
                            const style = notificationSeverityStyle(item.severity);
                            const reasons = item.data?.priorityReasons;
                            const hasPriority = Array.isArray(reasons) && reasons.length > 0;
                            const isNudge = item.type === "relationship.cooling";
                            return (
                                <article
                                    key={item.id}
                                    className={cn(
                                        "group flex gap-4 px-5 py-4 transition-colors hover:bg-muted/40",
                                        !item.readAt && "bg-muted/30",
                                    )}
                                >
                                    <span
                                        className={cn(
                                            "mt-0.5 flex size-9 shrink-0 items-center justify-center rounded-full",
                                            item.readAt ? "bg-foreground/5 text-muted-foreground" : style.chip,
                                        )}
                                    >
                                        <Icon className="size-5" />
                                    </span>
                                    <button
                                        type="button"
                                        onClick={() => void navigate(item)}
                                        className="min-w-0 flex-1 text-left"
                                    >
                                        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                                            <h2 className={cn("text-[15px]", item.readAt ? "font-medium" : "font-semibold")}>
                                                {content.title}
                                            </h2>
                                            {!item.readAt ? <span className={cn("size-1.5 shrink-0 rounded-full", style.dot)} /> : null}
                                            <span className="text-xs text-muted-foreground">
                                                {formatRelativeTime(item.triggeredAt, locale)}
                                            </span>
                                            {item.workspaceName ? (
                                                <span className="rounded-full bg-muted px-1.5 py-0.5 text-[11px] font-medium text-muted-foreground">
                                                    {item.workspaceName}
                                                </span>
                                            ) : null}
                                            {hasPriority ? (
                                                <span className="rounded-full bg-brand-light px-1.5 py-0.5 text-[11px] font-medium text-brand-dark">
                                                    {t("priority")}
                                                </span>
                                            ) : null}
                                        </div>
                                        {content.body ? <p className="mt-1 text-sm text-muted-foreground">{content.body}</p> : null}
                                    </button>
                                    <div className="flex shrink-0 items-center gap-1">
                                        {item.dismissedAt || item.resolvedAt ? (
                                            <Button
                                                variant="ghost"
                                                size="sm"
                                                onClick={() => void restore(item)}
                                            >
                                                <ArrowUturnLeftIcon />
                                                {t("restore")}
                                            </Button>
                                        ) : (
                                            <>
                                                {isNudge && item.sourceId != null ? (
                                                    <Button
                                                        variant="ghost"
                                                        size="sm"
                                                        onClick={() => void logTouch(item)}
                                                    >
                                                        {t("logTouch")}
                                                    </Button>
                                                ) : null}
                                                <Button
                                                    variant="ghost"
                                                    size="sm"
                                                    onClick={() => void toggleRead(item)}
                                                >
                                                    {item.readAt ? t("markUnread") : t("markRead")}
                                                </Button>
                                                <SnoozeMenu onSnooze={(hours) => void snooze(item, hours)} />
                                                <Button
                                                    variant="ghost"
                                                    size="icon-sm"
                                                    aria-label={t("dismiss")}
                                                    onClick={() => void dismiss(item)}
                                                >
                                                    <XMarkIcon />
                                                </Button>
                                            </>
                                        )}
                                    </div>
                                </article>
                            );
                        })}
                    </div>
                )}
                        </div>
                    </section>
                </Rise>

                {pageCount > 1 ? (
                    <Rise delay={0.18}>
                        <Pagination>
                            <PaginationContent>
                                <PaginationItem>
                                    <PaginationPrevious
                                        disabled={page <= 1}
                                        onClick={() => {
                                            setLoading(true);
                                            setPage((value) => Math.max(1, value - 1));
                                        }}
                                    />
                                </PaginationItem>
                                <PaginationItem>
                                    <span className="px-3 text-sm text-muted-foreground">{t("page", { page, pageCount })}</span>
                                </PaginationItem>
                                <PaginationItem>
                                    <PaginationNext
                                        disabled={page >= pageCount}
                                        onClick={() => {
                                            setLoading(true);
                                            setPage((value) => Math.min(pageCount, value + 1));
                                        }}
                                    />
                                </PaginationItem>
                            </PaginationContent>
                        </Pagination>
                    </Rise>
                ) : null}
            </div>
        </div>
    );
}
