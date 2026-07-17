"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useLocale, useTranslations } from "next-intl";
import { ArrowLeftIcon, TrashIcon } from "@heroicons/react/24/outline";
import { Loader2Icon } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
    Select,
    SelectTrigger,
    SelectValue,
    SelectContent,
    SelectItem,
} from "@/components/ui/select";
import {
    Dialog,
    DialogContent,
    DialogHeader,
    DialogTitle,
    DialogDescription,
    DialogFooter,
    DialogClose,
} from "@/components/ui/dialog";
import Panel from "@/app/components/overview/analytics/Panel";
import SegmentBuilder, { EMPTY_DEFINITION } from "@/app/components/records/SegmentBuilder";
import CampaignStatusBadge from "@/app/components/marketing/campaigns/CampaignStatusBadge";
import AudienceEstimatePanel from "@/app/components/marketing/campaigns/AudienceEstimatePanel";
import CampaignDelivery from "@/app/components/marketing/campaigns/CampaignDelivery";
import {
    type Campaign,
    type CampaignAudience,
    type CampaignAudienceEstimate,
    type CampaignAudienceRecordType,
    type CampaignAudienceSnapshotSummary,
    type CampaignMessage,
    type CampaignSend,
    type SegmentDefinition,
    type SegmentFields,
} from "@/app/lib/types";
import {
    deleteCampaign,
    estimateCampaignAudience,
    getSegmentFields,
    setCampaignAudience,
    snapshotCampaignAudience,
} from "@/app/lib/api";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { formatCurrency, formatDate } from "@/app/lib/utils";

const RECORD_TYPES: CampaignAudienceRecordType[] = ["person", "company", "deal"];

function OverviewRow({ label, value }: { label: string; value: string }) {
    return (
        <div className="flex items-baseline justify-between gap-4 py-2.5">
            <dt className="text-sm text-muted-foreground">{label}</dt>
            <dd className="text-sm font-medium text-foreground">{value}</dd>
        </div>
    );
}

