"use client";

import { useEffect, useRef, useState } from "react";
import { useLocale, useTranslations } from "next-intl";

import { Switch } from "@/components/ui/switch";
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";
import { fieldInputClass } from "@/components/ui/dialog-status-cover";
import { cn } from "@/lib/utils";
import { formatDate } from "@/app/lib/utils";
import { ApiError, updateEntityCustomField } from "@/app/lib/api";
import { toastError } from "@/app/lib/toast";
import type {
    CustomFieldCellValue,
    CustomFieldEntityType,
    CustomFieldOption,
    CustomFieldType,
} from "@/app/lib/types";

/** The slice of a definition that {@link CustomFieldValueCell} needs to render and edit a value. */
export type CustomFieldCellField = {
    definitionId: number;
    fieldType: CustomFieldType;
    options: CustomFieldOption[] | null;
    required: boolean;
    label: string;
};

/**
 * One custom-field value, displayed and editable in place. Double-click (or toggle, for a
 * boolean) edits; the change is saved per-field and optimistically reflected through
 * {@code onChange}, reverting on failure. Shared by the record Information list and the
 * records-table cells, so clicks never bubble to a surrounding row.
 */
export function CustomFieldValueCell({
    entityType,
    entityId,
    field,
    value,
    onChange,
    align = "left",
}: {
    entityType: CustomFieldEntityType;
    entityId: number;
    field: CustomFieldCellField;
    value: CustomFieldCellValue;
    onChange: (value: CustomFieldCellValue) => void;
    align?: "left" | "right";
}) {
    const t = useTranslations("RecordCustomFields");
    const locale = useLocale();
    const [editing, setEditing] = useState(false);
    const [saving, setSaving] = useState(false);

    const commit = async (raw: CustomFieldCellValue) => {
        setEditing(false);
        if (raw === value) return;
        const previous = value;
        onChange(raw);
        setSaving(true);
        try {
            await updateEntityCustomField(entityType, entityId, field.definitionId, raw ?? "");
        } catch (err) {
            onChange(previous);
            toastError(err instanceof ApiError ? err.message : t("saveFailed"));
        } finally {
            setSaving(false);
        }
    };

    const stop = (e: React.SyntheticEvent) => e.stopPropagation();

    if (field.fieldType === "boolean") {
        return (
            <span onClick={stop} className="inline-flex">
                <Switch
                    checked={value === true}
                    disabled={saving}
                    onCheckedChange={(next) => commit(next)}
                    aria-label={field.label}
                />
            </span>
        );
    }

    if (editing) {
        return (
            <span onClick={stop} className="block">
                <CustomFieldInput field={field} value={value} onCommit={commit} onCancel={() => setEditing(false)} />
            </span>
        );
    }

    const display = displayValue(field, value, locale);
    return (
        <button
            type="button"
            onClick={stop}
            onDoubleClick={() => setEditing(true)}
            title={t("doubleClickToEdit")}
            className={cn(
                "-mx-1.5 block w-[calc(100%+0.75rem)] cursor-text rounded-md px-1.5 py-0.5 transition-colors hover:bg-accent/50",
                align === "right" ? "text-right" : "text-left",
            )}
        >
            {display === null ? (
                <span className="text-muted-foreground/50">—</span>
            ) : (
                <span className="wrap-break-word text-foreground">{display}</span>
            )}
        </button>
    );
}

function CustomFieldInput({
    field,
    value,
    onCommit,
    onCancel,
}: {
    field: CustomFieldCellField;
    value: CustomFieldCellValue;
    onCommit: (value: CustomFieldCellValue) => void;
    onCancel: () => void;
}) {
    const [draft, setDraft] = useState(value === null || value === undefined ? "" : String(value));
    const inputRef = useRef<HTMLInputElement>(null);
    const textareaRef = useRef<HTMLTextAreaElement>(null);
    const doneRef = useRef(false);

    useEffect(() => {
        (inputRef.current ?? textareaRef.current)?.focus();
    }, []);

    const finish = (action: () => void) => {
        if (doneRef.current) return;
        doneRef.current = true;
        action();
    };

    if (field.fieldType === "select") {
        return (
            <Select
                defaultOpen
                value={draft || undefined}
                onValueChange={(next) => finish(() => onCommit(next))}
                onOpenChange={(open) => {
                    if (!open) finish(onCancel);
                }}
            >
                <SelectTrigger className="h-8 w-full">
                    <SelectValue />
                </SelectTrigger>
                <SelectContent>
                    {field.options?.map((option) => (
                        <SelectItem key={option.key} value={option.key}>
                            {option.label}
                        </SelectItem>
                    ))}
                </SelectContent>
            </Select>
        );
    }

    const onKeyDown = (e: React.KeyboardEvent) => {
        if (e.key === "Enter" && field.fieldType !== "textarea") {
            e.preventDefault();
            finish(() => onCommit(draft));
        } else if (e.key === "Escape") {
            e.preventDefault();
            finish(onCancel);
        }
    };

    if (field.fieldType === "textarea") {
        return (
            <textarea
                ref={textareaRef}
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                onBlur={() => finish(() => onCommit(draft))}
                onKeyDown={onKeyDown}
                rows={3}
                maxLength={2000}
                className={cn(fieldInputClass, "px-2 py-1 text-sm")}
            />
        );
    }

    const inputType =
        field.fieldType === "number" ? "number" : field.fieldType === "date" ? "date" : field.fieldType === "url" ? "url" : "text";
    return (
        <input
            ref={inputRef}
            type={inputType}
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onBlur={() => finish(() => onCommit(draft))}
            onKeyDown={onKeyDown}
            placeholder={field.fieldType === "url" ? "https://" : undefined}
            maxLength={field.fieldType === "url" ? 2048 : 1000}
            className={cn(fieldInputClass, "h-8 px-2 text-sm")}
        />
    );
}

function displayValue(field: CustomFieldCellField, value: CustomFieldCellValue, locale: string): React.ReactNode {
    if (value === null || value === undefined || value === "") return null;
    switch (field.fieldType) {
        case "select":
            return field.options?.find((option) => option.key === value)?.label ?? String(value);
        case "date":
            return formatDate(String(value), locale);
        default:
            return String(value);
    }
}
