"use client";

import { useCallback, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useFormatter, useLocale, useTranslations } from "next-intl";
import {
    ArrowRightIcon,
    CheckIcon,
    ClipboardDocumentCheckIcon,
    CurrencyYenIcon,
    ShieldCheckIcon,
    XMarkIcon,
} from "@heroicons/react/24/outline";

import {
    ApiError,
    completeMyWorkTask,
    decideMyWorkApproval,
    dismissMyWorkNotification,
    getMyWork,
    snoozeMyWorkNotification,
} from "@/app/lib/api";
import type {
    SnoozeRequest,
    WorkItem,
    WorkItemPage,
    WorkItemSource,
    WorkItemUrgency,
} from "@/app/lib/types";
import type { CookieResult } from "@/app/lib/api";
import { cn } from "@/lib/utils";
import { toastError, toastSuccess, toastWarn } from "@/app/lib/toast";
import { emitNotificationStateChanged } from "@/app/components/notifications/notificationEvents";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
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
import { SnoozeMenu } from "@/app/components/notifications/SnoozeMenu";
import { EmptyState } from "@/app/components/EmptyState";
import SectionUnavailable from "@/app/components/SectionUnavailable";
import SectionHeader from "@/app/components/dashboard/SectionHeader";
import { EllipsisHorizontalIcon } from "@heroicons/react/20/solid";

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

type RejectTarget = { item: WorkItem; comment: string };

type Props = {
    userId: number;
    initial: CookieResult<WorkItemPage>;
};

/**
 * The actionable, deterministically ranked queue at the heart of My Work: tasks,
 * deal-close notifications, and approval steps from the merged projection, each
 * with its reason, urgency, and version-guarded in-place actions.
 */
