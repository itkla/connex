"use client";

import { useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { Loader2Icon } from "lucide-react";
import { PlusIcon, XMarkIcon } from "@heroicons/react/24/outline";

import {
    Dialog,
    DialogClose,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";
import {
    DialogStatusCover,
    fieldErrorClass,
    fieldInputClass,
    resolveDialogStatus,
} from "@/components/ui/dialog-status-cover";

import { cn } from "@/lib/utils";
import { ApiError, createCustomField, updateCustomField } from "@/app/lib/api";
import { toastError, toastSuccess } from "@/app/lib/toast";
import type {
    CustomFieldDataClassification,
    CustomFieldDefinition,
    CustomFieldEntityType,
    CustomFieldType,
} from "@/app/lib/types";

const FIELD_TYPES: CustomFieldType[] = [
    "text",
    "textarea",
    "number",
    "date",
    "boolean",
    "select",
    "url",
];

const DATA_CLASSIFICATIONS: CustomFieldDataClassification[] = ["standard", "sensitive", "special_care"];

const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];

function slugify(value: string) {
    return value
        .trim()
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, "_")
        .replace(/^[^a-z]+/, "")
        .replace(/_+$/g, "")
        .slice(0, 64);
}

function fallbackKey(value: string) {
    let hash = 0;
    for (let i = 0; i < value.length; i++) {
        hash = (Math.imul(31, hash) + value.charCodeAt(i)) >>> 0;
    }
    return `field_${hash.toString(36)}`;
}

function toKey(value: string) {
    return slugify(value) || fallbackKey(value);
}

type OptionRow = { id: number; key: string; label: string };

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    mode: "create" | "edit";
    entityType: CustomFieldEntityType;
    field?: CustomFieldDefinition | null;
    onSaved: (field: CustomFieldDefinition) => void;
};

export default function CustomFieldDialog({ open, onOpenChange, mode, entityType, field, onSaved }: Props) {
    const [submitting, setSubmitting] = useState(false);

    const handleOpenChange = (next: boolean) => {
        if (!next && submitting) return;
        onOpenChange(next);
    };

    return (
        <Dialog open={open} onOpenChange={handleOpenChange}>
            <DialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                <CustomFieldForm
                    key={`${mode}-${field?.id ?? "new"}-${entityType}`}
                    mode={mode}
                    entityType={entityType}
                    field={field ?? null}
                    onSaved={onSaved}
                    onClose={() => onOpenChange(false)}
                    submitting={submitting}
                    setSubmitting={setSubmitting}
                />
            </DialogContent>
        </Dialog>
    );
}

