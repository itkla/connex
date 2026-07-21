/** Stable, locale-independent identifiers for every collapsible sidebar section. */
export const SIDEBAR_SECTION_IDS = [
    "pinned-views",
    "recent-records",
    "overview",
    "records",
    "marketing",
    "activity",
    "library",
    "workspace",
    "help",
] as const;

/** A collapsible sidebar section identifier persisted across sessions. */
export type SidebarSectionId = (typeof SIDEBAR_SECTION_IDS)[number];

const SIDEBAR_SECTION_ID_SET: ReadonlySet<string> = new Set(SIDEBAR_SECTION_IDS);
const SIDEBAR_SECTION_STATE_VERSION = 1;

type StoredSidebarSectionState = {
    v: number;
    collapsed: unknown[];
};

function isSidebarSectionId(value: unknown): value is SidebarSectionId {
    return typeof value === "string" && SIDEBAR_SECTION_ID_SET.has(value);
}

/** Returns the local-storage key for one user's sidebar groups in one workspace. */
export function sidebarSectionStorageKey(
    userId: number | null | undefined,
    workspaceId: number | null | undefined,
): string {
    return `connex:sidebar-sections:${userId ?? "anon"}:${workspaceId ?? "none"}`;
}

/** Parses a versioned sidebar payload, dropping malformed, duplicate, and unknown section IDs. */
export function parseCollapsedSidebarSections(raw: string | null): SidebarSectionId[] {
    if (!raw) return [];

    let parsed: unknown;
    try {
        parsed = JSON.parse(raw);
    } catch {
        return [];
    }
    if (typeof parsed !== "object" || parsed === null) return [];

    const candidate = parsed as Partial<StoredSidebarSectionState>;
    if (candidate.v !== SIDEBAR_SECTION_STATE_VERSION || !Array.isArray(candidate.collapsed)) return [];

    const collapsed = new Set<SidebarSectionId>();
    for (const value of candidate.collapsed) {
        if (isSidebarSectionId(value)) collapsed.add(value);
    }
    return SIDEBAR_SECTION_IDS.filter((id) => collapsed.has(id));
}

/** Serializes collapsed sidebar sections in stable navigation order. */
export function serializeCollapsedSidebarSections(collapsed: ReadonlySet<SidebarSectionId>): string {
    const state = {
        v: SIDEBAR_SECTION_STATE_VERSION,
        collapsed: SIDEBAR_SECTION_IDS.filter((id) => collapsed.has(id)),
    };
    return JSON.stringify(state);
}
