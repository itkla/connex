"use client";

import { type Dispatch, type FormEvent, type SetStateAction, useState } from "react";
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
import { Label } from "@/components/ui/label";
import {
    Select,
    SelectTrigger,
    SelectValue,
    SelectContent,
    SelectItem,
} from "@/components/ui/select";
import { DialogStatusCover } from "@/components/ui/dialog-status-cover";
import { cn } from "@/lib/utils";
import { type CampaignPayload, type CampaignStatus } from "@/app/lib/types";
import { isFieldError } from "@/app/lib/api";
import { useFieldErrors } from "@/app/hooks/useFieldErrors";
import {
    MegaphoneIcon,
    FlagIcon,
    TagIcon,
    BanknotesIcon,
    CalendarIcon,
} from "@heroicons/react/24/outline";
import { Loader2Icon } from "lucide-react";
import { useTranslations } from "next-intl";

const inputBase =
    "w-full rounded-lg bg-muted py-2 text-sm text-foreground placeholder:text-muted-foreground outline-none ring-1 ring-border transition focus:ring-2 focus:ring-brand";
const inputError = "ring-2 ring-destructive focus:ring-destructive";
const leadIcon =
    "pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground transition-colors group-focus-within:text-brand";

const STATUSES: CampaignStatus[] = [
    "draft",
    "scheduled",
    "active",
    "paused",
    "completed",
    "archived",
];

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    payload: CampaignPayload;
    setPayload: Dispatch<SetStateAction<CampaignPayload>>;
    isCreating: boolean;
    isSuccess?: boolean;
    createNewCampaign: () => void | Promise<void>;
};

/**
 * Controlled create-campaign dialog. The parent owns the payload and the submit handler,
 * which re-throws field errors so this form can surface them per field.
 */
