"use client";

import { useEffect, useRef, useState } from "react";
import { useTranslations } from "next-intl";

import { ApiError, getAiStandingApprovalPolicy, saveAiStandingApprovalPolicy } from "@/app/lib/api";
import type { AiStandingApprovalPolicy } from "@/app/lib/types";
import { workspacePinnedRequest } from "@/app/lib/renderedWorkspace";
import { subscribeStandingApprovalPolicy, validStandingApprovalCaps } from "@/app/lib/standingApprovalPolicy";
import { useApiErrorToast } from "@/app/hooks/useApiErrorToast";
import { toastSuccess } from "@/app/lib/toast";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";

const tools = [
    { key: "update_record_fields", label: "fields" },
    { key: "remove_tag", label: "tags" },
    { key: "set_response_due", label: "responseDue" },
    { key: "change_deal_stage", label: "dealStage" },
];

/** Organization ceilings use the existing settings grammar; never author member approvals here. */
export default function OrgStandingApprovalPolicy({ workspaceId }: { workspaceId: number }) {
    const t = useTranslations("OrgStandingApproval");
    const showError = useApiErrorToast("OrgStandingApproval");
    const [policy, setPolicy] = useState<AiStandingApprovalPolicy | null>(null);
    const [persisted, setPersisted] = useState<AiStandingApprovalPolicy | null>(null);
    const [reload, setReload] = useState(0);
    const [error, setError] = useState(false);
    const [saving, setSaving] = useState(false);
    const [stale, setStale] = useState(false);
    const dirty = useRef(false);
    const readGeneration = useRef(0);

    useEffect(() => {
        let active = true;
        const generation = ++readGeneration.current;
        getAiStandingApprovalPolicy(workspaceId, workspacePinnedRequest(workspaceId))
            .then((loaded) => {
                if (!active || generation !== readGeneration.current) return;
                if (dirty.current) {
                    setStale(true);
                    return;
                }
                setPolicy(loaded);
                setPersisted(loaded);
                setError(false);
                setStale(false);
            }).catch(() => {
                if (active && generation === readGeneration.current) setError(true);
            });
        return () => { active = false; };
    }, [workspaceId, reload]);

    useEffect(() => subscribeStandingApprovalPolicy(() => {
        if (dirty.current) setStale(true);
        else setReload((value) => value + 1);
    }), []);

    const refresh = () => {
        dirty.current = false;
        setReload((value) => value + 1);
    };

    const edit = (replacement: AiStandingApprovalPolicy) => {
        dirty.current = true;
        setPolicy(replacement);
    };

    const save = async (kill: boolean) => {
        const replacement = kill ? persisted : policy;
        if (replacement?.stateVersion == null) return;
        setSaving(true);
        try {
            const saved = await saveAiStandingApprovalPolicy(workspaceId, {
                enabled: kill ? false : replacement.enabled,
                allowedTools: replacement.allowedTools,
                maximumDailyCap: replacement.maximumDailyCap,
                maximumPerRunCap: replacement.maximumPerRunCap,
                expectedStateVersion: replacement.stateVersion,
            }, workspacePinnedRequest(workspaceId));
            readGeneration.current += 1;
            setPolicy(saved);
            setPersisted(saved);
            dirty.current = false;
            setStale(false);
            setError(false);
            toastSuccess(t(kill ? "stopped" : "saved"));
        } catch (failure) {
            if (failure instanceof ApiError && failure.status === 409) setStale(true);
            showError(failure, "saveFailed");
        } finally {
            setSaving(false);
        }
    };

    return <section className="space-y-4 rounded-2xl border border-border bg-card p-4" aria-label={t("title")}>
        <div>
            <h3 className="text-sm font-medium">{t("title")}</h3>
            <p className="text-sm text-muted-foreground">{t("description")}</p>
        </div>
        {error ? <div className="space-y-2" role="alert">
            <p className="text-sm">{t("loadFailed")}</p>
            <Button variant="outline" onClick={refresh}>{t("refresh")}</Button>
        </div> : policy == null ? <Skeleton className="h-20 w-full" /> : <>
            {!policy.authoringEnabled && <p className="text-sm text-muted-foreground">{t("unavailable")}</p>}
            {!policy.provisioned && <p role="status" className="text-sm">{t("provisioning")}</p>}
            {stale && <div className="flex flex-wrap items-center gap-3" role="status">
                <p className="text-sm">{t("changed")}</p>
                <Button variant="outline" onClick={refresh}>{t("refresh")}</Button>
            </div>}
            <fieldset disabled={saving || !policy.provisioned} className="space-y-4">
                <div className="flex items-center justify-between gap-4">
                    <Label htmlFor="standing-approval-enabled">{t("allow")}</Label>
                    <Switch id="standing-approval-enabled" checked={policy.enabled}
                        onCheckedChange={(enabled) => edit({ ...policy, enabled })} />
                </div>
                <div className="space-y-2">
                    <p className="text-sm font-medium">{t("allowedChanges")}</p>
                    {tools.map((tool) => <div key={tool.key} className="flex items-center gap-3">
                        <Checkbox id={`standing-approval-${tool.key}`} checked={policy.allowedTools.includes(tool.key)}
                            onCheckedChange={(checked) => edit({ ...policy, allowedTools: checked === true
                                ? [...policy.allowedTools, tool.key]
                                : policy.allowedTools.filter((value) => value !== tool.key) })} />
                        <Label htmlFor={`standing-approval-${tool.key}`}>{t(tool.label)}</Label>
                    </div>)}
                </div>
                <div className="grid gap-4 sm:grid-cols-2">
                    <div className="space-y-1.5">
                        <Label htmlFor="standing-approval-daily">{t("daily")}</Label>
                        <Input id="standing-approval-daily" type="number" min={1} max={10000}
                            value={policy.maximumDailyCap} onChange={(event) => edit({ ...policy,
                                maximumDailyCap: Number(event.target.value) })} />
                    </div>
                    <div className="space-y-1.5">
                        <Label htmlFor="standing-approval-run">{t("perRun")}</Label>
                        <Input id="standing-approval-run" type="number" min={1} max={policy.maximumDailyCap}
                            value={policy.maximumPerRunCap} onChange={(event) => edit({ ...policy,
                                maximumPerRunCap: Number(event.target.value) })} />
                    </div>
                </div>
                <p className="text-xs text-muted-foreground">{t("capsHint")}</p>
                <div className="flex flex-wrap gap-3">
                    <Button disabled={stale || !validStandingApprovalCaps(policy)} onClick={() => void save(false)}>
                        {t("save")}
                    </Button>
                    <Button variant="destructive" disabled={stale}
                        onClick={() => void save(true)}>{t("stop")}</Button>
                </div>
                <p className="text-xs text-muted-foreground">{t("stopHint")}</p>
            </fieldset>
        </>}
    </section>;
}
