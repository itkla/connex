"use client";

import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";

import AccessDenied from "@/app/components/AccessDenied";
import PermissionsUnavailable from "@/app/components/PermissionsUnavailable";
import Rise from "@/app/components/motion/Rise";
import { SettingsSection } from "@/app/components/settings/SettingsSection";
import { usePasskeyStepUpErrorHandler } from "@/app/hooks/usePasskeyStepUpError";
import { usePermissionCheck, usePermissionsRefresh } from "@/app/hooks/usePermissions";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { ApiError, updateWorkspaceIdentity } from "@/app/lib/api";
import { toastError, toastSuccess } from "@/app/lib/toast";
import type { Workspace } from "@/app/lib/types";
import { Button } from "@/components/ui/button";
import {
    Combobox,
    ComboboxContent,
    ComboboxEmpty,
    ComboboxInput,
    ComboboxItem,
    ComboboxList,
} from "@/components/ui/combobox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

function supportedTimezones(current: string | null): string[] {
    const intl = Intl as typeof Intl & { supportedValuesOf?: (key: string) => string[] };
    let supported: string[] = [];
    try {
        supported = intl.supportedValuesOf?.("timeZone") ?? [];
    } catch {
        supported = [];
    }
    return Array.from(new Set(["UTC", ...(current ? [current] : []), ...supported]));
}

