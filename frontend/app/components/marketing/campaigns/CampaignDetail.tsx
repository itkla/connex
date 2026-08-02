"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useLocale, useTranslations } from "next-intl";
import { ArrowLeftIcon, PencilSquareIcon, TrashIcon } from "@heroicons/react/24/outline";
import { Loader2Icon } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
    Select,
    SelectTrigger,
    SelectValue,
    SelectContent,
    SelectItem,
} from "@/components/ui/select";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
    ResponsiveDialog,
    ResponsiveDialogContent,
    ResponsiveDialogHeader,
    ResponsiveDialogTitle,
    ResponsiveDialogDescription,
    ResponsiveDialogFooter,
    ResponsiveDialogClose,
} from "@/components/ui/responsive-dialog";
import Panel from "@/app/components/overview/analytics/Panel";
import Rise from "@/app/components/motion/Rise";
import InfoRow from "@/app/components/me/InfoRow";
import SectionHeader from "@/app/components/dashboard/SectionHeader";
import SegmentBuilder, { EMPTY_DEFINITION } from "@/app/components/records/SegmentBuilder";
import CampaignStatusBadge from "@/app/components/marketing/campaigns/CampaignStatusBadge";
import AudienceEstimatePanel from "@/app/components/marketing/campaigns/AudienceEstimatePanel";
import CampaignDelivery from "@/app/components/marketing/campaigns/CampaignDelivery";
import CampaignEngagement from "@/app/components/marketing/campaigns/CampaignEngagement";
import CampaignExportPanel from "@/app/components/marketing/campaigns/CampaignExportPanel";
import CampaignFormDialog from "@/app/components/marketing/campaigns/CampaignFormDialog";
import { PageShell } from "@/app/components/PageShell";
import {
    type Campaign,
    type CampaignAudience,
    type CampaignAudienceEstimate,
    type CampaignAudienceExport,
    type CampaignAudienceRecordType,
    type CampaignAudienceSnapshotSummary,
    type CampaignEngagement as CampaignEngagementData,
    type CampaignMessage,
    type CampaignPayload,
    type CampaignSend,
    type SegmentDefinition,
    type SegmentFields,
} from "@/app/lib/types";
import {
    deleteCampaign,
    estimateCampaignAudience,
    getSegmentFields,
    isFieldError,
    setCampaignAudience,
    snapshotCampaignAudience,
    updateCampaign,
} from "@/app/lib/api";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { formatCurrency, formatDate } from "@/app/lib/utils";

const RECORD_TYPES: CampaignAudienceRecordType[] = ["person", "company", "deal"];

/**
 * Seeds the edit form from a campaign. `PUT /api/campaigns/{id}` replaces the whole record, so the
 * fields the form does not show — owner and parent campaign — are carried through rather than
 * dropped, and the timestamps are trimmed to the minute a `datetime-local` input accepts.
 */
function toPayload(campaign: Campaign): CampaignPayload {
    return {
        name: campaign.name,
        objective: campaign.objective,
        type: campaign.type,
        status: campaign.status,
        ownerUserId: campaign.ownerUserId,
        budgetAmount: campaign.budgetAmount,
        budgetCurrency: campaign.budgetCurrency,
        startAt: campaign.startAt?.slice(0, 16) ?? null,
        endAt: campaign.endAt?.slice(0, 16) ?? null,
        parentCampaignId: campaign.parentCampaignId,
    };
}

function GlanceTile({ label, value }: { label: string; value: string }) {
    return (
        <div className="flex flex-col gap-1.5 bg-card p-4 sm:p-5">
            <span className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                {label}
            </span>
            <span className="text-2xl leading-none tabular-nums text-foreground">{value}</span>
        </div>
    );
}