export default function NewCampaignDialog({
    open,
    onOpenChange,
    payload,
    setPayload,
    isCreating,
    isSuccess = false,
    createNewCampaign,
}: Props) {
    const t = useTranslations("CampaignsNewDialog");
    const statusT = useTranslations("CampaignStatus");
    const { fieldErrors, reset: resetFieldErrors, clearError, captureFieldErrors } = useFieldErrors();

    const hasErrors = Object.keys(fieldErrors).length > 0;
    const status: "idle" | "loading" | "success" | "error" = isCreating
        ? "loading"
        : hasErrors
            ? "error"
            : isSuccess
                ? "success"
                : "idle";

    const [wasOpen, setWasOpen] = useState(open);
    if (open !== wasOpen) {
        setWasOpen(open);
        if (open) resetFieldErrors();
    }

    const handleOpenChange = (next: boolean) => {
        if (!next && isCreating) return;
        onOpenChange(next);
    };

    const handleSubmit = async (e: FormEvent) => {
        e.preventDefault();
        if (isCreating) return;
        resetFieldErrors();
        try {
            await createNewCampaign();
        } catch (err) {
            captureFieldErrors(err);
            if (isFieldError(err)) {
                const firstKey = Object.keys(err.fieldErrors)[0];
                if (firstKey) {
                    requestAnimationFrame(() =>
                        document.getElementById(`campaign-${firstKey}`)?.focus(),
                    );
                }
            }
        }
    };

    return (
        <ResponsiveDialog open={open} onOpenChange={handleOpenChange}>
            <ResponsiveDialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                <DialogStatusCover status={status} />
                <div className="px-6 pt-6">
                    <ResponsiveDialogHeader className="mb-5">
                        <ResponsiveDialogTitle className="text-xl font-semibold tracking-tight">
                            {t("title")}
                        </ResponsiveDialogTitle>
                        <ResponsiveDialogDescription>{t("description")}</ResponsiveDialogDescription>
                    </ResponsiveDialogHeader>
                </div>

                <div className="max-h-[70dvh] overflow-y-auto px-6 pb-4">
                    <form id="new-campaign-form" onSubmit={handleSubmit} className="grid gap-5">
                        <div className="grid gap-1.5">
                            <Label htmlFor="campaign-name">
                                {t("name")} <span className="text-muted-foreground">*</span>
                            </Label>
                            <div className="group relative">
                                <MegaphoneIcon className={leadIcon} />
                                <input
                                    id="campaign-name"
                                    type="text"
                                    value={payload.name}
                                    onChange={(e) => {
                                        setPayload((prev) => ({ ...prev, name: e.target.value }));
                                        clearError("name");
                                    }}
                                    className={cn(inputBase, "pl-9 pr-3", fieldErrors.name && inputError)}
                                    placeholder={t("namePlaceholder")}
                                    aria-invalid={Boolean(fieldErrors.name)}
                                    aria-describedby={fieldErrors.name ? "campaign-name-error" : undefined}
                                    autoFocus
                                    required
                                />
                            </div>
                            {fieldErrors.name && (
                                <p id="campaign-name-error" className="text-sm text-destructive">
                                    {fieldErrors.name}
                                </p>
                            )}
                        </div>

                        <div className="grid gap-1.5">
                            <Label htmlFor="campaign-objective">{t("objective")}</Label>
                            <div className="group relative">
                                <FlagIcon className={leadIcon} />
                                <input
                                    id="campaign-objective"
                                    type="text"
                                    value={payload.objective ?? ""}
                                    onChange={(e) => {
                                        setPayload((prev) => ({ ...prev, objective: e.target.value }));
                                        clearError("objective");
                                    }}
                                    className={cn(inputBase, "pl-9 pr-3", fieldErrors.objective && inputError)}
                                    placeholder={t("objectivePlaceholder")}
                                    aria-invalid={Boolean(fieldErrors.objective)}
                                />
                            </div>
                            {fieldErrors.objective && (
                                <p className="text-sm text-destructive">{fieldErrors.objective}</p>
                            )}
                        </div>

                        <div className="grid grid-cols-2 gap-3">
                            <div className="grid gap-1.5">
                                <Label htmlFor="campaign-type">
                                    {t("type")} <span className="text-muted-foreground">*</span>
                                </Label>
                                <div className="group relative">
                                    <TagIcon className={leadIcon} />
                                    <input
                                        id="campaign-type"
                                        type="text"
                                        value={payload.type}
                                        onChange={(e) => {
                                            setPayload((prev) => ({ ...prev, type: e.target.value }));
                                            clearError("type");
                                        }}
                                        className={cn(inputBase, "pl-9 pr-3", fieldErrors.type && inputError)}
                                        placeholder={t("typePlaceholder")}
                                        aria-invalid={Boolean(fieldErrors.type)}
                                        required
                                    />
                                </div>
                                {fieldErrors.type && (
                                    <p className="text-sm text-destructive">{fieldErrors.type}</p>
                                )}
                            </div>
                            <div className="grid gap-1.5">
                                <Label htmlFor="campaign-status">{t("status")}</Label>
                                <Select
                                    value={payload.status ?? "draft"}
                                    onValueChange={(value) =>
                                        setPayload((prev) => ({ ...prev, status: value as CampaignStatus }))
                                    }
                                >
                                    <SelectTrigger id="campaign-status" className="w-full">
                                        <SelectValue />
                                    </SelectTrigger>
                                    <SelectContent>
                                        {STATUSES.map((value) => (
                                            <SelectItem key={value} value={value}>
                                                {statusT(value)}
                                            </SelectItem>
                                        ))}
                                    </SelectContent>
                                </Select>
                            </div>
                        </div>

                        <div className="grid grid-cols-[1fr_7rem] gap-3">
                            <div className="grid gap-1.5">
                                <Label htmlFor="campaign-budget">{t("budget")}</Label>
                                <div className="group relative">
                                    <BanknotesIcon className={leadIcon} />
                                    <input
                                        id="campaign-budgetAmount"
                                        type="number"
                                        min="0"
                                        step="0.01"
                                        value={payload.budgetAmount ?? ""}
                                        onChange={(e) => {
                                            const raw = e.target.value;
                                            setPayload((prev) => ({
                                                ...prev,
                                                budgetAmount: raw === "" ? null : Number(raw),
                                            }));
                                            clearError("budgetAmount");
                                        }}
                                        className={cn(
                                            inputBase,
                                            "pl-9 pr-3",
                                            fieldErrors.budgetAmount && inputError,
                                        )}
                                        placeholder={t("budgetAmountPlaceholder")}
                                        aria-invalid={Boolean(fieldErrors.budgetAmount)}
                                    />
                                </div>
                                {fieldErrors.budgetAmount && (
                                    <p className="text-sm text-destructive">{fieldErrors.budgetAmount}</p>
                                )}
                            </div>
                            <div className="grid gap-1.5">
                                <Label htmlFor="campaign-currency">{t("currency")}</Label>
                                <input
                                    id="campaign-budgetCurrency"
                                    type="text"
                                    inputMode="text"
                                    maxLength={3}
                                    value={payload.budgetCurrency ?? ""}
                                    onChange={(e) => {
                                        setPayload((prev) => ({
                                            ...prev,
                                            budgetCurrency: e.target.value.toUpperCase() || null,
                                        }));
                                        clearError("budgetCurrency");
                                    }}
                                    className={cn(
                                        inputBase,
                                        "px-3 uppercase",
                                        fieldErrors.budgetCurrency && inputError,
                                    )}
                                    placeholder={t("currencyPlaceholder")}
                                    aria-invalid={Boolean(fieldErrors.budgetCurrency)}
                                />
                            </div>
                        </div>

                        <div className="grid grid-cols-2 gap-3">
                            <div className="grid gap-1.5">
                                <Label htmlFor="campaign-startAt">{t("startAt")}</Label>
                                <div className="group relative">
                                    <CalendarIcon className={leadIcon} />
                                    <input
                                        id="campaign-startAt"
                                        type="datetime-local"
                                        value={payload.startAt ?? ""}
                                        onChange={(e) =>
                                            setPayload((prev) => ({
                                                ...prev,
                                                startAt: e.target.value || null,
                                            }))
                                        }
                                        className={cn(inputBase, "pl-9 pr-3", fieldErrors.startAt && inputError)}
                                    />
                                </div>
                            </div>
                            <div className="grid gap-1.5">
                                <Label htmlFor="campaign-endAt">{t("endAt")}</Label>
                                <div className="group relative">
                                    <CalendarIcon className={leadIcon} />
                                    <input
                                        id="campaign-endAt"
                                        type="datetime-local"
                                        value={payload.endAt ?? ""}
                                        onChange={(e) =>
                                            setPayload((prev) => ({
                                                ...prev,
                                                endAt: e.target.value || null,
                                            }))
                                        }
                                        className={cn(inputBase, "pl-9 pr-3", fieldErrors.endAt && inputError)}
                                    />
                                </div>
                            </div>
                        </div>
                        {fieldErrors.endAt && <p className="text-sm text-destructive">{fieldErrors.endAt}</p>}
                    </form>
                </div>

                <ResponsiveDialogFooter className="border-t border-border/60 bg-popover px-6 py-4">
                    <ResponsiveDialogClose asChild>
                        <Button type="button" variant="outline" disabled={isCreating}>
                            {t("cancel")}
                        </Button>
                    </ResponsiveDialogClose>
                    <Button
                        type="submit"
                        form="new-campaign-form"
                        variant="brand"
                        disabled={isCreating || hasErrors || isSuccess}
                        className="min-w-24 shadow-sm transition hover:shadow-md"
                    >
                        {isCreating ? (
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
