"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { useLocale, useTranslations } from "next-intl";
import { MegaphoneIcon, PlusIcon } from "@heroicons/react/24/outline";
import { Button } from "@/components/ui/button";
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
            <div className="mx-auto flex w-full max-w-[100rem] flex-col gap-8">
                <header className="flex flex-wrap items-end justify-between gap-4">
                    <div className="min-w-0">
                        <h1 className="text-4xl font-extrabold tracking-tight">{t("title")}</h1>
                        <p className="mt-2 max-w-2xl text-sm text-muted-foreground">{t("subtitle")}</p>
                    </div>
                    <Button variant="brand" onClick={openDialog} className="shrink-0">
                        <PlusIcon className="size-4" />
                        {t("new")}
                    </Button>
                </header>

                {campaigns.length === 0 ? (
                    <div className="flex flex-col items-center justify-center gap-3 rounded-2xl border border-dashed border-border bg-card px-6 py-20 text-center">
                        <span className="flex size-12 items-center justify-center rounded-full bg-brand-light text-brand-dark">
                            <MegaphoneIcon className="size-6" />
                        </span>
                        <h2 className="text-lg font-semibold">{t("empty")}</h2>
                        <p className="max-w-sm text-sm text-muted-foreground">{t("emptyHint")}</p>
                        <Button variant="brand" onClick={openDialog} className="mt-2">
                            <PlusIcon className="size-4" />
                            {t("new")}
                        </Button>
                    </div>
                ) : (
                    <div className="overflow-hidden rounded-2xl border border-border bg-card">
                        <table className="w-full text-sm">
                            <thead>
                                <tr className="border-b border-border text-left text-xs font-medium uppercase tracking-[0.08em] text-muted-foreground">
                                    <th className="px-5 py-3 font-medium">{t("colName")}</th>
                                    <th className="px-5 py-3 font-medium">{t("colStatus")}</th>
                                    <th className="px-5 py-3 font-medium">{t("colType")}</th>
                                    <th className="px-5 py-3 text-right font-medium">{t("colBudget")}</th>
                                    <th className="hidden px-5 py-3 font-medium md:table-cell">{t("colWindow")}</th>
                                </tr>
                            </thead>
                            <tbody>
                                {campaigns.map((campaign) => (
                                    <tr
                                        key={campaign.id}
                                        className="group border-b border-border/60 transition-colors last:border-0 hover:bg-muted/50"
                                    >
                                        <td className="px-5 py-3">
                                            <Link
                                                href={`/marketing/campaigns/${campaign.id}`}
                                                className="font-medium text-foreground underline-offset-2 outline-none focus-visible:underline group-hover:text-brand-hover"
                                            >
                                                {campaign.name}
                                            </Link>
                                            {campaign.objective && (
                                                <p className="truncate text-xs text-muted-foreground">
                                                    {campaign.objective}
                                                </p>
                                            )}
                                        </td>
                                        <td className="px-5 py-3">
                                            <CampaignStatusBadge status={campaign.status} />
                                        </td>
                                        <td className="px-5 py-3 text-muted-foreground">{campaign.type}</td>
                                        <td className="px-5 py-3 text-right tabular-nums text-muted-foreground">
                                            {campaign.budgetAmount != null && campaign.budgetCurrency
                                                ? formatCurrency(
                                                      campaign.budgetAmount,
                                                      campaign.budgetCurrency,
                                                      locale,
                                                  )
                                                : t("noBudget")}
                                        </td>
                                        <td className="hidden px-5 py-3 text-muted-foreground md:table-cell">
                                            {campaign.startAt || campaign.endAt
                                                ? `${formatShortDate(campaign.startAt ?? undefined, locale)} – ${formatShortDate(campaign.endAt ?? undefined, locale)}`
                                                : t("noBudget")}
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
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
