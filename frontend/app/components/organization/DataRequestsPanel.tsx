"use client";

import { useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { EllipsisVerticalIcon, ArrowDownTrayIcon, PencilSquareIcon, PlusIcon } from "@heroicons/react/24/outline";

import type { DataSubjectRequest, DataSubjectRequestStatus } from "@/app/lib/types";
import { ApiError, getDataSubjectRequests, getDataSubjectDisclosure } from "@/app/lib/api";
import { toastSuccess } from "@/app/lib/toast";
import { useApiErrorToast } from "@/app/hooks/useApiErrorToast";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";
import Rise from "@/app/components/motion/Rise";
import {
    SettingsPanelHeading,
    type SettingsPanelPresentation,
} from "@/app/components/settings/SettingsSection";
import { NoAccessCard, EmptyRow, ListCard, rowActionTrigger } from "@/app/components/organization/OrgPrimitives";
import DataRequestDialog from "@/app/components/organization/DataRequestDialog";

const PAGE_SIZE = 30;

export const REQUEST_STATUSES: DataSubjectRequestStatus[] = [
    "received",
    "verifying",
    "in_progress",
    "responded",
    "refused",
    "closed",
];

const STATUS_VARIANT: Record<DataSubjectRequestStatus, "default" | "secondary" | "destructive" | "outline" | "ghost"> = {
    received: "outline",
    verifying: "secondary",
    in_progress: "default",
    responded: "secondary",
    refused: "destructive",
    closed: "secondary",
};

function formatDate(iso: string | null | undefined, locale: string) {
    if (!iso) return null;
    const date = new Date(iso.includes("T") ? iso : `${iso.replace(" ", "T")}`);
    if (Number.isNaN(date.getTime())) return iso;
    return date.toLocaleDateString(locale, { year: "numeric", month: "short", day: "numeric" });
}

function isOverdue(request: DataSubjectRequest) {
    if (!request.dueAt || request.respondedAt || request.closedAt) return false;
    const due = new Date(request.dueAt);
    return !Number.isNaN(due.getTime()) && due.getTime() < Date.now();
}

/**
 * Org-admin tracker for APPI data-subject (開示等) requests: lifecycle list, intake and
 * edit dialogs, and the subject-scoped disclosure download for verified disclosure requests.
 */
/**
 * The organization's data-subject requests under the APPI, and the disclosure exports assembled for
 * them. §4 sanctions the statutory register on this compliance surface; it is deliberate here and
 * nowhere else.
 *
 * @param presentation - which of the panel's two homes is rendering it; defaults to its own route
 */
export default function DataRequestsPanel({
    presentation = "page",
}: {
    presentation?: SettingsPanelPresentation;
} = {}) {
    const t = useTranslations("OrgDataRequests");
    const showApiError = useApiErrorToast("OrgDataRequests");
    const locale = useLocale();
    const { activeWorkspace } = useWorkspace();
    const orgId = activeWorkspace?.orgId ?? null;

    const [requests, setRequests] = useState<DataSubjectRequest[]>([]);
    const [statusFilter, setStatusFilter] = useState<DataSubjectRequestStatus | "all">("all");
    const [loading, setLoading] = useState(true);
    const [accessDenied, setAccessDenied] = useState(false);
    const [loadError, setLoadError] = useState(false);
    const [loadingMore, setLoadingMore] = useState(false);
    const [hasMore, setHasMore] = useState(false);
    const [dialogOpen, setDialogOpen] = useState(false);
    const [editing, setEditing] = useState<DataSubjectRequest | null>(null);
    const [downloadingId, setDownloadingId] = useState<number | null>(null);

    useEffect(() => {
        if (!orgId) return;
        let cancelled = false;
        (async () => {
            setLoading(true);
            setLoadError(false);
            try {
                const page = await getDataSubjectRequests(orgId, {
                    status: statusFilter === "all" ? undefined : statusFilter,
                    limit: PAGE_SIZE,
                    offset: 0,
                });
                if (cancelled) return;
                setRequests(page);
                setHasMore(page.length === PAGE_SIZE);
            } catch (err) {
                if (cancelled) return;
                if (err instanceof ApiError && err.status === 403) setAccessDenied(true);
                else setLoadError(true);
            } finally {
                if (!cancelled) setLoading(false);
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [orgId, statusFilter]);

    async function loadMore() {
        if (!orgId) return;
        setLoadingMore(true);
        try {
            const page = await getDataSubjectRequests(orgId, {
                status: statusFilter === "all" ? undefined : statusFilter,
                limit: PAGE_SIZE,
                offset: requests.length,
            });
            setRequests((prev) => [...prev, ...page]);
            setHasMore(page.length === PAGE_SIZE);
        } catch (err) {
            showApiError(err, "loadMoreFailed");
        } finally {
            setLoadingMore(false);
        }
    }

    async function downloadDisclosure(request: DataSubjectRequest) {
        if (!orgId || downloadingId != null) return;
        setDownloadingId(request.id);
        try {
            const disclosure = await getDataSubjectDisclosure(orgId, request.id);
            const blob = new Blob([JSON.stringify(disclosure, null, 2)], { type: "application/json" });
            const url = URL.createObjectURL(blob);
            const anchor = document.createElement("a");
            anchor.href = url;
            anchor.download = `disclosure-request-${request.id}.json`;
            anchor.click();
            setTimeout(() => URL.revokeObjectURL(url), 0);
            toastSuccess(t("disclosureDownloaded"));
        } catch (err) {
            showApiError(err, "disclosureFailed");
        } finally {
            setDownloadingId(null);
        }
    }

    function openCreate() {
        setEditing(null);
        setDialogOpen(true);
    }

    function openEdit(request: DataSubjectRequest) {
        setEditing(request);
        setDialogOpen(true);
    }

    function handleSaved(saved: DataSubjectRequest) {
        setRequests((prev) => {
            const index = prev.findIndex((r) => r.id === saved.id);
            const matchesFilter = statusFilter === "all" || saved.status === statusFilter;
            if (index === -1) return matchesFilter ? [saved, ...prev] : prev;
            if (!matchesFilter) return prev.filter((r) => r.id !== saved.id);
            const next = [...prev];
            next[index] = saved;
            return next;
        });
    }

    if (accessDenied) return <NoAccessCard />;

    return (
        <Rise className="space-y-4">
            <SettingsPanelHeading
                presentation={presentation}
                title={t("title")}
                description={t("subtitle")}
                action={
                    <div className="flex items-center gap-2">
                        <Select
                            value={statusFilter}
                            onValueChange={(value) => setStatusFilter(value as DataSubjectRequestStatus | "all")}
                        >
                            <SelectTrigger size="sm" aria-label={t("filterLabel")}>
                                <SelectValue />
                            </SelectTrigger>
                            <SelectContent align="end">
                                <SelectItem value="all">{t("filterAll")}</SelectItem>
                                {REQUEST_STATUSES.map((status) => (
                                    <SelectItem key={status} value={status}>
                                        {t(`status_${status}`)}
                                    </SelectItem>
                                ))}
                            </SelectContent>
                        </Select>
                        <Button size="sm" onClick={openCreate}>
                            <PlusIcon className="size-4" />
                            {t("newRequest")}
                        </Button>
                    </div>
                }
            />

            {loading ? (
                <ListCard>
                    {[0, 1, 2].map((i) => (
                        <li key={i} className="flex items-center justify-between gap-3 px-4 py-3">
                            <span className="h-4 w-56 animate-pulse rounded bg-muted" />
                            <span className="h-4 w-20 animate-pulse rounded-4xl bg-muted" />
                        </li>
                    ))}
                </ListCard>
            ) : loadError ? (
                <EmptyRow>{t("loadError")}</EmptyRow>
            ) : requests.length === 0 ? (
                <EmptyRow>{statusFilter === "all" ? t("empty") : t("emptyFiltered")}</EmptyRow>
            ) : (
                <>
                    <ListCard>
                        {requests.map((request) => {
                            const overdue = isOverdue(request);
                            const received = formatDate(request.receivedAt, locale);
                            const due = formatDate(request.dueAt, locale);
                            return (
                                <li key={request.id} className="group flex items-center gap-4 px-4 py-3">
                                    <div className="min-w-0 flex-1">
                                        <p className="truncate text-sm font-medium text-foreground">
                                            {t(`type_${request.requestType}`)}
                                            <span className="text-muted-foreground"> · </span>
                                            <span className="font-normal">{request.subjectName}</span>
                                        </p>
                                        <p className="truncate text-xs text-muted-foreground">
                                            {t("receivedOn", { date: received ?? "" })}
                                            {due ? (
                                                <span className={overdue ? "text-destructive" : undefined}>
                                                    {" · "}
                                                    {overdue ? t("overdueOn", { date: due }) : t("dueOn", { date: due })}
                                                </span>
                                            ) : null}
                                        </p>
                                    </div>
                                    <Badge variant={STATUS_VARIANT[request.status]}>{t(`status_${request.status}`)}</Badge>
                                    <DropdownMenu>
                                        <DropdownMenuTrigger
                                            className={rowActionTrigger}
                                            aria-label={t("rowActions", { subject: request.subjectName })}
                                        >
                                            <EllipsisVerticalIcon className="size-4" />
                                        </DropdownMenuTrigger>
                                        <DropdownMenuContent align="end">
                                            <DropdownMenuItem onClick={() => openEdit(request)}>
                                                <PencilSquareIcon className="size-4" />
                                                {t("edit")}
                                            </DropdownMenuItem>
                                            {request.requestType === "disclosure" && (
                                                <DropdownMenuItem
                                                    disabled={downloadingId != null}
                                                    onClick={() => downloadDisclosure(request)}
                                                >
                                                    <ArrowDownTrayIcon className="size-4" />
                                                    {downloadingId === request.id ? t("downloading") : t("downloadDisclosure")}
                                                </DropdownMenuItem>
                                            )}
                                        </DropdownMenuContent>
                                    </DropdownMenu>
                                </li>
                            );
                        })}
                    </ListCard>
                    {hasMore && (
                        <div className="flex justify-center">
                            <Button variant="outline" size="sm" onClick={loadMore} disabled={loadingMore}>
                                {loadingMore ? t("loadingMore") : t("loadMore")}
                            </Button>
                        </div>
                    )}
                </>
            )}

            <DataRequestDialog
                open={dialogOpen}
                onOpenChange={setDialogOpen}
                orgId={orgId}
                editing={editing}
                onSaved={handleSaved}
            />
        </Rise>
    );
}
