"use client";

import { useEffect, useImperativeHandle, useState } from "react";
import { useTranslations } from "next-intl";

import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { ApiError, getEntityCustomFields, updateEntityCustomFields } from "@/app/lib/api";
import { toastError } from "@/app/lib/toast";
import type { CustomFieldCellValue, CustomFieldEntityType, CustomFieldEntry } from "@/app/lib/types";

/** Imperative handle a record edit sheet uses to commit custom-field edits on Save. */
export type CustomFieldsEditHandle = {
    hasChanges: () => boolean;
    save: () => Promise<void>;
};

function asText(value: CustomFieldCellValue): string {
    return value === null || value === undefined ? "" : String(value);
}

/**
 * Custom-field editor embedded in a record edit sheet. Holds a draft and persists only
 * the changed fields when the sheet saves (via the exposed {@link CustomFieldsEditHandle});
 * closing the sheet without saving discards the draft. Renders nothing when the workspace
 * has no fields for the entity type.
 */
export function CustomFieldsEditSection({
    entityType,
    entityId,
    ref,
}: {
    entityType: CustomFieldEntityType;
    entityId: number;
    ref?: React.Ref<CustomFieldsEditHandle>;
}) {
    const t = useTranslations("RecordCustomFields");
    const [entries, setEntries] = useState<CustomFieldEntry[]>([]);
    const [draft, setDraft] = useState<Record<number, CustomFieldCellValue>>({});

    useEffect(() => {
        let cancelled = false;
        getEntityCustomFields(entityType, entityId)
            .then((loaded) => {
                if (cancelled) return;
                setEntries(loaded);
                setDraft(Object.fromEntries(loaded.map((entry) => [entry.definitionId, entry.value])));
            })
            .catch(() => {});
        return () => {
            cancelled = true;
        };
    }, [entityType, entityId]);

    useImperativeHandle(
        ref,
        () => {
            const changed = entries.filter((entry) => asText(draft[entry.definitionId]) !== asText(entry.value));
            return {
                hasChanges: () => changed.length > 0,
                save: async () => {
                    if (changed.length === 0) return;
                    const values: Record<number, unknown> = {};
                    for (const entry of changed) {
                        values[entry.definitionId] = draft[entry.definitionId] ?? "";
                    }
                    try {
                        const saved = await updateEntityCustomFields(entityType, entityId, values);
                        setEntries(saved);
                        setDraft(Object.fromEntries(saved.map((entry) => [entry.definitionId, entry.value])));
                    } catch (err) {
                        toastError(err instanceof ApiError ? err.message : t("saveFailed"));
                        throw err;
                    }
                },
            };
        },
        [entries, draft, entityType, entityId, t],
    );

    if (entries.length === 0) return null;

    return (
        <div className="border-t pt-6">
            <h3 className="mb-3 text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase">{t("heading")}</h3>
            <div className="grid gap-3">
                {entries.map((entry) => (
                    <div key={entry.definitionId} className="grid gap-1.5">
                        <Label htmlFor={`cfe-${entry.definitionId}`}>
                            {entry.label}
                            {entry.required && <span className="ml-1 text-destructive">*</span>}
                        </Label>
                        <FieldInput
                            id={`cfe-${entry.definitionId}`}
                            entry={entry}
                            value={draft[entry.definitionId] ?? null}
                            onChange={(value) => setDraft((prev) => ({ ...prev, [entry.definitionId]: value }))}
                        />
                    </div>
                ))}
            </div>
        </div>
    );
}

function FieldInput({
    id,
    entry,
    value,
    onChange,
}: {
    id: string;
    entry: CustomFieldEntry;
    value: CustomFieldCellValue;
    onChange: (value: CustomFieldCellValue) => void;
}) {
    const str = asText(value);
    switch (entry.fieldType) {
        case "boolean":
            return <Switch id={id} checked={value === true} onCheckedChange={onChange} aria-label={entry.label} />;
        case "textarea":
            return (
                <Textarea
                    id={id}
                    value={str}
                    onChange={(e) => onChange(e.target.value)}
                    className="min-h-20"
                    maxLength={2000}
                />
            );
        case "select":
            return (
                <Select value={str || undefined} onValueChange={onChange}>
                    <SelectTrigger id={id} className="w-full">
                        <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                        {entry.options?.map((option) => (
                            <SelectItem key={option.key} value={option.key}>
                                {option.label}
                            </SelectItem>
                        ))}
                    </SelectContent>
                </Select>
            );
        default: {
            const inputType =
                entry.fieldType === "number" ? "number" : entry.fieldType === "date" ? "date" : entry.fieldType === "url" ? "url" : "text";
            return (
                <Input
                    id={id}
                    type={inputType}
                    value={str}
                    onChange={(e) => onChange(e.target.value)}
                    placeholder={entry.fieldType === "url" ? "https://" : undefined}
                    maxLength={entry.fieldType === "url" ? 2048 : 1000}
                />
            );
        }
    }
}