export default function MyWorkQueue({ userId, initial }: Props) {
    const t = useTranslations("MePage");
    const locale = useLocale();
    const format = useFormatter();
    const router = useRouter();
    const [page, setPage] = useState<WorkItemPage | null>(initial.ok ? initial.data : null);
    const [failed, setFailed] = useState(!initial.ok);
    const [pendingId, setPendingId] = useState<string | null>(null);
    const [rejecting, setRejecting] = useState<RejectTarget | null>(null);
    const [detail, setDetail] = useState<WorkItem | null>(null);
    const [adopted, setAdopted] = useState(initial);
    if (adopted !== initial) {
        setAdopted(initial);
        if (initial.ok) {
            setPage(initial.data);
            setFailed(false);
        }
    }

    const refresh = useCallback(async () => {
        try {
            const next = await getMyWork();
            setPage(next);
            setFailed(false);
        } catch {
            setFailed(true);
        }
        router.refresh();
    }, [router]);

    const act = useCallback(
        async (item: WorkItem, run: () => Promise<{ notificationStateVersion?: number | null }>, done: string) => {
            setPendingId(item.id);
            try {
                const response = await run();
                setPage((current) => current == null
                    ? current
                    : { ...current, items: current.items.filter((row) => row.id !== item.id) });
                setDetail(null);
                if (response.notificationStateVersion != null) {
                    emitNotificationStateChanged(userId, response.notificationStateVersion);
                }
                toastSuccess(done);
                await refresh();
            } catch (error) {
                if (error instanceof ApiError && error.status === 409) {
                    toastWarn(t("queueStale"));
                    await refresh();
                } else {
                    toastError(t("queueActionFailed"));
                }
            } finally {
                setPendingId(null);
            }
        },
        [refresh, t, userId],
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

    if (failed && page == null) {
        return (
            <section>
                <SectionHeader title={t("queueTitle")} />
                <SectionUnavailable
                    title={t("queueUnavailableTitle")}
                    body={t("queueUnavailableBody")}
                    onReset={() => { void refresh(); }}
                />
            </section>
        );
    }
    if (page == null) return null;
    if (page.availability === "unavailable") {
        return (
            <section>
                <SectionHeader title={t("queueTitle")} />
                <SectionUnavailable
                    title={t("queueUnavailableTitle")}
                    body={t("queueUnavailableBody")}
                    onReset={() => { void refresh(); }}
                />
            </section>
        );
    }

    const partial = page.availability === "partial";
    const countLabel = page.totalsComplete
        ? format.number(page.knownMatchingTotal)
        : t("queueAtLeast", { count: page.knownMatchingTotal });

    return (
        <section>
            <SectionHeader
                title={t("queueTitle")}
                action={page.items.length > 0 ? (
                    <span className="px-2 text-xs tabular-nums text-muted-foreground">{countLabel}</span>
                ) : undefined}
            />
            {partial && (
                <p className="mb-3 rounded-xl border border-border bg-muted/40 px-4 py-2.5 text-xs text-muted-foreground">
                    {t("queuePartial")}
                </p>
            )}
            {page.items.length === 0 ? (
                <EmptyState
                    icon={CheckIcon}
                    title={t("queueEmptyTitle")}
                    body={partial ? t("queuePartial") : t("queueEmptyBody")}
                    tone="muted"
                    className="py-12"
                />
            ) : (
                <div className="overflow-hidden rounded-2xl border border-border bg-card">
                    <ul className="divide-y divide-border">
                        {page.items.map((item) => (
                            <QueueRow
                                key={item.id}
                                item={item}
                                pending={pendingId === item.id}
                                locale={locale}
                                onComplete={complete}
                                onDismiss={dismiss}
                                onSnooze={snooze}
                                onApprove={approve}
                                onReject={(target) => setRejecting({ item: target, comment: "" })}
                                onDetail={setDetail}
                            />
                        ))}
                    </ul>
                </div>
            )}

            <ResponsiveDialog
                open={rejecting != null}
                onOpenChange={(open) => { if (!open) setRejecting(null); }}
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
                    />
                    <div className="flex justify-end gap-2">
                        <Button variant="ghost" onClick={() => setRejecting(null)}>
                            {t("queueCancel")}
                        </Button>
                        <Button
                            variant="destructive"
                            disabled={pendingId != null}
                            onClick={() => {
                                if (rejecting == null) return;
                                const target = rejecting;
                                setRejecting(null);
                                void reject(target.item, target.comment);
                            }}
                        >
                            {t("queueReject")}
                        </Button>
                    </div>
                </ResponsiveDialogContent>
            </ResponsiveDialog>

            <ResponsiveDialog
                open={detail != null}
                onOpenChange={(open) => { if (!open) setDetail(null); }}
            >
                <ResponsiveDialogContent className="space-y-3 p-5 sm:max-w-md sm:p-6">
                    <ResponsiveDialogTitle>{detail?.title ?? ""}</ResponsiveDialogTitle>
                    <ResponsiveDialogDescription>
                        {detail != null ? reasonLabel(t, format, locale, detail) : ""}
                    </ResponsiveDialogDescription>
                    {detail != null && (
                        <div className="space-y-3">
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
                            <div className="flex flex-wrap justify-end gap-2">
                                <Button asChild variant="outline">
                                    <Link href={detail.context.href}>
                                        {t("queueOpenContext")}
                                        <ArrowRightIcon className="size-3.5" />
                                    </Link>
                                </Button>
                                <PrimaryAction
                                    item={detail}
                                    pending={pendingId === detail.id}
                                    onComplete={complete}
                                    onApprove={approve}
                                    onDismiss={dismiss}
                                    onReject={(target) => setRejecting({ item: target, comment: "" })}
                                    labels={t}
                                />
                            </div>
                        </div>
                    )}
                </ResponsiveDialogContent>
            </ResponsiveDialog>
        </section>
    );
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

function reasonLabel(t: Translate, format: Format, locale: string, item: WorkItem): string {
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
            <Button size="sm" disabled={pending} onClick={() => onComplete(item)}>
                <CheckIcon className="size-3.5" />
                {t("queueComplete")}
            </Button>
        );
    }
    if (item.permittedActions.includes("approve")) {
        return (
            <span className="flex gap-1.5">
                <Button size="sm" disabled={pending} onClick={() => onApprove(item)}>
                    <CheckIcon className="size-3.5" />
                    {t("queueApprove")}
                </Button>
                <Button size="sm" variant="outline" disabled={pending} onClick={() => onReject(item)}>
                    <XMarkIcon className="size-3.5" />
                    {t("queueReject")}
                </Button>
            </span>
        );
    }
    if (item.permittedActions.includes("dismiss")) {
        return (
            <Button size="sm" variant="outline" disabled={pending} onClick={() => onDismiss(item)}>
                {t("queueDismiss")}
            </Button>
        );
    }
    return null;
}

function QueueRow({
    item,
    pending,
    locale,
    onComplete,
    onDismiss,
    onSnooze,
    onApprove,
    onReject,
    onDetail,
}: {
    item: WorkItem;
    pending: boolean;
    locale: string;
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
            </span>
            <button
                type="button"
                className="min-w-0 flex-1 text-left sm:hidden"
                onClick={() => onDetail(item)}
            >
                <RowText item={item} t={t} format={format} locale={locale} />
            </button>
            <Link href={item.context.href} className="group hidden min-w-0 flex-1 sm:block">
                <RowText item={item} t={t} format={format} locale={locale} />
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
                        <Button variant="ghost" size="icon-sm" aria-label={t("queueMore")} disabled={pending}>
                            <EllipsisHorizontalIcon />
                        </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end">
                        <DropdownMenuItem asChild>
                            <Link href={item.context.href}>{t("queueOpenContext")}</Link>
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
    locale,
}: {
    item: WorkItem;
    t: Translate;
    format: Format;
    locale: string;
}) {
    return (
        <>
            <span className="flex items-center gap-2">
                <span className="truncate text-sm font-medium text-foreground transition-colors group-hover:text-primary">
                    {item.title}
                </span>
            </span>
            <span className="mt-0.5 block truncate text-xs text-muted-foreground">
                {reasonLabel(t, format, locale, item)}
                {item.context.label !== item.title && ` · ${item.context.label}`}
            </span>
        </>
    );
}
