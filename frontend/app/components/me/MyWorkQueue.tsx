"use client";

import {
    useCallback,
    useEffect,
    useRef,
    useState,
    type MouseEvent as ReactMouseEvent,
    type PointerEvent as ReactPointerEvent,
} from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useFormatter, useTranslations } from "next-intl";
import {
    AdjustmentsHorizontalIcon,
    ArrowRightIcon,
    CheckIcon,
    ChevronLeftIcon,
    ChevronRightIcon,
    ClipboardDocumentCheckIcon,
    CurrencyYenIcon,
    LockClosedIcon,
    ShieldCheckIcon,
    XMarkIcon,
} from "@heroicons/react/24/outline";
import { EllipsisHorizontalIcon } from "@heroicons/react/20/solid";

import {
    ApiError,
    completeMyWorkTask,
    decideMyWorkApproval,
    dismissMyWorkNotification,
    getMyWork,
    snoozeMyWorkNotification,
} from "@/app/lib/api";
import type { CookieResult } from "@/app/lib/api";
import type {
    SnoozeRequest,
    WorkItem,
    WorkItemPage,
    WorkItemSource,
    WorkItemUrgency,
} from "@/app/lib/types";
import { cn } from "@/lib/utils";
import { toastError, toastSuccess, toastWarn } from "@/app/lib/toast";
import { emitNotificationStateChanged } from "@/app/components/notifications/notificationEvents";
import { dealDocumentsHref } from "@/app/components/records/deals/dealLinks";
import { useMediaQuery } from "@/app/components/calendar/useMediaQuery";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { Skeleton } from "@/components/ui/skeleton";
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Textarea } from "@/components/ui/textarea";
import {
    ResponsiveDialog,
    ResponsiveDialogContent,
    ResponsiveDialogDescription,
    ResponsiveDialogTitle,
} from "@/components/ui/responsive-dialog";
import {
    Drawer,
    DrawerContent,
    DrawerDescription,
    DrawerHeader,
    DrawerTitle,
} from "@/components/ui/drawer";
import {
    Pagination,
    PaginationContent,
    PaginationItem,
    PaginationNext,
    PaginationPrevious,
} from "@/components/ui/pagination";
import { MultiSelectFilter } from "@/app/components/filters/FilterPill";
import { SnoozeMenu } from "@/app/components/notifications/SnoozeMenu";
import { EmptyState } from "@/app/components/EmptyState";
import SectionUnavailable from "@/app/components/SectionUnavailable";
import SectionHeader from "@/app/components/dashboard/SectionHeader";
import ConfirmDiscardDialog from "@/app/components/ConfirmDiscardDialog";
import { useUnsavedChangesGuard } from "@/app/hooks/useUnsavedChangesGuard";

const URGENCY_TONE: Record<WorkItemUrgency, string> = {
    critical: "bg-risk-high/12 text-foreground ring-risk-high/40",
    high: "bg-risk-medium/12 text-foreground ring-risk-medium/40",
    normal: "bg-muted text-muted-foreground ring-border",
    low: "bg-muted text-muted-foreground ring-border",
};

const URGENCY_DOT: Record<WorkItemUrgency, string> = {
    critical: "bg-risk-high",
    high: "bg-risk-medium",
    normal: "bg-muted-foreground/40",
    low: "bg-muted-foreground/25",
};

const SOURCE_ICON: Record<WorkItemSource, React.ComponentType<{ className?: string }>> = {
    task: ClipboardDocumentCheckIcon,
    notification: CurrencyYenIcon,
    document_approval: ShieldCheckIcon,
};

const SOURCES: WorkItemSource[] = ["task", "notification", "document_approval"];
const URGENCIES: WorkItemUrgency[] = ["critical", "high", "normal", "low"];
const AUTO_ADVANCE_ACTION_GUARD_MS = 500;

type Query = {
    page: number;
    sources: ReadonlySet<string>;
    urgencies: ReadonlySet<string>;
};

const DEFAULT_QUERY: Query = { page: 1, sources: new Set(), urgencies: new Set() };

function setEquals(a: ReadonlySet<string>, b: ReadonlySet<string>): boolean {
    return a.size === b.size && [...a].every((value) => b.has(value));
}

function sameQuery(a: Query, b: Query): boolean {
    return a.page === b.page && setEquals(a.sources, b.sources) && setEquals(a.urgencies, b.urgencies);
}

function isDefaultQuery(query: Query): boolean {
    return query.page === 1 && query.sources.size === 0 && query.urgencies.size === 0;
}

function detailIdAfterRemoval(items: readonly WorkItem[], removedId: string): string | null {
    const removedIndex = items.findIndex((item) => item.id === removedId);
    if (removedIndex < 0) return null;
    const remaining = items.filter((item) => item.id !== removedId);
    return remaining[removedIndex]?.id ?? remaining[removedIndex - 1]?.id ?? null;
}

