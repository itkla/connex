"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";

import type {
    DeliveryProvider,
    DeliveryProviderConfig,
    DeliveryProviderConfigPayload,
    DeliveryWebhookToken,
} from "@/app/lib/types";
import {
    ApiError,
    deleteDeliveryProvider,
    getDeliveryProviders,
    issueDeliveryWebhookToken,
    saveDeliveryProvider,
} from "@/app/lib/api";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { usePasskeyStepUpErrorHandler } from "@/app/hooks/usePasskeyStepUpError";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { copyToClipboard } from "@/app/lib/utils";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import Rise from "@/app/components/motion/Rise";
import SectionHeader from "@/app/components/dashboard/SectionHeader";

const CHANNEL = "email";

type FormState = {
    provider: DeliveryProvider;
    endpoint: string;
    fromAddress: string;
    fromName: string;
    apiKey: string;
    enabled: boolean;
};

const EMPTY: FormState = {
    provider: "smtp",
    endpoint: "",
    fromAddress: "",
    fromName: "",
    apiKey: "",
    enabled: false,
};

function toForm(config: DeliveryProviderConfig): FormState {
    return {
        provider: config.provider,
        endpoint: config.endpoint ?? "",
        fromAddress: config.fromAddress ?? "",
        fromName: config.fromName ?? "",
        apiKey: "",
        enabled: config.enabled,
    };
}

function normalizeProvider(value: string): DeliveryProvider {
    return value === "http_esp" ? "http_esp" : "smtp";
}

/**
 * Workspace campaign-delivery provider configuration for the email channel. Mirrors the workspace
 * email panel: an enable toggle gates the provider fields, and the HTTP ESP path exposes a
 * write-only credential plus a one-time webhook token/secret reveal.
 */
