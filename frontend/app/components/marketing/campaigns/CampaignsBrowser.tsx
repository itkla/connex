"use client";

import { useState } from "react";
import Link from "next/link";
import { useLocale, useTranslations } from "next-intl";
import { ChevronRightIcon, MegaphoneIcon, PlusIcon } from "@heroicons/react/24/outline";
import { Button } from "@/components/ui/button";
import Rise from "@/app/components/motion/Rise";
import { type Campaign } from "@/app/lib/types";
import { formatCurrency, formatShortDate } from "@/app/lib/utils";
import NewCampaignDialog from "@/app/components/marketing/campaigns/NewCampaignDialog";
import CampaignStatusBadge from "@/app/components/marketing/campaigns/CampaignStatusBadge";
import { campaignBuilderPath } from "@/app/components/marketing/campaigns/campaignInstantCreate";
import { PageHeader } from "@/app/components/PageHeader";
import { PageShell } from "@/app/components/PageShell";

/**
 * The Campaigns list surface: a roster with the D5 instant-create entry point, offered only to a
 * viewer who may actually create one. `POST /api/campaigns` requires `CAMPAIGN_MANAGE`, which the
 * built-in `member` role does not hold.
 */
export default function CampaignsBrowser({
    campaigns,
    canCreate,
}: {
    campaigns: Campaign[];
    canCreate: boolean;
}) {
    const t = useTranslations("CampaignsPage");
    const locale = useLocale();
    const [open, setOpen] = useState(false);

    const openDialog = () => setOpen(true);

    return (
        <>
            <PageShell>
                <Rise>
                    <PageHeader
                        title={t("title")}
                        description={t("subtitle")}
                        actions={
                            canCreate ? (
                                <Button variant="brand" onClick={openDialog} className="shrink-0">
                                    <PlusIcon className="size-4" />
                                    {t("new")}
                                </Button>
                            ) : null
                        }
                    />
                </Rise>

                {campaigns.length === 0 ? (
                    <Rise delay={0.06} className="flex flex-col items-center justify-center gap-3 rounded-2xl border border-dashed border-border bg-card px-6 py-16 text-center sm:py-20">
                        <span className="flex size-12 items-center justify-center rounded-full bg-brand-light text-brand-dark">
                            <MegaphoneIcon className="size-6" />
                        </span>
                        <h2 className="text-lg font-semibold">{t("empty")}</h2>
                        <p className="max-w-sm text-sm text-muted-foreground">
                            {canCreate ? t("emptyHint") : t("emptyHintReadOnly")}
                        </p>
                        {canCreate && (
                            <Button variant="brand" onClick={openDialog} className="mt-2">
                                <PlusIcon className="size-4" />
                                {t("new")}
                            </Button>
                        )}
                    </Rise>
                ) : (
                    <Rise delay={0.06} className="flex flex-col gap-3">
                        <p className="px-1 text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                            {t("count", { count: campaigns.length })}
                        </p>
                        <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                            {campaigns.map((campaign) => {
                                const budget =
                                    campaign.budgetAmount != null && campaign.budgetCurrency
                                        ? formatCurrency(
                                              campaign.budgetAmount,
                                              campaign.budgetCurrency,
                                              locale,
                                          )
                                        : null;
                                const window =
                                    campaign.startAt || campaign.endAt
                                        ? `${formatShortDate(campaign.startAt ?? undefined, locale)} – ${formatShortDate(campaign.endAt ?? undefined, locale)}`
                                        : null;
                                return (
                                    <li key={campaign.id}>
                                        <Link
                                            href={campaignBuilderPath(campaign.id)}
                                            className="group flex items-center gap-3 px-4 py-3.5 outline-none transition-colors hover:bg-muted/50 focus-visible:bg-muted/50 sm:px-5"
                                        >
                                            <div className="min-w-0 flex-1">
                                                <p className="truncate font-medium text-foreground transition-colors group-hover:text-brand-hover">
                                                    {campaign.name}
                                                </p>
                                                <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs text-muted-foreground">
                                                    {campaign.objective && (
                                                        <span className="max-w-full truncate">
                                                            {campaign.objective}
                                                        </span>
                                                    )}
                                                    {campaign.objective && (
                                                        <span aria-hidden className="text-border">
                                                            ·
                                                        </span>
                                                    )}
                                                    <span>{campaign.type}</span>
                                                    {budget && (
                                                        <>
                                                            <span aria-hidden className="text-border">
                                                                ·
                                                            </span>
                                                            <span className="tabular-nums">{budget}</span>
                                                        </>
                                                    )}
                                                    {window && (
                                                        <>
                                                            <span aria-hidden className="hidden text-border sm:inline">
                                                                ·
                                                            </span>
                                                            <span className="hidden tabular-nums sm:inline">
                                                                {window}
                                                            </span>
                                                        </>
                                                    )}
                                                </div>
                                            </div>
                                            <CampaignStatusBadge status={campaign.status} />
                                            <ChevronRightIcon className="size-4 shrink-0 text-muted-foreground/40 transition-transform group-hover:translate-x-0.5 group-hover:text-muted-foreground" />
                                        </Link>
                                    </li>
                                );
                            })}
                        </ul>
                    </Rise>
                )}
            </PageShell>

            <NewCampaignDialog open={open} onOpenChange={setOpen} />
        </>
    );
}
