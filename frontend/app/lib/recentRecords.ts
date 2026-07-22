import type { SavedViewRecordType } from "@/app/lib/types";
import type { SelectionId } from "@/app/components/records/types";
import { savedViewRecordPath } from "@/app/lib/savedViewLink";

/** The record types a recent entry can point at; aligned with the saved-view record types. */
export type RecentRecordType = SavedViewRecordType;

/** A single most-recently-viewed record as persisted to local storage. */
export type RecentRecord = {
    /** Record type; the short `t` key keeps the serialized payload compact. */
    t: RecentRecordType;
    /** Record identifier. */
    id: SelectionId;
    /** Display label captured at view time. */
    label: string;
    /** Epoch-millis timestamp of the most recent view, used for MRU ordering. */
    ts: number;
};

/** Fields a caller supplies to record a record view; the stored shape is derived from it. */
export type RecentRecordInput = {
    type: RecentRecordType;
    id: SelectionId;
    label: string;
};

/** Current persisted schema version; a mismatch discards the stored payload. */
export const RECENTS_VERSION = 1;

/** Maximum number of recent records retained per user + workspace scope. */
export const RECENTS_CAP = 10;

const RECENT_RECORD_TYPES: readonly RecentRecordType[] = ["company", "person", "deal"];

type StoredRecents = { v: number; items: RecentRecord[] };

/** The local-storage key scoping a user's recent records to a workspace. */
export function recentRecordsStorageKey(
    userId: number | null | undefined,
    workspaceId: number | null | undefined,
): string {
    return `connex:recents:${userId ?? "anon"}:${workspaceId ?? "none"}`;
}

/** The in-app href that opens a recent record's detail page. */
export function recentRecordHref(type: RecentRecordType, id: SelectionId): string {
    return `/records/${savedViewRecordPath(type)}/${id}`;
}

function isRecentRecordType(value: unknown): value is RecentRecordType {
    return typeof value === "string" && (RECENT_RECORD_TYPES as readonly string[]).includes(value);
}

function parseItem(value: unknown): RecentRecord | null {
    if (typeof value !== "object" || value === null) return null;
    const candidate = value as Record<string, unknown>;
    const { t, id, label, ts } = candidate;
    if (!isRecentRecordType(t)) return null;
    if (typeof id !== "string" && typeof id !== "number") return null;
    if (typeof label !== "string" || label.length === 0) return null;
    if (typeof ts !== "number" || !Number.isFinite(ts)) return null;
    return { t, id, label, ts };
}

/**
 * Defensively parses a stored recents payload: drops any malformed or unknown-type entries, and
 * discards the whole payload on a version mismatch so a stale schema never surfaces bad data. Caps the
 * result to {@link RECENTS_CAP}, preserving the stored newest-first order.
 *
 * @param raw the raw local-storage string, or null when nothing is stored
 * @returns the parsed recent records, newest first
 */
export function parseRecents(raw: string | null): RecentRecord[] {
    if (!raw) return [];
    let parsed: unknown;
    try {
        parsed = JSON.parse(raw);
    } catch {
        return [];
    }
    if (typeof parsed !== "object" || parsed === null) return [];
    const store = parsed as Partial<StoredRecents>;
    if (store.v !== RECENTS_VERSION || !Array.isArray(store.items)) return [];
    const items: RecentRecord[] = [];
    for (const entry of store.items) {
        const item = parseItem(entry);
        if (item) items.push(item);
    }
    return items.slice(0, RECENTS_CAP);
}

/** Serializes recent records into the versioned storage envelope, capped to {@link RECENTS_CAP}. */
export function serializeRecents(items: RecentRecord[]): string {
    const store: StoredRecents = { v: RECENTS_VERSION, items: items.slice(0, RECENTS_CAP) };
    return JSON.stringify(store);
}

/**
 * Inserts or refreshes a record at the front of the MRU list: dedupes by `type:id`, refreshes the
 * label and timestamp of an existing entry while moving it to the front, and caps the list to
 * {@link RECENTS_CAP}.
 *
 * @param items the current recent records, newest first
 * @param next the just-viewed record
 * @returns the updated list, newest first
 */
export function upsertRecent(items: RecentRecord[], next: RecentRecord): RecentRecord[] {
    const filtered = items.filter((item) => !(item.t === next.t && String(item.id) === String(next.id)));
    return [next, ...filtered].slice(0, RECENTS_CAP);
}
