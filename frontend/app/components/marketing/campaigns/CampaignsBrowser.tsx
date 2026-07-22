"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { useLocale, useTranslations } from "next-intl";
import { ChevronRightIcon, MegaphoneIcon, PlusIcon } from "@heroicons/react/24/outline";
import { Button } from "@/components/ui/button";
import Rise from "@/app/components/motion/Rise";
import { type Campaign, type CampaignPayload } from "@/app/lib/types";
import { createCampaign, isFieldError } from "@/app/lib/api";
import { toastError } from "@/app/lib/toast";
import { formatCurrency, formatShortDate } from "@/app/lib/utils";
import NewCampaignDialog from "@/app/components/marketing/campaigns/NewCampaignDialog";
import CampaignStatusBadge from "@/app/components/marketing/campaigns/CampaignStatusBadge";

const EMPTY_PAYLOAD: CampaignPayload = {
    name: "",
    objective: null,
    type: "",
    status: "draft",
    budgetAmount: null,
    budgetCurrency: null,
    startAt: null,
    endAt: null,
    parentCampaignId: null,
};

/** The Campaigns list surface: a permission-aware roster with an inline create flow. */
export default function CampaignsBrowser({ campaigns }: { campaigns: Campaign[] }) {
    const t = useTranslations("CampaignsPage");
    const locale = useLocale();
    const router = useRouter();
    const [open, setOpen] = useState(false);
    const [payload, setPayload] = useState<CampaignPayload>(EMPTY_PAYLOAD);
    const [isCreating, setIsCreating] = useState(false);
    const [isSuccess, setIsSuccess] = useState(false);

    const openDialog = () => {
        setPayload(EMPTY_PAYLOAD);
        setIsSuccess(false);
        setOpen(true);
    };

    const createNewCampaign = async () => {
        setIsCreating(true);
        try {
            const created = await createCampaign({
                ...payload,
                objective: payload.objective?.trim() || null,
            });
            setIsSuccess(true);
            router.push(`/marketing/campaigns/${created.id}`);
        } catch (err) {
            setIsCreating(false);
            if (isFieldError(err)) throw err;
            toastError(err instanceof Error ? err.message : String(err));
            return;
        }
    };

    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-[100rem] flex-col gap-10">
                <Rise>
                    <header className="flex flex-wrap items-end justify-between gap-x-4 gap-y-3">
                        <div className="min-w-0">
                            <h1 className="text-4xl font-extrabold tracking-tight">{t("title")}</h1>
                            <p className="mt-2 max-w-2xl text-sm text-muted-foreground">{t("subtitle")}</p>
                        </div>
                        <Button variant="brand" onClick={openDialog} className="shrink-0">
                            <PlusIcon className="size-4" />
                            {t("new")}
                        </Button>
                    </header>
                </Rise>

                {campaigns.length === 0 ? (
                    <Rise delay={0.06} className="flex flex-col items-center justify-center gap-3 rounded-2xl border border-dashed border-border bg-card px-6 py-16 text-center sm:py-20">
                        <span className="flex size-12 items-center justify-center rounded-full bg-brand-light text-brand-dark">
                            <MegaphoneIcon className="size-6" />
                        </span>
                        <h2 className="text-lg font-semibold">{t("empty")}</h2>
                        <p className="max-w-sm text-sm text-muted-foreground">{t("emptyHint")}</p>
                        <Button variant="brand" onClick={openDialog} className="mt-2">
                            <PlusIcon className="size-4" />
                            {t("new")}
                        </Button>
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
                                            href={`/marketing/campaigns/${campaign.id}`}
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
            </div>

            <NewCampaignDialog
                open={open}
                onOpenChange={setOpen}
                payload={payload}
                setPayload={setPayload}
                isCreating={isCreating}
                isSuccess={isSuccess}
                createNewCampaign={createNewCampaign}
            />
        </div>
    );
}
