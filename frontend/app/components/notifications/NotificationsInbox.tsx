"use client";

import { ArrowUturnLeftIcon, BellSnoozeIcon, CheckCircleIcon, ExclamationTriangleIcon, FunnelIcon, XMarkIcon } from "@heroicons/react/24/outline";
import { CheckCheck } from "lucide-react";
import { useReducedMotion } from "motion/react";
import { useLocale, useTranslations } from "next-intl";
import { useEffect, useRef, useState } from "react";

import {
    ApiError,
    completeTask,
    dismissNotification,
    getNotificationFacets,
    getNotifications,
    markAllNotificationsRead,
    markNotificationRead,
    markNotificationUnread,
    restoreNotification,
    snoozeNotification,
    unsnoozeNotification,
} from "@/app/lib/api";
import { type Notification, type NotificationFacets, type NotificationState, type SnoozeRequest } from "@/app/lib/types";
import { formatDateTime, formatRelativeTime } from "@/app/lib/utils";
import { toastError } from "@/app/lib/toast";
import { useNotifications } from "@/app/hooks/useNotifications";
import { notificationContent, notificationIcon, notificationSeverityStyle, safeNotificationUrl } from "@/app/components/notifications/notificationContent";
import {
    emitAllNotificationsRead,
    emitNotificationStateChanged,
    onNotificationStateChanged,
} from "@/app/components/notifications/notificationEvents";
import { SnoozeMenu } from "@/app/components/notifications/SnoozeMenu";
import { isNotificationSnoozedAt } from "@/app/components/notifications/notificationSnooze";
import { useNotificationWorkspaceActions } from "@/app/components/notifications/useNotificationWorkspaceActions";
import { cn } from "@/lib/utils";
import { FilterBar, MultiSelectFilter, RadioFilter, SegmentedToggle } from "@/app/components/filters";
import Rise from "@/app/components/motion/Rise";
import { PageHeader } from "@/app/components/PageHeader";
import SectionHeader from "@/app/components/dashboard/SectionHeader";
import { PageShell } from "@/app/components/PageShell";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
    Pagination,
    PaginationContent,
    PaginationItem,
    PaginationNext,
    PaginationPrevious,
} from "@/components/ui/pagination";

const PAGE_SIZE = 20;

type NotificationRowAction = "complete" | "other";

function matchesState(n: Notification, state: NotificationState, asOf: string): boolean {
    const inactive = Boolean(n.dismissedAt || n.resolvedAt);
    const snoozed = isNotificationSnoozedAt(n, asOf);
    if (state === "snoozed") return snoozed;
    if (state === "unread") return !inactive && !snoozed && !n.readAt;
    if (state === "history") return inactive;
    if (state === "active") return !inactive && !snoozed;
    return true;
}

/**
 * Notifications inbox component.
 * @returns The notifications inbox component.
 */
