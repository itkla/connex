"use client";

import { useMemo } from "react";

import { useRegisterActions } from "@/app/hooks/useActions";
import { useRecentRecords } from "@/app/hooks/useRecentRecords";
import { recentRecordHref } from "@/app/lib/recentRecords";
import { savedViewRecordIcon } from "@/app/lib/savedViewLink";
import type { AppAction } from "@/app/lib/actions/types";

const RECENT_RECORD_ACTION_BASE_ORDER = 300;

/**
 * Registers a navigation action for each recently viewed record, so they are reachable from the
 * command palette by name. Each action carries the record's dynamic {@code label} — the generic
 * {@code navigate.recentRecord} key is only a fallback. Rendered inside {@link RecentRecordsProvider}
 * so it re-registers as the recents list changes. Renders nothing.
 */
export default function RecentRecordsActionsBridge(): null {
    const { recents } = useRecentRecords();

    const actions = useMemo<readonly AppAction[]>(
        () => recents.map((entry, index) => ({
            id: `navigate.recent-record:${entry.t}:${entry.id}`,
            group: "navigate",
            labelKey: "navigate.recentRecord",
            label: entry.label,
            icon: savedViewRecordIcon(entry.t),
            order: RECENT_RECORD_ACTION_BASE_ORDER + index,
            execute: (_context, helpers) => {
                helpers.router.push(recentRecordHref(entry.t, entry.id));
            },
        })),
        [recents],
    );

    useRegisterActions(actions);
    return null;
}