/** Campaign detail: overview plus the smart-segment audience, its estimate, and frozen snapshots. */
export default function CampaignDetail({
    campaign,
    initialAudience,
    initialSnapshots,
    initialMessages,
    initialSends,
    canManage,
    canSend,
}: {
    campaign: Campaign;
    initialAudience: CampaignAudience | null;
    initialSnapshots: CampaignAudienceSnapshotSummary[];
    initialMessages: CampaignMessage[];
    initialSends: CampaignSend[];
    canManage: boolean;
    canSend: boolean;
}) {
    const t = useTranslations("CampaignDetail");
    const at = useTranslations("CampaignAudience");
    const locale = useLocale();
    const router = useRouter();

    const [recordType, setRecordType] = useState<CampaignAudienceRecordType>(
        initialAudience?.recordType ?? "person",
    );
    const [definition, setDefinition] = useState<SegmentDefinition>(
        initialAudience?.definition ?? EMPTY_DEFINITION,
    );
    const [segmentFields, setSegmentFields] = useState<SegmentFields | null>(null);
    const [audienceSaved, setAudienceSaved] = useState(Boolean(initialAudience));
    const [estimate, setEstimate] = useState<CampaignAudienceEstimate | null>(null);
    const [snapshots, setSnapshots] = useState<CampaignAudienceSnapshotSummary[]>(initialSnapshots);
    const [isSaving, setIsSaving] = useState(false);
    const [isEstimating, setIsEstimating] = useState(false);
    const [isFreezing, setIsFreezing] = useState(false);
    const [isDeleting, setIsDeleting] = useState(false);
    const [confirmDelete, setConfirmDelete] = useState(false);

    useEffect(() => {
        let active = true;
        getSegmentFields(recordType)
            .then((fields) => {
                if (active) setSegmentFields(fields);
            })
            .catch(() => {
                if (active) setSegmentFields(null);
            });
        return () => {
            active = false;
        };
    }, [recordType]);

    const changeRecordType = (next: CampaignAudienceRecordType) => {
        setRecordType(next);
        setDefinition(EMPTY_DEFINITION);
        setAudienceSaved(false);
        setEstimate(null);
    };

    const changeDefinition = (next: SegmentDefinition) => {
        setDefinition(next);
        setAudienceSaved(false);
        setEstimate(null);
    };

    const saveAudience = async () => {
        setIsSaving(true);
        try {
            await setCampaignAudience(campaign.id, { recordType, definition });
            setAudienceSaved(true);
            toastSuccess(at("saved"));
        } catch (err) {
            toastError(err instanceof Error ? err.message : String(err));
        } finally {
            setIsSaving(false);
        }
    };

    const runEstimate = async () => {
        setIsEstimating(true);
        try {
            setEstimate(await estimateCampaignAudience(campaign.id));
        } catch (err) {
            toastError(err instanceof Error ? err.message : String(err));
        } finally {
            setIsEstimating(false);
        }
    };

    const freezeSnapshot = async () => {
        setIsFreezing(true);
        try {
            const snapshot = await snapshotCampaignAudience(campaign.id);
            setSnapshots((prev) => [snapshot, ...prev]);
            toastSuccess(at("frozen"));
        } catch (err) {
            toastError(err instanceof Error ? err.message : String(err));
        } finally {
            setIsFreezing(false);
        }
    };

    const removeCampaign = async () => {
        setIsDeleting(true);
        try {
            await deleteCampaign(campaign.id);
            router.push("/marketing/campaigns");
        } catch (err) {
            setIsDeleting(false);
            toastError(err instanceof Error ? err.message : String(err));
        }
    };

    const budget =
        campaign.budgetAmount != null && campaign.budgetCurrency
            ? formatCurrency(campaign.budgetAmount, campaign.budgetCurrency, locale)
            : t("noValue");
    const windowText =
        campaign.startAt || campaign.endAt
            ? `${formatDate(campaign.startAt ?? undefined, locale)} – ${formatDate(campaign.endAt ?? undefined, locale)}`
            : t("noValue");

    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-[100rem] flex-col gap-8">
                <div className="flex flex-col gap-4">
                    <Link
                        href="/marketing/campaigns"
                        className="inline-flex w-fit items-center gap-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground"
                    >
                        <ArrowLeftIcon className="size-4" />
                        {t("back")}
                    </Link>
                    <div className="flex flex-wrap items-center justify-between gap-4">
                        <div className="flex flex-wrap items-center gap-3">
                            <h1 className="text-3xl font-extrabold tracking-tight">{campaign.name}</h1>
                            <CampaignStatusBadge status={campaign.status} />
                        </div>
                        <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => setConfirmDelete(true)}
                            className="text-muted-foreground hover:text-destructive"
                        >
                            <TrashIcon className="size-4" />
                            {t("delete")}
                        </Button>
                    </div>
                </div>

                <div className="grid grid-cols-1 gap-6 lg:grid-cols-[20rem_1fr]">
                    <Panel title={t("overview")} className="h-fit">
                        <dl className="divide-y divide-border">
                            <OverviewRow label={t("objective")} value={campaign.objective ?? t("noValue")} />
                            <OverviewRow label={t("type")} value={campaign.type} />
                            <OverviewRow label={t("budget")} value={budget} />
                            <OverviewRow label={t("window")} value={windowText} />
                            <OverviewRow
                                label={t("created")}
                                value={formatDate(campaign.createdAt, locale)}
                            />
                            <OverviewRow
                                label={t("updated")}
                                value={formatDate(campaign.updatedAt, locale)}
                            />
                        </dl>
                    </Panel>

                    <div className="flex flex-col gap-6">
                        <Panel
                            title={at("title")}
                            subtitle={at("subtitle")}
                            action={
                                <div className="flex items-center gap-2">
                                    <Select
                                        value={recordType}
                                        onValueChange={(value) =>
                                            changeRecordType(value as CampaignAudienceRecordType)
                                        }
                                    >
                                        <SelectTrigger size="sm" className="w-36">
                                            <SelectValue />
                                        </SelectTrigger>
                                        <SelectContent>
                                            {RECORD_TYPES.map((value) => (
                                                <SelectItem key={value} value={value}>
                                                    {at(value)}
                                                </SelectItem>
                                            ))}
                                        </SelectContent>
                                    </Select>
                                </div>
                            }
                        >
                            <div className="flex flex-col gap-4">
                                <div className="flex flex-wrap items-center gap-2">
                                    <SegmentBuilder
                                        definition={definition}
                                        fields={segmentFields}
                                        onChange={changeDefinition}
                                        recordType={recordType}
                                        options={null}
                                        advanced
                                    />
                                    <Button
                                        variant="outline"
                                        size="sm"
                                        onClick={saveAudience}
                                        disabled={isSaving || audienceSaved}
                                    >
                                        {isSaving ? (
                                            <>
                                                <Loader2Icon className="size-4 animate-spin" />
                                                {at("saving")}
                                            </>
                                        ) : (
                                            at("saveAudience")
                                        )}
                                    </Button>
                                    <Button
                                        variant="brand"
                                        size="sm"
                                        onClick={runEstimate}
                                        disabled={!audienceSaved || isEstimating}
                                    >
                                        {isEstimating ? (
                                            <>
                                                <Loader2Icon className="size-4 animate-spin" />
                                                {at("estimating")}
                                            </>
                                        ) : (
                                            at("estimate")
                                        )}
                                    </Button>
                                </div>

                                {estimate ? (
                                    <div className="flex flex-col gap-4 border-t border-border pt-4">
                                        <div>
                                            <h3 className="text-sm font-semibold text-foreground">
                                                {at("estimateTitle")}
                                            </h3>
                                            <p className="text-xs text-muted-foreground">
                                                {at("estimateSubtitle")}
                                            </p>
                                        </div>
                                        <AudienceEstimatePanel estimate={estimate} recordType={recordType} />
                                        <div>
                                            <Button
                                                variant="outline"
                                                size="sm"
                                                onClick={freezeSnapshot}
                                                disabled={isFreezing}
                                            >
                                                {isFreezing ? (
                                                    <>
                                                        <Loader2Icon className="size-4 animate-spin" />
                                                        {at("freezing")}
                                                    </>
                                                ) : (
                                                    at("freeze")
                                                )}
                                            </Button>
                                        </div>
                                    </div>
                                ) : (
                                    <div className="rounded-xl border border-dashed border-border px-4 py-8 text-center">
                                        <p className="text-sm font-medium text-foreground">
                                            {at("noAudience")}
                                        </p>
                                        <p className="mt-1 text-xs text-muted-foreground">
                                            {at("noAudienceHint")}
                                        </p>
                                    </div>
                                )}
                            </div>
                        </Panel>

                        <Panel title={at("snapshots")} subtitle={at("snapshotsSubtitle")}>
                            {snapshots.length === 0 ? (
                                <p className="py-4 text-sm text-muted-foreground">{at("noSnapshots")}</p>
                            ) : (
                                <ul className="divide-y divide-border">
                                    {snapshots.map((snapshot) => (
                                        <li
                                            key={snapshot.version}
                                            className="flex flex-wrap items-center justify-between gap-3 py-3"
                                        >
                                            <div className="flex items-center gap-3">
                                                <span className="inline-flex items-center rounded-full bg-muted px-2 py-0.5 font-mono text-xs text-foreground ring-1 ring-inset ring-border">
                                                    {at("version", { version: snapshot.version })}
                                                </span>
                                                <span className="text-sm text-muted-foreground">
                                                    {formatDate(snapshot.createdAt, locale)}
                                                </span>
                                            </div>
                                            <div className="flex items-center gap-3 tabular-nums text-sm">
                                                <span className="font-medium text-brand-hover">
                                                    {at("snapshotIncluded", {
                                                        count: snapshot.estimatedIncluded.toLocaleString(locale),
                                                    })}
                                                </span>
                                                <span className="text-muted-foreground">
                                                    {at("snapshotExcluded", {
                                                        count: snapshot.excludedTotal.toLocaleString(locale),
                                                    })}
                                                </span>
                                            </div>
                                        </li>
                                    ))}
                                </ul>
                            )}
                        </Panel>
                    </div>
                </div>

                <CampaignDelivery
                    campaignId={campaign.id}
                    initialMessages={initialMessages}
                    initialSends={initialSends}
                    snapshots={snapshots}
                    canManage={canManage}
                    canSend={canSend}
                />
            </div>

            <Dialog open={confirmDelete} onOpenChange={setConfirmDelete}>
                <DialogContent className="sm:max-w-md">
                    <DialogHeader>
                        <DialogTitle>{t("deleteTitle")}</DialogTitle>
                        <DialogDescription>{t("deleteBody")}</DialogDescription>
                    </DialogHeader>
                    <DialogFooter>
                        <DialogClose asChild>
                            <Button type="button" variant="outline" disabled={isDeleting}>
                                {t("cancel")}
                            </Button>
                        </DialogClose>
                        <Button
                            type="button"
                            variant="destructive"
                            onClick={removeCampaign}
                            disabled={isDeleting}
                        >
                            {isDeleting ? (
                                <>
                                    <Loader2Icon className="size-4 animate-spin" />
                                    {t("delete")}
                                </>
                            ) : (
                                t("delete")
                            )}
                        </Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>
        </div>
    );
}
