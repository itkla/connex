"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";
import { PencilSquareIcon } from "@heroicons/react/24/outline";

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
import { fieldInputClass } from "@/components/ui/dialog-status-cover";
import { cn } from "@/lib/utils";
import { ApiError, getEntityCustomFields, updateEntityCustomFields } from "@/app/lib/api";
import { toastError, toastSuccess } from "@/app/lib/toast";
import type { CustomFieldEntityType, CustomFieldEntry } from "@/app/lib/types";

type DraftValue = string | number | boolean | null;

/**
 * Self-contained card that shows a record's custom-field values and lets a user
 * edit them. Renders nothing when the workspace has no fields for the entity type.
 */
export default function CustomFieldsCard({
    entityType,
    entityId,
    surfaceClass = "bg-muted",
    className,
}: {
    entityType: CustomFieldEntityType;
    entityId: number;
    surfaceClass?: string;
    className?: string;
}) {
    const t = useTranslations("RecordCustomFields");
    const [entries, setEntries] = useState<CustomFieldEntry[] | null>(null);
    const [dialogOpen, setDialogOpen] = useState(false);

    useEffect(() => {
        let cancelled = false;
        getEntityCustomFields(entityType, entityId)
            .then((loaded) => {
                if (!cancelled) setEntries(loaded);
            })
            .catch(() => {
                if (!cancelled) setEntries([]);
            });
        return () => {
            cancelled = true;
        };
    }, [entityType, entityId]);

    if (!entries || entries.length === 0) return null;

    const display = (entry: CustomFieldEntry) => {
        const value = entry.value;
        if (value === null || value === undefined || value === "") return "—";
        if (entry.fieldType === "boolean") return value ? t("yes") : t("no");
        if (entry.fieldType === "select") {
            return entry.options?.find((option) => option.key === value)?.label ?? String(value);
        }
        return String(value);
    };

    return (
        <section className={cn("space-y-3", className)}>
            <div className="flex h-8 items-center justify-between px-6">
                <h2 className="text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase">{t("heading")}</h2>
                <button
                    type="button"
                    onClick={() => setDialogOpen(true)}
                    className="inline-flex items-center gap-1 text-xs font-medium text-muted-foreground transition-colors hover:text-foreground"
                >
                    <PencilSquareIcon className="size-3.5" />
                    {t("edit")}
                </button>
            </div>
            <dl className={cn("divide-y divide-border overflow-hidden rounded-2xl ring-1 ring-border", surfaceClass)}>
                {entries.map((entry) => (
                    <div key={entry.definitionId} className="flex flex-col gap-1 px-6 py-4">
                        <dt className="text-sm text-muted-foreground">{entry.label}</dt>
                        <dd className="wrap-break-word text-base text-foreground">{display(entry)}</dd>
                    </div>
                ))}
            </dl>

            <Dialog
                open={dialogOpen}
                onOpenChange={setDialogOpen}
            >
                <DialogContent className="sm:max-w-lg">
                    {dialogOpen && (
                        <EditForm
                            entityType={entityType}
                            entityId={entityId}
                            entries={entries}
                            onSaved={setEntries}
                            onClose={() => setDialogOpen(false)}
                        />
                    )}
                </DialogContent>
            </Dialog>
        </section>
    );
}

function EditForm({
    entityType,
    entityId,
    entries,
    onSaved,
    onClose,
}: {
    entityType: CustomFieldEntityType;
    entityId: number;
    entries: CustomFieldEntry[];
    onSaved: (entries: CustomFieldEntry[]) => void;
    onClose: () => void;
}) {
    const t = useTranslations("RecordCustomFields");
    const [draft, setDraft] = useState<Record<number, DraftValue>>(() =>
        Object.fromEntries(entries.map((entry) => [entry.definitionId, entry.value])),
    );
    const [saving, setSaving] = useState(false);

    const handleSubmit = async (e: React.SubmitEvent<HTMLFormElement>) => {
        e.preventDefault();
        setSaving(true);
        try {
            const values: Record<number, unknown> = {};
            for (const entry of entries) {
                values[entry.definitionId] = draft[entry.definitionId] ?? "";
            }
            onSaved(await updateEntityCustomFields(entityType, entityId, values));
            toastSuccess(t("saved"));
            onClose();
        } catch (err) {
            toastError(err instanceof ApiError ? err.message : err instanceof Error ? err.message : t("saveFailed"));
        } finally {
            setSaving(false);
        }
    };

    return (
        <>
            <DialogHeader>
                <DialogTitle>{t("heading")}</DialogTitle>
                <DialogDescription>{t("editDescription")}</DialogDescription>
            </DialogHeader>
            <form onSubmit={handleSubmit} className="grid max-h-[60vh] gap-4 overflow-y-auto px-1">
                {entries.map((entry) => (
                    <div key={entry.definitionId} className="grid gap-1.5">
                        <Label htmlFor={`cf-${entry.definitionId}`}>
                            {entry.label}
                            {entry.required && <span className="ml-1 text-destructive">*</span>}
                        </Label>
                        <FieldInput
                            id={`cf-${entry.definitionId}`}
                            entry={entry}
                            value={draft[entry.definitionId] ?? null}
                            onChange={(value) => setDraft((prev) => ({ ...prev, [entry.definitionId]: value }))}
                        />
                    </div>
                ))}
                <DialogFooter className="mt-1">
                    <DialogClose asChild>
                        <Button type="button" variant="outline" disabled={saving}>
                            {t("cancel")}
                        </Button>
                    </DialogClose>
                    <Button type="submit" disabled={saving} className="min-w-24 bg-brand text-white hover:bg-brand-hover">
                        {saving ? <Loader2Icon className="size-4 animate-spin" /> : t("save")}
                    </Button>
                </DialogFooter>
            </form>
        </>
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
    value: DraftValue;
    onChange: (value: DraftValue) => void;
}) {
    const str = value === null || value === undefined ? "" : String(value);
    switch (entry.fieldType) {
        case "boolean":
            return <Switch id={id} checked={value === true} onCheckedChange={onChange} />;
        case "textarea":
            return (
                <textarea
                    id={id}
                    value={str}
                    onChange={(e) => onChange(e.target.value)}
                    className={cn(fieldInputClass, "min-h-20 px-3")}
                    maxLength={2000}
                />
            );
        case "number":
            return (
                <input
                    id={id}
                    type="number"
                    value={str}
                    onChange={(e) => onChange(e.target.value)}
                    className={cn(fieldInputClass, "px-3")}
                />
            );
        case "date":
            return (
                <input
                    id={id}
                    type="date"
                    value={str}
                    onChange={(e) => onChange(e.target.value)}
                    className={cn(fieldInputClass, "px-3")}
                />
            );
        case "url":
            return (
                <input
                    id={id}
                    type="url"
                    value={str}
                    onChange={(e) => onChange(e.target.value)}
                    placeholder="https://"
                    className={cn(fieldInputClass, "px-3")}
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
        default:
            return (
                <input
                    id={id}
                    type="text"
                    value={str}
                    onChange={(e) => onChange(e.target.value)}
                    className={cn(fieldInputClass, "px-3")}
                    maxLength={1000}
                />
            );
    }
}
