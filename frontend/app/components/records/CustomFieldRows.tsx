"use client";

import { useState } from "react";

import { CustomFieldValueCell } from "./CustomFieldValueCell";
import type { CustomFieldCellValue, CustomFieldEntityType, CustomFieldEntry } from "@/app/lib/types";

/**
 * A record's custom-field values rendered as rows inside its Information list. Each row is
 * editable in place (double-click, or toggle a boolean); values persist per-field and update
 * optimistically. Renders nothing when the workspace has no fields for the entity type.
 */
export default function CustomFieldRows({
    entityType,
    entityId,
    initialEntries,
}: {
    entityType: CustomFieldEntityType;
    entityId: number;
    initialEntries: CustomFieldEntry[];
}) {
    const [entries, setEntries] = useState(initialEntries);

    if (entries.length === 0) return null;

    const setValue = (definitionId: number, value: CustomFieldCellValue) =>
        setEntries((prev) => prev.map((entry) => (entry.definitionId === definitionId ? { ...entry, value } : entry)));

    return (
        <>
            {entries.map((entry) => (
                <div key={entry.definitionId} className="flex flex-col gap-1 px-6 py-4">
                    <dt className="text-sm text-muted-foreground">
                        {entry.label}
                        {entry.required && <span className="ml-1 text-destructive">*</span>}
                    </dt>
                    <dd className="text-base">
                        <CustomFieldValueCell
                            entityType={entityType}
                            entityId={entityId}
                            field={entry}
                            value={entry.value}
                            onChange={(value) => setValue(entry.definitionId, value)}
                        />
                    </dd>
                </div>
            ))}
        </>
    );
}
