"use client";

import { useMemo, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";
import { InformationCircleIcon } from "@heroicons/react/24/outline";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import {
    Select,
    SelectTrigger,
    SelectValue,
    SelectContent,
    SelectItem,
} from "@/components/ui/select";
import Panel from "@/app/components/overview/analytics/Panel";
import PermissionsUnavailable from "@/app/components/PermissionsUnavailable";
import WorkspaceUnavailableRetry from "@/app/components/WorkspaceUnavailableRetry";
import CampaignCounter from "@/app/components/marketing/campaigns/CampaignCounter";
import ExportStatusBadge from "@/app/components/marketing/campaigns/ExportStatusBadge";
import { ApiError, createCampaignExport } from "@/app/lib/api";
import {
    type CampaignAudienceExport,
    type CampaignAudienceSnapshotSummary,
} from "@/app/lib/types";
import { canCreateExport, type CampaignAccess } from "@/app/lib/campaignAccess";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { formatDate } from "@/app/lib/utils";
import type { CapabilityAvailability } from "@/app/lib/capabilityAvailability";

const CONNECTORS = ["http_list"];

/**
 * The audience-export surface for a campaign. Pushes a frozen snapshot to an external connector and
 * lists the resulting exports, gating the create action on the caller's resolved manage permission.
 *
 * Exporting rides the same instance delivery capability as sending, so an instance without it says
 * so before the reader fills the form rather than answering with a 403 afterwards. That statement
 * sits outside the permission gate on purpose: it is most useful to a reader who cannot act and
 * would otherwise have no way to tell a disabled instance from a missing permission.
 */
export default function CampaignExportPanel({
    campaignId,
    initialExports,
    snapshots,
    access,
    deliveryAvailability,
}: {
    campaignId: number;
    initialExports: CampaignAudienceExport[];
    snapshots: CampaignAudienceSnapshotSummary[];
    access: CampaignAccess;
    deliveryAvailability: CapabilityAvailability;
}) {
    const t = useTranslations("CampaignExports");
    const tCapability = useTranslations("CapabilityUnavailable");
    const locale = useLocale();

    const [exports, setExports] = useState<CampaignAudienceExport[]>(initialExports);
    const [exportSnapshot, setExportSnapshot] = useState<string>("");
    const [exportConnector, setExportConnector] = useState<string>("");
    const [isCreatingExport, setIsCreatingExport] = useState(false);
    const [exportRefused, setExportRefused] = useState(false);

    const exportDisabled = deliveryAvailability === "disabled" || exportRefused;
    const exportUnavailable = deliveryAvailability !== "enabled" || exportRefused;
    const canPushExport = canCreateExport(access);

    const chosenSnapshot = useMemo(
        () => snapshots.find((snapshot) => String(snapshot.version) === exportSnapshot) ?? null,
        [snapshots, exportSnapshot],
    );

    const connectorLabel = (connector: string) =>
        connector === "http_list" ? t("connectorHttpList") : connector;

    const createExport = async () => {
        const snapshotVersion = Number(exportSnapshot);
        if (!snapshotVersion || !exportConnector) return;
        setIsCreatingExport(true);
        try {
            const created = await createCampaignExport(campaignId, {
                snapshotVersion,
                connector: exportConnector,
            });
            setExports((prev) => [created, ...prev]);
            setExportSnapshot("");
            setExportConnector("");
            toastSuccess(t("created"));
        } catch (err) {
            if (err instanceof ApiError && err.status === 403) {
                setExportRefused(true);
                toastError(t("exportUnavailable"));
            } else {
                toastError(err instanceof Error ? err.message : String(err));
            }
        } finally {
            setIsCreatingExport(false);
        }
    };

    return (
        <Panel title={t("title")} subtitle={t("subtitle")}>
            <div className="flex flex-col gap-6">
                {deliveryAvailability === "unavailable" ? (
                    <PermissionsUnavailable
                        variant="inline"
                        title={tCapability("title")}
                        body={tCapability("body")}
                        action={(
                            <WorkspaceUnavailableRetry
                                label={tCapability("retry")}
                                pendingLabel={tCapability("retrying")}
                            />
                        )}
                    />
                ) : exportDisabled ? (
                    <div className="flex items-start gap-3 rounded-xl border border-dashed border-border bg-muted/40 px-4 py-3">
                        <InformationCircleIcon
                            aria-hidden
                            className="mt-0.5 size-4 shrink-0 text-muted-foreground"
                        />
                        <p className="text-sm text-muted-foreground">{t("exportUnavailable")}</p>
                    </div>
                ) : null}
                {canPushExport && (
                    <div className="flex flex-col gap-4 rounded-xl border border-border bg-card p-4">
                        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                            <div className="grid gap-1.5">
                                <Label htmlFor="export-snapshot">{t("snapshot")}</Label>
                                <Select value={exportSnapshot} onValueChange={setExportSnapshot}>
                                    <SelectTrigger id="export-snapshot" className="w-full">
                                        <SelectValue placeholder={t("snapshotPlaceholder")} />
                                    </SelectTrigger>
                                    <SelectContent>
                                        {snapshots.map((snapshot) => (
                                            <SelectItem key={snapshot.version} value={String(snapshot.version)}>
                                                {t("snapshotOption", {
                                                    version: snapshot.version,
                                                    count: snapshot.estimatedIncluded.toLocaleString(locale),
                                                })}
                                            </SelectItem>
                                        ))}
                                    </SelectContent>
                                </Select>
                            </div>
                            <div className="grid gap-1.5">
                                <Label htmlFor="export-connector">{t("connector")}</Label>
                                <Select value={exportConnector} onValueChange={setExportConnector}>
                                    <SelectTrigger id="export-connector" className="w-full">
                                        <SelectValue placeholder={t("connectorPlaceholder")} />
                                    </SelectTrigger>
                                    <SelectContent>
                                        {CONNECTORS.map((connector) => (
                                            <SelectItem key={connector} value={connector}>
                                                {connectorLabel(connector)}
                                            </SelectItem>
                                        ))}
                                    </SelectContent>
                                </Select>
                            </div>
                        </div>

                        {chosenSnapshot && (
                            <div className="flex flex-wrap items-center gap-4 rounded-lg bg-muted/60 px-4 py-3">
                                <span className="text-xs font-medium uppercase tracking-[0.1em] text-muted-foreground">
                                    {t("eligible")}
                                </span>
                                <span className="tabular-nums text-sm font-semibold text-brand-hover">
                                    {t("eligibleIncluded", {
                                        count: chosenSnapshot.estimatedIncluded.toLocaleString(locale),
                                    })}
                                </span>
                                <span className="tabular-nums text-sm text-muted-foreground">
                                    {t("eligibleExcluded", {
                                        count: chosenSnapshot.excludedTotal.toLocaleString(locale),
                                    })}
                                </span>
                            </div>
                        )}

                        <div className="flex items-center justify-between gap-3">
                            <p className="text-xs text-muted-foreground">{t("createHint")}</p>
                            <Button
                                variant="outline"
                                size="sm"
                                onClick={createExport}
                                disabled={
                                    isCreatingExport ||
                                    !exportSnapshot ||
                                    !exportConnector ||
                                    exportUnavailable
                                }
                            >
                                {isCreatingExport ? (
                                    <>
                                        <Loader2Icon className="size-4 animate-spin" />
                                        {t("creating")}
                                    </>
                                ) : (
                                    t("create")
                                )}
                            </Button>
                        </div>

                    </div>
                )}

                {exports.length === 0 ? (
                    <p className="py-4 text-sm text-muted-foreground">{t("empty")}</p>
                ) : (
                    <ul className="divide-y divide-border">
                        {exports.map((entry) => (
                            <li key={entry.id} className="flex flex-col gap-3 py-4">
                                <div className="flex flex-wrap items-center justify-between gap-3">
                                    <div className="flex min-w-0 flex-wrap items-center gap-3">
                                        <ExportStatusBadge status={entry.status} />
                                        <span className="truncate text-sm font-medium text-foreground">
                                            {connectorLabel(entry.connector)}
                                        </span>
                                        {entry.externalListId && (
                                            <span className="text-xs text-muted-foreground">
                                                {t("externalListLabel", { list: entry.externalListId })}
                                            </span>
                                        )}
                                    </div>
                                    <span className="shrink-0 text-xs text-muted-foreground">
                                        {formatDate(entry.createdAt, locale)}
                                    </span>
                                </div>

                                <div className="grid grid-cols-3 gap-3 sm:gap-4">
                                    <CampaignCounter
                                        label={t("total")}
                                        value={entry.totalMembers.toLocaleString(locale)}
                                    />
                                    <CampaignCounter
                                        label={t("pushed")}
                                        value={entry.pushedCount.toLocaleString(locale)}
                                    />
                                    <CampaignCounter
                                        label={t("failed")}
                                        value={entry.failedCount.toLocaleString(locale)}
                                    />
                                </div>
                            </li>
                        ))}
                    </ul>
                )}
            </div>
        </Panel>
    );
}
