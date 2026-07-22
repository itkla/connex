"use client";

import { useEffect, useMemo } from "react";

import { useRecentRecords } from "@/app/hooks/useRecentRecords";
import type { RecentRecordType } from "@/app/lib/recentRecords";
import type { SelectionId } from "@/app/components/records/types";

/**
 * Records a detail page's record into the most-recently-viewed list when the page mounts, so the
 * sidebar and command palette can surface it as a shortcut. Renders nothing; usable from a Server
 * Component the same way as {@code ActionRecordBridge}. Place it after the server fetch so a 404 page
 * never records a view.
 */
export default function RecentRecordBridge({
    type,
    id,
    label,
}: {
    type: RecentRecordType;
    id: SelectionId;
    label: string;
}): null {
    const { record } = useRecentRecords();
    const input = useMemo(() => ({ type, id, label }), [type, id, label]);
    useEffect(() => {
        record(input);
    }, [record, input]);
    return null;
}