/** Campaign detail: a tabbed workspace over overview, audience, delivery, engagement, and exports. */
export default function CampaignDetail({
    campaign,
    initialAudience,
    initialSnapshots,
    initialMessages,
    initialSends,
    initialExports,
    initialEngagement,
    canManage,
    canSend,
    deliveryEnabled,
}: {
    campaign: Campaign;
    initialAudience: CampaignAudience | null;
    initialSnapshots: CampaignAudienceSnapshotSummary[];
    initialMessages: CampaignMessage[];
    initialSends: CampaignSend[];
    initialExports: CampaignAudienceExport[];
    initialEngagement: CampaignEngagementData | null;
    canManage: boolean;
    canSend: boolean;
    deliveryEnabled: boolean;
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
    const [tab, setTab] = useState("overview");
    const [current, setCurrent] = useState<Campaign>(campaign);
    const [editOpen, setEditOpen] = useState(false);
    const [editPayload, setEditPayload] = useState<CampaignPayload>(() => toPayload(campaign));
    const [isSavingCampaign, setIsSavingCampaign] = useState(false);

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
            await setCampaignAudience(current.id, { recordType, definition });
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
            setEstimate(await estimateCampaignAudience(current.id));
        } catch (err) {
            toastError(err instanceof Error ? err.message : String(err));
        } finally {
            setIsEstimating(false);
        }
    };

    const freezeSnapshot = async () => {
        setIsFreezing(true);
        try {
            const snapshot = await snapshotCampaignAudience(current.id);
            setSnapshots((prev) => [snapshot, ...prev]);
            toastSuccess(at("frozen"));
        } catch (err) {
            toastError(err instanceof Error ? err.message : String(err));
        } finally {
            setIsFreezing(false);
        }
    };

    const openEdit = () => {
        setEditPayload(toPayload(current));
        setEditOpen(true);
    };

    const saveCampaign = async () => {
        setIsSavingCampaign(true);
        try {
            const updated = await updateCampaign(current.id, {
                ...editPayload,
                objective: editPayload.objective?.trim() || null,
            });
            setCurrent(updated);
            setIsSavingCampaign(false);
            setEditOpen(false);
            toastSuccess(t("saved"));
            router.refresh();
        } catch (err) {
            setIsSavingCampaign(false);
            if (isFieldError(err)) throw err;
            toastError(err instanceof Error ? err.message : String(err));
        }
    };

    const removeCampaign = async () => {
        setIsDeleting(true);
        try {
            await deleteCampaign(current.id);
            router.push("/marketing/campaigns");
        } catch (err) {
            setIsDeleting(false);
            toastError(err instanceof Error ? err.message : String(err));
        }
    };

    const budget =
        current.budgetAmount != null && current.budgetCurrency
            ? formatCurrency(current.budgetAmount, current.budgetCurrency, locale)
            : t("noValue");
    const windowText =
        current.startAt || current.endAt
            ? `${formatDate(current.startAt ?? undefined, locale)} – ${formatDate(current.endAt ?? undefined, locale)}`
            : t("noValue");
    const latestSnapshot = snapshots[0] ?? null;

    return (
        <>
            <PageShell tier="reading">
                <Rise className="flex flex-col gap-4">
                    <Link
                        href="/marketing/campaigns"
                        className="inline-flex w-fit items-center gap-2 text-base text-brand transition-colors hover:text-brand-hover"
                    >
                        <ArrowLeftIcon className="h-4 w-4" />
                        {t("back")}
                    </Link>
                    <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-3">
                        <div className="flex min-w-0 flex-wrap items-center gap-3">
                            <h1 className="text-4xl font-extrabold tracking-tight text-foreground">
                                {current.name}
                            </h1>
                            <CampaignStatusBadge status={current.status} />
                        </div>
                        {canManage && (
                            <div className="flex shrink-0 items-center gap-1">
                                <Button variant="outline" size="sm" onClick={openEdit}>
                                    <PencilSquareIcon className="size-4" />
                                    {t("edit")}
                                </Button>
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
                        )}
                    </div>
                </Rise>

                <Rise delay={0.06}>
                    <Tabs value={tab} onValueChange={setTab} className="gap-6">
                    <div className="-mx-2 overflow-x-auto px-2 pb-px">
                        <TabsList className="w-max">
                            <TabsTrigger value="overview">{t("tabOverview")}</TabsTrigger>
                            <TabsTrigger value="audience">{t("tabAudience")}</TabsTrigger>
                            <TabsTrigger value="delivery">{t("tabDelivery")}</TabsTrigger>
                            <TabsTrigger value="engagement">{t("tabEngagement")}</TabsTrigger>
                            <TabsTrigger value="exports">{t("tabExports")}</TabsTrigger>
                        </TabsList>
                    </div>

                    <TabsContent value="overview" forceMount className="data-[state=inactive]:hidden">
                        <div className="flex flex-col gap-8">
                            <div>
                                <SectionHeader title={t("detailsTitle")} />
                                <dl className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                                    <InfoRow
                                        label={t("objective")}
                                        value={current.objective ?? t("noValue")}
                                    />
                                    <InfoRow label={t("type")} value={current.type} />
                                    <InfoRow label={t("budget")} value={budget} />
                                    <InfoRow label={t("window")} value={windowText} />
                                    <InfoRow
                                        label={t("created")}
                                        value={formatDate(current.createdAt, locale)}
                                    />
                                    <InfoRow
                                        label={t("updated")}
                                        value={formatDate(current.updatedAt, locale)}
                                    />
                                </dl>
                            </div>
                            <div>
                                <SectionHeader title={t("glanceTitle")} />
                                <div className="grid grid-cols-2 gap-px overflow-hidden rounded-2xl bg-border ring-1 ring-border sm:grid-cols-4">
                                    <GlanceTile label={t("audienceType")} value={at(recordType)} />
                                    <GlanceTile
                                        label={t("snapshotCount")}
                                        value={snapshots.length.toLocaleString(locale)}
                                    />
                                    <GlanceTile
                                        label={t("latestSnapshot")}
                                        value={
                                            latestSnapshot
                                                ? latestSnapshot.estimatedIncluded.toLocaleString(locale)
                                                : t("noValue")
                                        }
                                    />
                                    <GlanceTile
                                        label={t("recipientsReached")}
                                        value={
                                            initialEngagement
                                                ? initialEngagement.totalRecipients.toLocaleString(locale)
                                                : t("noValue")
                                        }
                                    />
                                </div>
                            </div>
                        </div>
                    </TabsContent>

                    <TabsContent value="audience" forceMount className="data-[state=inactive]:hidden">
                        <div className="flex flex-col gap-6">
                            <Panel
                                title={at("title")}
                                subtitle={at("subtitle")}
                                action={
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
                                }
                            >
                                <div className="flex flex-col gap-4">
                                    <div className="flex flex-wrap items-center gap-2">
                                        {canManage && (
                                            <>
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
                                            </>
                                        )}
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
                                            <AudienceEstimatePanel
                                                estimate={estimate}
                                                recordType={recordType}
                                            />
                                            {canManage && (
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
                                            )}
                                        </div>
                                    ) : (
                                        <div className="rounded-xl border border-dashed border-border px-4 py-8 text-center">
                                            <p className="text-sm font-medium text-foreground">
                                                {at("noAudience")}
                                            </p>
                                            <p className="mt-1 text-xs text-muted-foreground">
                                                {canManage
                                                    ? at("noAudienceHint")
                                                    : at("noAudienceHintReadOnly")}
                                            </p>
                                        </div>
                                    )}
                                </div>
                            </Panel>

                            <Panel title={at("snapshots")} subtitle={at("snapshotsSubtitle")}>
                                {snapshots.length === 0 ? (
                                    <p className="py-4 text-sm text-muted-foreground">
                                        {at("noSnapshots")}
                                    </p>
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
                                                            count: snapshot.estimatedIncluded.toLocaleString(
                                                                locale,
                                                            ),
                                                        })}
                                                    </span>
                                                    <span className="text-muted-foreground">
                                                        {at("snapshotExcluded", {
                                                            count: snapshot.excludedTotal.toLocaleString(
                                                                locale,
                                                            ),
                                                        })}
                                                    </span>
                                                </div>
                                            </li>
                                        ))}
                                    </ul>
                                )}
                            </Panel>
                        </div>
                    </TabsContent>

                    <TabsContent value="delivery" forceMount className="data-[state=inactive]:hidden">
                        <CampaignDelivery
                            campaignId={current.id}
                            initialMessages={initialMessages}
                            initialSends={initialSends}
                            snapshots={snapshots}
                            canManage={canManage}
                            canSend={canSend}
                            deliveryEnabled={deliveryEnabled}
                        />
                    </TabsContent>

                    <TabsContent value="engagement" forceMount className="data-[state=inactive]:hidden">
                        <CampaignEngagement engagement={initialEngagement} />
                    </TabsContent>

                    <TabsContent value="exports" forceMount className="data-[state=inactive]:hidden">
                        <CampaignExportPanel
                            campaignId={current.id}
                            initialExports={initialExports}
                            snapshots={snapshots}
                            canManage={canManage}
                            deliveryEnabled={deliveryEnabled}
                        />
                    </TabsContent>
                </Tabs>
                </Rise>
            </PageShell>

            <CampaignFormDialog
                mode="edit"
                open={editOpen}
                onOpenChange={setEditOpen}
                payload={editPayload}
                setPayload={setEditPayload}
                isSubmitting={isSavingCampaign}
                onSubmit={saveCampaign}
            />

            <ResponsiveDialog open={confirmDelete} onOpenChange={setConfirmDelete}>
                <ResponsiveDialogContent className="p-0 sm:max-w-md">
                    <div className="flex flex-col gap-4 p-6">
                        <ResponsiveDialogHeader>
                            <ResponsiveDialogTitle className="text-lg font-semibold tracking-tight">
                                {t("deleteTitle")}
                            </ResponsiveDialogTitle>
                            <ResponsiveDialogDescription>{t("deleteBody")}</ResponsiveDialogDescription>
                        </ResponsiveDialogHeader>
                        <ResponsiveDialogFooter>
                            <ResponsiveDialogClose asChild>
                                <Button type="button" variant="outline" disabled={isDeleting}>
                                    {t("cancel")}
                                </Button>
                            </ResponsiveDialogClose>
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
                        </ResponsiveDialogFooter>
                    </div>
                </ResponsiveDialogContent>
            </ResponsiveDialog>
        </>
    );
}
