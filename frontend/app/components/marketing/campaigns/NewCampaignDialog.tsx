"use client";

import { type FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";

import {
    ResponsiveDialog,
    ResponsiveDialogContent,
    ResponsiveDialogHeader,
    ResponsiveDialogTitle,
    ResponsiveDialogDescription,
    ResponsiveDialogFooter,
    ResponsiveDialogClose,
} from "@/components/ui/responsive-dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { DialogStatusCover } from "@/components/ui/dialog-status-cover";
import { createCampaign, isFieldError } from "@/app/lib/api";
import { useFieldErrors, quickEditErrorId } from "@/app/hooks/useFieldErrors";
import { useApiErrorToast } from "@/app/hooks/useApiErrorToast";
import {
    campaignBuilderPath,
    campaignInstantCreatePayload,
} from "@/app/components/marketing/campaigns/campaignInstantCreate";

/**
 * The campaigns instant-create prompt (D5 builder-artifact archetype): name it, then land in the
 * full-page campaign builder, where the audience, messages, budget, and window are the real work.
 *
 * It asks for the campaign type alongside the name because `POST /api/campaigns` requires one and
 * no default would be truthful — the type is a fact about the customer's marketing, not a Connex
 * mechanism. That two-field prompt is this surface's documented D5 adaptation; everything else the
 * old create dialog collected now belongs to the builder.
 */
export default function NewCampaignDialog({
    open,
    onOpenChange,
}: {
    open: boolean;
    onOpenChange: (open: boolean) => void;
}) {
    const t = useTranslations("CampaignsNewDialog");
    const router = useRouter();
    const showApiError = useApiErrorToast("CampaignsNewDialog");
    const { fieldErrors, reset: resetFieldErrors, clearError, captureFieldErrors } = useFieldErrors();
    const [name, setName] = useState("");
    const [type, setType] = useState("");
    const [isCreating, setIsCreating] = useState(false);
    const [isCreated, setIsCreated] = useState(false);

    const hasErrors = Object.keys(fieldErrors).length > 0;
    const status: "idle" | "loading" | "success" | "error" = isCreating
        ? "loading"
        : hasErrors
            ? "error"
            : isCreated
                ? "success"
                : "idle";

    const [wasOpen, setWasOpen] = useState(open);
    if (open !== wasOpen) {
        setWasOpen(open);
        if (open) {
            setName("");
            setType("");
            setIsCreated(false);
            resetFieldErrors();
        }
    }

    const handleOpenChange = (next: boolean) => {
        if (!next && (isCreating || isCreated)) return;
        onOpenChange(next);
    };

    const handleSubmit = async (event: FormEvent) => {
        event.preventDefault();
        if (isCreating || isCreated) return;
        resetFieldErrors();
        setIsCreating(true);
        try {
            const created = await createCampaign(campaignInstantCreatePayload(name, type));
            setIsCreated(true);
            router.push(campaignBuilderPath(created.id));
        } catch (err) {
            setIsCreating(false);
            if (captureFieldErrors(err)) {
                const firstKey = Object.keys(isFieldError(err) ? err.fieldErrors : {})[0];
                if (firstKey) {
                    requestAnimationFrame(() => document.getElementById(`campaign-${firstKey}`)?.focus());
                }
                return;
            }
            showApiError(err, "createFailed");
        }
    };

    return (
        <ResponsiveDialog open={open} onOpenChange={handleOpenChange}>
            <ResponsiveDialogContent className="gap-0 overflow-hidden p-0 sm:max-w-md">
                <DialogStatusCover status={status} />
                <div className="px-6 pt-6">
                    <ResponsiveDialogHeader className="mb-5">
                        <ResponsiveDialogTitle className="text-xl font-semibold tracking-tight">
                            {t("title")}
                        </ResponsiveDialogTitle>
                        <ResponsiveDialogDescription>{t("description")}</ResponsiveDialogDescription>
                    </ResponsiveDialogHeader>
                </div>

                <div className="px-6 pb-4">
                    <form id="new-campaign-form" onSubmit={handleSubmit} className="grid gap-5">
                        <div className="grid gap-1.5">
                            <Label htmlFor="campaign-name">
                                {t("name")}
                                <span className="text-destructive">*</span>
                            </Label>
                            <Input
                                id="campaign-name"
                                type="text"
                                value={name}
                                onChange={(event) => {
                                    setName(event.target.value);
                                    clearError("name");
                                }}
                                placeholder={t("namePlaceholder")}
                                aria-invalid={Boolean(fieldErrors.name)}
                                aria-describedby={fieldErrors.name ? quickEditErrorId("campaign-name") : undefined}
                                autoFocus
                                required
                            />
                            {fieldErrors.name ? (
                                <p id={quickEditErrorId("campaign-name")} className="text-sm text-destructive">
                                    {fieldErrors.name}
                                </p>
                            ) : null}
                        </div>

                        <div className="grid gap-1.5">
                            <Label htmlFor="campaign-type">
                                {t("type")}
                                <span className="text-destructive">*</span>
                            </Label>
                            <Input
                                id="campaign-type"
                                type="text"
                                value={type}
                                onChange={(event) => {
                                    setType(event.target.value);
                                    clearError("type");
                                }}
                                placeholder={t("typePlaceholder")}
                                aria-invalid={Boolean(fieldErrors.type)}
                                aria-describedby={fieldErrors.type ? quickEditErrorId("campaign-type") : undefined}
                                required
                            />
                            {fieldErrors.type ? (
                                <p id={quickEditErrorId("campaign-type")} className="text-sm text-destructive">
                                    {fieldErrors.type}
                                </p>
                            ) : null}
                            <p className="text-xs text-muted-foreground">{t("typeHint")}</p>
                        </div>
                    </form>
                </div>

                <ResponsiveDialogFooter className="border-t border-border/60 bg-popover px-6 py-4">
                    <ResponsiveDialogClose asChild>
                        <Button type="button" variant="outline" size="dialog" disabled={isCreating || isCreated}>
                            {t("cancel")}
                        </Button>
                    </ResponsiveDialogClose>
                    <Button
                        type="submit"
                        form="new-campaign-form"
                        variant="brand"
                        size="dialog"
                        disabled={isCreating || isCreated || hasErrors}
                        className="min-w-24"
                    >
                        {isCreating || isCreated ? (
                            <>
                                <Loader2Icon className="size-4 animate-spin" />
                                {t("creating")}
                            </>
                        ) : (
                            t("submit")
                        )}
                    </Button>
                </ResponsiveDialogFooter>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}