type Loaded = { page: WorkItemPage; query: Query };
type LoadFailure = "failed" | "forbidden" | null;
type RejectDraft = { id: string; comment: string };

type Props = {
    userId: number;
    initial: CookieResult<WorkItemPage>;
};

/**
 * The actionable, deterministically ranked queue at the heart of My Work: tasks,
 * deal-close notifications, and approval steps from the merged projection, each with
 * its reason, urgency, and version-guarded in-place actions.
 *
 * <p>Every rendered claim is bound to the query that produced it: a result commits only
 * while it still answers the user's current page/filter selection, the server-rendered
 * default projection is adopted only while the default query is active, and a later
 * failed refresh demotes retained rows to an explicitly stale presentation with no
 * exact-count or caught-up claims. A forbidden filter renders a permission state, never
 * another query's rows.
 */
export default function MyWorkQueue({ userId, initial }: Props) {
    const t = useTranslations("MePage");
    const format = useFormatter();
    const router = useRouter();
    const wide = useMediaQuery("(min-width: 768px)");
    const [query, setQuery] = useState<Query>(DEFAULT_QUERY);
    const [loaded, setLoaded] = useState<Loaded | null>(
        initial.ok ? { page: initial.data, query: DEFAULT_QUERY } : null,
    );
    const [loadFailure, setLoadFailure] = useState<LoadFailure>(initial.ok ? null : "failed");
    const [inFlight, setInFlight] = useState(false);
    const [pendingIds, setPendingIds] = useState<ReadonlySet<string>>(new Set());
    const [rejecting, setRejecting] = useState<RejectDraft | null>(null);
    const [rejectBusy, setRejectBusy] = useState(false);
    const [detailId, setDetailId] = useState<string | null>(null);
    const [actionGuardId, setActionGuardId] = useState<string | null>(null);
    const [filterSheetOpen, setFilterSheetOpen] = useState(false);
    const [clientLoaded, setClientLoaded] = useState(false);
    const detailTitleRef = useRef<HTMLHeadingElement | null>(null);
    const guardedPointerDownTargetRef = useRef<HTMLButtonElement | null>(null);
    const detailIdRef = useRef(detailId);
    useEffect(() => {
        detailIdRef.current = detailId;
    }, [detailId]);
    const queryRef = useRef(query);
    useEffect(() => {
        queryRef.current = query;
    }, [query]);
    const fetchGeneration = useRef(0);
    const [adopted, setAdopted] = useState(initial);
    if (adopted !== initial) {
        setAdopted(initial);
        if (isDefaultQuery(query) && !clientLoaded) {
            if (initial.ok) {
                setLoaded({ page: initial.data, query: DEFAULT_QUERY });
                setLoadFailure(null);
            } else if (loaded == null) {
                setLoadFailure("failed");
            }
        }
    }

    const load = useCallback(async (target: Query) => {
        const generation = ++fetchGeneration.current;
        setClientLoaded(true);
        setInFlight(true);
        try {
            const next = await getMyWork({
                page: target.page,
                sources: [...target.sources] as WorkItemSource[],
                urgencies: [...target.urgencies] as WorkItemUrgency[],
            });
            if (generation !== fetchGeneration.current) return;
            if (sameQuery(target, queryRef.current)) {
                setLoaded({ page: next, query: target });
                setLoadFailure(null);
            }
        } catch (error) {
            if (generation !== fetchGeneration.current) return;
            if (sameQuery(target, queryRef.current)) {
                setLoadFailure(error instanceof ApiError && error.status === 403
                    ? "forbidden"
                    : "failed");
            }
        } finally {
            if (generation === fetchGeneration.current) setInFlight(false);
        }
    }, []);

    const applyQuery = useCallback((next: Query) => {
        setQuery(next);
        queryRef.current = next;
        setLoadFailure(null);
        void load(next);
    }, [load]);

    const reconcile = useCallback(() => {
        void load(queryRef.current);
        router.refresh();
    }, [load, router]);

    const toggleFilter = useCallback((kind: "source" | "urgency", value: string) => {
        const current = queryRef.current;
        const set = new Set(kind === "source" ? current.sources : current.urgencies);
        if (set.has(value)) set.delete(value); else set.add(value);
        applyQuery({
            page: 1,
            sources: kind === "source" ? set : current.sources,
            urgencies: kind === "urgency" ? set : current.urgencies,
        });
    }, [applyQuery]);

    const clearFilter = useCallback((kind: "source" | "urgency") => {
        const current = queryRef.current;
        applyQuery({
            page: 1,
            sources: kind === "source" ? new Set() : current.sources,
            urgencies: kind === "urgency" ? new Set() : current.urgencies,
        });
    }, [applyQuery]);

    const current = loaded != null && sameQuery(loaded.query, query) ? loaded.page : null;

    const act = useCallback(
        async (
            item: WorkItem,
            run: () => Promise<{ notificationStateVersion?: number | null }>,
            done: string,
        ) => {
            if (pendingIds.has(item.id)) return false;
            const focusedAtStart = detailId === item.id;
            const nextDetailId = focusedAtStart
                ? detailIdAfterRemoval(current?.items ?? [], item.id)
                : null;
            setPendingIds((current) => new Set(current).add(item.id));
            try {
                const response = await run();
                setLoaded((current) => current == null ? current : {
                    ...current,
                    page: {
                        ...current.page,
                        items: current.page.items.filter((row) => row.id !== item.id),
                        knownMatchingTotal: Math.max(0, current.page.knownMatchingTotal - 1),
                        knownOverallTotal: Math.max(0, current.page.knownOverallTotal - 1),
                    },
                });
                if (focusedAtStart && detailIdRef.current === item.id) {
                    guardedPointerDownTargetRef.current = null;
                    setActionGuardId(nextDetailId);
                    setDetailId(nextDetailId);
                }
                if (response.notificationStateVersion != null) {
                    emitNotificationStateChanged(userId, response.notificationStateVersion);
                }
                toastSuccess(done);
                reconcile();
                return true;
            } catch (error) {
                if (error instanceof ApiError && error.status === 409) {
                    toastWarn(t("queueStale"));
                } else if (error instanceof ApiError && error.status === 403) {
                    toastError(t("queueActionForbidden"));
                } else {
                    const reference = error instanceof ApiError && error.correlationId
                        ? t("queueReference", { id: error.correlationId })
                        : "";
                    toastError(`${t("queueActionUnconfirmed")}${reference}`);
                }
                reconcile();
                return false;
            } finally {
                setPendingIds((current) => {
                    const next = new Set(current);
                    next.delete(item.id);
                    return next;
                });
            }
        },
        [current, detailId, pendingIds, reconcile, t, userId],
    );

    const complete = useCallback((item: WorkItem) =>
        act(item, () => completeMyWorkTask(item.sourceId, item.etag), t("queueCompleted")), [act, t]);
    const dismiss = useCallback((item: WorkItem) =>
        act(item, () => dismissMyWorkNotification(item.sourceId, item.etag), t("queueDismissed")), [act, t]);
    const snooze = useCallback((item: WorkItem, body: SnoozeRequest) =>
        act(item, () => snoozeMyWorkNotification(item.sourceId, item.etag, body), t("queueSnoozed")), [act, t]);
    const approve = useCallback((item: WorkItem) =>
        act(item, () => decideMyWorkApproval(item.sourceId, item.etag, {
            stepId: requireStepId(item), decision: "approved",
        }), t("queueApproved")), [act, t]);
    const reject = useCallback((item: WorkItem, comment: string) =>
        act(item, () => decideMyWorkApproval(item.sourceId, item.etag, {
            stepId: requireStepId(item), decision: "rejected",
            ...(comment.trim().length > 0 ? { comment: comment.trim() } : {}),
        }), t("queueRejected")), [act, t]);

    const detail = detailId != null
        ? current?.items.find((row) => row.id === detailId) ?? null
        : null;
    if (detailId != null && detail == null) {
        setDetailId(null);
    }
    const rejectItem = rejecting != null
        ? loaded?.page.items.find((row) => row.id === rejecting.id) ?? null
        : null;
    if (rejecting != null && rejectItem == null) {
        setRejecting(null);
        if (rejectBusy) setRejectBusy(false);
    }

    const detailIndex = detail == null || current == null
        ? -1
        : current.items.findIndex((item) => item.id === detail.id);
    const stepDetail = useCallback((offset: -1 | 1) => {
        if (current == null || detailId == null) return;
        const index = current.items.findIndex((item) => item.id === detailId);
        const target = current.items[index + offset];
        if (target == null) return;
        guardedPointerDownTargetRef.current = null;
        setActionGuardId(null);
        setDetailId(target.id);
    }, [current, detailId]);

    useEffect(() => {
        if (detailId != null) detailTitleRef.current?.focus();
    }, [detailId]);

    useEffect(() => {
        if (actionGuardId == null) return;
        const guardedId = actionGuardId;
        const timeout = window.setTimeout(() => {
            guardedPointerDownTargetRef.current = null;
            setActionGuardId((currentId) => currentId === guardedId ? null : currentId);
        }, AUTO_ADVANCE_ACTION_GUARD_MS);
        return () => window.clearTimeout(timeout);
    }, [actionGuardId]);

    const rememberGuardedPointerDown = useCallback((event: ReactPointerEvent<HTMLElement>) => {
        if (actionGuardId !== detailId) return;
        const target = event.target instanceof Element ? event.target.closest("button") : null;
        guardedPointerDownTargetRef.current = target;
    }, [actionGuardId, detailId]);

    const guardDetailPointerClick = useCallback((event: ReactMouseEvent<HTMLElement>) => {
        if (actionGuardId !== detailId) return;
        if (event.detail === 0) {
            guardedPointerDownTargetRef.current = null;
            return;
        }
        const target = event.target instanceof Element ? event.target.closest("button") : null;
        const beganAfterAdvance = target != null && guardedPointerDownTargetRef.current === target;
        guardedPointerDownTargetRef.current = null;
        if (target == null || beganAfterAdvance) return;
        event.preventDefault();
        event.stopPropagation();
    }, [actionGuardId, detailId]);

    const rejectDirty = (rejecting?.comment.trim().length ?? 0) > 0;
    const rejectGuard = useUnsavedChangesGuard({
        isDirty: rejectDirty,
        onClose: () => setRejecting(null),
        enabled: rejecting != null && !rejectBusy,
    });

    const filtered = query.sources.size > 0 || query.urgencies.size > 0;
    const stale = current != null && loadFailure === "failed";
    const incomplete = current != null
        && (!current.totalsComplete || current.availability !== "available");
    const unavailableSources = current?.sourceStatuses
        .filter((status) => status.status !== "available")
        .map((status) => t(`queueSource_${status.source}`)) ?? [];
    const countLabel = current == null || stale
        ? null
        : incomplete
            ? t("queueAtLeast", { count: current.knownMatchingTotal })
            : format.number(current.knownMatchingTotal);
    const pageIsFull = current != null && current.items.length >= current.size;
    const nextEnabled = current != null
        && (current.hasNextKnown ? current.hasNext : pageIsFull);
    const showPagination = query.page > 1 || nextEnabled;

    const filterControls = (variant: "menus" | "rows") => (
        variant === "menus" ? (
            <>
                <MultiSelectFilter
                    label={t("queueFilterSource")}
                    ariaLabel={t("queueFilterSource")}
                    options={SOURCES.map((value) => ({ value, label: t(`queueSource_${value}`) }))}
                    selected={new Set(query.sources)}
                    onToggle={(value) => toggleFilter("source", value)}
                    onClear={() => clearFilter("source")}
                    clearLabel={t("queueFilterClear")}
                />
                <MultiSelectFilter
                    label={t("queueFilterUrgency")}
                    ariaLabel={t("queueFilterUrgency")}
                    options={URGENCIES.map((value) => ({ value, label: t(`queueUrgency_${value}`) }))}
                    selected={new Set(query.urgencies)}
                    onToggle={(value) => toggleFilter("urgency", value)}
                    onClear={() => clearFilter("urgency")}
                    clearLabel={t("queueFilterClear")}
                />
            </>
        ) : (
            <div className="flex flex-col gap-4 px-4 pb-4">
                {([
                    ["source", t("queueFilterSource"), SOURCES.map((value) => [value, t(`queueSource_${value}`)] as const), query.sources],
                    ["urgency", t("queueFilterUrgency"), URGENCIES.map((value) => [value, t(`queueUrgency_${value}`)] as const), query.urgencies],
                ] as const).map(([kind, heading, options, selected]) => (
                    <div key={kind}>
                        <p className="mb-2 text-xs font-medium tracking-wide text-muted-foreground uppercase">{heading}</p>
                        <div className="flex flex-wrap gap-2">
                            {options.map(([value, label]) => (
                                <Button
                                    key={value}
                                    size="inline"
                                    variant={selected.has(value) ? "default" : "outline"}
                                    onClick={() => toggleFilter(kind, value)}
                                >
                                    {label}
                                </Button>
                            ))}
                        </div>
                    </div>
                ))}
            </div>
        )
    );

    return (
        <section>
            <SectionHeader
                title={t("queueTitle")}
                action={(
                    <span className="flex items-center gap-2 px-1">
                        {countLabel != null && current != null && current.items.length > 0 && (
                            <span className="px-1 text-xs tabular-nums text-muted-foreground">{countLabel}</span>
                        )}
                        <span className="hidden items-center gap-2 md:flex">
                            {filterControls("menus")}
                        </span>
                        <span className="md:hidden">
                            <IconButton label={t("queueFilterTitle")} onClick={() => setFilterSheetOpen(true)}>
                                <AdjustmentsHorizontalIcon />
                            </IconButton>
                        </span>
                    </span>
                )}
            />
            {current == null ? (
                loadFailure === "forbidden" ? (
                    <EmptyState
                        icon={LockClosedIcon}
                        title={t("queueForbiddenTitle")}
                        body={t("queueForbiddenBody")}
                        tone="muted"
                        className="py-12"
                        action={(
                            <Button variant="outline" onClick={() => applyQuery(DEFAULT_QUERY)}>
                                {t("queueFilterClearAll")}
                            </Button>
                        )}
                    />
                ) : inFlight ? (
                    <div className="overflow-hidden rounded-2xl border border-border bg-card">
                        {Array.from({ length: 3 }).map((_, index) => (
                            <div key={index} className="flex items-center gap-4 border-b border-border px-5 py-3.5 last:border-b-0">
                                <Skeleton className="size-9 rounded-lg" />
                                <div className="min-w-0 flex-1 space-y-1.5">
                                    <Skeleton className="h-4 w-2/5" />
                                    <Skeleton className="h-3 w-3/5" />
                                </div>
                                <Skeleton className="h-8 w-24 rounded-full" />
                            </div>
                        ))}
                    </div>
                ) : (
                    <SectionUnavailable
                        title={t("queueUnavailableTitle")}
                        body={t("queueUnavailableBody")}
                        onReset={() => { void load(queryRef.current); }}
                    />
                )
            ) : (
                <>
                    {stale && current.items.length > 0 && (
                        <p className="mb-3 rounded-xl border border-border bg-muted/40 px-4 py-2.5 text-xs text-muted-foreground">
                            {t("queueStaleBanner")}
                        </p>
                    )}
                    {incomplete && unavailableSources.length > 0 && current.items.length > 0 && (
                        <p className="mb-3 rounded-xl border border-border bg-muted/40 px-4 py-2.5 text-xs text-muted-foreground">
                            {t("queuePartialNamed", { sources: unavailableSources.join(t("queueSourceJoin")) })}
                        </p>
                    )}
                    {current.items.length === 0 ? (
                        stale || incomplete ? (
                            <SectionUnavailable
                                title={t("queuePartialEmptyTitle")}
                                body={unavailableSources.length > 0
                                    ? t("queuePartialNamed", { sources: unavailableSources.join(t("queueSourceJoin")) })
                                    : t("queueUnavailableBody")}
                                onReset={() => { void load(queryRef.current); }}
                            />
                        ) : query.page > 1 ? (
                            <EmptyState
                                icon={CheckIcon}
                                title={t("queuePageEmptyTitle")}
                                body={t("queuePageEmptyBody")}
                                tone="muted"
                                className="py-12"
                                action={(
                                    <Button
                                        variant="outline"
                                        onClick={() => applyQuery({ ...queryRef.current, page: 1 })}
                                    >
                                        {t("queueBackToFirstPage")}
                                    </Button>
                                )}
                            />
                        ) : filtered ? (
                            <EmptyState
                                icon={CheckIcon}
                                title={t("queueNoResultsTitle")}
                                body={t("queueNoResultsBody")}
                                tone="muted"
                                className="py-12"
                                action={(
                                    <Button variant="outline" onClick={() => applyQuery(DEFAULT_QUERY)}>
                                        {t("queueFilterClearAll")}
                                    </Button>
                                )}
                            />
                        ) : (
                            <EmptyState
                                icon={CheckIcon}
                                title={t("queueEmptyTitle")}
                                body={t("queueEmptyBody")}
                                tone="brand"
                                className="py-12"
                                action={(
                                    <Button asChild variant="outline">
                                        <Link href="/activity/tasks">{t("queueEmptyAction")}</Link>
                                    </Button>
                                )}
                            />
                        )
                    ) : (
                        <div className="overflow-hidden rounded-2xl border border-border bg-card">
                            <ul className="divide-y divide-border">
                                {current.items.map((item) => (
                                    <QueueRow
                                        key={item.id}
                                        item={item}
                                        pending={pendingIds.has(item.id)}
                                        onComplete={complete}
                                        onDismiss={dismiss}
                                        onSnooze={snooze}
                                        onApprove={approve}
                                        onReject={(target) => setRejecting({ id: target.id, comment: "" })}
                                        onDetail={(target) => {
                                            setActionGuardId(null);
                                            setDetailId(target.id);
                                        }}
                                    />
                                ))}
                            </ul>
                        </div>
                    )}
                    {showPagination && (
                        <Pagination className="mt-3">
                            <PaginationContent>
                                <PaginationItem>
                                    <PaginationPrevious
                                        disabled={query.page <= 1 || inFlight}
                                        aria-label={t("queuePrevious")}
                                        onClick={() => applyQuery({ ...queryRef.current, page: Math.max(1, query.page - 1) })}
                                    />
                                </PaginationItem>
                                <PaginationItem>
                                    <span className="px-3 text-sm tabular-nums text-muted-foreground">
                                        {t("queuePage", { page: query.page })}
                                    </span>
                                </PaginationItem>
                                <PaginationItem>
                                    <PaginationNext
                                        disabled={!nextEnabled || inFlight}
                                        aria-label={t("queueNext")}
                                        onClick={() => applyQuery({ ...queryRef.current, page: query.page + 1 })}
                                    />
                                </PaginationItem>
                            </PaginationContent>
                        </Pagination>
                    )}
                </>
            )}

            <Drawer
                open={filterSheetOpen}
                onOpenChange={setFilterSheetOpen}
                swipeDirection="down"
                showSwipeHandle
            >
                <DrawerContent className="mx-auto flex max-h-[70vh] w-full flex-col gap-0 rounded-t-2xl pb-[max(1rem,env(safe-area-inset-bottom))]">
                    <DrawerHeader>
                        <DrawerTitle>{t("queueFilterTitle")}</DrawerTitle>
                    </DrawerHeader>
                    <div className="min-h-0 flex-1 overflow-y-auto">
                        {filterControls("rows")}
                    </div>
                </DrawerContent>
            </Drawer>

            <ResponsiveDialog
                open={rejecting != null}
                onOpenChange={(open) => {
                    if (!open && !rejectBusy) rejectGuard.requestClose();
                }}
            >
                <ResponsiveDialogContent className="space-y-3 p-5 sm:max-w-md sm:p-6">
                    <ResponsiveDialogTitle>{t("queueRejectTitle")}</ResponsiveDialogTitle>
                    <ResponsiveDialogDescription>
                        {rejectItem != null ? t("queueRejectBody", { title: rejectItem.title }) : ""}
                    </ResponsiveDialogDescription>
                    <Textarea
                        value={rejecting?.comment ?? ""}
                        onChange={(event) => setRejecting((current) =>
                            current == null ? current : { ...current, comment: event.target.value })}
                        placeholder={t("queueRejectCommentPlaceholder")}
                        maxLength={1000}
                        rows={3}
                        disabled={rejectBusy}
                    />
                    <div className="flex justify-end gap-2">
                        <Button variant="ghost" disabled={rejectBusy} onClick={() => rejectGuard.requestClose()}>
                            {t("queueCancel")}
                        </Button>
                        <Button
                            variant="destructive"
                            disabled={rejectBusy || rejectItem == null}
                            onClick={() => {
                                if (rejecting == null || rejectItem == null) return;
                                const comment = rejecting.comment;
                                setRejectBusy(true);
                                void reject(rejectItem, comment).then((succeeded) => {
                                    setRejectBusy(false);
                                    if (succeeded) setRejecting(null);
                                });
                            }}
                        >
                            {t("queueReject")}
                        </Button>
                    </div>
                </ResponsiveDialogContent>
            </ResponsiveDialog>
            <ConfirmDiscardDialog
                open={rejectGuard.confirm.open}
                onKeepEditing={rejectGuard.confirm.onKeepEditing}
                onDiscard={rejectGuard.confirm.onDiscard}
            />

            <Drawer
                open={detail != null}
                onOpenChange={(open) => {
                    if (!open) {
                        guardedPointerDownTargetRef.current = null;
                        setActionGuardId(null);
                        setDetailId(null);
                    }
                }}
                swipeDirection={wide ? "right" : "down"}
                showSwipeHandle={!wide}
            >
                <DrawerContent
                    initialFocus={detailTitleRef}
                    className="mx-auto flex max-h-[85vh] w-full flex-col gap-0 rounded-t-2xl pb-[max(1rem,env(safe-area-inset-bottom))] md:mx-0 md:h-full md:max-h-none md:max-w-md md:rounded-2xl md:pb-4"
                    onKeyDown={(event) => {
                        if (rejecting != null || event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return;
                        const target = event.target instanceof Element ? event.target : null;
                        const targetDrawer = target?.closest("[data-slot='drawer-content']");
                        if (
                            target?.closest("[data-slot='dropdown-menu-content'],[data-slot='dialog-content']") != null
                            || (targetDrawer != null && targetDrawer !== event.currentTarget)
                        ) return;
                        const offset = event.key === "ArrowLeft" ? -1 : event.key === "ArrowRight" ? 1 : null;
                        if (offset == null) return;
                        event.preventDefault();
                        stepDetail(offset);
                    }}
                >
                    {detail != null && (
                        <>
                            <div className="flex shrink-0 items-center gap-2 border-b border-border px-4 py-3">
                                <Button
                                    variant="outline"
                                    className="min-h-11 min-w-11 md:min-h-9"
                                    disabled={detailIndex <= 0}
                                    onClick={() => stepDetail(-1)}
                                >
                                    <ChevronLeftIcon className="size-4" />
                                    {t("queueStepPrevious")}
                                </Button>
                                <p className="min-w-0 flex-1 text-center text-xs tabular-nums text-muted-foreground">
                                    {t("queueStepPosition", {
                                        position: detailIndex + 1,
                                        count: current?.items.length ?? 0,
                                    })}
                                </p>
                                <Button
                                    variant="outline"
                                    className="min-h-11 min-w-11 md:min-h-9"
                                    disabled={current == null || detailIndex >= current.items.length - 1}
                                    onClick={() => stepDetail(1)}
                                >
                                    {t("queueStepNext")}
                                    <ChevronRightIcon className="size-4" />
                                </Button>
                            </div>
                            <DrawerHeader className="gap-2">
                                <Badge variant="secondary" className="w-fit gap-1.5">
                                    <span className={cn("size-1.5 rounded-full", URGENCY_DOT[detail.urgency])} />
                                    {t(`queueSource_${detail.source}`)}
                                    {" · "}
                                    {t(`queueUrgency_${detail.urgency}`)}
                                </Badge>
                                <DrawerTitle
                                    ref={detailTitleRef}
                                    tabIndex={-1}
                                    className="rounded-sm text-lg leading-snug outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
                                >
                                    {detail.title}
                                </DrawerTitle>
                                <DrawerDescription>{reasonLabel(t, format, detail)}</DrawerDescription>
                                <p className="sr-only" aria-live="polite" aria-atomic="true">
                                    {t("queueStepAnnouncement", {
                                        title: detail.title,
                                        position: detailIndex + 1,
                                        count: current?.items.length ?? 0,
                                    })}
                                </p>
                            </DrawerHeader>
                            <div className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto px-4 pb-2">
                                <ul className="space-y-1.5 text-sm text-muted-foreground">
                                    {detail.evidence.map((row, index) => (
                                        <li key={`${row.code}:${index}`} className="flex items-center gap-2">
                                            <span className={cn("size-1.5 shrink-0 rounded-full", URGENCY_DOT[detail.urgency])} />
                                            <span>
                                                {row.label != null && row.label.length > 0 && row.label !== detail.title
                                                    ? `${row.label} · `
                                                    : ""}
                                                {t(`queueEvidence_${row.code}`)}
                                                {" · "}
                                                {formatInstant(format, row.date != null ? `${row.date}T00:00:00` : row.occurredAt)}
                                            </span>
                                        </li>
                                    ))}
                                </ul>
                                <p className="text-xs text-muted-foreground">
                                    {t("queueFreshness", { at: formatInstant(format, detail.freshnessAt) })}
                                    {" · "}
                                    {t("queueAsOf", { at: formatInstant(format, detail.asOf) })}
                                </p>
                                <div
                                    className="flex flex-wrap justify-end gap-2"
                                    onPointerDownCapture={rememberGuardedPointerDown}
                                    onClickCapture={guardDetailPointerClick}
                                >
                                    <Button asChild variant="outline">
                                        <Link href={contextHref(detail)}>
                                            {t("queueOpenContext")}
                                            <ArrowRightIcon className="size-3.5" />
                                        </Link>
                                    </Button>
                                    {detail.permittedActions.includes("snooze") && (
                                        <SnoozeMenu
                                            disabled={pendingIds.has(detail.id)}
                                            onSnooze={(body) => snooze(detail, body)}
                                        />
                                    )}
                                    <PrimaryAction
                                        item={detail}
                                        pending={pendingIds.has(detail.id)}
                                        onComplete={complete}
                                        onApprove={approve}
                                        onDismiss={dismiss}
                                        onReject={(target) => setRejecting({ id: target.id, comment: "" })}
                                        labels={t}
                                    />
                                </div>
                            </div>
                        </>
                    )}
                </DrawerContent>
            </Drawer>
        </section>
    );
}

function contextHref(item: WorkItem): string {
    return item.source === "document_approval"
        ? dealDocumentsHref(item.context.id)
        : item.context.href;
}

function requireStepId(item: WorkItem): number {
    const stepId = item.context.stepId;
    if (stepId == null) throw new ApiError("Approval step is missing", 409);
    return stepId;
}

type Translate = ReturnType<typeof useTranslations<"MePage">>;
type Format = ReturnType<typeof useFormatter>;

function formatDay(format: Format, isoDate: string): string {
    return format.dateTime(new Date(`${isoDate}T00:00:00`), { month: "short", day: "numeric" });
}

function formatInstant(format: Format, iso: string): string {
    return format.dateTime(new Date(iso), {
        month: "short", day: "numeric", hour: "numeric", minute: "numeric",
    });
}

function reasonLabel(t: Translate, format: Format, item: WorkItem): string {
    const { code, date, days, requestedByLabel } = item.reason;
    switch (code) {
        case "task_overdue":
            return t("queueReason_task_overdue", { days: days ?? 0 });
        case "task_due_today":
            return t("queueReason_task_due_today");
        case "task_due_soon":
            return date != null
                ? t("queueReason_task_due_soon", { date: formatDay(format, date) })
                : t("queueReason_task_open");
        case "task_open":
            return t("queueReason_task_open");
        case "deal_close_overdue":
            return t("queueReason_deal_close_overdue", { days: days ?? 0 });
        case "deal_closing_soon":
            return date != null
                ? t("queueReason_deal_closing_soon", { date: formatDay(format, date) })
                : t("queueReason_deal_closing_soon_undated");
        case "document_approval_pending":
            return requestedByLabel != null && requestedByLabel.length > 0
                ? t("queueReason_document_approval_pending_by", { name: requestedByLabel })
                : t("queueReason_document_approval_pending");
    }
}

function PrimaryAction({
    item,
    pending,
    onComplete,
    onApprove,
    onDismiss,
    onReject,
    labels: t,
}: {
    item: WorkItem;
    pending: boolean;
    onComplete: (item: WorkItem) => void;
    onApprove: (item: WorkItem) => void;
    onDismiss: (item: WorkItem) => void;
    onReject: (item: WorkItem) => void;
    labels: Translate;
}) {
    if (item.permittedActions.includes("complete")) {
        return (
            <Button size="inline" disabled={pending} onClick={() => onComplete(item)}>
                <CheckIcon className="size-3.5" />
                {t("queueComplete")}
            </Button>
        );
    }
    if (item.permittedActions.includes("approve")) {
        return (
            <span className="flex gap-1.5">
                <Button size="inline" disabled={pending} onClick={() => onApprove(item)}>
                    <CheckIcon className="size-3.5" />
                    {t("queueApprove")}
                </Button>
                <Button size="inline" variant="outline" disabled={pending} onClick={() => onReject(item)}>
                    <XMarkIcon className="size-3.5" />
                    {t("queueReject")}
                </Button>
            </span>
        );
    }
    if (item.permittedActions.includes("dismiss")) {
        return (
            <Button size="inline" variant="outline" disabled={pending} onClick={() => onDismiss(item)}>
                {t("queueDismiss")}
            </Button>
        );
    }
    return null;
}

function QueueRow({
    item,
    pending,
    onComplete,
    onDismiss,
    onSnooze,
    onApprove,
    onReject,
    onDetail,
}: {
    item: WorkItem;
    pending: boolean;
    onComplete: (item: WorkItem) => void;
    onDismiss: (item: WorkItem) => void;
    onSnooze: (item: WorkItem, body: SnoozeRequest) => void;
    onApprove: (item: WorkItem) => void;
    onReject: (item: WorkItem) => void;
    onDetail: (item: WorkItem) => void;
}) {
    const t = useTranslations("MePage");
    const format = useFormatter();
    const Icon = SOURCE_ICON[item.source];
    const canSnooze = item.permittedActions.includes("snooze");
    return (
        <li className={cn("flex items-center gap-3 px-4 py-3.5 sm:gap-4 sm:px-5", pending && "opacity-60")}>
            <span className={cn(
                "grid size-9 shrink-0 place-items-center rounded-lg ring-1 ring-inset",
                URGENCY_TONE[item.urgency],
            )}>
                <Icon className="size-4" aria-hidden="true" />
                <span className="sr-only">
                    {t(`queueSource_${item.source}`)}
                    {" · "}
                    {t(`queueUrgency_${item.urgency}`)}
                </span>
            </span>
            <button
                type="button"
                className="min-w-0 flex-1 text-left sm:hidden"
                onClick={() => onDetail(item)}
            >
                <RowText item={item} t={t} format={format} />
            </button>
            <Link href={contextHref(item)} className="group hidden min-w-0 flex-1 sm:block">
                <RowText item={item} t={t} format={format} />
            </Link>
            {(item.urgency === "critical" || item.urgency === "high") && (
                <Badge variant="outline" className={cn("hidden shrink-0 sm:inline-flex", URGENCY_TONE[item.urgency])}>
                    <span className={cn("size-1.5 rounded-full", URGENCY_DOT[item.urgency])} />
                    {t(`queueUrgency_${item.urgency}`)}
                </Badge>
            )}
            <span className="hidden shrink-0 items-center gap-1.5 sm:flex">
                <PrimaryAction
                    item={item}
                    pending={pending}
                    onComplete={onComplete}
                    onApprove={onApprove}
                    onDismiss={onDismiss}
                    onReject={onReject}
                    labels={t}
                />
                {canSnooze && (
                    <SnoozeMenu disabled={pending} onSnooze={(body) => onSnooze(item, body)} />
                )}
                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <IconButton label={t("queueMore")} disabled={pending}>
                            <EllipsisHorizontalIcon />
                        </IconButton>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end">
                        <DropdownMenuItem asChild>
                            <Link href={contextHref(item)}>{t("queueOpenContext")}</Link>
                        </DropdownMenuItem>
                        <DropdownMenuItem onSelect={() => onDetail(item)}>
                            {t("queueDetails")}
                        </DropdownMenuItem>
                    </DropdownMenuContent>
                </DropdownMenu>
            </span>
        </li>
    );
}

function RowText({
    item,
    t,
    format,
}: {
    item: WorkItem;
    t: Translate;
    format: Format;
}) {
    return (
        <>
            <span className="flex items-center gap-2">
                <span className="truncate text-sm font-medium text-foreground transition-colors group-hover:text-primary">
                    {item.title}
                </span>
            </span>
            <span className="mt-0.5 block truncate text-xs text-muted-foreground">
                {reasonLabel(t, format, item)}
                {item.context.label !== item.title && ` · ${item.context.label}`}
            </span>
            <span className="mt-0.5 hidden truncate text-[11px] text-muted-foreground/80 sm:block">
                {t(`queueSource_${item.source}`)}
                {" · "}
                {t(`queueUrgency_${item.urgency}`)}
                {" · "}
                {t("queueFreshness", { at: formatInstant(format, item.freshnessAt) })}
            </span>
        </>
    );
}
