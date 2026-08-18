"use client";

import { useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";

import type { AuditLogEntry } from "@/app/lib/types";
import { ApiError, getOrgAudit } from "@/app/lib/api";
import { toastError } from "@/app/lib/toast";
import { useLiveNow } from "@/app/hooks/useNow";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { Button } from "@/components/ui/button";
import SectionHeader from "@/app/components/dashboard/SectionHeader";
import Rise from "@/app/components/motion/Rise";
import { NoAccessCard, EmptyRow, ListCard } from "@/app/components/organization/OrgPrimitives";

const PAGE_SIZE = 30;

/**
 * Last-resort label for an audit action Connex has no sentence for yet: one readable phrase in
 * sentence case rather than the raw dotted code.
 */
function titleCaseAction(action: string) {
    const words = action.split(/[._]/).filter(Boolean);
    if (words.length === 0) return action;
    const [first, ...rest] = words;
    return [first.charAt(0).toUpperCase() + first.slice(1), ...rest].join(" ");
}

function relativeTime(iso: string, locale: string, now: number) {
    const then = new Date(iso.includes("T") ? iso : iso.replace(" ", "T") + "Z").getTime();
    if (Number.isNaN(then)) return iso;
    const mins = Math.round((now - then) / 60000);
    const rtf = new Intl.RelativeTimeFormat(locale, { numeric: "auto" });
    if (mins < 1) return rtf.format(0, "second");
    if (mins < 60) return rtf.format(-mins, "minute");
    const hours = Math.round(mins / 60);
    if (hours < 24) return rtf.format(-hours, "hour");
    return new Date(then).toLocaleDateString(locale);
}

export default function OrgAuditPanel() {
    const t = useTranslations("OrgAudit");
    const locale = useLocale();
    const now = useLiveNow();
    const { activeWorkspace } = useWorkspace();
    const orgId = activeWorkspace?.orgId ?? null;

    const [entries, setEntries] = useState<AuditLogEntry[]>([]);
    const [loading, setLoading] = useState(true);
    const [accessDenied, setAccessDenied] = useState(false);
    const [loadError, setLoadError] = useState(false);
    const [loadingMore, setLoadingMore] = useState(false);
    const [hasMore, setHasMore] = useState(false);

    const actionLabel = (action: string) => {
        const key = `action.${action}`;
        return t.has(key) ? t(key) : titleCaseAction(action);
    };

    useEffect(() => {
        if (!orgId) return;
        let cancelled = false;
        (async () => {
            setLoading(true);
            try {
                const page = await getOrgAudit(orgId, { limit: PAGE_SIZE, offset: 0 });
                if (cancelled) return;
                setEntries(page);
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
    }, [orgId]);

    async function loadMore() {
        if (!orgId) return;
        setLoadingMore(true);
        try {
            const page = await getOrgAudit(orgId, { limit: PAGE_SIZE, offset: entries.length });
            setEntries((prev) => [...prev, ...page]);
            setHasMore(page.length === PAGE_SIZE);
        } catch (err) {
            toastError(err instanceof Error ? err.message : String(err));
        } finally {
            setLoadingMore(false);
        }
    }

    if (accessDenied) return <NoAccessCard />;

    return (
        <Rise className="space-y-4">
            <div>
                <SectionHeader title={t("title")} />
                <p className="px-6 text-sm text-muted-foreground">{t("subtitle")}</p>
            </div>

            {loading ? (
                <ListCard>
                    {[0, 1, 2].map((i) => (
                        <li key={i} className="flex items-center justify-between gap-3 px-4 py-3">
                            <span className="h-4 w-48 animate-pulse rounded bg-muted" />
                            <span className="h-3 w-16 animate-pulse rounded bg-muted" />
                        </li>
                    ))}
                </ListCard>
            ) : loadError ? (
                <EmptyRow>{t("loadError")}</EmptyRow>
            ) : entries.length === 0 ? (
                <EmptyRow>{t("empty")}</EmptyRow>
            ) : (
                <>
                    <ListCard>
                        {entries.map((entry) => (
                            <li key={entry.id} className="flex items-start justify-between gap-4 px-4 py-3">
                                <div className="min-w-0 flex-1">
                                    <p className="truncate text-sm font-medium text-foreground">
                                        {entry.summary || actionLabel(entry.action)}
                                    </p>
                                    <p className="truncate text-xs text-muted-foreground">
                                        {actionLabel(entry.action)} ·{" "}
                                        {entry.currentActorLabel || entry.actorLabel || t("actorSystem")}
                                    </p>
                                </div>
                                <time
                                    className="shrink-0 pt-0.5 text-xs tabular-nums text-muted-foreground"
                                    dateTime={entry.createdAt}
                                    title={entry.createdAt}
                                >
                                    {relativeTime(entry.createdAt, locale, now)}
                                </time>
                            </li>
                        ))}
                    </ListCard>
                    {hasMore && (
                        <div className="flex justify-center">
                            <Button variant="outline" onClick={loadMore} disabled={loadingMore}>
                                {loadingMore ? <Loader2Icon className="size-4 animate-spin" /> : t("loadMore")}
                            </Button>
                        </div>
                    )}
                </>
            )}
        </Rise>
    );
}
