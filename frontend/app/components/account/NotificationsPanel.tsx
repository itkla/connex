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

const IN_APP = "in_app";
const EMAIL = "email";

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
    { type: "task.mention", icon: AtSymbolIcon, titleKey: "taskMentionTitle", descriptionKey: "taskMentionDescription" },
    { type: "activity.mention", icon: AtSymbolIcon, titleKey: "activityMentionTitle", descriptionKey: "activityMentionDescription" },
    { type: "introduction.mention", icon: AtSymbolIcon, titleKey: "introductionMentionTitle", descriptionKey: "introductionMentionDescription" },
    { type: "deal.mention", icon: AtSymbolIcon, titleKey: "dealMentionTitle", descriptionKey: "dealMentionDescription" },
] as const;

type Channels = { inApp: boolean; email: boolean };

function resolve(
    preferences: NotificationPreference[],
    type: string,
    channel: string,
    fallback: boolean,
): boolean {
    const match = preferences.find((p) => p.type === type && p.channel === channel);
    if (match) return match.enabled;
    const wildcard = preferences.find((p) => p.type === "*" && p.channel === channel);
    return wildcard ? wildcard.enabled : fallback;
}

export default function NotificationsPanel() {
    const t = useTranslations("AccountNotifications");
    const [prefs, setPrefs] = useState<Record<string, Channels>>({});
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
                const preferences = await getNotificationPreferences();
                if (cancelled) return;
                const next: Record<string, Channels> = {};
                for (const { type } of TYPES) {
                    next[type] = {
                        inApp: resolve(preferences, type, IN_APP, true),
                        email: resolve(preferences, type, EMAIL, false),
                    };
                }
                setPrefs(next);
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

    const toggle = async (type: string, channel: "inApp" | "email", value: boolean) => {
        const previous = prefs;
        const next = { ...prefs, [type]: { ...prefs[type], [channel]: value } };
        setPrefs(next);
        setSaving(true);
        try {
            const payload: NotificationPreference[] = TYPES.flatMap(({ type: key }) => [
                { type: key, channel: IN_APP, enabled: next[key]?.inApp ?? true },
                { type: key, channel: EMAIL, enabled: next[key]?.email ?? false },
            ]);
            await updateNotificationPreferences(payload);
        } catch {
            setPrefs(previous);
            toastError(t("saveFailed"));
        } finally {
            setSaving(false);
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
                <div className="overflow-hidden rounded-2xl border border-border bg-card">
                    <div className="flex items-center gap-4 border-b border-border px-4 py-2">
                        <div className="min-w-0 flex-1" />
                        <span className="w-16 shrink-0 text-center text-xs font-medium uppercase tracking-wide text-muted-foreground">
                            {t("columnInApp")}
                        </span>
                        <span className="w-16 shrink-0 text-center text-xs font-medium uppercase tracking-wide text-muted-foreground">
                            {t("columnEmail")}
                        </span>
                    </div>
                    <ul className="divide-y divide-border">
                        {TYPES.map(({ type, icon: Icon, titleKey, descriptionKey }) => (
                            <li key={type} className="flex items-center gap-4 px-4 py-3.5">
                                <Icon aria-hidden className="size-5 shrink-0 text-muted-foreground" />
                                <div className="min-w-0 flex-1">
                                    <p className="text-sm font-medium text-foreground">{t(titleKey)}</p>
                                    <p className="text-sm text-muted-foreground">{t(descriptionKey)}</p>
                                </div>
                                {loading ? (
                                    <>
                                        <Skeleton className="h-[18.4px] w-16 shrink-0 rounded-full" />
                                        <Skeleton className="h-[18.4px] w-16 shrink-0 rounded-full" />
                                    </>
                                ) : (
                                    <>
                                        <div className="flex w-16 shrink-0 justify-center">
                                            <Switch
                                                checked={prefs[type]?.inApp ?? true}
                                                disabled={saving}
                                                onCheckedChange={(value) => toggle(type, "inApp", value)}
                                                aria-label={`${t(titleKey)} — ${t("columnInApp")}`}
                                            />
                                        </div>
                                        <div className="flex w-16 shrink-0 justify-center">
                                            <Switch
                                                checked={prefs[type]?.email ?? false}
                                                disabled={saving}
                                                onCheckedChange={(value) => toggle(type, "email", value)}
                                                aria-label={`${t(titleKey)} — ${t("columnEmail")}`}
                                            />
                                        </div>
                                    </>
                                )}
                            </li>
                        ))}
                    </ul>
                </div>
            )}
        </Rise>
    );
}
