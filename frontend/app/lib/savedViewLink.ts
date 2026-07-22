import type { ComponentType } from "react";
import { BriefcaseIcon, BuildingOffice2Icon, UsersIcon } from "@heroicons/react/24/outline";

import type { SavedView, SavedViewRecordType } from "@/app/lib/types";

const RECORD_PATHS: Record<SavedViewRecordType, string> = {
    company: "companies",
    person: "contacts",
    deal: "deals",
};

const RECORD_ICONS: Record<SavedViewRecordType, ComponentType<{ className?: string }>> = {
    company: BuildingOffice2Icon,
    person: UsersIcon,
    deal: BriefcaseIcon,
};

/** The records-list URL segment for a saved-view record type (e.g. {@code person} → {@code contacts}). */
export function savedViewRecordPath(recordType: SavedViewRecordType): string {
    return RECORD_PATHS[recordType];
}

/** The sidebar/palette icon that represents a saved view's record type. */
export function savedViewRecordIcon(recordType: SavedViewRecordType): ComponentType<{ className?: string }> {
    return RECORD_ICONS[recordType];
}

/** The stable `<workspaceId>:<id>` pointer that identifies a saved view in the URL, pins, and palette. */
export function savedViewToken(view: Pick<SavedView, "workspaceId" | "id">): string {
    return `${view.workspaceId}:${view.id}`;
}

/** The in-app href that opens a records list with a saved view applied via its `?sv=` pointer. */
export function savedViewHref(view: SavedView): string {
    return `/records/${savedViewRecordPath(view.recordType)}?sv=${savedViewToken(view)}`;
}

/**
 * Parses a `<workspaceId>:<id>` saved-view pointer, returning null for any malformed or non-positive
 * value so a crafted `?sv=` can never drive a lookup with a bad id.
 */
export function parseSavedViewToken(sv: string): { workspaceId: number; id: number } | null {
    const [rawWorkspace, rawId] = sv.split(":");
    const workspaceId = Number(rawWorkspace);
    const id = Number(rawId);
    if (!Number.isInteger(workspaceId) || !Number.isInteger(id) || workspaceId <= 0 || id <= 0) return null;
    return { workspaceId, id };
}