export default function NotificationsInbox() {
    const t = useTranslations("Notifications");
    const tf = useTranslations("Filters");
    const locale = useLocale();
    const reduce = useReducedMotion() ?? false;
    const { recipientId, unread, snoozed, refreshUnread } = useNotifications();
    const { executeInNotificationWorkspace, openInNotificationWorkspace } = useNotificationWorkspaceActions();
    const [state, setState] = useState<NotificationState>("active");
    const [items, setItems] = useState<Notification[]>([]);
    const [pendingActions, setPendingActions] = useState<Map<number, NotificationRowAction>>(new Map());
    const [page, setPage] = useState(1);
    const [total, setTotal] = useState(0);
    const [pageAsOf, setPageAsOf] = useState("");
    const [loading, setLoading] = useState(true);
    const [categories, setCategories] = useState<Set<string>>(new Set());
    const [severities, setSeverities] = useState<Set<string>>(new Set());
    const [workspaceFilter, setWorkspaceFilter] = useState("all");
    const [facets, setFacets] = useState<NotificationFacets | null>(null);
    const [refreshKey, setRefreshKey] = useState(0);
    const [facetRefreshKey, setFacetRefreshKey] = useState(0);
    const [listRetrying, setListRetrying] = useState(false);
    const [facetRetrying, setFacetRetrying] = useState(false);
    const [loadFailure, setLoadFailure] = useState<{ recipientId: number | null; generation: number } | null>(null);
    const [facetFailure, setFacetFailure] = useState<{ recipientId: number | null; generation: number } | null>(null);
    const loadGenerationRef = useRef(0);
    const requestRef = useRef<AbortController | null>(null);
    const facetGenerationRef = useRef(0);
    const facetRequestRef = useRef<AbortController | null>(null);
    const serverStateVersionRef = useRef(0);
    const facetStateVersionRef = useRef(0);
    const requiredStateVersionRef = useRef(0);
    const workspaceFilterRef = useRef("all");
    const filterRegionRef = useRef<HTMLDivElement | null>(null);
    const workspaceFilterControlRef = useRef<HTMLSpanElement | null>(null);
    const workspaceMenuOpenRef = useRef(false);
    const inboxSectionRef = useRef<HTMLElement | null>(null);
    const retryingLoadRef = useRef(false);
    const retryingFacetsRef = useRef(false);
    const pendingRowsRef = useRef<Set<number>>(new Set());
    const optimisticTotalRef = useRef(0);

    function focusPrimaryFilter() {
        requestAnimationFrame(() => {
            filterRegionRef.current?.querySelector<HTMLButtonElement>("button")?.focus();
        });
    }

    useEffect(() => {
        serverStateVersionRef.current = 0;
        facetStateVersionRef.current = 0;
        requiredStateVersionRef.current = 0;
    }, [recipientId]);

    useEffect(() => {
        const controller = new AbortController();
        const generation = ++loadGenerationRef.current;
        requestRef.current = controller;
        getNotifications({
            status: state,
            category: categories.size > 0 ? Array.from(categories) : undefined,
            severity: severities.size > 0 ? Array.from(severities) : undefined,
            workspaceId: workspaceFilter === "all" ? undefined : Number(workspaceFilter),
            page,
            size: PAGE_SIZE,
        }, { signal: controller.signal })
            .then((result) => {
                if (loadGenerationRef.current !== generation) return;
                if (result.stateVersion < requiredStateVersionRef.current) {
                    loadGenerationRef.current += 1;
                    setRefreshKey((current) => current + 1);
                    return;
                }
                const restoreFocus = retryingLoadRef.current;
                retryingLoadRef.current = false;
                serverStateVersionRef.current = result.stateVersion;
                setLoadFailure(null);
                setListRetrying(false);
                setItems(result.items);
                optimisticTotalRef.current = result.total;
                setTotal(result.total);
                setPageAsOf(result.asOf);
                if (restoreFocus) {
                    requestAnimationFrame(() => inboxSectionRef.current?.focus());
                }
            })
            .catch((error) => {
                if (error instanceof DOMException && error.name === "AbortError") return;
                if (loadGenerationRef.current !== generation) return;
                retryingLoadRef.current = false;
                setListRetrying(false);
                setLoadFailure({ recipientId, generation });
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
    }, [categories, page, recipientId, refreshKey, severities, state, t, workspaceFilter]);

    useEffect(() => {
        const controller = new AbortController();
        const generation = ++facetGenerationRef.current;
        facetRequestRef.current = controller;
        getNotificationFacets({ signal: controller.signal })
            .then((result) => {
                if (facetGenerationRef.current !== generation) return;
                if (result.stateVersion < requiredStateVersionRef.current) {
                    facetGenerationRef.current += 1;
                    setFacetRefreshKey((current) => current + 1);
                    return;
                }
                const restoreFocus = retryingFacetsRef.current;
                retryingFacetsRef.current = false;
                facetStateVersionRef.current = result.stateVersion;
                setFacetFailure(null);
                setFacetRetrying(false);
                setFacets(result);
                const currentWorkspace = workspaceFilterRef.current;
                if (currentWorkspace !== "all"
                        && !result.workspaces.some((facet) => facet.key === currentWorkspace)) {
                    const workspaceHadFocus = (workspaceFilterControlRef.current?.contains(document.activeElement) ?? false)
                        || workspaceMenuOpenRef.current;
                    loadGenerationRef.current += 1;
                    requestRef.current?.abort();
                    requestRef.current = null;
                    setLoadFailure(null);
                    setLoading(true);
                    setPage(1);
                    workspaceFilterRef.current = "all";
                    workspaceMenuOpenRef.current = false;
                    setWorkspaceFilter("all");
                    if (workspaceHadFocus) focusPrimaryFilter();
                }
                if (restoreFocus) focusPrimaryFilter();
            })
            .catch((error) => {
                if (error instanceof DOMException && error.name === "AbortError") return;
                if (facetGenerationRef.current !== generation) return;
                retryingFacetsRef.current = false;
                setFacetRetrying(false);
                setFacetFailure({ recipientId, generation });
            })
            .finally(() => {
                if (facetGenerationRef.current === generation) {
                    facetRequestRef.current = null;
                }
            });
        return () => {
            controller.abort();
        };
    }, [facetRefreshKey, recipientId, refreshKey, t]);

    useEffect(
        () => onNotificationStateChanged(recipientId, ({ stateVersion, forceRefresh }) => {
            if (!forceRefresh
                    && stateVersion <= serverStateVersionRef.current
                    && stateVersion <= facetStateVersionRef.current) return;
            const restoreLoadFocus = retryingLoadRef.current;
            const restoreFacetFocus = retryingFacetsRef.current;
            requiredStateVersionRef.current = Math.max(requiredStateVersionRef.current, stateVersion);
            loadGenerationRef.current += 1;
            requestRef.current?.abort();
            requestRef.current = null;
            facetGenerationRef.current += 1;
            facetRequestRef.current?.abort();
            facetRequestRef.current = null;
            retryingLoadRef.current = false;
            retryingFacetsRef.current = false;
            setLoadFailure(null);
            setFacetFailure(null);
            setListRetrying(false);
            setFacetRetrying(false);
            setLoading(true);
            setRefreshKey((current) => current + 1);
            if (restoreLoadFocus) {
                requestAnimationFrame(() => inboxSectionRef.current?.focus());
            }
            if (restoreFacetFocus) focusPrimaryFilter();
        }),
        [recipientId],
    );

    function startRowAction(itemId: number, action: NotificationRowAction) {
        if (pendingRowsRef.current.has(itemId)) return false;
        pendingRowsRef.current.add(itemId);
        setPendingActions((current) => new Map(current).set(itemId, action));
        return true;
    }

    function finishRowAction(itemId: number) {
        pendingRowsRef.current.delete(itemId);
        setPendingActions((current) => {
            const next = new Map(current);
            next.delete(itemId);
            return next;
        });
    }

    async function toggleRead(item: Notification) {
        if (!startRowAction(item.id, "other")) return;
        try {
            const updated = item.readAt
                ? await markNotificationUnread(item.id)
                : await markNotificationRead(item.id);
            if (updated.stateVersion != null) {
                emitNotificationStateChanged(recipientId, updated.stateVersion);
            }
            if (matchesState(updated, state, pageAsOf)) {
                setItems((current) => current.map((entry) => entry.id === updated.id ? updated : entry));
            } else {
                removeNotification(updated.id);
            }
            await refreshUnread();
        } catch {
            toastError(t("actionError"));
        } finally {
            finishRowAction(item.id);
        }
    }

    async function dismiss(item: Notification) {
        if (!startRowAction(item.id, "other")) return;
        try {
            const updated = await dismissNotification(item.id);
            if (updated.stateVersion != null) {
                emitNotificationStateChanged(recipientId, updated.stateVersion);
            }
            removeNotification(item.id);
            await refreshUnread();
        } catch {
            toastError(t("actionError"));
        } finally {
            finishRowAction(item.id);
        }
    }

    function removeNotification(itemId: number, restoreFocus = false) {
        const rowButtons = Array.from(
            inboxSectionRef.current?.querySelectorAll<HTMLButtonElement>("[data-notification-row]") ?? [],
        );
        const itemIndex = rowButtons.findIndex((button) => button.dataset.notificationRow === String(itemId));
        if (itemIndex < 0) return;
        const focusTarget = [
            ...rowButtons.slice(itemIndex + 1),
            ...rowButtons.slice(0, itemIndex).reverse(),
        ].find((button) => {
            const rowId = Number(button.dataset.notificationRow);
            return !button.disabled && !pendingRowsRef.current.has(rowId);
        }) ?? null;
        const nextTotal = Math.max(0, optimisticTotalRef.current - 1);
        optimisticTotalRef.current = nextTotal;
        setItems((current) => current.filter((entry) => entry.id !== itemId));
        setTotal(nextTotal);
        const nextPageCount = Math.max(1, Math.ceil(nextTotal / PAGE_SIZE));
        if (page > nextPageCount) {
            beginQueryChange();
            retryingLoadRef.current = true;
            setPage(nextPageCount);
            return;
        }
        if (!restoreFocus) return;
        requestAnimationFrame(() => {
            const targetId = Number(focusTarget?.dataset.notificationRow);
            if (focusTarget?.isConnected
                    && !focusTarget.disabled
                    && !pendingRowsRef.current.has(targetId)) focusTarget.focus();
            else inboxSectionRef.current?.focus();
        });
    }

    async function completeFromInbox(item: Notification) {
        const taskId = item.sourceId;
        if (
            item.type !== "task.due" ||
            item.sourceType !== "task" ||
            taskId == null ||
            !startRowAction(item.id, "complete")
        ) {
            return;
        }
        try {
            const completed = await executeInNotificationWorkspace(item, async () => {
                await completeTask(taskId);
                removeNotification(item.id, true);
                await refreshUnread();
            }, "/notifications");
            if (!completed) toastError(t("completeError"));
        } catch {
            toastError(t("completeError"));
        } finally {
            finishRowAction(item.id);
        }
    }

    function refetch() {
        const restoreFacetFocus = retryingFacetsRef.current;
        retryingLoadRef.current = loadFailed;
        retryingFacetsRef.current = false;
        loadGenerationRef.current += 1;
        requestRef.current?.abort();
        requestRef.current = null;
        facetGenerationRef.current += 1;
        facetRequestRef.current?.abort();
        facetRequestRef.current = null;
        setLoadFailure(null);
        setFacetFailure(null);
        setListRetrying(loadFailed);
        setFacetRetrying(false);
        setLoading(true);
        setRefreshKey((current) => current + 1);
        if (restoreFacetFocus) focusPrimaryFilter();
    }

    function retryFacets() {
        retryingFacetsRef.current = true;
        facetGenerationRef.current += 1;
        facetRequestRef.current?.abort();
        facetRequestRef.current = null;
        setFacetFailure(null);
        setFacetRetrying(true);
        setFacetRefreshKey((current) => current + 1);
    }

    async function snooze(item: Notification, body: SnoozeRequest) {
        if (!startRowAction(item.id, "other")) return;
        try {
            const updated = await snoozeNotification(item.id, body);
            if (updated.stateVersion != null) {
                emitNotificationStateChanged(recipientId, updated.stateVersion);
            }
            if (matchesState(updated, state, pageAsOf)) {
                setItems((current) => current.map((entry) => entry.id === updated.id ? updated : entry));
            } else {
                removeNotification(item.id);
            }
            await refreshUnread();
        } catch (error) {
            if (error instanceof ApiError && error.status === 409) {
                toastError(t("snoozeConflict"));
                refetch();
            } else if (error instanceof ApiError && error.status === 404) {
                toastError(t("snoozeGone"));
                refetch();
            } else {
                toastError(t("actionError"));
            }
        } finally {
            finishRowAction(item.id);
        }
    }

    async function unsnooze(item: Notification) {
        if (!startRowAction(item.id, "other")) return;
        try {
            const updated = await unsnoozeNotification(item.id);
            if (updated.stateVersion != null) {
                emitNotificationStateChanged(recipientId, updated.stateVersion);
            }
            if (matchesState(updated, state, pageAsOf)) {
                setItems((current) => current.map((entry) => entry.id === updated.id ? updated : entry));
            } else {
                removeNotification(item.id);
            }
            await refreshUnread();
        } catch (error) {
            if (error instanceof ApiError && error.status === 404) {
                toastError(t("snoozeGone"));
                refetch();
            } else {
                toastError(t("actionError"));
            }
        } finally {
            finishRowAction(item.id);
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
        if (!startRowAction(item.id, "other")) return;
        try {
            const updated = await restoreNotification(item.id);
            if (updated.stateVersion != null) {
                emitNotificationStateChanged(recipientId, updated.stateVersion);
            }
            if (matchesState(updated, state, pageAsOf)) {
                setItems((current) => current.map((entry) => entry.id === updated.id ? updated : entry));
            } else {
                removeNotification(updated.id);
            }
            await refreshUnread();
        } catch {
            toastError(t("actionError"));
        } finally {
            finishRowAction(item.id);
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

    function toggleCategory(value: string) {
        beginQueryChange();
        setPage(1);
        setCategories((current) => {
            const next = new Set(current);
            if (next.has(value)) next.delete(value);
            else next.add(value);
            return next;
        });
    }

    function toggleSeverity(value: string) {
        beginQueryChange();
        setPage(1);
        setSeverities((current) => {
            const next = new Set(current);
            if (next.has(value)) next.delete(value);
            else next.add(value);
            return next;
        });
    }

    function changeWorkspace(value: string) {
        if (value === workspaceFilter) return;
        beginQueryChange();
        setPage(1);
        workspaceFilterRef.current = value;
        setWorkspaceFilter(value);
        if (value === "all" && (facets?.workspaces.length ?? 0) <= 1) {
            focusPrimaryFilter();
        }
    }

    function clearFacetFilters() {
        beginQueryChange();
        setPage(1);
        setCategories(new Set());
        setSeverities(new Set());
        workspaceFilterRef.current = "all";
        setWorkspaceFilter("all");
        focusPrimaryFilter();
    }

    function clearCategories() {
        beginQueryChange();
        setPage(1);
        setCategories(new Set());
        focusPrimaryFilter();
    }

    function clearSeverities() {
        beginQueryChange();
        setPage(1);
        setSeverities(new Set());
        focusPrimaryFilter();
    }

    function beginQueryChange() {
        loadGenerationRef.current += 1;
        requestRef.current?.abort();
        requestRef.current = null;
        retryingLoadRef.current = false;
        setLoadFailure(null);
        setListRetrying(false);
        setLoading(true);
    }

    const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));
    const loadFailed = loadFailure?.recipientId === recipientId;
    const facetFailed = facetFailure?.recipientId === recipientId;
    const hasFacetFilters = categories.size > 0 || severities.size > 0 || workspaceFilter !== "all";
    const categoryLabels = new Map<string, string>([
        ["activity", t("categoryActivity")],
        ["company", t("categoryCompany")],
        ["deal", t("categoryDeal")],
        ["introduction", t("categoryIntroduction")],
        ["note", t("categoryNote")],
        ["person", t("categoryPerson")],
        ["relationship", t("categoryRelationship")],
        ["task", t("categoryTask")],
        ["workspace", t("categoryWorkspace")],
    ]);
    const severityLabels = new Map<string, string>([
        ["critical", t("severityCritical")],
        ["warning", t("severityWarning")],
        ["info", t("severityInfo")],
    ]);
    const categoryOptions = facets?.categories.map((facet) => ({
        value: facet.key,
        label: categoryLabels.get(facet.key) ?? facet.label ?? facet.key,
        total: facet.count,
    })) ?? [];
    const severityOptions = facets?.severities.map((facet) => ({
        value: facet.key,
        label: severityLabels.get(facet.key) ?? facet.label ?? facet.key,
        total: facet.count,
    })) ?? [];
    const workspaceOptions = [
        { value: "all", label: t("filterAllWorkspaces") },
        ...(facets?.workspaces.map((facet) => ({
            value: facet.key,
            label: facet.label ?? facet.key,
            count: facet.count,
        })) ?? []),
    ];
    const selectedWorkspaceLabel = workspaceOptions.find((option) => option.value === workspaceFilter)?.label
        ?? t("filterWorkspace");

    return (
        <PageShell tier="wide">
                <Rise>
                    <PageHeader
                        className="px-4 sm:px-6"
                        variant="compact"
                        title={t("title")}
                        description={t("inboxDescription")}
                        actions={
                            <Button variant="outline" disabled={unread === 0} onClick={() => void readAll()}>
                                <CheckCheck />
                                {t("markAllRead")}
                            </Button>
                        }
                    />
                </Rise>

                <Rise delay={0.06} className="px-4 sm:px-6">
                    <div ref={filterRegionRef}>
                        <FilterBar
                            chips={[]}
                            hasActiveFilters={hasFacetFilters}
                            onClearAll={clearFacetFilters}
                            clearAllLabel={tf("clearAll")}
                            reduce={reduce}
                        >
                        <SegmentedToggle<NotificationState>
                            ariaLabel={t("filterAria")}
                            value={state}
                            onChange={(value) => {
                                if (value === state) return;
                                beginQueryChange();
                                setState(value);
                                setPage(1);
                            }}
                            options={[
                                { value: "active", label: t("filter_active") },
                                { value: "unread", label: t("filter_unread") },
                                {
                                    value: "snoozed",
                                    label: (
                                        <span className="inline-flex items-center gap-1.5">
                                            {t("filter_snoozed")}
                                            {snoozed > 0 ? (
                                                <span className="inline-flex min-w-4 items-center justify-center rounded-full bg-foreground/10 px-1 text-[10px] font-semibold leading-4 text-foreground">
                                                    {snoozed > 99 ? "99+" : snoozed}
                                                </span>
                                            ) : null}
                                        </span>
                                    ),
                                },
                                { value: "history", label: t("filter_history") },
                                { value: "all", label: t("filter_all") },
                            ]}
                        />
                        {categoryOptions.length > 1 || categories.size > 0 ? (
                            <MultiSelectFilter
                                label={t("filterCategory")}
                                ariaLabel={categories.size > 0
                                    ? t("filterCategorySelected", { count: categories.size })
                                    : t("filterCategory")}
                                options={categoryOptions}
                                selected={categories}
                                onToggle={toggleCategory}
                                onClear={clearCategories}
                                clearLabel={tf("clear")}
                            />
                        ) : null}
                        {severityOptions.length > 1 || severities.size > 0 ? (
                            <MultiSelectFilter
                                label={t("filterSeverity")}
                                ariaLabel={severities.size > 0
                                    ? t("filterSeveritySelected", { count: severities.size })
                                    : t("filterSeverity")}
                                options={severityOptions}
                                selected={severities}
                                onToggle={toggleSeverity}
                                onClear={clearSeverities}
                                clearLabel={tf("clear")}
                            />
                        ) : null}
                        {workspaceOptions.length > 2 || workspaceFilter !== "all" ? (
                            <span ref={workspaceFilterControlRef} className="contents">
                                <RadioFilter
                                    label={t("filterWorkspace")}
                                    ariaLabel={workspaceFilter === "all"
                                        ? t("filterWorkspace")
                                        : t("filterWorkspaceSelected", { workspace: selectedWorkspaceLabel })}
                                    value={workspaceFilter}
                                    onValueChange={changeWorkspace}
                                    onOpenChange={(open) => {
                                        workspaceMenuOpenRef.current = open;
                                    }}
                                    options={workspaceOptions}
                                />
                            </span>
                        ) : null}
                            {facetFailed || facetRetrying ? (
                                <div role="alert" aria-busy={facetRetrying} className="inline-flex min-h-9 items-center gap-2 rounded-full border border-destructive/30 bg-destructive/5 px-3 text-xs text-destructive">
                                    <ExclamationTriangleIcon className="size-4 shrink-0" />
                                    <span>{t("facetLoadError")}</span>
                                    <Button
                                        variant="outline"
                                        size="xs"
                                        className="transition-none active:not-aria-[haspopup]:translate-y-0 motion-reduce:transform-none aria-disabled:pointer-events-none aria-disabled:opacity-50"
                                        aria-disabled={facetRetrying}
                                        onClick={() => {
                                            if (!facetRetrying) retryFacets();
                                        }}
                                    >
                                        {facetRetrying ? t("loadingFilters") : t("retryFilters")}
                                    </Button>
                                </div>
                            ) : null}
                        </FilterBar>
                    </div>
                </Rise>

                <Rise delay={0.12}>
                    <section ref={inboxSectionRef} tabIndex={-1} aria-label={t("inbox")} aria-busy={loading} className="outline-none">
                        <SectionHeader title={t("inbox")} />
                        <p className="sr-only" aria-live="polite" aria-atomic="true">
                            {loading || loadFailed || listRetrying ? "" : t("resultCount", { count: total })}
                        </p>
                        <div className="overflow-hidden rounded-2xl border border-border bg-card">
                {loadFailed || listRetrying ? (
                    <div role="alert" className="flex flex-col items-center px-6 py-16 text-center">
                        <ExclamationTriangleIcon className="size-7 text-destructive" />
                        <h2 className="mt-4 text-base font-semibold text-foreground">{t("loadError")}</h2>
                        <p className="mt-1 max-w-md text-sm text-muted-foreground">{t("loadErrorHint")}</p>
                        <Button
                            variant="outline"
                            className="mt-5 transition-none active:not-aria-[haspopup]:translate-y-0 motion-reduce:transform-none aria-disabled:pointer-events-none aria-disabled:opacity-50"
                            aria-disabled={listRetrying}
                            onClick={() => {
                                if (!listRetrying) refetch();
                            }}
                        >
                            {listRetrying ? t("loading") : t("retry")}
                        </Button>
                    </div>
                ) : loading ? (
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
                            {hasFacetFilters ? <FunnelIcon className="size-6" /> : <CheckCircleIcon className="size-6" />}
                        </span>
                        <div>
                            <p className="font-medium">{hasFacetFilters ? tf("noMatchesTitle") : t("empty")}</p>
                            <p className="mt-1 text-sm text-muted-foreground">
                                {hasFacetFilters ? tf("noMatchesBody") : t("emptyHint")}
                            </p>
                        </div>
                        {hasFacetFilters ? (
                            <Button variant="outline" size="sm" onClick={clearFacetFilters}>
                                {tf("clearAll")}
                            </Button>
                        ) : null}
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
                            const isTaskReminder = item.type === "task.due"
                                && item.sourceType === "task"
                                && item.sourceId != null;
                            const isSnoozed = isNotificationSnoozedAt(item, pageAsOf);
                            const pendingAction = pendingActions.get(item.id);
                            const isCompleting = pendingAction === "complete";
                            const isPending = pendingAction != null;
                            return (
                                <article
                                    key={item.id}
                                    aria-busy={isPending}
                                    className={cn(
                                        "group flex flex-wrap gap-4 px-5 py-4 transition-colors hover:bg-muted/40 sm:flex-nowrap",
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
                                        data-notification-row={item.id}
                                        disabled={isPending}
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
                                    <div className="flex basis-full flex-wrap items-center justify-end gap-1 sm:basis-auto sm:flex-nowrap">
                                        {item.dismissedAt || item.resolvedAt ? (
                                            <Button
                                                variant="ghost"
                                                size="sm"
                                                disabled={isPending}
                                                onClick={() => void restore(item)}
                                            >
                                                <ArrowUturnLeftIcon />
                                                {t("restore")}
                                            </Button>
                                        ) : isSnoozed ? (
                                            <>
                                                <span className="hidden text-xs text-muted-foreground sm:inline">
                                                    {t("snoozedUntil", { date: formatDateTime(item.snoozedUntil ?? undefined, locale) })}
                                                </span>
                                                <Button
                                                    variant="ghost"
                                                    size="sm"
                                                    disabled={isPending}
                                                    onClick={() => void unsnooze(item)}
                                                >
                                                    <BellSnoozeIcon />
                                                    {t("unsnooze")}
                                                </Button>
                                            </>
                                        ) : (
                                            <>
                                                {isTaskReminder ? (
                                                    <Checkbox
                                                        checked={isCompleting}
                                                        disabled={isPending}
                                                        onCheckedChange={(value) => {
                                                            if (value === true) void completeFromInbox(item);
                                                        }}
                                                        aria-label={isCompleting ? t("completingTask") : t("completeTask")}
                                                        className="mr-1 size-[18px] rounded-full border-border transition data-[state=checked]:border-brand data-[state=checked]:bg-brand data-[state=checked]:text-brand-foreground"
                                                    />
                                                ) : null}
                                                {isNudge && item.sourceId != null ? (
                                                    <Button
                                                        variant="ghost"
                                                        size="sm"
                                                        disabled={isPending}
                                                        onClick={() => void logTouch(item)}
                                                    >
                                                        {t("logTouch")}
                                                    </Button>
                                                ) : null}
                                                <Button
                                                    variant="ghost"
                                                    size="sm"
                                                    disabled={isPending}
                                                    onClick={() => void toggleRead(item)}
                                                >
                                                    {item.readAt ? t("markUnread") : t("markRead")}
                                                </Button>
                                                <SnoozeMenu
                                                    disabled={isPending}
                                                    onSnooze={(body) => void snooze(item, body)}
                                                />
                                                <Button
                                                    variant="ghost"
                                                    size="icon-sm"
                                                    aria-label={t("dismiss")}
                                                    disabled={isPending}
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

                {!loading && !loadFailed && !listRetrying && pageCount > 1 ? (
                    <Rise delay={0.18}>
                        <Pagination>
                            <PaginationContent>
                                <PaginationItem>
                                    <PaginationPrevious
                                        disabled={page <= 1}
                                        onClick={() => {
                                            beginQueryChange();
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
                                            beginQueryChange();
                                            setPage((value) => Math.min(pageCount, value + 1));
                                        }}
                                    />
                                </PaginationItem>
                            </PaginationContent>
                        </Pagination>
                    </Rise>
                ) : null}
        </PageShell>
    );
}