function CustomFieldForm({
    mode,
    entityType,
    field,
    onSaved,
    onClose,
    submitting,
    setSubmitting,
}: {
    mode: "create" | "edit";
    entityType: CustomFieldEntityType;
    field: CustomFieldDefinition | null;
    onSaved: (field: CustomFieldDefinition) => void;
    onClose: () => void;
    submitting: boolean;
    setSubmitting: React.Dispatch<React.SetStateAction<boolean>>;
}) {
    const t = useTranslations("WorkspaceCustomFields");
    const reduce = useReducedMotion() ?? false;

    const [label, setLabel] = useState(field?.label ?? "");
    const [fieldType, setFieldType] = useState<CustomFieldType>(field?.fieldType ?? "text");
    const [dataClassification, setDataClassification] = useState<CustomFieldDataClassification>(
        field?.dataClassification ?? "standard",
    );
    const [confirmOpen, setConfirmOpen] = useState(false);
    const [required, setRequired] = useState(field?.required ?? false);
    const [options, setOptions] = useState<OptionRow[]>(
        field?.options?.length
            ? field.options.map((option, index) => ({ id: index, key: option.key, label: option.label }))
            : [{ id: 0, key: "", label: "" }],
    );
    const nextOptionId = useRef(field?.options?.length ?? 1);
    const [succeeded, setSucceeded] = useState(false);
    const status = resolveDialogStatus({ isLoading: submitting, isSuccess: succeeded });

    const typeLabels: Record<CustomFieldType, string> = {
        text: t("typeText"),
        textarea: t("typeTextarea"),
        number: t("typeNumber"),
        date: t("typeDate"),
        boolean: t("typeBoolean"),
        select: t("typeSelect"),
        url: t("typeUrl"),
    };

    const classificationLabels: Record<CustomFieldDataClassification, string> = {
        standard: t("dialog.classificationStandard"),
        sensitive: t("dialog.classificationSensitive"),
        special_care: t("dialog.classificationSpecialCare"),
    };

    const trimmedLabel = label.trim();
    const derivedKey = mode === "edit" && field ? field.fieldKey : trimmedLabel ? toKey(trimmedLabel) : "";
    const keyValid = /^[a-z][a-z0-9_]{0,63}$/.test(derivedKey);
    const isSelect = fieldType === "select";

    const submitOptions = options
        .filter((option) => option.label.trim().length > 0)
        .map((option) => ({ key: option.key || toKey(option.label.trim()), label: option.label.trim() }));
    const optionKeys = submitOptions.map((option) => option.key);
    const optionsUnique = new Set(optionKeys).size === optionKeys.length;
    const optionsValid = !isSelect || (submitOptions.length > 0 && optionsUnique);

    const canSubmit = trimmedLabel.length > 0 && keyValid && optionsValid && !submitting && !succeeded;

    const addOption = () =>
        setOptions((prev) => [...prev, { id: nextOptionId.current++, key: "", label: "" }]);
    const updateOption = (id: number, value: string) =>
        setOptions((prev) => prev.map((option) => (option.id === id ? { ...option, label: value } : option)));
    const removeOption = (id: number) =>
        setOptions((prev) => (prev.length <= 1 ? prev : prev.filter((option) => option.id !== id)));

    const isNewlySpecialCare = dataClassification === "special_care" && field?.dataClassification !== "special_care";

    const handleSubmit = async (e: React.SubmitEvent<HTMLFormElement>) => {
        e.preventDefault();
        if (!trimmedLabel || !keyValid) {
            toastError(t("dialog.nameRequired"));
            return;
        }
        if (isSelect && !optionsValid) {
            toastError(submitOptions.length === 0 ? t("dialog.optionsRequired") : t("dialog.optionsDuplicate"));
            return;
        }
        if (isNewlySpecialCare) {
            setConfirmOpen(true);
            return;
        }
        await doSubmit();
    };

    const doSubmit = async () => {
        setConfirmOpen(false);
        setSubmitting(true);
        try {
            const payload = {
                entityType,
                fieldKey: derivedKey,
                label: trimmedLabel,
                fieldType,
                dataClassification,
                required,
                options: isSelect ? submitOptions : null,
                position: field?.position ?? 0,
                archived: field?.archived ?? false,
            };
            const saved =
                mode === "create"
                    ? await createCustomField(payload)
                    : await updateCustomField(field!.id, payload);
            toastSuccess(mode === "create" ? t("created") : t("updated"));
            setSubmitting(false);
            setSucceeded(true);
            setTimeout(() => {
                onSaved(saved);
                onClose();
            }, 900);
        } catch (err) {
            const message =
                err instanceof ApiError ? err.message : err instanceof Error ? err.message : t("saveFailed");
            toastError(message);
            setSubmitting(false);
        }
    };

    return (
        <>
            <DialogStatusCover status={status} />

            <div className="px-6 pb-6">
                <DialogHeader className="ncd-rise -mt-12 mb-5" style={{ animationDelay: "40ms" }}>
                    <DialogTitle className="text-xl font-semibold tracking-tight">
                        {mode === "create" ? t("dialog.createTitle") : t("dialog.editTitle")}
                    </DialogTitle>
                    <DialogDescription>{t("dialog.description")}</DialogDescription>
                </DialogHeader>

                <form onSubmit={handleSubmit} className="grid gap-4">
                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: "90ms" }}>
                        <Label htmlFor="cf-label">{t("dialog.labelLabel")}</Label>
                        <input
                            id="cf-label"
                            value={label}
                            onChange={(e) => setLabel(e.target.value)}
                            placeholder={t("dialog.labelPlaceholder")}
                            className={cn(fieldInputClass, "px-3", trimmedLabel.length > 0 && !keyValid && fieldErrorClass)}
                            maxLength={128}
                            autoFocus
                            required
                        />
                        {trimmedLabel.length > 0 && keyValid && (
                            <p className="font-mono text-xs text-muted-foreground">
                                {t("dialog.keyHint", { key: derivedKey })}
                            </p>
                        )}
                    </div>

                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: "140ms" }}>
                        <Label htmlFor="cf-type">{t("dialog.typeLabel")}</Label>
                        <Select
                            value={fieldType}
                            onValueChange={(value) => setFieldType(value as CustomFieldType)}
                            disabled={mode === "edit"}
                        >
                            <SelectTrigger id="cf-type" className="w-full">
                                <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                                {FIELD_TYPES.map((type) => (
                                    <SelectItem key={type} value={type}>
                                        {typeLabels[type]}
                                    </SelectItem>
                                ))}
                            </SelectContent>
                        </Select>
                    </div>

                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: "165ms" }}>
                        <Label htmlFor="cf-classification">{t("dialog.classificationLabel")}</Label>
                        <Select
                            value={dataClassification}
                            onValueChange={(value) => setDataClassification(value as CustomFieldDataClassification)}
                        >
                            <SelectTrigger id="cf-classification" className="w-full">
                                <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                                {DATA_CLASSIFICATIONS.map((classification) => (
                                    <SelectItem key={classification} value={classification}>
                                        {classificationLabels[classification]}
                                    </SelectItem>
                                ))}
                            </SelectContent>
                        </Select>
                        <p className="text-xs text-muted-foreground">
                            {dataClassification === "special_care"
                                ? t("dialog.classificationSpecialCareHint")
                                : t("dialog.classificationHint")}
                        </p>
                    </div>

                    <AnimatePresence initial={false}>
                        {isSelect && (
                            <motion.div
                                className="grid gap-2 overflow-hidden"
                                initial={reduce ? false : { opacity: 0, height: 0 }}
                                animate={{ opacity: 1, height: "auto" }}
                                exit={reduce ? { opacity: 0 } : { opacity: 0, height: 0 }}
                                transition={{ duration: 0.2, ease: EASE_OUT }}
                            >
                                <Label>{t("dialog.optionsLabel")}</Label>
                                <div className="grid gap-2">
                                    <AnimatePresence initial={false}>
                                        {options.map((option) => (
                                            <motion.div
                                                key={option.id}
                                                layout={!reduce}
                                                initial={reduce ? false : { opacity: 0, y: 8 }}
                                                animate={{ opacity: 1, y: 0 }}
                                                exit={reduce ? { opacity: 0 } : { opacity: 0, y: -8 }}
                                                transition={{ duration: 0.18, ease: EASE_OUT }}
                                                className="flex items-center gap-2"
                                            >
                                                <input
                                                    value={option.label}
                                                    onChange={(e) => updateOption(option.id, e.target.value)}
                                                    placeholder={t("dialog.optionPlaceholder")}
                                                    aria-label={t("dialog.optionPlaceholder")}
                                                    className={cn(fieldInputClass, "px-3")}
                                                    maxLength={128}
                                                />
                                                <button
                                                    type="button"
                                                    onClick={() => removeOption(option.id)}
                                                    disabled={options.length <= 1}
                                                    aria-label={t("dialog.removeOption")}
                                                    className="grid size-9 shrink-0 place-items-center rounded-md text-muted-foreground transition hover:bg-muted hover:text-foreground active:scale-95 disabled:pointer-events-none disabled:opacity-40"
                                                >
                                                    <XMarkIcon className="size-4" />
                                                </button>
                                            </motion.div>
                                        ))}
                                    </AnimatePresence>
                                </div>
                                <button
                                    type="button"
                                    onClick={addOption}
                                    className="inline-flex w-fit items-center gap-1.5 rounded-md px-2 py-1 text-sm font-medium text-brand transition hover:bg-brand/10 active:scale-[0.98]"
                                >
                                    <PlusIcon className="size-4" />
                                    {t("dialog.addOption")}
                                </button>
                            </motion.div>
                        )}
                    </AnimatePresence>

                    <div
                        className="ncd-rise flex items-center justify-between gap-4 rounded-xl bg-muted px-4 py-3 ring-1 ring-border"
                        style={{ animationDelay: "190ms" }}
                    >
                        <div className="space-y-0.5">
                            <Label htmlFor="cf-required" className="cursor-pointer">
                                {t("dialog.requiredLabel")}
                            </Label>
                            <p className="text-xs text-muted-foreground">{t("dialog.requiredHint")}</p>
                        </div>
                        <Switch id="cf-required" checked={required} onCheckedChange={setRequired} />
                    </div>

                    <DialogFooter className="ncd-rise mt-1" style={{ animationDelay: "240ms" }}>
                        <DialogClose asChild>
                            <Button type="button" variant="outline" disabled={submitting}>
                                {t("dialog.cancel")}
                            </Button>
                        </DialogClose>
                        <Button
                            type="submit"
                            disabled={!canSubmit}
                            className="min-w-24 bg-brand text-white shadow-sm transition hover:bg-brand-hover hover:shadow-md"
                        >
                            {submitting ? (
                                <Loader2Icon className="size-4 animate-spin" />
                            ) : mode === "create" ? (
                                t("dialog.create")
                            ) : (
                                t("dialog.save")
                            )}
                        </Button>
                    </DialogFooter>
                </form>
            </div>

            <Dialog open={confirmOpen} onOpenChange={(next) => !submitting && setConfirmOpen(next)}>
                <DialogContent className="sm:max-w-md">
                    <DialogHeader>
                        <DialogTitle>{t("dialog.specialCareConfirmTitle")}</DialogTitle>
                        <DialogDescription>
                            {t("dialog.specialCareConfirmBody", { field: trimmedLabel })}
                        </DialogDescription>
                    </DialogHeader>
                    <DialogFooter>
                        <Button type="button" variant="outline" disabled={submitting} onClick={() => setConfirmOpen(false)}>
                            {t("dialog.cancel")}
                        </Button>
                        <Button type="button" variant="destructive" disabled={submitting} onClick={doSubmit}>
                            {submitting ? <Loader2Icon className="size-4 animate-spin" /> : t("dialog.specialCareConfirm")}
                        </Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>
        </>
    );
}
