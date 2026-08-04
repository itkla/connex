"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";

import { usePasskeyStepUpErrorHandler } from "@/app/hooks/usePasskeyStepUpError";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { ApiError, updateOrganizationIdentity } from "@/app/lib/api";
import { toastError, toastSuccess } from "@/app/lib/toast";
import type { OrganizationIdentity } from "@/app/lib/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

export default function OrganizationIdentityForm({
    organization,
    onUpdated,
}: {
    organization: OrganizationIdentity;
    onUpdated: (organization: OrganizationIdentity) => void;
}) {
    const t = useTranslations("OrgOverview");
    const router = useRouter();
    const handlePasskeyStepUpError = usePasskeyStepUpErrorHandler();
    const { publishOrganizationIdentity } = useWorkspace();
    const [name, setName] = useState(organization.name);
    const [fieldError, setFieldError] = useState("");
    const [saving, setSaving] = useState(false);
    const normalizedName = name.trim();
    const dirty = normalizedName !== organization.name;

    async function save() {
        if (normalizedName.length === 0) {
            setFieldError(t("nameRequired"));
            return;
        }
        if (normalizedName.length > 128) {
            setFieldError(t("nameTooLong"));
            return;
        }
        if (!dirty) return;

        const optimistic = { ...organization, name: normalizedName };
        onUpdated(optimistic);
        publishOrganizationIdentity(optimistic);
        setSaving(true);
        try {
            const updated = await updateOrganizationIdentity(organization.id, normalizedName);
            onUpdated(updated);
            publishOrganizationIdentity(updated);
            setName(updated.name);
            toastSuccess(t("saved"));
            router.refresh();
        } catch (error) {
            onUpdated(organization);
            publishOrganizationIdentity(organization);
            if (error instanceof ApiError && error.fieldErrors?.name) {
                setFieldError(error.fieldErrors.name);
            } else if (!handlePasskeyStepUpError(error)) {
                toastError(error instanceof Error ? error.message : t("saveFailed"));
            }
        } finally {
            setSaving(false);
        }
    }

    return (
        <form
            className="overflow-hidden rounded-2xl border border-border bg-card"
            onSubmit={(event) => {
                event.preventDefault();
                void save();
            }}
        >
            <div className="grid gap-6 p-6 md:grid-cols-2">
                <div className="grid gap-2">
                    <Label htmlFor="organization-name">{t("nameLabel")}</Label>
                    <Input
                        id="organization-name"
                        value={name}
                        maxLength={128}
                        aria-invalid={Boolean(fieldError)}
                        aria-describedby={fieldError ? "organization-name-error" : "organization-name-hint"}
                        onChange={(event) => {
                            setName(event.target.value);
                            setFieldError("");
                        }}
                    />
                    <p id="organization-name-hint" className="text-sm text-muted-foreground">{t("nameHint")}</p>
                    {fieldError ? (
                        <p id="organization-name-error" className="text-sm text-destructive">{fieldError}</p>
                    ) : null}
                </div>

                <div className="grid gap-2">
                    <Label htmlFor="organization-slug">{t("slugLabel")}</Label>
                    <Input
                        id="organization-slug"
                        value={organization.slug}
                        readOnly
                        aria-readonly
                        className="cursor-not-allowed font-mono text-muted-foreground"
                    />
                    <p className="text-sm text-muted-foreground">{t("slugHint")}</p>
                </div>
            </div>
            <div className="flex items-center justify-end border-t border-border bg-muted/30 px-6 py-4">
                <Button type="submit" variant="brand" disabled={!dirty || saving}>
                    {saving ? <Loader2Icon className="size-4 animate-spin" /> : null}
                    {saving ? t("saving") : t("save")}
                </Button>
            </div>
        </form>
    );
}
