"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";

import type { SsoConnectionDto, SsoConnectionRequest, SsoProtocol } from "@/app/lib/types";
import { getSsoConfig, saveSsoConfig } from "@/app/lib/api";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { Button } from "@/components/ui/button";
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
import SectionHeader from "@/app/components/dashboard/SectionHeader";

type FormState = {
    protocol: SsoProtocol;
    enabled: boolean;
    enforceSso: boolean;
    jitWorkspaceId: string;
    defaultRole: string;
    oidcIssuer: string;
    oidcClientId: string;
    oidcClientSecret: string;
    oidcScopes: string;
    samlIdpEntityId: string;
    samlSsoUrl: string;
    samlIdpMetadataXml: string;
    samlIdpX509: string;
    domains: string;
};

function toForm(config: SsoConnectionDto, fallbackWorkspaceId: number | null): FormState {
    return {
        protocol: config.protocol ?? "oidc",
        enabled: config.enabled,
        enforceSso: config.enforceSso,
        jitWorkspaceId:
            config.jitWorkspaceId != null
                ? String(config.jitWorkspaceId)
                : fallbackWorkspaceId != null
                  ? String(fallbackWorkspaceId)
                  : "",
        defaultRole: config.defaultRole || "member",
        oidcIssuer: config.oidcIssuer ?? "",
        oidcClientId: config.oidcClientId ?? "",
        oidcClientSecret: "",
        oidcScopes: config.oidcScopes ?? "openid,email,profile",
        samlIdpEntityId: config.samlIdpEntityId ?? "",
        samlSsoUrl: config.samlSsoUrl ?? "",
        samlIdpMetadataXml: config.samlIdpMetadataXml ?? "",
        samlIdpX509: config.samlIdpX509 ?? "",
        domains: config.domains.join("\n"),
    };
}

