"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";

import type { MailConfig, MailConfigRequest } from "@/app/lib/types";
import {
    deleteWorkspaceMailConfig,
    getWorkspaceMailConfig,
    saveWorkspaceMailConfig,
    sendWorkspaceMailTest,
} from "@/app/lib/api";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import Rise from "@/app/components/motion/Rise";
import SectionHeader from "@/app/components/dashboard/SectionHeader";

type FormState = {
    enabled: boolean;
    host: string;
    port: string;
    username: string;
    password: string;
    fromAddress: string;
    fromName: string;
    starttls: boolean;
    ssl: boolean;
    auth: boolean;
};

const EMPTY: FormState = {
    enabled: false,
    host: "",
    port: "587",
    username: "",
    password: "",
    fromAddress: "",
    fromName: "",
    starttls: true,
    ssl: false,
    auth: true,
};

function toForm(config: MailConfig): FormState {
    return {
        enabled: config.enabled,
        host: config.host ?? "",
        port: config.port != null ? String(config.port) : "587",
        username: config.username ?? "",
        password: "",
        fromAddress: config.fromAddress ?? "",
        fromName: config.fromName ?? "",
        starttls: config.starttls,
        ssl: config.ssl,
        auth: config.auth,
    };
}

export default function EmailPanel() {
    const t = useTranslations("WorkspaceEmail");
    const { activeWorkspaceId } = useWorkspace();

    const [form, setForm] = useState<FormState>(EMPTY);
    const [hasPassword, setHasPassword] = useState(false);
    const [configured, setConfigured] = useState(false);
    const [savedEnabled, setSavedEnabled] = useState(false);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(false);
    const [saving, setSaving] = useState(false);
    const [testing, setTesting] = useState(false);
    const [reloadKey, setReloadKey] = useState(0);

    useEffect(() => {
        if (activeWorkspaceId == null) return;
        let cancelled = false;
        (async () => {
            setLoading(true);
            setError(false);
            try {
                const config = await getWorkspaceMailConfig(activeWorkspaceId);
                if (cancelled) return;
                setForm(toForm(config));
                setHasPassword(config.hasPassword);
                setConfigured(config.configured);
                setSavedEnabled(config.enabled);
            } catch {
                if (!cancelled) {
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

    const buildRequest = (): MailConfigRequest => {
        const parsedPort = Number.parseInt(form.port.trim(), 10);
        return {
        enabled: form.enabled,
        host: form.host.trim() || null,
        port: Number.isFinite(parsedPort) ? parsedPort : null,
        username: form.username.trim() || null,
        password: form.password ? form.password : null,
        fromAddress: form.fromAddress.trim() || null,
        fromName: form.fromName.trim() || null,
        starttls: form.starttls,
        ssl: form.ssl,
        auth: form.auth,
        };
    };

    const save = async () => {
        if (activeWorkspaceId == null) return;
        setSaving(true);
        try {
            const saved = await saveWorkspaceMailConfig(activeWorkspaceId, buildRequest());
            setForm(toForm(saved));
            setHasPassword(saved.hasPassword);
            setConfigured(saved.configured);
            setSavedEnabled(saved.enabled);
            toastSuccess(t("saved"));
        } catch (err) {
            toastError(err instanceof Error ? err.message : t("saveFailed"));
        } finally {
            setSaving(false);
        }
    };

    const runTest = async () => {
        if (activeWorkspaceId == null) return;
        setTesting(true);
        try {
            const result = await sendWorkspaceMailTest(activeWorkspaceId);
            if (result.success) {
                toastSuccess(t("testSent"));
            } else {
                toastError(result.error ?? t("testFailed"));
            }
        } catch (err) {
            toastError(err instanceof Error ? err.message : t("testFailed"));
        } finally {
            setTesting(false);
        }
    };

    const remove = async () => {
        if (activeWorkspaceId == null) return;
        setSaving(true);
        try {
            await deleteWorkspaceMailConfig(activeWorkspaceId);
            setForm(EMPTY);
            setHasPassword(false);
            setConfigured(false);
            setSavedEnabled(false);
            toastSuccess(t("removed"));
        } catch (err) {
            toastError(err instanceof Error ? err.message : t("saveFailed"));
        } finally {
            setSaving(false);
        }
    };

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
                            <div className="grid gap-4 sm:grid-cols-[2fr_1fr]">
                                <div className="space-y-1.5">
                                    <Label htmlFor="mail-host">{t("host")}</Label>
                                    <Input
                                        id="mail-host"
                                        value={form.host}
                                        placeholder="smtp.example.com"
                                        onChange={(e) => set("host", e.target.value)}
                                    />
                                </div>
                                <div className="space-y-1.5">
                                    <Label htmlFor="mail-port">{t("port")}</Label>
                                    <Input
                                        id="mail-port"
                                        inputMode="numeric"
                                        value={form.port}
                                        placeholder="587"
                                        onChange={(e) => set("port", e.target.value)}
                                    />
                                </div>
                            </div>

                            <div className="grid gap-4 sm:grid-cols-2">
                                <div className="space-y-1.5">
                                    <Label htmlFor="mail-from">{t("fromAddress")}</Label>
                                    <Input
                                        id="mail-from"
                                        type="email"
                                        value={form.fromAddress}
                                        placeholder="no-reply@example.com"
                                        onChange={(e) => set("fromAddress", e.target.value)}
                                    />
                                </div>
                                <div className="space-y-1.5">
                                    <Label htmlFor="mail-from-name">{t("fromName")}</Label>
                                    <Input
                                        id="mail-from-name"
                                        value={form.fromName}
                                        placeholder="Connex"
                                        onChange={(e) => set("fromName", e.target.value)}
                                    />
                                </div>
                            </div>

                            <div className="grid gap-4 sm:grid-cols-2">
                                <div className="space-y-1.5">
                                    <Label htmlFor="mail-username">{t("username")}</Label>
                                    <Input
                                        id="mail-username"
                                        autoComplete="off"
                                        value={form.username}
                                        onChange={(e) => set("username", e.target.value)}
                                    />
                                </div>
                                <div className="space-y-1.5">
                                    <Label htmlFor="mail-password">{t("password")}</Label>
                                    <Input
                                        id="mail-password"
                                        type="password"
                                        autoComplete="new-password"
                                        value={form.password}
                                        placeholder={hasPassword ? t("passwordConfigured") : ""}
                                        onChange={(e) => set("password", e.target.value)}
                                    />
                                    <p className="text-xs text-muted-foreground">{t("passwordHint")}</p>
                                </div>
                            </div>

                            <div className="flex flex-wrap gap-6">
                                <label className="flex items-center gap-2 text-sm text-foreground">
                                    <Switch
                                        checked={form.starttls}
                                        onCheckedChange={(value) => set("starttls", value)}
                                        aria-label={t("starttls")}
                                    />
                                    {t("starttls")}
                                </label>
                                <label className="flex items-center gap-2 text-sm text-foreground">
                                    <Switch
                                        checked={form.ssl}
                                        onCheckedChange={(value) => set("ssl", value)}
                                        aria-label={t("ssl")}
                                    />
                                    {t("ssl")}
                                </label>
                                <label className="flex items-center gap-2 text-sm text-foreground">
                                    <Switch
                                        checked={form.auth}
                                        onCheckedChange={(value) => set("auth", value)}
                                        aria-label={t("auth")}
                                    />
                                    {t("auth")}
                                </label>
                            </div>
                        </fieldset>
                    )}

                    {!loading && (
                        <div className="flex flex-wrap items-center gap-3 border-t border-border pt-4">
                            <Button onClick={save} disabled={saving}>
                                {t("save")}
                            </Button>
                            <Button variant="outline" onClick={runTest} disabled={testing || saving || !savedEnabled}>
                                {testing ? t("testing") : t("sendTest")}
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
