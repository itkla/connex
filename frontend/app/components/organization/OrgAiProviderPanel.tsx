"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";

import type {
    AiProviderConfig,
    AiProviderConfigRequest,
    AiProviderKind,
    AiOrganizationBudget,
    AiWorkspaceGovernance,
} from "@/app/lib/types";
import {
    ApiError,
    getAiOrganizationBudget,
    getAiProviderConfig,
    getAiWorkspaceGovernance,
    revokeAiProviderConfig,
    saveAiProviderConfig,
    saveAiOrganizationBudget,
    saveAiWorkspaceGovernance,
} from "@/app/lib/api";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { useApiErrorToast } from "@/app/hooks/useApiErrorToast";
import { usePasskeyStepUpErrorHandler } from "@/app/hooks/usePasskeyStepUpError";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";
import Rise from "@/app/components/motion/Rise";
import {
    SettingsPanelHeading,
    type SettingsPanelPresentation,
} from "@/app/components/settings/SettingsSection";
import { NoAccessCard } from "@/app/components/organization/OrgPrimitives";

const BEDROCK_REGIONS = [
    "ap-northeast-1",
    "ap-southeast-1",
    "ap-southeast-2",
    "eu-central-1",
    "eu-west-1",
    "eu-west-3",
    "us-east-1",
    "us-west-2",
] as const;

type FormState = {
    provider: AiProviderKind;
    region: string;
    endpoint: string;
    apiVersion: string;
    deployment: string;
    projectId: string;
    allowInternalEndpoint: boolean;
    modelId: string;
    accessKeyId: string;
    secretAccessKey: string;
    sessionToken: string;
    apiKey: string;
    serviceAccountJson: string;
    noTrainingAttested: boolean;
    enabled: boolean;
};

const EMPTY: FormState = {
    provider: "bedrock",
    region: "ap-northeast-1",
    endpoint: "",
    apiVersion: "",
    deployment: "",
    projectId: "",
    allowInternalEndpoint: false,
    modelId: "",
    accessKeyId: "",
    secretAccessKey: "",
    sessionToken: "",
    apiKey: "",
    serviceAccountJson: "",
    noTrainingAttested: false,
    enabled: false,
};

function toForm(config: AiProviderConfig): FormState {
    if (config.provider == null) {
        return EMPTY;
    }
    return {
        provider: config.provider,
        region: config.region ?? "",
        endpoint: config.endpoint ?? "",
        apiVersion: config.apiVersion ?? "",
        deployment: config.deployment ?? "",
        projectId: config.projectId ?? "",
        allowInternalEndpoint: config.allowInternalEndpoint,
        modelId: config.modelId ?? "",
        accessKeyId: "",
        secretAccessKey: "",
        sessionToken: "",
        apiKey: "",
        serviceAccountJson: "",
        noTrainingAttested: config.noTrainingAttested,
        enabled: config.enabled,
    };
}

function buildRequest(state: FormState): AiProviderConfigRequest {
    const base = {
        provider: state.provider,
        modelId: state.modelId.trim(),
        noTrainingAttested: state.noTrainingAttested,
        enabled: state.enabled,
    };
    switch (state.provider) {
        case "bedrock":
            return {
                ...base,
                region: state.region,
                accessKeyId: state.accessKeyId.trim() || null,
                secretAccessKey: state.secretAccessKey ? state.secretAccessKey : null,
                sessionToken: state.sessionToken.trim() || null,
            };
        case "azure_openai":
            return {
                ...base,
                endpoint: state.endpoint.trim(),
                deployment: state.deployment.trim(),
                apiVersion: state.apiVersion.trim(),
                apiKey: state.apiKey ? state.apiKey : null,
            };
        case "vertex":
            return {
                ...base,
                projectId: state.projectId.trim(),
                region: state.region.trim(),
                serviceAccountJson: state.serviceAccountJson.trim() || null,
            };
        case "openai_compatible":
            return {
                ...base,
                endpoint: state.endpoint.trim(),
                allowInternalEndpoint: state.allowInternalEndpoint,
                apiKey: state.apiKey ? state.apiKey : null,
            };
    }
}

/**
 * The organization's AI provider, the workspace controls over it, and its daily limit.
 *
 * @param presentation - which of the panel's two homes is rendering it; defaults to its own route
 */