export default function DeliveryPanel() {
    const t = useTranslations("WorkspaceDelivery");
    const handlePasskeyStepUpError = usePasskeyStepUpErrorHandler();
    const { activeWorkspaceId } = useWorkspace();

    const [form, setForm] = useState<FormState>(EMPTY);
    const [savedProvider, setSavedProvider] = useState<DeliveryProvider>("smtp");
    const [hasCredential, setHasCredential] = useState(false);
    const [credentialLast4, setCredentialLast4] = useState<string | null>(null);
    const [webhookConfigured, setWebhookConfigured] = useState(false);
    const [configured, setConfigured] = useState(false);
    const [revealed, setRevealed] = useState<DeliveryWebhookToken | null>(null);

    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(false);
    const [forbidden, setForbidden] = useState(false);
    const [saving, setSaving] = useState(false);
    const [generating, setGenerating] = useState(false);
    const [reloadKey, setReloadKey] = useState(0);

    useEffect(() => {
        if (activeWorkspaceId == null) return;
        let cancelled = false;
        (async () => {
            setLoading(true);
            setError(false);
            setForbidden(false);
            try {
                const providers = await getDeliveryProviders();
                if (cancelled) return;
                const config = providers.find((entry) => entry.channel === CHANNEL) ?? null;
                if (config) {
                    setForm(toForm(config));
                    setSavedProvider(config.provider);
                    setHasCredential(config.hasCredential);
                    setCredentialLast4(config.credentialLast4);
                    setWebhookConfigured(config.webhookConfigured);
                    setConfigured(true);
                } else {
                    setForm(EMPTY);
                    setSavedProvider("smtp");
                    setHasCredential(false);
                    setCredentialLast4(null);
                    setWebhookConfigured(false);
                    setConfigured(false);
                }
                setRevealed(null);
            } catch (err) {
                if (cancelled) return;
                if (err instanceof ApiError && err.status === 403) {
                    setForbidden(true);
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
        setForm((prev) => ({ ...prev, [key]: value }));

    const applyConfig = (config: DeliveryProviderConfig) => {
        setForm(toForm(config));
        setSavedProvider(config.provider);
        setHasCredential(config.hasCredential);
        setCredentialLast4(config.credentialLast4);
        setWebhookConfigured(config.webhookConfigured);
        setConfigured(true);
    };

    const buildPayload = (): DeliveryProviderConfigPayload => {
        const httpEsp = form.provider === "http_esp";
        return {
            channel: CHANNEL,
            provider: form.provider,
            endpoint: httpEsp ? form.endpoint.trim() || null : null,
            fromAddress: httpEsp ? form.fromAddress.trim() || null : null,
            fromName: httpEsp ? form.fromName.trim() || null : null,
            apiKey: httpEsp && form.apiKey ? form.apiKey : null,
            enabled: form.enabled,
        };
    };

    const save = async () => {
        if (activeWorkspaceId == null) return;
        setSaving(true);
        try {
            const saved = await saveDeliveryProvider(buildPayload());
            applyConfig(saved);
            toastSuccess(t("saved"));
        } catch (err) {
            if (!handlePasskeyStepUpError(err)) {
                toastError(err instanceof Error ? err.message : t("saveFailed"));
            }
        } finally {
            setSaving(false);
        }
    };

    const generate = async () => {
        if (activeWorkspaceId == null) return;
        setGenerating(true);
        try {
            const token = await issueDeliveryWebhookToken(CHANNEL);
            setRevealed(token);
            setWebhookConfigured(true);
            toastSuccess(t("tokenIssued"));
        } catch (err) {
            if (!handlePasskeyStepUpError(err)) {
                toastError(err instanceof Error ? err.message : t("tokenFailed"));
            }
        } finally {
            setGenerating(false);
        }
    };

    const remove = async () => {
        if (activeWorkspaceId == null) return;
        setSaving(true);
        try {
            await deleteDeliveryProvider(CHANNEL);
            setForm(EMPTY);
            setSavedProvider("smtp");
            setHasCredential(false);
            setCredentialLast4(null);
            setWebhookConfigured(false);
            setConfigured(false);
            setRevealed(null);
            toastSuccess(t("removed"));
        } catch (err) {
            if (!handlePasskeyStepUpError(err)) {
                toastError(err instanceof Error ? err.message : t("saveFailed"));
            }
        } finally {
            setSaving(false);
        }
    };

    const copy = (value: string) => {
        if (copyToClipboard(value, "value")) {
            toastSuccess(t("copied"));
        }
    };

    const isHttpEsp = form.provider === "http_esp";
    const origin = typeof window === "undefined" ? "" : window.location.origin;

    return (
        <Rise className="space-y-3">
            <div>
                <SectionHeader title={t("title")} />
                <p className="max-w-prose px-6 text-sm text-muted-foreground">{t("subtitle")}</p>
            </div>

            {forbidden ? (
                <div className="flex flex-col items-center gap-2 rounded-2xl border border-border bg-card px-4 py-8 text-center">
                    <p className="text-sm font-medium text-foreground">{t("forbiddenTitle")}</p>
                    <p className="max-w-prose text-sm text-muted-foreground">{t("forbiddenBody")}</p>
                </div>
            ) : error ? (
                <div className="flex flex-col items-center gap-3 rounded-2xl border border-border bg-card px-4 py-8 text-center">
                    <p className="text-sm text-muted-foreground">{t("loadFailed")}</p>
                    <Button variant="outline" size="sm" onClick={() => setReloadKey((key) => key + 1)}>
                        {t("retry")}
                    </Button>
                </div>
            ) : (
                <div className="space-y-3 rounded-2xl border border-border bg-card p-4">
                    <div className="flex items-center gap-4">
                        <div className="min-w-0 flex-1">
                            <p className="text-sm font-medium text-foreground">{t("enableTitle")}</p>
                            <p className="text-sm text-muted-foreground">{t("enableDescription")}</p>
                        </div>
                        {loading ? (
                            <Skeleton className="h-[18.4px] w-8 shrink-0 rounded-full" />
                        ) : (
                            <Switch
                                checked={form.enabled}
                                disabled={saving}
                                onCheckedChange={(value) => set("enabled", value)}
                                aria-label={t("enableTitle")}
                            />
                        )}
                    </div>

                    {loading ? (
                        <div className="space-y-3 pt-2">
                            <Skeleton className="h-9 w-full rounded-md" />
                            <Skeleton className="h-9 w-full rounded-md" />
                            <Skeleton className="h-9 w-full rounded-md" />
                        </div>
                    ) : (
                        <fieldset
                            disabled={!form.enabled || saving}
                            className="space-y-4 border-t border-border pt-4 transition-opacity disabled:opacity-50"
                        >
                            <div className="space-y-1.5">
                                <Label htmlFor="delivery-provider">{t("provider")}</Label>
                                <Select
                                    value={form.provider}
                                    onValueChange={(value) => set("provider", normalizeProvider(value))}
                                >
                                    <SelectTrigger id="delivery-provider" className="w-full">
                                        <SelectValue />
                                    </SelectTrigger>
                                    <SelectContent>
                                        <SelectItem value="smtp">{t("providerSmtp")}</SelectItem>
                                        <SelectItem value="http_esp">{t("providerHttpEsp")}</SelectItem>
                                    </SelectContent>
                                </Select>
                            </div>

                            {isHttpEsp ? (
                                <div className="space-y-4">
                                    <div className="space-y-1.5">
                                        <Label htmlFor="delivery-endpoint">{t("endpoint")}</Label>
                                        <Input
                                            id="delivery-endpoint"
                                            value={form.endpoint}
                                            placeholder="https://api.esp.example.com/v1/send"
                                            onChange={(e) => set("endpoint", e.target.value)}
                                        />
                                    </div>

                                    <div className="grid gap-4 sm:grid-cols-2">
                                        <div className="space-y-1.5">
                                            <Label htmlFor="delivery-from">{t("fromAddress")}</Label>
                                            <Input
                                                id="delivery-from"
                                                type="email"
                                                value={form.fromAddress}
                                                placeholder="campaigns@example.com"
                                                onChange={(e) => set("fromAddress", e.target.value)}
                                            />
                                        </div>
                                        <div className="space-y-1.5">
                                            <Label htmlFor="delivery-from-name">{t("fromName")}</Label>
                                            <Input
                                                id="delivery-from-name"
                                                value={form.fromName}
                                                placeholder="Connex"
                                                onChange={(e) => set("fromName", e.target.value)}
                                            />
                                        </div>
                                    </div>

                                    <div className="space-y-1.5">
                                        <Label htmlFor="delivery-api-key">{t("apiKey")}</Label>
                                        <Input
                                            id="delivery-api-key"
                                            type="password"
                                            autoComplete="new-password"
                                            value={form.apiKey}
                                            placeholder={
                                                hasCredential && credentialLast4
                                                    ? t("apiKeyConfigured", { last4: credentialLast4 })
                                                    : ""
                                            }
                                            onChange={(e) => set("apiKey", e.target.value)}
                                        />
                                        <p className="text-xs text-muted-foreground">{t("apiKeyHint")}</p>
                                    </div>
                                </div>
                            ) : (
                                <p className="text-sm text-muted-foreground">{t("smtpNote")}</p>
                            )}
                        </fieldset>
                    )}

                    {!loading && isHttpEsp && (
                        <div className="space-y-3 border-t border-border pt-4">
                            <div>
                                <p className="text-sm font-medium text-foreground">{t("webhookTitle")}</p>
                                <p className="max-w-prose text-sm text-muted-foreground">
                                    {t("webhookDescription")}
                                </p>
                            </div>

                            <div className="space-y-1.5">
                                <Label>{t("webhookUrlLabel")}</Label>
                                <code className="block truncate rounded-md border border-border bg-muted/40 px-3 py-2 font-mono text-xs text-foreground">
                                    {`${origin}/api/delivery/webhooks/http_esp/${
                                        revealed?.token ?? "{token}"
                                    }`}
                                </code>
                            </div>

                            <div className="flex flex-wrap items-center gap-3">
                                <Button
                                    variant="outline"
                                    onClick={generate}
                                    disabled={generating || saving || savedProvider !== "http_esp"}
                                >
                                    {generating
                                        ? t("generating")
                                        : webhookConfigured
                                          ? t("rotateToken")
                                          : t("generateToken")}
                                </Button>
                                <p className="text-xs text-muted-foreground">
                                    {savedProvider !== "http_esp"
                                        ? t("webhookSaveFirst")
                                        : webhookConfigured
                                          ? t("webhookConfiguredYes")
                                          : t("webhookConfiguredNo")}
                                </p>
                            </div>

                            {revealed && (
                                <Alert variant="destructive">
                                    <AlertTitle>{t("revealTitle")}</AlertTitle>
                                    <AlertDescription>
                                        <p>{t("revealWarning")}</p>
                                        <div className="mt-2 grid w-full gap-2">
                                            <RevealRow
                                                label={t("revealToken")}
                                                value={revealed.token}
                                                copyLabel={t("copy")}
                                                onCopy={copy}
                                            />
                                            <RevealRow
                                                label={t("revealSecret")}
                                                value={revealed.secret}
                                                copyLabel={t("copy")}
                                                onCopy={copy}
                                            />
                                            <RevealRow
                                                label={t("revealSignatureHeader")}
                                                value={revealed.signatureHeader}
                                                copyLabel={t("copy")}
                                                onCopy={copy}
                                            />
                                        </div>
                                    </AlertDescription>
                                </Alert>
                            )}
                        </div>
                    )}

                    {!loading && (
                        <div className="flex flex-wrap items-center gap-3 border-t border-border pt-4">
                            <Button onClick={save} disabled={saving}>
                                {t("save")}
                            </Button>
                            {configured && (
                                <Button
                                    variant="ghost"
                                    className="ml-auto text-destructive hover:text-destructive"
                                    onClick={remove}
                                    disabled={saving}
                                >
                                    {t("remove")}
                                </Button>
                            )}
                        </div>
                    )}
                </div>
            )}
        </Rise>
    );
}

type RevealRowProps = {
    label: string;
    value: string;
    copyLabel: string;
    onCopy: (value: string) => void;
};

function RevealRow({ label, value, copyLabel, onCopy }: RevealRowProps) {
    return (
        <div className="grid w-full gap-1.5">
            <span className="text-xs font-medium text-foreground">{label}</span>
            <div className="flex items-center gap-2">
                <code className="min-w-0 flex-1 truncate rounded-md border border-border bg-background px-3 py-2 font-mono text-xs text-foreground">
                    {value}
                </code>
                <Button type="button" variant="outline" size="sm" onClick={() => onCopy(value)}>
                    {copyLabel}
                </Button>
            </div>
        </div>
    );
}
