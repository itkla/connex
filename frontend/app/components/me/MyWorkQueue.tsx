"use client";

import { useCallback, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useFormatter, useTranslations } from "next-intl";
import {
    ArrowRightIcon,
    CheckIcon,
    ClipboardDocumentCheckIcon,
    CurrencyYenIcon,
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
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
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

type RejectTarget = { item: WorkItem; comment: string };

type Props = {
    userId: number;
    initial: CookieResult<WorkItemPage>;
};

/**
 * The actionable, deterministically ranked queue at the heart of My Work: tasks,
 * deal-close notifications, and approval steps from the merged projection, each with
 * its reason, urgency, and version-guarded in-place actions. Renders only claims the
 * projection can currently back: a failed or partial load never reads as caught-up,
 * and counts turn into at-least language whenever reconciliation is incomplete.
 */
export default function MyWorkQueue({ userId, initial }: Props) {
    const t = useTranslations("MePage");
    const format = useFormatter();
    const router = useRouter();
    const [page, setPage] = useState<WorkItemPage | null>(initial.ok ? initial.data : null);
    const [stale, setStale] = useState(!initial.ok);
    const [pendingIds, setPendingIds] = useState<ReadonlySet<string>>(new Set());
    const [rejecting, setRejecting] = useState<RejectTarget | null>(null);
    const [rejectBusy, setRejectBusy] = useState(false);
    const [detailId, setDetailId] = useState<string | null>(null);
    const [pageNumber, setPageNumber] = useState(1);
    const [sourceFilter, setSourceFilter] = useState<ReadonlySet<string>>(new Set());
    const [urgencyFilter, setUrgencyFilter] = useState<ReadonlySet<string>>(new Set());
    const fetchGeneration = useRef(0);
    const [adopted, setAdopted] = useState(initial);
    if (adopted !== initial) {
        setAdopted(initial);
        if (initial.ok) {
            setPage(initial.data);
            setStale(false);
        } else {
            setStale(true);
        }
    }

    const detail = detailId != null
        ? page?.items.find((row) => row.id === detailId) ?? null
        : null;
    if (detailId != null && detail == null) {
        setDetailId(null);
    }

    const load = useCallback(async (
        target: { page: number; sources: ReadonlySet<string>; urgencies: ReadonlySet<string> },
    ) => {
        const generation = ++fetchGeneration.current;
        try {
            const next = await getMyWork({
                page: target.page,
                sources: [...target.sources] as WorkItemSource[],
                urgencies: [...target.urgencies] as WorkItemUrgency[],
            });
            if (generation !== fetchGeneration.current) return;
            setPage(next);
            setStale(false);
        } catch {
            if (generation !== fetchGeneration.current) return;
            setStale(true);
        }
        router.refresh();
    }, [router]);

    const reload = useCallback(
        () => load({ page: pageNumber, sources: sourceFilter, urgencies: urgencyFilter }),
        [load, pageNumber, sourceFilter, urgencyFilter],
    );

    const changePage = useCallback((next: number) => {
        setPageNumber(next);
        void load({ page: next, sources: sourceFilter, urgencies: urgencyFilter });
    }, [load, sourceFilter, urgencyFilter]);

    const toggleFilter = useCallback((kind: "source" | "urgency", value: string) => {
        const [current, apply] = kind === "source"
            ? [sourceFilter, setSourceFilter] as const
            : [urgencyFilter, setUrgencyFilter] as const;
        const next = new Set(current);
        if (next.has(value)) next.delete(value); else next.add(value);
        apply(next);
        setPageNumber(1);
        void load({
            page: 1,
            sources: kind === "source" ? next : sourceFilter,
            urgencies: kind === "urgency" ? next : urgencyFilter,
        });
    }, [load, sourceFilter, urgencyFilter]);

    const clearFilter = useCallback((kind: "source" | "urgency") => {
        const empty = new Set<string>();
        if (kind === "source") setSourceFilter(empty); else setUrgencyFilter(empty);
        setPageNumber(1);
        void load({
            page: 1,
            sources: kind === "source" ? empty : sourceFilter,
            urgencies: kind === "urgency" ? empty : urgencyFilter,
        });
    }, [load, sourceFilter, urgencyFilter]);

    const clearAllFilters = useCallback(() => {
        const empty = new Set<string>();
        setSourceFilter(empty);
        setUrgencyFilter(empty);
        setPageNumber(1);
        void load({ page: 1, sources: empty, urgencies: empty });
    }, [load]);

    const act = useCallback(
        async (
            item: WorkItem,
            run: () => Promise<{ notificationStateVersion?: number | null }>,
            done: string,
        ) => {
            if (pendingIds.has(item.id)) return false;
            setPendingIds((current) => new Set(current).add(item.id));
            try {
                const response = await run();
                setPage((current) => current == null ? current : {
                    ...current,
                    items: current.items.filter((row) => row.id !== item.id),
                    knownMatchingTotal: Math.max(0, current.knownMatchingTotal - 1),
                    knownOverallTotal: Math.max(0, current.knownOverallTotal - 1),
                });
                setDetailId((current) => (current === item.id ? null : current));
                if (response.notificationStateVersion != null) {
                    emitNotificationStateChanged(userId, response.notificationStateVersion);
                }
                toastSuccess(done);
                await reload();
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
                await reload();
                return false;
            } finally {
                setPendingIds((current) => {
                    const next = new Set(current);
                    next.delete(item.id);
                    return next;
                });
            }
        },
        [pendingIds, reload, t, userId],
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

    const rejectDirty = (rejecting?.comment.trim().length ?? 0) > 0;
    const rejectGuard = useUnsavedChangesGuard({
        isDirty: rejectDirty,
        onClose: () => setRejecting(null),
        enabled: rejecting != null && !rejectBusy,
    });

    if (page == null) {
        return (
            <section>
                <SectionHeader title={t("queueTitle")} />
                <SectionUnavailable
                    title={t("queueUnavailableTitle")}
                    body={t("queueUnavailableBody")}
                    onReset={() => { void reload(); }}
                />
            </section>
        );
    }

    const unavailableSources = page.sourceStatuses
        .filter((status) => status.status !== "available")
        .map((status) => t(`queueSource_${status.source}`));
    const partial = page.availability === "partial" || page.availability === "unavailable";
    const filtered = sourceFilter.size > 0 || urgencyFilter.size > 0;
    const exact = page.totalsComplete && !stale && !partial;
    const countLabel = exact
        ? format.number(page.knownMatchingTotal)
        : t("queueAtLeast", { count: page.knownMatchingTotal });
    const showPagination = pageNumber > 1
        || (page.hasNextKnown ? page.hasNext : page.items.length >= page.size);

    return (
        <section>
            <SectionHeader
                title={t("queueTitle")}
                action={(
                    <span className="flex items-center gap-2 px-1">
                        {page.items.length > 0 && (
                            <span className="px-1 text-xs tabular-nums text-muted-foreground">{countLabel}</span>
                        )}
                        <MultiSelectFilter
                            label={t("queueFilterSource")}
                            ariaLabel={t("queueFilterSource")}
                            options={SOURCES.map((value) => ({ value, label: t(`queueSource_${value}`) }))}
                            selected={new Set(sourceFilter)}
                            onToggle={(value) => toggleFilter("source", value)}
                            onClear={() => clearFilter("source")}
                            clearLabel={t("queueFilterClear")}
                        />
                        <MultiSelectFilter
                            label={t("queueFilterUrgency")}
                            ariaLabel={t("queueFilterUrgency")}
                            options={URGENCIES.map((value) => ({ value, label: t(`queueUrgency_${value}`) }))}
                            selected={new Set(urgencyFilter)}
                            onToggle={(value) => toggleFilter("urgency", value)}
                            onClear={() => clearFilter("urgency")}
                            clearLabel={t("queueFilterClear")}
                        />
                    </span>
                )}
            />
            {stale && page.items.length > 0 && (
                <p className="mb-3 rounded-xl border border-border bg-muted/40 px-4 py-2.5 text-xs text-muted-foreground">
                    {t("queueStaleBanner")}
                </p>
            )}
            {partial && !stale && page.items.length > 0 && (
                <p className="mb-3 rounded-xl border border-border bg-muted/40 px-4 py-2.5 text-xs text-muted-foreground">
                    {t("queuePartialNamed", { sources: unavailableSources.join(t("queueSourceJoin")) })}
                </p>
            )}
            {page.items.length === 0 ? (
                partial || stale ? (
                    <SectionUnavailable
                        title={t("queuePartialEmptyTitle")}
                        body={unavailableSources.length > 0
                            ? t("queuePartialNamed", { sources: unavailableSources.join(t("queueSourceJoin")) })
                            : t("queueUnavailableBody")}
                        onReset={() => { void reload(); }}
                    />
                ) : filtered ? (
                    <EmptyState
                        icon={CheckIcon}
                        title={t("queueNoResultsTitle")}
                        body={t("queueNoResultsBody")}
                        tone="muted"
                        className="py-12"
                        action={(
                            <Button variant="outline" onClick={clearAllFilters}>
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
                        {page.items.map((item) => (
                            <QueueRow
                                key={item.id}
                                item={item}
                                pending={pendingIds.has(item.id)}
                                onComplete={complete}
                                onDismiss={dismiss}
                                onSnooze={snooze}
                                onApprove={approve}
                                onReject={(target) => setRejecting({ item: target, comment: "" })}
                                onDetail={(target) => setDetailId(target.id)}
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
                                disabled={pageNumber <= 1}
                                aria-label={t("queuePrevious")}
                                onClick={() => changePage(Math.max(1, pageNumber - 1))}
                            />
                        </PaginationItem>
                        <PaginationItem>
                            <span className="px-3 text-sm tabular-nums text-muted-foreground">
                                {t("queuePage", { page: pageNumber })}
                            </span>
                        </PaginationItem>
                        <PaginationItem>
                            <PaginationNext
                                disabled={page.hasNextKnown && !page.hasNext}
                                aria-label={t("queueNext")}
                                onClick={() => changePage(pageNumber + 1)}
                            />
                        </PaginationItem>
                    </PaginationContent>
                </Pagination>
            )}

            <ResponsiveDialog
                open={rejecting != null}
                onOpenChange={(open) => { if (!open) rejectGuard.requestClose(); }}
            >
                <ResponsiveDialogContent className="space-y-3 p-5 sm:max-w-md sm:p-6">
                    <ResponsiveDialogTitle>{t("queueRejectTitle")}</ResponsiveDialogTitle>
                    <ResponsiveDialogDescription>
                        {rejecting != null ? t("queueRejectBody", { title: rejecting.item.title }) : ""}
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
                            disabled={rejectBusy || rejecting == null}
                            onClick={() => {
                                if (rejecting == null) return;
                                setRejectBusy(true);
                                void reject(rejecting.item, rejecting.comment).then((succeeded) => {
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
                onOpenChange={(open) => { if (!open) setDetailId(null); }}
            >
                <DrawerContent className="mx-auto flex max-h-[85vh] w-full flex-col gap-0 rounded-t-2xl pb-[max(1rem,env(safe-area-inset-bottom))] md:mx-0 md:h-full md:max-h-none md:max-w-md md:rounded-2xl md:pb-4">
                    {detail != null && (
                        <>
                            <DrawerHeader className="gap-2">
                                <Badge variant="secondary" className="w-fit gap-1.5">
                                    <span className={cn("size-1.5 rounded-full", URGENCY_DOT[detail.urgency])} />
                                    {t(`queueSource_${detail.source}`)}
                                    {" · "}
                                    {t(`queueUrgency_${detail.urgency}`)}
                                </Badge>
                                <DrawerTitle className="text-lg leading-snug">{detail.title}</DrawerTitle>
                                <DrawerDescription>{reasonLabel(t, format, detail)}</DrawerDescription>
                            </DrawerHeader>
                            <div className="flex flex-col gap-3 px-4 pb-2">
                                <ul className="space-y-1.5 text-sm text-muted-foreground">
                                    {detail.evidence.map((row, index) => (
                                        <li key={`${row.code}:${index}`} className="flex items-center gap-2">
                                            <span className={cn("size-1.5 shrink-0 rounded-full", URGENCY_DOT[detail.urgency])} />
                                            <span>
                                                {t(`queueEvidence_${row.code}`)}
                                                {row.date != null && ` · ${formatDay(format, row.date)}`}
                                            </span>
                                        </li>
                                    ))}
                                </ul>
                                <p className="text-xs text-muted-foreground">
                                    {t("queueFreshness", {
                                        at: format.dateTime(new Date(detail.freshnessAt), {
                                            month: "short", day: "numeric", hour: "numeric", minute: "numeric",
                                        }),
                                    })}
                                </p>
                                <div className="flex flex-wrap justify-end gap-2">
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
                                        onReject={(target) => setRejecting({ item: target, comment: "" })}
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
        </>
    );
}
