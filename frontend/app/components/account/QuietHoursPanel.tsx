"use client";

import { useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { MoonIcon } from "@heroicons/react/24/outline";

import type { QuietHours, QuietHoursConfig, QuietHoursDay } from "@/app/lib/types";
import { getQuietHours, updateQuietHours } from "@/app/lib/api";
import { toastError } from "@/app/lib/toast";
import { formatDateTime } from "@/app/lib/utils";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import { cn } from "@/lib/utils";
import Rise from "@/app/components/motion/Rise";
import SectionHeader from "@/app/components/dashboard/SectionHeader";

const DAYS: readonly { value: QuietHoursDay; key: string }[] = [
    { value: "MONDAY", key: "dayMon" },
    { value: "TUESDAY", key: "dayTue" },
    { value: "WEDNESDAY", key: "dayWed" },
    { value: "THURSDAY", key: "dayThu" },
    { value: "FRIDAY", key: "dayFri" },
    { value: "SATURDAY", key: "daySat" },
    { value: "SUNDAY", key: "daySun" },
];

/**
 * Projects the server-returned quiet-hours state to the editable config the PUT
 * endpoint expects, dropping the server-computed `activeNow` / transition fields.
 */
function toConfig(data: QuietHours): QuietHoursConfig {
    return {
        enabled: data.enabled,
        timezone: data.timezone,
        start: data.start,
        end: data.end,
        days: data.days,
        bypassPolicy: data.bypassPolicy,
    };
}

/**
 * Account-level quiet-hours settings: an enable switch, a start/end window,
 * per-day selection, and a read-only timezone, with an active-now indicator and
 * the next transition. Every change optimistically PUTs a full replacement and
 * reverts on failure.
 */
export default function QuietHoursPanel() {
    const t = useTranslations("AccountQuietHours");
    const locale = useLocale();
    const [data, setData] = useState<QuietHours | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(false);
    const [saving, setSaving] = useState(false);
    const [reloadKey, setReloadKey] = useState(0);

    useEffect(() => {
        let cancelled = false;
        (async () => {
            setLoading(true);
            setError(false);
            try {
                const result = await getQuietHours();
                if (!cancelled) setData(result);
            } catch {
                if (!cancelled) {
                    setError(true);
                    toastError(t("loadFailed"));
                }
            } finally {
                if (!cancelled) setLoading(false);
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [t, reloadKey]);

    async function save(patch: Partial<QuietHoursConfig>) {
        if (!data) return;
        const previous = data;
        setData({ ...data, ...patch });
        setSaving(true);
        try {
            const result = await updateQuietHours({ ...toConfig(data), ...patch });
            setData(result);
        } catch {
            setData(previous);
            toastError(t("saveFailed"));
        } finally {
            setSaving(false);
        }
    }

    function toggleDay(day: QuietHoursDay) {
        if (!data) return;
        const selected = new Set(data.days);
        if (selected.has(day)) selected.delete(day);
        else selected.add(day);
        const days = DAYS.map((entry) => entry.value).filter((value) => selected.has(value));
        void save({ days });
    }

    return (
        <Rise className="space-y-3">
            <div>
                <SectionHeader title={t("title")} />
                <p className="max-w-prose px-6 text-sm text-muted-foreground">{t("subtitle")}</p>
            </div>

            {error ? (
                <div className="flex flex-col items-center gap-3 rounded-2xl border border-border bg-card px-4 py-8 text-center">
                    <p className="text-sm text-muted-foreground">{t("loadFailed")}</p>
                    <Button variant="outline" size="sm" onClick={() => setReloadKey((key) => key + 1)}>
                        {t("retry")}
                    </Button>
                </div>
            ) : (
                <div className="overflow-hidden rounded-2xl border border-border bg-card">
                    <div className="flex items-center gap-4 px-4 py-3.5">
                        <MoonIcon aria-hidden className="size-5 shrink-0 text-muted-foreground" />
                        <div className="min-w-0 flex-1">
                            <p className="text-sm font-medium text-foreground">{t("enableLabel")}</p>
                            <p className="text-sm text-muted-foreground">{t("enableDescription")}</p>
                        </div>
                        {loading || !data ? (
                            <Skeleton className="h-[18.4px] w-16 shrink-0 rounded-full" />
                        ) : (
                            <Switch
                                checked={data.enabled}
                                disabled={saving}
                                onCheckedChange={(value) => void save({ enabled: value })}
                                aria-label={t("enableLabel")}
                            />
                        )}
                    </div>

                    {data && data.enabled ? (
                        <div className="space-y-5 border-t border-border px-4 py-4">
                            {data.activeNow || data.nextTransitionAt ? (
                                <div className="flex flex-wrap items-center gap-2">
                                    {data.activeNow ? (
                                        <span className="inline-flex items-center gap-1.5 rounded-full bg-brand-light px-2 py-0.5 text-xs font-medium text-brand-dark">
                                            <span className="size-1.5 rounded-full bg-brand-dark" />
                                            {t("activeNow")}
                                        </span>
                                    ) : null}
                                    {data.nextTransitionAt ? (
                                        <span className="text-xs text-muted-foreground">
                                            {t(data.activeNow ? "resumesAt" : "nextStartsAt", {
                                                time: formatDateTime(data.nextTransitionAt, locale),
                                            })}
                                        </span>
                                    ) : null}
                                </div>
                            ) : null}

                            <div className="grid gap-4 sm:grid-cols-2">
                                <div className="grid gap-2">
                                    <Label htmlFor="quiet-hours-start">{t("startLabel")}</Label>
                                    <Input
                                        id="quiet-hours-start"
                                        type="time"
                                        value={data.start}
                                        disabled={saving}
                                        onChange={(event) => void save({ start: event.target.value })}
                                    />
                                </div>
                                <div className="grid gap-2">
                                    <Label htmlFor="quiet-hours-end">{t("endLabel")}</Label>
                                    <Input
                                        id="quiet-hours-end"
                                        type="time"
                                        value={data.end}
                                        disabled={saving}
                                        onChange={(event) => void save({ end: event.target.value })}
                                    />
                                </div>
                            </div>

                            <div className="grid gap-2">
                                <Label>{t("daysLabel")}</Label>
                                <div className="flex flex-wrap gap-1.5" role="group" aria-label={t("daysLabel")}>
                                    {DAYS.map((day) => {
                                        const active = data.days.includes(day.value);
                                        return (
                                            <button
                                                key={day.value}
                                                type="button"
                                                aria-pressed={active}
                                                disabled={saving}
                                                onClick={() => toggleDay(day.value)}
                                                className={cn(
                                                    "inline-flex h-8 min-w-11 items-center justify-center rounded-full px-3 text-xs font-medium outline-none transition-colors active:scale-[0.97] focus-visible:ring-2 focus-visible:ring-brand/40 disabled:opacity-60",
                                                    active
                                                        ? "bg-brand text-brand-foreground"
                                                        : "bg-muted text-muted-foreground hover:text-foreground",
                                                )}
                                            >
                                                {t(day.key)}
                                            </button>
                                        );
                                    })}
                                </div>
                            </div>

                            <div className="grid gap-1">
                                <span className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                                    {t("timezoneLabel")}
                                </span>
                                <p className="text-sm text-foreground">{data.timezone}</p>
                                <p className="text-xs text-muted-foreground">{t("timezoneHint")}</p>
                            </div>
                        </div>
                    ) : null}
                </div>
            )}
        </Rise>
    );
}
