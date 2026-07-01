"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { ArrowTrendingDownIcon, AtSymbolIcon, BriefcaseIcon, CheckCircleIcon } from "@heroicons/react/24/outline";

import type { NotificationPreference } from "@/app/lib/types";
import { getNotificationPreferences, updateNotificationPreferences } from "@/app/lib/api";
import { toastError } from "@/app/lib/toast";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import Rise from "@/app/components/motion/Rise";
import SectionHeader from "@/app/components/dashboard/SectionHeader";

const CHANNEL = "in_app";

const TYPES = [
    { type: "task.due", icon: CheckCircleIcon, titleKey: "taskTitle", descriptionKey: "taskDescription" },
    { type: "deal.close", icon: BriefcaseIcon, titleKey: "dealTitle", descriptionKey: "dealDescription" },
    {
        type: "relationship.cooling",
        icon: ArrowTrendingDownIcon,
        titleKey: "relationshipTitle",
        descriptionKey: "relationshipDescription",
    },
    { type: "note.mention", icon: AtSymbolIcon, titleKey: "mentionTitle", descriptionKey: "mentionDescription" },
] as const;

export default function NotificationsPanel() {
    const t = useTranslations("WorkspaceNotifications");
    const [enabled, setEnabled] = useState<Record<string, boolean>>({});
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(false);
    const [savingType, setSavingType] = useState<string | null>(null);
    const [reloadKey, setReloadKey] = useState(0);

    useEffect(() => {
        let cancelled = false;
        (async () => {
            setLoading(true);
            setError(false);
            try {
                const preferences = await getNotificationPreferences();
                if (cancelled) return;
                const wildcard = preferences.find((p) => p.type === "*" && p.channel === CHANNEL);
                const next: Record<string, boolean> = {};
                for (const { type } of TYPES) {
                    const match = preferences.find((p) => p.type === type && p.channel === CHANNEL);
                    next[type] = match ? match.enabled : wildcard ? wildcard.enabled : true;
                }
                setEnabled(next);
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

    const toggle = async (type: string, value: boolean) => {
        const previous = enabled;
        const next = { ...enabled, [type]: value };
        setEnabled(next);
        setSavingType(type);
        try {
            const payload: NotificationPreference[] = TYPES.map(({ type: key }) => ({
                type: key,
                channel: CHANNEL,
                enabled: next[key] ?? true,
            }));
            await updateNotificationPreferences(payload);
        } catch {
            setEnabled(previous);
            toastError(t("saveFailed"));
        } finally {
            setSavingType(null);
        }
    };

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
                <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                    {TYPES.map(({ type, icon: Icon, titleKey, descriptionKey }) => (
                        <li key={type} className="flex items-center gap-4 px-4 py-3.5">
                            <Icon aria-hidden className="size-5 shrink-0 text-muted-foreground" />
                            <div className="min-w-0 flex-1">
                                <p className="text-sm font-medium text-foreground">{t(titleKey)}</p>
                                <p className="text-sm text-muted-foreground">{t(descriptionKey)}</p>
                            </div>
                            {loading ? (
                                <Skeleton className="h-[18.4px] w-8 shrink-0 rounded-full" />
                            ) : (
                                <Switch
                                    checked={enabled[type] ?? true}
                                    disabled={savingType !== null}
                                    onCheckedChange={(value) => toggle(type, value)}
                                    aria-label={t(titleKey)}
                                />
                            )}
                        </li>
                    ))}
                </ul>
            )}
        </Rise>
    );
}
