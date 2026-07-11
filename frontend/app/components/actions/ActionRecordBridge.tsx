"use client";

import { useMemo } from "react";

import { useActionRecord } from "@/app/hooks/useActions";
import type { RecordType } from "@/app/lib/actions/types";
import type { SelectionId } from "@/app/components/records/types";

/**
 * Publishes the record a detail page is showing into the action registry so record-scoped actions
 * (e.g. "copy link to this record") become available while the page is mounted. Renders nothing;
 * usable from a Server Component the same way as `CrumbLabel`.
 */
export default function ActionRecordBridge({
    type,
    id,
    label,
}: {
    type: RecordType;
    id: SelectionId;
    label: string;
}): null {
    const record = useMemo(() => ({ type, id, label }), [type, id, label]);
    useActionRecord(record);
    return null;
}
