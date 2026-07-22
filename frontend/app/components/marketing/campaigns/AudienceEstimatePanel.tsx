"use client";

import { useLocale, useTranslations } from "next-intl";
import { cn } from "@/lib/utils";
import {
    type CampaignAudienceEstimate,
    type CampaignAudienceRecordType,
} from "@/app/lib/types";
import CountUp from "@/app/components/dashboard/CountUp";

function ExclusionRow({
    label,
    hint,
    value,
    dotClass,
    locale,
}: {
    label: string;
    hint: string;
    value: number;
    dotClass: string;
    locale: string;
}) {
    return (
        <div className="flex items-start justify-between gap-4 px-5 py-3">
            <div className="min-w-0">
                <div className="flex items-center gap-2">
                    <span className={cn("size-2 shrink-0 rounded-full", dotClass)} />
                    <span className="text-sm font-medium text-foreground">{label}</span>
                </div>
                <p className="mt-0.5 pl-4 text-xs text-muted-foreground">{hint}</p>
            </div>
            <span className="shrink-0 tabular-nums text-sm font-semibold text-foreground">
                {value.toLocaleString(locale)}
            </span>
        </div>
    );
}

/**
 * A point-in-time audience estimate: who would receive the campaign now, and why others are
 * excluded. Consent and suppression only apply to contact audiences; other record types show a note.
 */
export default function AudienceEstimatePanel({
    estimate,
    recordType,
}: {
    estimate: CampaignAudienceEstimate;
    recordType: CampaignAudienceRecordType;
}) {
    const t = useTranslations("CampaignAudience");
    const locale = useLocale();
    const total = estimate.estimatedIncluded + estimate.excludedTotal;
    const includedPct = total === 0 ? 0 : Math.round((estimate.estimatedIncluded / total) * 100);
    const isPerson = recordType === "person";

    return (
        <div className="flex flex-col gap-5">
            <div className="grid grid-cols-1 gap-px overflow-hidden rounded-2xl bg-border ring-1 ring-border sm:grid-cols-2">
                <div className="flex flex-col gap-2 bg-card p-5">
                    <span className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                        {t("included")}
                    </span>
                    <CountUp
                        value={estimate.estimatedIncluded}
                        className="text-3xl leading-none tabular-nums text-foreground"
                    />
                </div>
                <div className="flex flex-col gap-2 bg-card p-5">
                    <span className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                        {t("excluded")}
                    </span>
                    <span className="text-3xl leading-none tabular-nums text-muted-foreground">
                        {estimate.excludedTotal.toLocaleString(locale)}
                    </span>
                </div>
            </div>

            <div
                className="flex h-2 overflow-hidden rounded-full bg-muted"
                role="img"
                aria-label={`${includedPct}%`}
            >
                <div className="h-full bg-brand transition-[width]" style={{ width: `${includedPct}%` }} />
            </div>

            {isPerson ? (
                <div className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                    <ExclusionRow
                        label={t("excludedRestricted")}
                        hint={t("restrictedHint")}
                        value={estimate.excludedRestricted}
                        dotClass="bg-destructive"
                        locale={locale}
                    />
                    <ExclusionRow
                        label={t("excludedSuppressed")}
                        hint={t("suppressedHint")}
                        value={estimate.excludedSuppressed}
                        dotClass="bg-risk-medium"
                        locale={locale}
                    />
                    <ExclusionRow
                        label={t("excludedConsent")}
                        hint={t("consentHint")}
                        value={estimate.excludedConsent}
                        dotClass="bg-muted-foreground"
                        locale={locale}
                    />
                </div>
            ) : (
                <p className="text-xs text-muted-foreground">{t("onlyPersonNote")}</p>
            )}

            {estimate.sampleLabels.length > 0 && (
                <div className="flex flex-col gap-2">
                    <span className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                        {t("sample")}
                    </span>
                    <div className="flex flex-wrap gap-1.5">
                        {estimate.sampleLabels.map((label) => (
                            <span
                                key={label.id}
                                className="inline-flex items-center rounded-full bg-muted px-2.5 py-1 text-xs text-foreground ring-1 ring-inset ring-border"
                            >
                                {label.label}
                            </span>
                        ))}
                    </div>
                </div>
            )}
        </div>
    );
}
