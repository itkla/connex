"use client";

import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import { useTranslations } from "next-intl";
import { ChevronDownIcon, PlusIcon } from "@heroicons/react/24/outline";

import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import CustomFieldDialog from "@/app/components/settings/CustomFieldDialog";
import DeleteRecordDialog from "@/app/components/records/DeleteRecordDialog";
import { CustomFieldValueCell, type CustomFieldCellField } from "./CustomFieldValueCell";
import { deleteCustomField, getCustomFields, getEntityCustomFields, getEntityCustomFieldValues } from "@/app/lib/api";
import { toastError, toastSuccess } from "@/app/lib/toast";
import type { ColumnDef } from "@/app/components/records/types";
import type {
    CustomFieldCellValue,
    CustomFieldDefinition,
    CustomFieldEntityType,
    CustomFieldEntry,
    EntityCustomFieldValues,
} from "@/app/lib/types";

function definitionField(def: CustomFieldDefinition): CustomFieldCellField {
    return { definitionId: def.id, fieldType: def.fieldType, options: def.options ?? null, required: def.required, label: def.label };
}

function entryField(entry: CustomFieldEntry): CustomFieldCellField {
    return { definitionId: entry.definitionId, fieldType: entry.fieldType, options: entry.options, required: entry.required, label: entry.label };
}

/**
 * Builds the dynamic custom-field columns for a records table: one editable column per
 * non-archived definition (cells save per-field and update optimistically), plus an
 * "add field" affordance for admins. Admins read the schema from the (gated) catalog;
 * members derive it from a visible record, so everyone sees and edits values.
 */
export function useCustomFieldColumns<T extends { id: number }>(
    entityType: CustomFieldEntityType,
    rows: T[],
): { columns: ColumnDef<T>[]; addColumnSlot: ReactNode } {
    const [fields, setFields] = useState<CustomFieldCellField[]>([]);
    const [definitions, setDefinitions] = useState<CustomFieldDefinition[]>([]);
    const [canManage, setCanManage] = useState(false);
    const [values, setValues] = useState<EntityCustomFieldValues>({});

    const firstId = rows.length > 0 ? rows[0].id : null;

    const loadSchema = useCallback(() => {
        let cancelled = false;
        getCustomFields(entityType)
            .then((defs) => {
                if (cancelled) return;
                const active = defs.filter((def) => !def.archived);
                setCanManage(true);
                setDefinitions(active);
                setFields(active.map(definitionField));
            })
            .catch(() => {
                if (cancelled) return;
                setCanManage(false);
                setDefinitions([]);
                if (firstId == null) {
                    setFields([]);
                    return;
                }
                getEntityCustomFields(entityType, firstId)
                    .then((entries) => {
                        if (!cancelled) setFields(entries.map(entryField));
                    })
                    .catch(() => {});
            });
        return () => {
            cancelled = true;
        };
    }, [entityType, firstId]);

    useEffect(() => loadSchema(), [loadSchema]);

    const rowIdsKey = useMemo(() => rows.map((row) => row.id).sort((a, b) => a - b).join(","), [rows]);
    useEffect(() => {
        const ids = rowIdsKey ? rowIdsKey.split(",").map(Number) : [];
        let cancelled = false;
        getEntityCustomFieldValues(entityType, ids)
            .then((loaded) => {
                if (!cancelled) setValues(loaded);
            })
            .catch(() => {});
        return () => {
            cancelled = true;
        };
    }, [entityType, rowIdsKey]);

    const setCellValue = useCallback((entityId: number, definitionId: number, value: CustomFieldCellValue) => {
        setValues((prev) => ({
            ...prev,
            [entityId]: { ...prev[entityId], [definitionId]: value },
        }));
    }, []);

    const columns = useMemo<ColumnDef<T>[]>(
        () =>
            fields.map((field) => ({
                key: `cf_${field.definitionId}`,
                label: field.label,
                widthClass: "min-w-44",
                renderHeader: canManage
                    ? () => {
                          const def = definitions.find((d) => d.id === field.definitionId);
                          return def ? (
                              <CustomFieldColumnHeader definition={def} entityType={entityType} onChanged={loadSchema} />
                          ) : (
                              <span>{field.label}</span>
                          );
                      }
                    : undefined,
                render: (item: T) => (
                    <CustomFieldValueCell
                        entityType={entityType}
                        entityId={item.id}
                        field={field}
                        value={values[String(item.id)]?.[String(field.definitionId)] ?? null}
                        onChange={(value) => setCellValue(item.id, field.definitionId, value)}
                    />
                ),
            })),
        [fields, definitions, values, canManage, entityType, loadSchema, setCellValue],
    );

    const addColumnSlot = canManage ? <AddCustomFieldColumn entityType={entityType} onCreated={loadSchema} /> : null;

    return { columns, addColumnSlot };
}

function AddCustomFieldColumn({ entityType, onCreated }: { entityType: CustomFieldEntityType; onCreated: () => void }) {
    const t = useTranslations("RecordCustomFields");
    const [open, setOpen] = useState(false);
    return (
        <>
            <button
                type="button"
                onClick={() => setOpen(true)}
                title={t("addColumn")}
                aria-label={t("addColumn")}
                className="inline-flex size-7 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
            >
                <PlusIcon className="size-4" />
            </button>
            <CustomFieldDialog
                open={open}
                onOpenChange={setOpen}
                mode="create"
                entityType={entityType}
                field={null}
                onSaved={() => onCreated()}
            />
        </>
    );
}

function CustomFieldColumnHeader({
    definition,
    entityType,
    onChanged,
}: {
    definition: CustomFieldDefinition;
    entityType: CustomFieldEntityType;
    onChanged: () => void;
}) {
    const t = useTranslations("RecordCustomFields");
    const [editOpen, setEditOpen] = useState(false);
    const [removeOpen, setRemoveOpen] = useState(false);
    const [removing, setRemoving] = useState(false);

    const confirmDelete = async () => {
        setRemoving(true);
        try {
            await deleteCustomField(definition.id);
            toastSuccess(t("columnDeleted"));
            setRemoveOpen(false);
            onChanged();
        } catch {
            toastError(t("saveFailed"));
        } finally {
            setRemoving(false);
        }
    };

    return (
        <>
            <DropdownMenu>
                <DropdownMenuTrigger asChild>
                    <button type="button" className="inline-flex items-center gap-1 transition-colors hover:text-foreground">
                        {definition.label}
                        <ChevronDownIcon className="size-3 opacity-50" aria-hidden="true" />
                    </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="start">
                    <DropdownMenuItem onSelect={() => setEditOpen(true)}>{t("configureColumn")}</DropdownMenuItem>
                    <DropdownMenuItem variant="destructive" onSelect={() => setRemoveOpen(true)}>
                        {t("deleteColumn")}
                    </DropdownMenuItem>
                </DropdownMenuContent>
            </DropdownMenu>
            <CustomFieldDialog
                open={editOpen}
                onOpenChange={setEditOpen}
                mode="edit"
                entityType={entityType}
                field={definition}
                onSaved={() => onChanged()}
            />
            <DeleteRecordDialog
                open={removeOpen}
                onOpenChange={setRemoveOpen}
                selectedIds={new Set([definition.id])}
                selectedItems={[definition]}
                entityLabel={t("fieldLabel")}
                getDisplayName={(field) => field.label}
                isDeleting={removing}
                confirmDelete={confirmDelete}
            />
        </>
    );
}
