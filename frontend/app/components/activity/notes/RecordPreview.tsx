"use client";

import { useEffect, useState } from "react";
import { BriefcaseIcon, BuildingOffice2Icon, EnvelopeIcon, GlobeAltIcon, UserIcon } from "@heroicons/react/24/outline";
import { useLocale, useTranslations } from "next-intl";

import { getCompanyById, getContactById, getDealSummary } from "@/app/lib/api";
import { formatShortDate } from "@/app/lib/utils";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";

type RecordType = "person" | "deal" | "company";

function useRecord<T>(fetcher: (id: number) => Promise<T>, id: number) {
    const [data, setData] = useState<T | null>(null);
    const [loading, setLoading] = useState(true);
    useEffect(() => {
        let cancelled = false;
        fetcher(id)
            .then((value) => {
                if (!cancelled) setData(value);
            })
            .catch(() => {})
            .finally(() => {
                if (!cancelled) setLoading(false);
            });
        return () => {
            cancelled = true;
        };
    }, [fetcher, id]);
    return { data, loading };
}

export function PreviewSkeleton() {
    return (
        <div className="flex animate-pulse gap-3">
            <div className="size-10 shrink-0 rounded-full bg-muted" />
            <div className="min-w-0 flex-1 space-y-2 py-1">
                <div className="h-3 w-2/3 rounded bg-muted" />
                <div className="h-2.5 w-1/2 rounded bg-muted" />
            </div>
        </div>
    );
}

function formatCurrency(value: number, currency: string, locale: string): string {
    try {
        return new Intl.NumberFormat(locale, {
            style: "currency",
            currency: currency || "USD",
            maximumFractionDigits: 0,
        }).format(value);
    } catch {
        return `${value}`;
    }
}

function ContactPreview({ id }: { id: number }) {
    const t = useTranslations("ActivityNotesReferencePreview");
    const { data, loading } = useRecord(getContactById, id);
    if (loading) return <PreviewSkeleton />;
    if (!data) return <p className="text-sm text-muted-foreground">{t("contactUnavailable")}</p>;
    return (
        <div className="flex gap-3">
            <Avatar size="lg" className="ring-1 ring-border">
                {data.imageUrl ? (
                    <AvatarImage src={data.imageUrl} alt={data.name} />
                ) : (
                    <AvatarFallback>
                        <UserIcon className="size-4 text-muted-foreground" />
                    </AvatarFallback>
                )}
            </Avatar>
            <div className="min-w-0 flex-1">
                <p className="truncate font-semibold text-foreground">{data.name}</p>
                {data.title ? <p className="truncate text-sm text-muted-foreground">{data.title}</p> : null}
                {data.company?.name ? (
                    <p className="mt-1 flex items-center gap-1 truncate text-xs text-muted-foreground">
                        <BuildingOffice2Icon className="size-3 shrink-0" />
                        {data.company.name}
                    </p>
                ) : null}
                {data.email ? (
                    <p className="flex items-center gap-1 truncate text-xs text-muted-foreground">
                        <EnvelopeIcon className="size-3 shrink-0" />
                        {data.email}
                    </p>
                ) : null}
            </div>
        </div>
    );
}

function DealPreview({ id }: { id: number }) {
    const locale = useLocale();
    const t = useTranslations("ActivityNotesReferencePreview");
    const { data, loading } = useRecord(getDealSummary, id);
    if (loading) return <PreviewSkeleton />;
    if (!data) return <p className="text-sm text-muted-foreground">{t("dealUnavailable")}</p>;
    const stage = [data.pipelineName, data.stageName].filter(Boolean).join(" · ");
    return (
        <div>
            <div className="flex items-center gap-2">
                <span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-muted">
                    <BriefcaseIcon className="size-4 text-muted-foreground" />
                </span>
                <p className="min-w-0 flex-1 truncate font-semibold text-foreground">{data.name}</p>
            </div>
            <dl className="mt-2 space-y-1 text-xs text-muted-foreground">
                <div className="flex justify-between gap-2">
                    <dt>{t("value")}</dt>
                    <dd className="font-medium tabular-nums text-foreground">
                        {formatCurrency(data.value, data.currency, locale)}
                    </dd>
                </div>
                {data.companyName ? (
                    <div className="flex justify-between gap-2">
                        <dt>{t("company")}</dt>
                        <dd className="ml-2 truncate font-medium text-foreground">{data.companyName}</dd>
                    </div>
                ) : null}
                {stage ? (
                    <div className="flex justify-between gap-2">
                        <dt>{t("stage")}</dt>
                        <dd className="ml-2 truncate font-medium text-foreground">{stage}</dd>
                    </div>
                ) : null}
                <div className="flex justify-between gap-2">
                    <dt>{t("status")}</dt>
                    <dd className="font-medium capitalize text-foreground">{data.status}</dd>
                </div>
                {data.expectedCloseDate ? (
                    <div className="flex justify-between gap-2">
                        <dt>{t("expectedClose")}</dt>
                        <dd className="font-medium text-foreground">
                            {formatShortDate(data.expectedCloseDate, locale)}
                        </dd>
                    </div>
                ) : null}
            </dl>
        </div>
    );
}

function CompanyPreview({ id }: { id: number }) {
    const t = useTranslations("ActivityNotesReferencePreview");
    const { data, loading } = useRecord(getCompanyById, id);
    if (loading) return <PreviewSkeleton />;
    if (!data) return <p className="text-sm text-muted-foreground">{t("companyUnavailable")}</p>;
    return (
        <div className="flex gap-3">
            <Avatar size="lg" className="ring-1 ring-border">
                {data.logoUrl ? (
                    <AvatarImage src={data.logoUrl} alt={data.name} />
                ) : (
                    <AvatarFallback>
                        <BuildingOffice2Icon className="size-4 text-muted-foreground" />
                    </AvatarFallback>
                )}
            </Avatar>
            <div className="min-w-0 flex-1">
                <p className="truncate font-semibold text-foreground">{data.name}</p>
                {data.industry ? <p className="truncate text-sm text-muted-foreground">{data.industry}</p> : null}
                {data.website ? (
                    <p className="flex items-center gap-1 truncate text-xs text-muted-foreground">
                        <GlobeAltIcon className="size-3 shrink-0" />
                        {data.website}
                    </p>
                ) : null}
            </div>
        </div>
    );
}

/**
 * Compact overview of a referenced CRM record, fetched lazily when the hover
 * card opens. Reachable data only — the chip itself links to the full record.
 */
export default function RecordPreview({ type, id }: { type: RecordType; id: number }) {
    if (type === "person") return <ContactPreview id={id} />;
    if (type === "deal") return <DealPreview id={id} />;
    return <CompanyPreview id={id} />;
}
