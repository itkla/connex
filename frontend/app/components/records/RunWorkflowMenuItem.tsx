"use client";

import { useTranslations } from "next-intl";

import { useActions } from "@/app/hooks/useActions";
import { actionLabel } from "@/app/lib/actions/actionLabels";
import type { ActiveRecordRef } from "@/app/lib/actions/types";
import { DropdownMenuItem } from "@/components/ui/dropdown-menu";

/** Opens the shared workflow launcher with the detail page's exact record scope. */
export default function RunWorkflowMenuItem({ record }: { record: ActiveRecordRef }) {
    const { getAction, isAvailableForRecord, run } = useActions();
    const t = useTranslations("Actions");
    const tMessage = useTranslations();
    const action = getAction("record.run-workflow");
    if (!action || !isAvailableForRecord(action.id, record)) return null;
    const Icon = action.icon;

    return (
        <DropdownMenuItem onSelect={() => void run(action.id, { source: "menu", record })}>
            {Icon ? <Icon className="size-4" /> : null}
            {actionLabel(action, t, tMessage)}
        </DropdownMenuItem>
    );
}