function WorkspaceIdentityForm({ workspace }: { workspace: Workspace }) {
    const t = useTranslations("WorkspaceIdentity");
    const router = useRouter();
    const handlePasskeyStepUpError = usePasskeyStepUpErrorHandler();
    const { publishWorkspaceIdentity, restoreWorkspaceIdentity } = useWorkspace();
    const [name, setName] = useState(workspace.name);
    const [timezone, setTimezone] = useState<string | null>(workspace.timezone);
    const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
    const [saving, setSaving] = useState(false);
    const timezones = useMemo(() => supportedTimezones(workspace.timezone), [workspace.timezone]);
    const normalizedName = name.trim();
    const dirty = normalizedName !== workspace.name || timezone !== workspace.timezone;

    async function save() {
        const errors: Record<string, string> = {};
        if (normalizedName.length === 0) errors.name = t("nameRequired");
        if (normalizedName.length > 128) errors.name = t("nameTooLong");
        if (timezone !== null && !timezones.includes(timezone)) errors.timezone = t("timezoneInvalid");
        setFieldErrors(errors);
        if (Object.keys(errors).length > 0 || !dirty) return;

        const previous = {
            id: workspace.id,
            name: workspace.name,
            slug: workspace.slug,
            timezone: workspace.timezone,
            identityVersion: workspace.identityVersion,
        };
        const optimistic = { ...previous, name: normalizedName, timezone };
        publishWorkspaceIdentity(optimistic);
        setSaving(true);
        try {
            const updated = await updateWorkspaceIdentity(
                workspace.id,
                normalizedName,
                timezone,
                previous.name,
                previous.timezone,
                previous.identityVersion,
            );
            publishWorkspaceIdentity(updated);
            setName(updated.name);
            setTimezone(updated.timezone);
            toastSuccess(t("saved"));
            router.refresh();
        } catch (error) {
            restoreWorkspaceIdentity(optimistic, previous);
            router.refresh();
            if (error instanceof ApiError && error.fieldErrors) {
                setFieldErrors(error.fieldErrors);
            } else if (error instanceof ApiError && error.status === 409) {
                toastError(t("stale"));
            } else if (!handlePasskeyStepUpError(error)) {
                toastError(error instanceof Error ? error.message : t("saveFailed"));
            }
        } finally {
            setSaving(false);
        }
    }

    return (
        <Rise>
            <SettingsSection title={t("title")} description={t("description")}>
                <form
                    className="overflow-hidden rounded-2xl border border-border bg-card"
                    onSubmit={(event) => {
                        event.preventDefault();
                        void save();
                    }}
                >
                    <div className="grid gap-6 p-6 md:grid-cols-2">
                        <div className="grid gap-2">
                            <Label htmlFor="workspace-name">{t("nameLabel")}</Label>
                            <Input
                                id="workspace-name"
                                value={name}
                                maxLength={128}
                                aria-invalid={Boolean(fieldErrors.name)}
                                aria-describedby={fieldErrors.name ? "workspace-name-error" : "workspace-name-hint"}
                                onChange={(event) => {
                                    setName(event.target.value);
                                    setFieldErrors((current) => ({ ...current, name: "" }));
                                }}
                            />
                            <p id="workspace-name-hint" className="text-sm text-muted-foreground">{t("nameHint")}</p>
                            {fieldErrors.name ? (
                                <p id="workspace-name-error" className="text-sm text-destructive">{fieldErrors.name}</p>
                            ) : null}
                        </div>

                        <div className="grid gap-2">
                            <Label htmlFor="workspace-slug">{t("slugLabel")}</Label>
                            <Input
                                id="workspace-slug"
                                value={workspace.slug}
                                readOnly
                                aria-readonly
                                className="cursor-not-allowed font-mono text-muted-foreground"
                            />
                            <p className="text-sm text-muted-foreground">{t("slugHint")}</p>
                        </div>

                        <div className="grid gap-2 md:col-span-2">
                            <div className="flex flex-wrap items-center justify-between gap-2">
                                <Label htmlFor="workspace-timezone">{t("timezoneLabel")}</Label>
                                {timezone !== null ? (
                                    <Button type="button" variant="ghost" size="sm" onClick={() => setTimezone(null)}>
                                        {t("timezoneUseAccount")}
                                    </Button>
                                ) : null}
                            </div>
                            <Combobox
                                items={timezones}
                                value={timezone}
                                onValueChange={(value) => {
                                    setTimezone(value);
                                    setFieldErrors((current) => ({ ...current, timezone: "" }));
                                }}
                                itemToStringLabel={(value: string) => value}
                            >
                                <ComboboxInput
                                    id="workspace-timezone"
                                    placeholder={t("timezoneDefault")}
                                    aria-invalid={Boolean(fieldErrors.timezone)}
                                />
                                <ComboboxContent className="pointer-events-auto">
                                    <ComboboxList>
                                        <ComboboxEmpty>{t("timezoneEmpty")}</ComboboxEmpty>
                                        {timezones.map((value) => (
                                            <ComboboxItem key={value} value={value}>{value}</ComboboxItem>
                                        ))}
                                    </ComboboxList>
                                </ComboboxContent>
                            </Combobox>
                            <p className="text-sm text-muted-foreground">
                                {timezone === null ? t("timezoneAccountHint") : t("timezoneOverrideHint")}
                            </p>
                            {fieldErrors.timezone ? (
                                <p className="text-sm text-destructive">{fieldErrors.timezone}</p>
                            ) : null}
                        </div>
                    </div>

                    <div className="flex items-center justify-end border-t border-border bg-muted/30 px-6 py-4">
                        <Button type="submit" variant="brand" disabled={!dirty || saving}>
                            {saving ? <Loader2Icon className="size-4 animate-spin" /> : null}
                            {saving ? t("saving") : t("save")}
                        </Button>
                    </div>
                </form>
            </SettingsSection>
        </Rise>
    );
}

export default function WorkspaceIdentityPanel() {
    const t = useTranslations("WorkspaceIdentity");
    const permission = usePermissionCheck("WORKSPACE_SETTINGS");
    const refreshPermissions = usePermissionsRefresh();
    const { activeWorkspace } = useWorkspace();

    if (permission === "unavailable") {
        return (
            <PermissionsUnavailable
                variant="inline"
                title={t("permissionUnavailableTitle")}
                body={t("permissionUnavailableBody")}
                action={<Button variant="outline" onClick={() => void refreshPermissions()}>{t("retry")}</Button>}
            />
        );
    }
    if (permission !== "granted") {
        return <AccessDenied variant="inline" title={t("noAccessTitle")} body={t("noAccessBody")} />;
    }
    if (!activeWorkspace) return null;
    return <WorkspaceIdentityForm key={activeWorkspace.id} workspace={activeWorkspace} />;
}