export default function SsoPanel() {
    const t = useTranslations("WorkspaceSso");
    const { activeWorkspaceId } = useWorkspace();

    const [form, setForm] = useState<FormState | null>(null);
    const [hasClientSecret, setHasClientSecret] = useState(false);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(false);
    const [accessDenied, setAccessDenied] = useState(false);
    const [saving, setSaving] = useState(false);
    const [reloadKey, setReloadKey] = useState(0);

    useEffect(() => {
        if (activeWorkspaceId == null) return;
        let cancelled = false;
        (async () => {
            setLoading(true);
            setError(false);
            setAccessDenied(false);
            try {
                const config = await getSsoConfig(activeWorkspaceId);
                if (cancelled) return;
                setForm(toForm(config, activeWorkspaceId));
                setHasClientSecret(config.hasClientSecret);
            } catch (err) {
                if (cancelled) return;
                if (err instanceof Error && "status" in err && (err as { status?: number }).status === 403) {
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

    const buildRequest = (state: FormState): SsoConnectionRequest => {
        const parsedWorkspace = Number.parseInt(state.jitWorkspaceId.trim(), 10);
        return {
            protocol: state.protocol,
            enabled: state.enabled,
            enforceSso: state.enforceSso,
            jitWorkspaceId: Number.isFinite(parsedWorkspace) ? parsedWorkspace : 0,
            defaultRole: state.defaultRole,
            oidcIssuer: state.oidcIssuer.trim() || null,
            oidcClientId: state.oidcClientId.trim() || null,
            oidcClientSecret: state.oidcClientSecret ? state.oidcClientSecret : null,
            oidcScopes: state.oidcScopes.trim() || null,
            samlIdpEntityId: state.samlIdpEntityId.trim() || null,
            samlSsoUrl: state.samlSsoUrl.trim() || null,
            samlIdpMetadataXml: state.samlIdpMetadataXml.trim() || null,
            samlIdpX509: state.samlIdpX509.trim() || null,
            domains: state.domains
                .split(/[\n,]/)
                .map((d) => d.trim())
                .filter(Boolean),
        };
    };

    const save = async () => {
        if (activeWorkspaceId == null || form == null) return;
        setSaving(true);
        try {
            const saved = await saveSsoConfig(activeWorkspaceId, buildRequest(form));
            setForm(toForm(saved, activeWorkspaceId));
            setHasClientSecret(saved.hasClientSecret);
            toastSuccess(t("saved"));
        } catch (err) {
            toastError(err instanceof Error ? err.message : t("saveFailed"));
        } finally {
            setSaving(false);
        }
    };

    if (accessDenied) {
        return (
            <p className="rounded-2xl border border-border bg-card px-4 py-6 text-center text-sm text-muted-foreground">
                {t("noAccess")}
            </p>
        );
    }

    return (
        <Rise className="space-y-3">
            <div>
                <SectionHeader title={t("title")} />
                <p className="max-w-prose px-6 text-sm text-muted-foreground">{t("subtitle")}</p>
            </div>

            {error ? (
                <div className="flex flex-col items-center gap-3 rounded-2xl border border-border bg-card px-4 py-8 text-center">
                    <p className="text-sm text-muted-foreground">{t("loadFailed")}</p>
                    <Button variant="outline" size="sm" onClick={() => setReloadKey((key) => key + 1)}>
                        {t("retry")}
                    </Button>
                </div>
            ) : loading || form == null ? (
                <div className="space-y-3 rounded-2xl border border-border bg-card p-4">
                    <Skeleton className="h-9 w-full rounded-md" />
                    <Skeleton className="h-9 w-full rounded-md" />
                    <Skeleton className="h-9 w-full rounded-md" />
                    <Skeleton className="h-9 w-2/3 rounded-md" />
                </div>
            ) : (
                <div className="space-y-5 rounded-2xl border border-border bg-card p-4">
                    <div className="flex items-center gap-4">
                        <div className="min-w-0 flex-1">
                            <p className="text-sm font-medium text-foreground">{t("enableTitle")}</p>
                            <p className="text-sm text-muted-foreground">{t("enableDescription")}</p>
                        </div>
                        <Switch
                            checked={form.enabled}
                            disabled={saving}
                            onCheckedChange={(value) => set("enabled", value)}
                            aria-label={t("enableTitle")}
                        />
                    </div>

                    <div className="flex items-center gap-4">
                        <div className="min-w-0 flex-1">
                            <p className="text-sm font-medium text-foreground">{t("enforceTitle")}</p>
                            <p className="text-sm text-muted-foreground">{t("enforceDescription")}</p>
                        </div>
                        <Switch
                            checked={form.enforceSso}
                            disabled={saving}
                            onCheckedChange={(value) => set("enforceSso", value)}
                            aria-label={t("enforceTitle")}
                        />
                    </div>

                    <fieldset disabled={saving} className="space-y-4 border-t border-border pt-4">
                        <div className="grid gap-4 sm:grid-cols-3">
                            <div className="space-y-1.5">
                                <Label htmlFor="sso-protocol">{t("protocol")}</Label>
                                <Select
                                    value={form.protocol}
                                    onValueChange={(value) => set("protocol", value as SsoProtocol)}
                                >
                                    <SelectTrigger id="sso-protocol" className="w-full">
                                        <SelectValue />
                                    </SelectTrigger>
                                    <SelectContent>
                                        <SelectItem value="oidc">{t("protocolOidc")}</SelectItem>
                                        <SelectItem value="saml">{t("protocolSaml")}</SelectItem>
                                    </SelectContent>
                                </Select>
                            </div>
                            <div className="space-y-1.5">
                                <Label htmlFor="sso-role">{t("defaultRole")}</Label>
                                <Select
                                    value={form.defaultRole}
                                    onValueChange={(value) => set("defaultRole", value)}
                                >
                                    <SelectTrigger id="sso-role" className="w-full">
                                        <SelectValue />
                                    </SelectTrigger>
                                    <SelectContent>
                                        <SelectItem value="member">{t("roleMember")}</SelectItem>
                                        <SelectItem value="admin">{t("roleAdmin")}</SelectItem>
                                    </SelectContent>
                                </Select>
                            </div>
                            <div className="space-y-1.5">
                                <Label htmlFor="sso-workspace">{t("jitWorkspace")}</Label>
                                <Input
                                    id="sso-workspace"
                                    inputMode="numeric"
                                    value={form.jitWorkspaceId}
                                    onChange={(e) => set("jitWorkspaceId", e.target.value)}
                                />
                                <p className="text-xs text-muted-foreground">{t("jitWorkspaceHint")}</p>
                            </div>
                        </div>

                        {form.protocol === "oidc" ? (
                            <div className="space-y-4 border-t border-border pt-4">
                                <div className="space-y-1.5">
                                    <Label htmlFor="sso-issuer">{t("oidcIssuer")}</Label>
                                    <Input
                                        id="sso-issuer"
                                        value={form.oidcIssuer}
                                        placeholder="https://idp.example.com"
                                        onChange={(e) => set("oidcIssuer", e.target.value)}
                                    />
                                    <p className="text-xs text-muted-foreground">{t("oidcIssuerHint")}</p>
                                </div>
                                <div className="grid gap-4 sm:grid-cols-2">
                                    <div className="space-y-1.5">
                                        <Label htmlFor="sso-client-id">{t("oidcClientId")}</Label>
                                        <Input
                                            id="sso-client-id"
                                            autoComplete="off"
                                            value={form.oidcClientId}
                                            onChange={(e) => set("oidcClientId", e.target.value)}
                                        />
                                    </div>
                                    <div className="space-y-1.5">
                                        <Label htmlFor="sso-client-secret">{t("oidcClientSecret")}</Label>
                                        <Input
                                            id="sso-client-secret"
                                            type="password"
                                            autoComplete="new-password"
                                            value={form.oidcClientSecret}
                                            placeholder={hasClientSecret ? t("secretConfigured") : ""}
                                            onChange={(e) => set("oidcClientSecret", e.target.value)}
                                        />
                                        <p className="text-xs text-muted-foreground">{t("oidcClientSecretHint")}</p>
                                    </div>
                                </div>
                                <div className="space-y-1.5">
                                    <Label htmlFor="sso-scopes">{t("oidcScopes")}</Label>
                                    <Input
                                        id="sso-scopes"
                                        value={form.oidcScopes}
                                        placeholder="openid,email,profile"
                                        onChange={(e) => set("oidcScopes", e.target.value)}
                                    />
                                </div>
                            </div>
                        ) : (
                            <div className="space-y-4 border-t border-border pt-4">
                                <div className="grid gap-4 sm:grid-cols-2">
                                    <div className="space-y-1.5">
                                        <Label htmlFor="sso-entity">{t("samlEntityId")}</Label>
                                        <Input
                                            id="sso-entity"
                                            value={form.samlIdpEntityId}
                                            placeholder="https://idp.example.com/metadata"
                                            onChange={(e) => set("samlIdpEntityId", e.target.value)}
                                        />
                                    </div>
                                    <div className="space-y-1.5">
                                        <Label htmlFor="sso-ssourl">{t("samlSsoUrl")}</Label>
                                        <Input
                                            id="sso-ssourl"
                                            value={form.samlSsoUrl}
                                            placeholder="https://idp.example.com/sso"
                                            onChange={(e) => set("samlSsoUrl", e.target.value)}
                                        />
                                    </div>
                                </div>
                                <div className="space-y-1.5">
                                    <Label htmlFor="sso-metadata">{t("samlMetadata")}</Label>
                                    <Textarea
                                        id="sso-metadata"
                                        rows={4}
                                        className="font-mono text-xs"
                                        value={form.samlIdpMetadataXml}
                                        placeholder="<EntityDescriptor …>"
                                        onChange={(e) => set("samlIdpMetadataXml", e.target.value)}
                                    />
                                    <p className="text-xs text-muted-foreground">{t("samlMetadataHint")}</p>
                                </div>
                                <div className="space-y-1.5">
                                    <Label htmlFor="sso-x509">{t("samlX509")}</Label>
                                    <Textarea
                                        id="sso-x509"
                                        rows={4}
                                        className="font-mono text-xs"
                                        value={form.samlIdpX509}
                                        placeholder="-----BEGIN CERTIFICATE-----"
                                        onChange={(e) => set("samlIdpX509", e.target.value)}
                                    />
                                </div>
                            </div>
                        )}

                        <div className="space-y-1.5 border-t border-border pt-4">
                            <Label htmlFor="sso-domains">{t("domains")}</Label>
                            <Textarea
                                id="sso-domains"
                                rows={3}
                                value={form.domains}
                                placeholder="example.com"
                                onChange={(e) => set("domains", e.target.value)}
                            />
                            <p className="text-xs text-muted-foreground">{t("domainsHint")}</p>
                        </div>
                    </fieldset>

                    <div className="flex items-center gap-3 border-t border-border pt-4">
                        <Button onClick={save} disabled={saving}>
                            {saving ? t("saving") : t("save")}
                        </Button>
                    </div>
                </div>
            )}
        </Rise>
    );
}