export default function OrgAiProviderPanel({
    presentation = "page",
}: {
    presentation?: SettingsPanelPresentation;
} = {}) {
    const t = useTranslations("OrgAi");
    const showApiError = useApiErrorToast("OrgAi");
    const handlePasskeyStepUpError = usePasskeyStepUpErrorHandler();
    const { activeWorkspaceId } = useWorkspace();

    const [form, setForm] = useState<FormState | null>(null);
    const [governance, setGovernance] = useState<AiWorkspaceGovernance | null>(null);
    const [budget, setBudget] = useState<AiOrganizationBudget | null>(null);
    const [storedProvider, setStoredProvider] = useState<AiProviderKind | null>(null);
    const [hasCredential, setHasCredential] = useState(false);
    const [credentialLast4, setCredentialLast4] = useState<string | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(false);
    const [accessDenied, setAccessDenied] = useState(false);
    const [saving, setSaving] = useState(false);
    const [savingGovernance, setSavingGovernance] = useState(false);
    const [savingBudget, setSavingBudget] = useState(false);
    const [reloadKey, setReloadKey] = useState(0);

    useEffect(() => {
        if (activeWorkspaceId == null) return;
        let cancelled = false;
        (async () => {
            setLoading(true);
            setError(false);
            setAccessDenied(false);
            try {
                const [config, loadedGovernance, loadedBudget] = await Promise.all([
                    getAiProviderConfig(activeWorkspaceId),
                    getAiWorkspaceGovernance(activeWorkspaceId),
                    getAiOrganizationBudget(activeWorkspaceId),
                ]);
                if (cancelled) return;
                setForm(toForm(config));
                setGovernance(loadedGovernance);
                setBudget(loadedBudget);
                setStoredProvider(config.provider);
                setHasCredential(config.hasCredential);
                setCredentialLast4(config.credentialLast4);
            } catch (err) {
                if (cancelled) return;
                if (err instanceof ApiError && err.status === 403) {
                    setAccessDenied(true);
                } else {
                    setError(true);
                    toastError(t("loadFailed"));
                }
            } finally {
                if (!cancelled) setLoading(false);
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [activeWorkspaceId, t, reloadKey]);

    const set = <K extends keyof FormState>(key: K, value: FormState[K]) =>
        setForm((prev) => (prev ? { ...prev, [key]: value } : prev));

    const setAttested = (value: boolean) =>
        setForm((prev) =>
            prev ? { ...prev, noTrainingAttested: value, enabled: value ? prev.enabled : false } : prev,
        );

    const applySaved = (saved: AiProviderConfig) => {
        setForm(toForm(saved));
        setStoredProvider(saved.provider);
        setHasCredential(saved.hasCredential);
        setCredentialLast4(saved.credentialLast4);
    };

    const save = async () => {
        if (activeWorkspaceId == null || form == null) return;
        setSaving(true);
        try {
            const saved = await saveAiProviderConfig(activeWorkspaceId, buildRequest(form));
            applySaved(saved);
            toastSuccess(t("saved"));
        } catch (err) {
            if (handlePasskeyStepUpError(err)) {
                return;
            }
            if (err instanceof ApiError && err.status === 403) {
                toastError(t("stepUpRequired"));
            } else {
                showApiError(err, "saveFailed");
            }
        } finally {
            setSaving(false);
        }
    };

    const saveGovernance = async () => {
        if (activeWorkspaceId == null || governance == null) return;
        setSavingGovernance(true);
        try {
            const saved = await saveAiWorkspaceGovernance(activeWorkspaceId, {
                enabled: governance.enabled,
                assistantMaxSteps: governance.assistantMaxSteps,
            });
            setGovernance(saved);
            toastSuccess(t("governanceSaved"));
        } catch (err) {
            showApiError(err, "governanceSaveFailed");
        } finally {
            setSavingGovernance(false);
        }
    };

    const saveBudget = async () => {
        if (activeWorkspaceId == null || budget == null) return;
        setSavingBudget(true);
        try {
            const saved = await saveAiOrganizationBudget(
                activeWorkspaceId,
                budget.dailyUsageLimit,
            );
            setBudget(saved);
            toastSuccess(t("budgetSaved"));
        } catch (err) {
            showApiError(err, "budgetSaveFailed");
        } finally {
            setSavingBudget(false);
        }
    };

    const revoke = async () => {
        if (activeWorkspaceId == null) return;
        setSaving(true);
        try {
            await revokeAiProviderConfig(activeWorkspaceId);
            setForm(EMPTY);
            setStoredProvider(null);
            setHasCredential(false);
            setCredentialLast4(null);
            toastSuccess(t("revoked"));
        } catch (err) {
            if (handlePasskeyStepUpError(err)) {
                return;
            }
            if (err instanceof ApiError && err.status === 403) {
                toastError(t("stepUpRequired"));
            } else {
                showApiError(err, "revokeFailed");
            }
        } finally {
            setSaving(false);
        }
    };

    if (accessDenied) {
        return <NoAccessCard />;
    }

    const credentialStored = form != null && hasCredential && form.provider === storedProvider;
    const credentialPlaceholder = credentialStored ? t("credentialConfigured") : "";
    const governanceValid = governance != null
        && Number.isInteger(governance.assistantMaxSteps)
        && governance.assistantMaxSteps >= 1
        && governance.assistantMaxSteps <= 12;
    const budgetValid = budget != null
        && Number.isSafeInteger(budget.dailyUsageLimit)
        && budget.dailyUsageLimit >= 0
        && budget.dailyUsageLimit <= 1_000_000_000_000;

    return (
        <Rise className="space-y-3">
            <SettingsPanelHeading
                presentation={presentation}
                title={t("title")}
                description={t("subtitle")}
                descriptionClassName="max-w-prose"
            />

            {error ? (
                <div className="flex flex-col items-center gap-3 rounded-2xl border border-border bg-card px-4 py-8 text-center">
                    <p className="text-sm text-muted-foreground">{t("loadFailed")}</p>
                    <Button variant="outline" size="sm" onClick={() => setReloadKey((key) => key + 1)}>
                        {t("retry")}
                    </Button>
                </div>
            ) : loading || form == null || governance == null || budget == null ? (
                <div className="space-y-3 rounded-2xl border border-border bg-card p-4">
                    <Skeleton className="h-9 w-full rounded-md" />
                    <Skeleton className="h-9 w-full rounded-md" />
                    <Skeleton className="h-9 w-full rounded-md" />
                    <Skeleton className="h-9 w-2/3 rounded-md" />
                </div>
            ) : (
                <>
                    <div className="space-y-4 rounded-2xl border border-border bg-card p-4">
                        <div className="flex items-center gap-4">
                            <div className="min-w-0 flex-1">
                                <p className="text-sm font-medium text-foreground">{t("workspaceEnableTitle")}</p>
                                <p className="text-sm text-muted-foreground">{t("workspaceEnableDescription")}</p>
                            </div>
                            <Switch
                                checked={governance.enabled}
                                disabled={savingGovernance}
                                onCheckedChange={(enabled) => setGovernance((current) => (
                                    current ? { ...current, enabled } : current
                                ))}
                                aria-label={t("workspaceEnableTitle")}
                            />
                        </div>
                        <div className="space-y-1.5 border-t border-border pt-4">
                            <Label htmlFor="assistant-max-steps">{t("assistantMaxSteps")}</Label>
                            <Input
                                id="assistant-max-steps"
                                type="number"
                                min={1}
                                max={12}
                                value={governance.assistantMaxSteps}
                                onChange={(event) => setGovernance((current) => current ? {
                                    ...current,
                                    assistantMaxSteps: Number(event.target.value),
                                } : current)}
                                className="max-w-28"
                                disabled={savingGovernance}
                            />
                            <p className="text-xs text-muted-foreground">{t("assistantMaxStepsDescription")}</p>
                        </div>
                        <Button onClick={() => void saveGovernance()} disabled={!governanceValid || savingGovernance}>
                            {savingGovernance ? t("saving") : t("saveWorkspaceControls")}
                        </Button>
                    </div>

                    <div className="space-y-4 rounded-2xl border border-border bg-card p-4">
                        <div>
                            <div className="flex flex-wrap items-center gap-2">
                                <p className="text-sm font-medium text-foreground">{t("dailyBudgetTitle")}</p>
                                {budget.exhausted ? (
                                    <span className="rounded-full bg-destructive/10 px-2 py-0.5 text-xs font-medium text-destructive">
                                        {t("budgetExhausted")}
                                    </span>
                                ) : null}
                            </div>
                            <p className="text-sm text-muted-foreground">{t("dailyBudgetDescription")}</p>
                        </div>
                        <div className="grid gap-4 sm:grid-cols-2">
                            <div className="space-y-1.5">
                                <Label htmlFor="ai-daily-budget">{t("dailyTokenLimit")}</Label>
                                <Input
                                    id="ai-daily-budget"
                                    type="number"
                                    min={0}
                                    max={1_000_000_000_000}
                                    value={budget.dailyUsageLimit}
                                    onChange={(event) => setBudget((current) => current ? {
                                        ...current,
                                        dailyUsageLimit: Number(event.target.value),
                                    } : current)}
                                    disabled={savingBudget}
                                />
                                <p className="text-xs text-muted-foreground">{t("dailyTokenLimitHint")}</p>
                            </div>
                            <dl className="grid grid-cols-2 gap-3 text-sm">
                                <div>
                                    <dt className="text-muted-foreground">{t("tokensUsed")}</dt>
                                    <dd className="font-medium text-foreground">{budget.consumedUsage.toLocaleString()}</dd>
                                </div>
                                <div>
                                    <dt className="text-muted-foreground">{t("tokensRemaining")}</dt>
                                    <dd className="font-medium text-foreground">
                                        {budget.dailyUsageLimit === 0 ? t("unlimited") : budget.remainingUsage.toLocaleString()}
                                    </dd>
                                </div>
                            </dl>
                        </div>
                        {budget.usage.length > 0 ? (
                            <div className="space-y-2 border-t border-border pt-4">
                                <p className="text-sm font-medium text-foreground">{t("todayUsage")}</p>
                                <ul className="space-y-2">
                                    {budget.usage.map((entry) => (
                                        <li key={`${entry.userId ?? 'system'}:${entry.feature}`} className="flex items-center gap-3 text-sm">
                                            <span className="min-w-0 flex-1 truncate text-foreground">
                                                {entry.displayName}
                                            </span>
                                            <span className="text-muted-foreground">{entry.feature}</span>
                                            <span className="tabular-nums text-foreground">
                                                {(entry.inputUsage + entry.outputUsage).toLocaleString()}
                                            </span>
                                        </li>
                                    ))}
                                </ul>
                            </div>
                        ) : null}
                        <Button onClick={() => void saveBudget()} disabled={!budgetValid || savingBudget}>
                            {savingBudget ? t("saving") : t("saveBudget")}
                        </Button>
                    </div>

                    <div className="space-y-5 rounded-2xl border border-border bg-card p-4">
                    <div className="flex items-center gap-4">
                        <div className="min-w-0 flex-1">
                            <p className="text-sm font-medium text-foreground">{t("enableTitle")}</p>
                            <p className="text-sm text-muted-foreground">
                                {t("enableDescription")}
                                {!form.noTrainingAttested && <> {t("enableRequiresAttestation")}</>}
                            </p>
                        </div>
                        <Switch
                            checked={form.enabled}
                            disabled={saving || !form.noTrainingAttested}
                            onCheckedChange={(value) => set("enabled", value)}
                            aria-label={t("enableTitle")}
                        />
                    </div>

                    <fieldset disabled={saving} className="space-y-4 border-t border-border pt-4">
                        <div className="grid gap-4 sm:grid-cols-2">
                            <div className="space-y-1.5">
                                <Label htmlFor="ai-provider-kind">{t("provider")}</Label>
                                <Select
                                    value={form.provider}
                                    onValueChange={(value) => set("provider", value as AiProviderKind)}
                                >
                                    <SelectTrigger id="ai-provider-kind" className="w-full">
                                        <SelectValue />
                                    </SelectTrigger>
                                    <SelectContent>
                                        <SelectItem value="bedrock">{t("providerBedrock")}</SelectItem>
                                        <SelectItem value="azure_openai">{t("providerAzure")}</SelectItem>
                                        <SelectItem value="vertex">{t("providerVertex")}</SelectItem>
                                        <SelectItem value="openai_compatible">
                                            {t("providerOpenAiCompatible")}
                                        </SelectItem>
                                    </SelectContent>
                                </Select>
                            </div>
                            <div className="space-y-1.5">
                                <Label htmlFor="ai-model">{t("modelId")}</Label>
                                <Input
                                    id="ai-model"
                                    autoComplete="off"
                                    value={form.modelId}
                                    onChange={(e) => set("modelId", e.target.value)}
                                />
                                {form.provider === "bedrock" && (
                                    <p className="text-xs text-muted-foreground">{t("bedrockModelIdHint")}</p>
                                )}
                            </div>
                        </div>

                        {form.provider === "bedrock" && (
                            <div className="space-y-4 border-t border-border pt-4">
                                <div className="grid gap-4 sm:grid-cols-2">
                                    <div className="space-y-1.5">
                                        <Label htmlFor="ai-region">{t("region")}</Label>
                                        <Select
                                            value={form.region}
                                            onValueChange={(value) => set("region", value)}
                                        >
                                            <SelectTrigger id="ai-region" className="w-full">
                                                <SelectValue placeholder={t("regionPlaceholder")} />
                                            </SelectTrigger>
                                            <SelectContent>
                                                {BEDROCK_REGIONS.map((region) => (
                                                    <SelectItem key={region} value={region}>
                                                        {region}
                                                    </SelectItem>
                                                ))}
                                            </SelectContent>
                                        </Select>
                                    </div>
                                    <div className="space-y-1.5">
                                        <Label htmlFor="ai-access-key">{t("accessKeyId")}</Label>
                                        <Input
                                            id="ai-access-key"
                                            autoComplete="off"
                                            value={form.accessKeyId}
                                            onChange={(e) => set("accessKeyId", e.target.value)}
                                        />
                                    </div>
                                </div>
                                <div className="grid gap-4 sm:grid-cols-2">
                                    <div className="space-y-1.5">
                                        <Label htmlFor="ai-secret-key">{t("secretAccessKey")}</Label>
                                        <Input
                                            id="ai-secret-key"
                                            type="password"
                                            autoComplete="new-password"
                                            value={form.secretAccessKey}
                                            placeholder={credentialPlaceholder}
                                            onChange={(e) => set("secretAccessKey", e.target.value)}
                                        />
                                    </div>
                                    <div className="space-y-1.5">
                                        <Label htmlFor="ai-session-token">{t("sessionToken")}</Label>
                                        <Input
                                            id="ai-session-token"
                                            type="password"
                                            autoComplete="new-password"
                                            value={form.sessionToken}
                                            onChange={(e) => set("sessionToken", e.target.value)}
                                        />
                                    </div>
                                </div>
                            </div>
                        )}

                        {form.provider === "azure_openai" && (
                            <div className="space-y-4 border-t border-border pt-4">
                                <div className="space-y-1.5">
                                    <Label htmlFor="ai-endpoint">{t("azureEndpoint")}</Label>
                                    <Input
                                        id="ai-endpoint"
                                        autoComplete="off"
                                        value={form.endpoint}
                                        placeholder="https://your-resource.openai.azure.com"
                                        onChange={(e) => set("endpoint", e.target.value)}
                                    />
                                    <p className="text-xs text-muted-foreground">{t("azureEndpointHint")}</p>
                                </div>
                                <div className="grid gap-4 sm:grid-cols-3">
                                    <div className="space-y-1.5">
                                        <Label htmlFor="ai-deployment">{t("azureDeployment")}</Label>
                                        <Input
                                            id="ai-deployment"
                                            autoComplete="off"
                                            value={form.deployment}
                                            onChange={(e) => set("deployment", e.target.value)}
                                        />
                                    </div>
                                    <div className="space-y-1.5">
                                        <Label htmlFor="ai-api-version">{t("azureApiVersion")}</Label>
                                        <Input
                                            id="ai-api-version"
                                            autoComplete="off"
                                            value={form.apiVersion}
                                            placeholder="2024-10-21"
                                            onChange={(e) => set("apiVersion", e.target.value)}
                                        />
                                    </div>
                                    <div className="space-y-1.5">
                                        <Label htmlFor="ai-api-key">{t("apiKey")}</Label>
                                        <Input
                                            id="ai-api-key"
                                            type="password"
                                            autoComplete="new-password"
                                            value={form.apiKey}
                                            placeholder={credentialPlaceholder}
                                            onChange={(e) => set("apiKey", e.target.value)}
                                        />
                                    </div>
                                </div>
                            </div>
                        )}

                        {form.provider === "vertex" && (
                            <div className="space-y-4 border-t border-border pt-4">
                                <div className="grid gap-4 sm:grid-cols-2">
                                    <div className="space-y-1.5">
                                        <Label htmlFor="ai-project">{t("vertexProjectId")}</Label>
                                        <Input
                                            id="ai-project"
                                            autoComplete="off"
                                            value={form.projectId}
                                            onChange={(e) => set("projectId", e.target.value)}
                                        />
                                    </div>
                                    <div className="space-y-1.5">
                                        <Label htmlFor="ai-location">{t("vertexRegion")}</Label>
                                        <Input
                                            id="ai-location"
                                            autoComplete="off"
                                            value={form.region}
                                            placeholder="asia-northeast1"
                                            onChange={(e) => set("region", e.target.value)}
                                        />
                                        <p className="text-xs text-muted-foreground">{t("vertexRegionHint")}</p>
                                    </div>
                                </div>
                                <div className="space-y-1.5">
                                    <Label htmlFor="ai-sa-json">{t("serviceAccountJson")}</Label>
                                    <Textarea
                                        id="ai-sa-json"
                                        rows={4}
                                        className="font-mono text-xs"
                                        value={form.serviceAccountJson}
                                        placeholder={credentialStored ? t("credentialConfigured") : "{ … }"}
                                        onChange={(e) => set("serviceAccountJson", e.target.value)}
                                    />
                                    <p className="text-xs text-muted-foreground">{t("serviceAccountJsonHint")}</p>
                                </div>
                            </div>
                        )}

                        {form.provider === "openai_compatible" && (
                            <div className="space-y-4 border-t border-border pt-4">
                                <div className="grid gap-4 sm:grid-cols-2">
                                    <div className="space-y-1.5">
                                        <Label htmlFor="ai-base-url">{t("genericEndpoint")}</Label>
                                        <Input
                                            id="ai-base-url"
                                            autoComplete="off"
                                            value={form.endpoint}
                                            placeholder="https://api.example.com/v1"
                                            onChange={(e) => set("endpoint", e.target.value)}
                                        />
                                        <p className="text-xs text-muted-foreground">
                                            {t("genericEndpointHint")}
                                        </p>
                                    </div>
                                    <div className="space-y-1.5">
                                        <Label htmlFor="ai-generic-key">{t("genericApiKey")}</Label>
                                        <Input
                                            id="ai-generic-key"
                                            type="password"
                                            autoComplete="new-password"
                                            value={form.apiKey}
                                            placeholder={credentialPlaceholder}
                                            onChange={(e) => set("apiKey", e.target.value)}
                                        />
                                    </div>
                                </div>
                                <div className="flex items-center gap-4">
                                    <div className="min-w-0 flex-1">
                                        <p className="text-sm font-medium text-foreground">
                                            {t("allowInternalTitle")}
                                        </p>
                                        <p className="text-sm text-muted-foreground">
                                            {t("allowInternalDescription")}
                                        </p>
                                    </div>
                                    <Switch
                                        checked={form.allowInternalEndpoint}
                                        disabled={saving}
                                        onCheckedChange={(value) => set("allowInternalEndpoint", value)}
                                        aria-label={t("allowInternalTitle")}
                                    />
                                </div>
                            </div>
                        )}

                        {credentialStored && (
                            <p className="text-xs text-muted-foreground">
                                {credentialLast4
                                    ? `${t("credentialLast4Hint", { last4: credentialLast4 })} ${t("credentialKeepHint")}`
                                    : t("credentialKeepHint")}
                            </p>
                        )}

                        <div className="flex items-start gap-3 border-t border-border pt-4">
                            <Checkbox
                                id="ai-attest"
                                checked={form.noTrainingAttested}
                                onCheckedChange={(value) => setAttested(value === true)}
                                className="mt-0.5"
                            />
                            <div className="min-w-0 flex-1 space-y-1">
                                <Label htmlFor="ai-attest">{t("attestLabel")}</Label>
                                <p className="text-sm text-muted-foreground">{t("attestDescription")}</p>
                            </div>
                        </div>
                    </fieldset>

                    <div className="flex flex-wrap items-center gap-3 border-t border-border pt-4">
                        <Button onClick={save} disabled={saving}>
                            {saving ? t("saving") : t("save")}
                        </Button>
                        {storedProvider != null && (
                            <Button
                                variant="ghost"
                                className="ml-auto text-destructive hover:text-destructive"
                                onClick={revoke}
                                disabled={saving}
                            >
                                {t("revoke")}
                            </Button>
                        )}
                    </div>
                    </div>
                </>
            )}
        </Rise>
    );
}
