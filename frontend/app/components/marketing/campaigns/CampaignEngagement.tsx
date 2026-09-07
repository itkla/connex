"use client";

import { useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import CountUp from "@/app/components/dashboard/CountUp";
import Panel from "@/app/components/overview/analytics/Panel";
import CampaignCounter from "@/app/components/marketing/campaigns/CampaignCounter";
import CampaignRecipientsDialog from "@/app/components/marketing/campaigns/CampaignRecipientsDialog";
import SendStatusBadge from "@/app/components/marketing/campaigns/SendStatusBadge";
import { type EngagementCounter } from "@/app/components/marketing/campaigns/recipientFilters";
import {
    type CampaignEngagement as CampaignEngagementData,
    type CampaignSendEngagement,
    type CampaignSendStatus,
} from "@/app/lib/types";

const SEND_STATUSES: readonly CampaignSendStatus[] = [
    "draft",
    "queued",
    "running",
    "paused",
    "completed",
    "failed",
    "cancelled",
    "triggered",
];

const SKIP_REASON_KEYS = new Set([
    "consent_revoked",
    "suppressed",
    "restricted",
    "frequency_capped",
    "quiet_hours",
    "no_address",
    "consent_missing",
]);

function isSendStatus(status: string): status is CampaignSendStatus {
    return (SEND_STATUSES as readonly string[]).includes(status);
}

type CountTile = {
    key: EngagementCounter;
    label: string;
    value: number;
    unavailable: boolean;
};

type RateRow = {
    key: string;
    label: string;
    rate: number | null;
    receiptsAvailable: boolean;
};

function CountTiles({
    tiles,
    notMeasuredHint,
    drillLabel,
    onDrill,
}: {
    tiles: CountTile[];
    notMeasuredHint: string;
    drillLabel: (tile: CountTile) => string;
    onDrill: ((tile: CountTile) => void) | null;
}) {
    return (
        <div className="grid grid-cols-2 gap-px overflow-hidden rounded-2xl bg-border ring-1 ring-border sm:grid-cols-4 lg:grid-cols-7">
            {tiles.map((tile) => {
                const body = (
                    <>
                        <span className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                            {tile.label}
                        </span>
                        {tile.unavailable ? (
                            <span aria-hidden className="text-2xl leading-none text-muted-foreground tabular-nums">
                                —
                            </span>
                        ) : (
                            <CountUp value={tile.value} className="text-2xl leading-none text-foreground tabular-nums" />
                        )}
                        {tile.unavailable && (
                            <span className="text-xs text-muted-foreground">{notMeasuredHint}</span>
                        )}
                    </>
                );
                const drillable = onDrill !== null && !tile.unavailable && tile.value > 0;
                if (!drillable) {
                    return (
                        <div key={tile.key} className="flex flex-col gap-1.5 bg-card p-4 sm:p-5">
                            {body}
                        </div>
                    );
                }
                return (
                    <button
                        key={tile.key}
                        type="button"
                        aria-label={drillLabel(tile)}
                        onClick={() => onDrill(tile)}
                        className="flex flex-col gap-1.5 bg-card p-4 text-left outline-none transition-colors hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50 sm:p-5"
                    >
                        {body}
                    </button>
                );
            })}
        </div>
    );
}

function RateTiles({
    rates,
    notMeasured,
    notMeasuredHint,
    locale,
}: {
    rates: RateRow[];
    notMeasured: string;
    notMeasuredHint: string;
    locale: string;
}) {
    const percent = new Intl.NumberFormat(locale, {
        style: "percent",
        maximumFractionDigits: 1,
    });
    return (
        <div className="grid grid-cols-1 gap-px overflow-hidden rounded-2xl bg-border ring-1 ring-border sm:grid-cols-3">
            {rates.map((row) => {
                const measured = row.receiptsAvailable && row.rate != null;
                return (
                    <div key={row.key} className="flex flex-col gap-1.5 bg-card p-5">
                        <span className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                            {row.label}
                        </span>
                        {measured ? (
                            <span className="text-2xl leading-none text-foreground tabular-nums">
                                {percent.format(row.rate as number)}
                            </span>
                        ) : (
                            <>
                                <span className="text-base font-medium leading-none text-muted-foreground">
                                    {notMeasured}
                                </span>
                                <span className="text-xs text-muted-foreground">{notMeasuredHint}</span>
                            </>
                        )}
                    </div>
                );
            })}
        </div>
    );
}

/**
 * Read-only engagement rollup for a campaign: campaign-level delivery tiles, delivery/bounce/complaint
 * rates, a channel and skip-reason breakdown, and a per-send counter list. Receipt-derived figures
 * (delivered, bounced, complained, unsubscribed) and every rate render "Not measured" — never a
 * misleading zero — when delivery receipts are unavailable or the rate is {@code null}.
 *
 * A measured, non-zero tile opens the contacts it counted, but only for a reader with consent
 * access: the roster behind a count is per-contact marketing data the server guards separately, so
 * a reader without it is shown the plain counts rather than an affordance that would be refused.
 *
 * @param campaignId - the campaign the counts belong to
 * @param canReadRecipients - whether the reader may open the contacts behind a count
 * @param canReconcileRecipients - whether the reader may confirm ambiguous provider outcomes
 */
export default function CampaignEngagement({
    campaignId,
    engagement,
    canReadRecipients,
    canReconcileRecipients,
}: {
    campaignId: number;
    engagement: CampaignEngagementData | null;
    canReadRecipients: boolean;
    canReconcileRecipients: boolean;
}) {
    const t = useTranslations("CampaignEngagement");
    const locale = useLocale();
    const [openCounter, setOpenCounter] = useState<CountTile | null>(null);

    const channelLabel = (channel: string) => {
        if (channel === "email") return t("channels.email");
        if (channel === "sms") return t("channels.sms");
        return channel;
    };

    const skipReasonLabel = (reason: string) =>
        SKIP_REASON_KEYS.has(reason) ? t(`skipReasons.${reason}`) : reason;

    if (!engagement || engagement.sends.length === 0) {
        return (
            <Panel title={t("title")} subtitle={t("subtitle")}>
                <div className="rounded-xl border border-dashed border-border px-4 py-8 text-center">
                    <p className="text-sm font-medium text-foreground">{t("empty")}</p>
                    <p className="mt-1 text-xs text-muted-foreground">{t("emptyHint")}</p>
                </div>
            </Panel>
        );
    }

    const receipts = engagement.deliveryReceiptsAvailable;
    const tiles: CountTile[] = [
        { key: "recipients", label: t("recipients"), value: engagement.totalRecipients, unavailable: false },
        { key: "dispatched", label: t("dispatched"), value: engagement.dispatched, unavailable: false },
        { key: "delivered", label: t("delivered"), value: engagement.delivered, unavailable: !receipts },
        { key: "bounced", label: t("bounced"), value: engagement.bounced, unavailable: !receipts },
        { key: "complained", label: t("complained"), value: engagement.complained, unavailable: !receipts },
        {
            key: "unsubscribed",
            label: t("unsubscribed"),
            value: engagement.unsubscribed,
            unavailable: !receipts,
        },
        { key: "skipped", label: t("skipped"), value: engagement.skipped, unavailable: false },
    ];

    const rates: RateRow[] = [
        {
            key: "deliveryRate",
            label: t("deliveryRate"),
            rate: engagement.deliveryRate,
            receiptsAvailable: receipts,
        },
        {
            key: "bounceRate",
            label: t("bounceRate"),
            rate: engagement.bounceRate,
            receiptsAvailable: receipts,
        },
        {
            key: "complaintRate",
            label: t("complaintRate"),
            rate: engagement.complaintRate,
            receiptsAvailable: receipts,
        },
    ];

    const skipEntries = Object.entries(engagement.skipReasons).filter(([, count]) => count > 0);

    return (
        <Panel title={t("title")} subtitle={t("subtitle")}>
            <div className="flex flex-col gap-6">
                <CountTiles
                    tiles={tiles}
                    notMeasuredHint={t("notMeasuredHint")}
                    drillLabel={(tile) =>
                        t("drillThrough", { counter: tile.label, count: tile.value.toLocaleString(locale) })
                    }
                    onDrill={canReadRecipients ? setOpenCounter : null}
                />

                <RateTiles
                    rates={rates}
                    notMeasured={t("notMeasured")}
                    notMeasuredHint={t("notMeasuredHint")}
                    locale={locale}
                />

                {engagement.channels.length > 0 && (
                    <div className="flex flex-col gap-2">
                        <h3 className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                            {t("channelsTitle")}
                        </h3>
                        <div className="flex flex-wrap items-center gap-2">
                            {engagement.channels.map((channel) => (
                                <span
                                    key={channel.channel}
                                    className="inline-flex items-center gap-1.5 rounded-full bg-muted px-2.5 py-0.5 text-xs text-foreground ring-1 ring-inset ring-border"
                                >
                                    <span className="font-medium">{channelLabel(channel.channel)}</span>
                                    <span className="tabular-nums text-muted-foreground">
                                        {t("deliveries", {
                                            count: channel.deliveries.toLocaleString(locale),
                                        })}
                                    </span>
                                </span>
                            ))}
                        </div>
                    </div>
                )}

                {skipEntries.length > 0 && (
                    <div className="flex flex-col gap-2">
                        <h3 className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                            {t("skipTitle")}
                        </h3>
                        <ul className="flex flex-wrap items-center gap-2">
                            {skipEntries.map(([reason, count]) => (
                                <li
                                    key={reason}
                                    className="inline-flex items-center gap-1.5 rounded-full bg-muted px-2.5 py-0.5 text-xs text-foreground ring-1 ring-inset ring-border"
                                >
                                    <span>{skipReasonLabel(reason)}</span>
                                    <span className="tabular-nums text-muted-foreground">
                                        {count.toLocaleString(locale)}
                                    </span>
                                </li>
                            ))}
                        </ul>
                    </div>
                )}

                <div className="flex flex-col gap-2">
                    <h3 className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                        {t("sendsTitle")}
                    </h3>
                    <ul className="divide-y divide-border">
                        {engagement.sends.map((send: CampaignSendEngagement) => {
                            const sendReceipts = send.deliveryReceiptsAvailable;
                            return (
                                <li key={send.sendId} className="flex flex-col gap-3 py-4">
                                    <div className="flex flex-wrap items-center gap-3">
                                        {isSendStatus(send.status) ? (
                                            <SendStatusBadge status={send.status} />
                                        ) : (
                                            <span className="inline-flex items-center rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground ring-1 ring-inset ring-border">
                                                {send.status}
                                            </span>
                                        )}
                                        <span className="text-sm font-medium text-foreground">
                                            {channelLabel(send.channel)}
                                        </span>
                                        <span className="text-xs text-muted-foreground">
                                            {t("sendLabel", { id: send.sendId })}
                                        </span>
                                    </div>
                                    <div className="grid grid-cols-3 gap-3 sm:grid-cols-6 sm:gap-4">
                                        <CampaignCounter
                                            label={t("recipients")}
                                            value={send.totalRecipients.toLocaleString(locale)}
                                        />
                                        <CampaignCounter
                                            label={t("dispatched")}
                                            value={send.dispatched.toLocaleString(locale)}
                                        />
                                        <CampaignCounter
                                            label={t("delivered")}
                                            value={sendReceipts ? send.delivered.toLocaleString(locale) : "—"}
                                        />
                                        <CampaignCounter
                                            label={t("bounced")}
                                            value={sendReceipts ? send.bounced.toLocaleString(locale) : "—"}
                                        />
                                        <CampaignCounter
                                            label={t("skipped")}
                                            value={send.skipped.toLocaleString(locale)}
                                        />
                                        <CampaignCounter
                                            label={t("failed")}
                                            value={send.failed.toLocaleString(locale)}
                                        />
                                    </div>
                                </li>
                            );
                        })}
                    </ul>
                </div>
            </div>

            {openCounter ? (
                <CampaignRecipientsDialog
                    key={openCounter.key}
                    campaignId={campaignId}
                    counter={openCounter.key}
                    counterLabel={openCounter.label}
                    open
                    canReconcile={canReconcileRecipients}
                    onOpenChange={(next) => {
                        if (!next) setOpenCounter(null);
                    }}
                />
            ) : null}
        </Panel>
    );
}
