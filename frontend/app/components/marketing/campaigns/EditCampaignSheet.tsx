"use client";

import { type Dispatch, type SetStateAction, useState } from "react";
import { useTranslations } from "next-intl";
import { MegaphoneIcon } from "@heroicons/react/24/outline";

import {
    QuickEditField,
    QuickEditSheetShell,
} from "@/app/components/records/quick-edit/QuickEditSheetShell";
import { Input } from "@/components/ui/input";
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";
import { quickEditErrorId, useFieldErrors } from "@/app/hooks/useFieldErrors";
import { isFieldError } from "@/app/lib/api";
import { type CampaignPayload, type CampaignStatus } from "@/app/lib/types";

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
    isSubmitting: boolean;
    statusLocked?: boolean;
    onSubmit: () => void | Promise<void>;
};

/**
 * Campaign settings edit, in the D5 edit archetype: a right drawer over the builder, so the
 * campaign the user is editing stays visible behind it. The parent owns the payload and the submit,
 * which re-throws field errors so this form surfaces them per field instead of as a toast.
 *
 * The fields here are exactly the ones `PUT /api/campaigns/{id}` rewrites, so an edit that seeds
 * the payload from the campaign round-trips without dropping anything the form does not show.
 * @param statusLocked whether the campaign has reached a terminal status, which the backend refuses
 * to transition out of; the control is shown but inert rather than offering a doomed choice
 */
export default function EditCampaignSheet({
    open,
    onOpenChange,
    payload,
    setPayload,
    isSubmitting,
    statusLocked = false,
    onSubmit,
}: Props) {
    const t = useTranslations("CampaignsEditSheet");
    const statusT = useTranslations("CampaignStatus");
    const { fieldErrors, reset: resetFieldErrors, clearError, captureFieldErrors } = useFieldErrors();

    const [wasOpen, setWasOpen] = useState(open);
    if (open !== wasOpen) {
        setWasOpen(open);
        if (open) resetFieldErrors();
    }

    const handleSave = () => {
        resetFieldErrors();
        void (async () => {
            try {
                await onSubmit();
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
        })();
    };

    return (
        <QuickEditSheetShell
            open={open}
            onOpenChange={onOpenChange}
            icon={<MegaphoneIcon />}
            title={t("title")}
            description={t("description")}
            count={1}
            isSaving={isSubmitting}
            onSave={handleSave}
            saveLabel={isSubmitting ? t("saving") : t("submit")}
            cancelLabel={t("cancel")}
            dirtySnapshot={payload}
        >
            <QuickEditField
                label={t("name")}
                htmlFor="campaign-name"
                required
                error={fieldErrors.name}
            >
                <Input
                    id="campaign-name"
                    type="text"
                    value={payload.name}
                    onChange={(event) => {
                        setPayload((prev) => ({ ...prev, name: event.target.value }));
                        clearError("name");
                    }}
                    aria-invalid={Boolean(fieldErrors.name)}
                    aria-describedby={fieldErrors.name ? quickEditErrorId("campaign-name") : undefined}
                    required
                />
            </QuickEditField>

            <QuickEditField
                label={t("objective")}
                htmlFor="campaign-objective"
                error={fieldErrors.objective}
            >
                <Input
                    id="campaign-objective"
                    type="text"
                    value={payload.objective ?? ""}
                    onChange={(event) => {
                        setPayload((prev) => ({ ...prev, objective: event.target.value }));
                        clearError("objective");
                    }}
                    placeholder={t("objectivePlaceholder")}
                    aria-invalid={Boolean(fieldErrors.objective)}
                    aria-describedby={
                        fieldErrors.objective ? quickEditErrorId("campaign-objective") : undefined
                    }
                />
            </QuickEditField>

            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <QuickEditField label={t("type")} htmlFor="campaign-type" required error={fieldErrors.type}>
                    <Input
                        id="campaign-type"
                        type="text"
                        value={payload.type}
                        onChange={(event) => {
                            setPayload((prev) => ({ ...prev, type: event.target.value }));
                            clearError("type");
                        }}
                        aria-invalid={Boolean(fieldErrors.type)}
                        aria-describedby={fieldErrors.type ? quickEditErrorId("campaign-type") : undefined}
                        required
                    />
                </QuickEditField>
                <QuickEditField label={t("status")} htmlFor="campaign-status">
                    <Select
                        value={payload.status ?? "draft"}
                        disabled={statusLocked}
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
                    {statusLocked ? (
                        <p className="text-xs text-muted-foreground">{t("statusLockedHint")}</p>
                    ) : null}
                </QuickEditField>
            </div>

            <div className="grid grid-cols-1 gap-3 sm:grid-cols-[1fr_7rem]">
                <QuickEditField
                    label={t("budget")}
                    htmlFor="campaign-budgetAmount"
                    error={fieldErrors.budgetAmount}
                >
                    <Input
                        id="campaign-budgetAmount"
                        type="number"
                        min="0"
                        step="0.01"
                        value={payload.budgetAmount ?? ""}
                        onChange={(event) => {
                            const raw = event.target.value;
                            setPayload((prev) => ({
                                ...prev,
                                budgetAmount: raw === "" ? null : Number(raw),
                            }));
                            clearError("budgetAmount");
                        }}
                        placeholder={t("budgetAmountPlaceholder")}
                        aria-invalid={Boolean(fieldErrors.budgetAmount)}
                        aria-describedby={
                            fieldErrors.budgetAmount ? quickEditErrorId("campaign-budgetAmount") : undefined
                        }
                    />
                </QuickEditField>
                <QuickEditField
                    label={t("currency")}
                    htmlFor="campaign-budgetCurrency"
                    error={fieldErrors.budgetCurrency}
                >
                    <Input
                        id="campaign-budgetCurrency"
                        type="text"
                        inputMode="text"
                        maxLength={3}
                        className="uppercase"
                        value={payload.budgetCurrency ?? ""}
                        onChange={(event) => {
                            setPayload((prev) => ({
                                ...prev,
                                budgetCurrency: event.target.value.toUpperCase() || null,
                            }));
                            clearError("budgetCurrency");
                        }}
                        placeholder={t("currencyPlaceholder")}
                        aria-invalid={Boolean(fieldErrors.budgetCurrency)}
                        aria-describedby={
                            fieldErrors.budgetCurrency
                                ? quickEditErrorId("campaign-budgetCurrency")
                                : undefined
                        }
                    />
                </QuickEditField>
            </div>

            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <QuickEditField label={t("startAt")} htmlFor="campaign-startAt" error={fieldErrors.startAt}>
                    <Input
                        id="campaign-startAt"
                        type="datetime-local"
                        value={payload.startAt ?? ""}
                        onChange={(event) => {
                            setPayload((prev) => ({ ...prev, startAt: event.target.value || null }));
                            clearError("startAt");
                        }}
                        aria-invalid={Boolean(fieldErrors.startAt)}
                    />
                </QuickEditField>
                <QuickEditField label={t("endAt")} htmlFor="campaign-endAt" error={fieldErrors.endAt}>
                    <Input
                        id="campaign-endAt"
                        type="datetime-local"
                        value={payload.endAt ?? ""}
                        onChange={(event) => {
                            setPayload((prev) => ({ ...prev, endAt: event.target.value || null }));
                            clearError("endAt");
                        }}
                        aria-invalid={Boolean(fieldErrors.endAt)}
                        aria-describedby={fieldErrors.endAt ? quickEditErrorId("campaign-endAt") : undefined}
                    />
                </QuickEditField>
            </div>
        </QuickEditSheetShell>
    );
}
