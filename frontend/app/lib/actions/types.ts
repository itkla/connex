import type { ComponentType } from "react";
import type { useRouter } from "next/navigation";

import type { User, Workspace } from "@/app/lib/types";
import type { SelectionId } from "@/app/components/records/types";

/**
 * Canonical, ordered set of action groups. The array order is the primary sort rank when actions
 * are listed, so every surface renders groups in the same predictable sequence.
 */
export const ACTION_GROUPS = ["create", "navigate", "record", "workspace", "utility"] as const;

/** A functional grouping used for ordering and section headings across every action surface. */
export type ActionGroup = (typeof ACTION_GROUPS)[number];

/**
 * A stable, locale-independent action identifier in `group.verb-noun` form, e.g. `navigate.dashboard`.
 * IDs must never be derived from localized labels so usage history and favorites can key off them.
 */
export type ActionId = string;

/** Where an invocation originated; retained for future usage-history and favorites. */
export type ActionSource = "menu" | "shortcut" | "palette" | "empty-state" | "programmatic";

/** The record types an action can be scoped to. */
export const RECORD_TYPES = ["company", "person", "deal", "task", "activity", "note"] as const;

/** A record type an action or contributor hook can reference. */
export type RecordType = (typeof RECORD_TYPES)[number];

/** A reference to the record the current page is focused on. */
export type ActiveRecordRef = {
    type: RecordType;
    id: SelectionId;
    label: string;
};

/** A reference to the current multi-selection in a list surface. */
export type ActiveSelection = {
    type: RecordType;
    ids: ReadonlySet<SelectionId>;
};

/**
 * Coarse permission predicate. Today it is backed by a role-derived policy table; the signature is
 * the contract, so it keeps working unchanged when a real effective-permissions endpoint replaces it.
 */
export type PermissionCheck = (permission: string) => boolean;

/**
 * The read-only snapshot passed to {@link AppAction.isAvailable} and {@link AppAction.execute}. It is
 * pure data with no side-effect capability so availability predicates stay deterministic.
 */
export type ActionContext = {
    workspace: Workspace | null;
    user: User | null;
    route: { pathname: string };
    locale: string;
    record: ActiveRecordRef | null;
    selection: ActiveSelection | null;
    can: PermissionCheck;
};

/**
 * Context-aware prefills carried into a create overlay. Every field is optional and always remains
 * user-editable; the shell never silently carries defaults across workspaces. Ids pair with a label so
 * a form can render a selected option without first fetching the full reference list.
 */
export type CreateDefaults = {
    companyId?: number;
    companyLabel?: string;
    personId?: number;
    personLabel?: string;
    dealId?: number;
    dealLabel?: string;
    pipelineId?: number;
};

/** Values typed into a quick-create form, carried into the full dialog when the user asks for more detail. */
export type TaskDraft = {
    description?: string;
    dueDate?: string;
    assigneeId?: number | null;
    personId?: number | null;
    dealId?: number | null;
};
export type NoteDraft = {
    content?: string;
    personId?: number | null;
    dealId?: number | null;
};
export type ActivityDraft = { type?: string; subject?: string; notes?: string };

/** A shell-owned overlay an action can open. The union is closed; later work extends it additively. */
export type OverlayRequest =
    | { kind: "create-task"; defaults?: CreateDefaults; draft?: TaskDraft; restoredDraftGeneration?: number }
    | { kind: "create-note"; defaults?: CreateDefaults; draft?: NoteDraft; restoredDraftGeneration?: number }
    | {
        kind: "create-activity";
        defaults?: CreateDefaults;
        draft?: ActivityDraft;
        restoredDraftGeneration?: number;
        requireRelationshipTarget?: boolean;
    }
    | { kind: "create-company"; defaults?: CreateDefaults }
    | { kind: "create-person"; defaults?: CreateDefaults }
    | { kind: "create-deal"; defaults?: CreateDefaults }
    | { kind: "import-companies" }
    | { kind: "import-contacts" }
    | { kind: "import-deals" };

/**
 * The imperative capabilities handed to {@link AppAction.execute}. Kept out of {@link ActionContext}
 * so availability predicates cannot cause side effects.
 */
export type ActionHelpers = {
    router: ReturnType<typeof useRouter>;
    openOverlay: (request: OverlayRequest) => void;
    closeOverlay: () => void;
    /** Translates a key within the `Actions` next-intl namespace. */
    translate: (key: string, values?: Record<string, string | number>) => string;
};

/** A component rendered as an action's leading icon; matches the sidebar's icon contract. */
export type ActionIcon = ComponentType<{ className?: string }>;

/**
 * A single registered action. One definition powers every surface (menus, palette, shortcuts, empty
 * states) so behavior never diverges. `execute` is invoked lazily, so registering an action neither
 * mounts its form nor fetches any data until the user runs it.
 */
export type AppAction = {
    /** Stable identifier; see {@link ActionId}. */
    id: ActionId;
    /** Functional grouping used for ordering and section headings. */
    group: ActionGroup;
    /** Key within the `Actions` next-intl namespace that resolves the display label. */
    labelKey: string;
    /** Key within the `Actions` namespace resolving supporting text shown on richer action surfaces. */
    descriptionKey?: string;
    /**
     * A resolved display label used verbatim when present, taking precedence over {@link labelKey}.
     * For dynamic actions whose label is data (e.g. a pinned saved view's name) and so cannot be a
     * translation key; static actions omit it and are localized via {@link labelKey}.
     */
    label?: string;
    /** Locale-neutral search aliases (e.g. `csv`, `kanban`). */
    keywords?: readonly string[];
    /** Key within the `Actions` namespace resolving a comma-separated, per-locale alias list. */
    keywordsKey?: string;
    /**
     * A single canonical chord (e.g. `mod+alt+t`). Metadata only for now — normalized and checked for
     * conflicts at registration, but not yet dispatched; the shortcut dispatcher lands with the palette.
     */
    shortcut?: string;
    /** Leading icon. */
    icon?: ActionIcon;
    /** Sort weight within the group; defaults to {@link DEFAULT_ACTION_ORDER}. Ties break by `id`. */
    order?: number;
    /** When omitted the action is always available; otherwise it is hidden while this returns false. */
    isAvailable?: (context: ActionContext) => boolean;
    /** Performs the action. Side effects go through {@link ActionHelpers}; the snapshot is read-only. */
    execute: (context: ActionContext, helpers: ActionHelpers) => void | Promise<void>;
};

/** The outcome of {@link ActionsContextValue.run}. The call never rejects. */
export type ActionRunResult =
    | { status: "completed" }
    | { status: "skipped"; reason: "pending" | "unavailable" | "unknown-id" }
    | { status: "failed"; error: unknown };

/** The value exposed by {@link useActions} to every consuming surface. */
export type ActionsContextValue = {
    /** All live registrations in canonical order (group rank, then order, then id), deduped by id. */
    actions: readonly AppAction[];
    /** The current context snapshot; consumers re-render when it changes. */
    context: ActionContext;
    /** Ids currently mid-execute; drive per-item spinners and disabled state. */
    pendingIds: ReadonlySet<ActionId>;
    /**
     * Runs an action by id: rejects re-entrancy, tracks pending, and toasts unhandled failures. Pass
     * `record` to run the action against a specific record (e.g. a list row) instead of the page's
     * published record slot; availability and execution both see the override for that one invocation.
     */
    run: (
        id: ActionId,
        options?: { source?: ActionSource; record?: ActiveRecordRef | null },
    ) => Promise<ActionRunResult>;
    /** Resolves a live action by id. */
    getAction: (id: ActionId) => AppAction | undefined;
    /**
     * Whether an action is available for a specific record without publishing it to the shared slot.
     * Powers per-row menus, which must reflect the row's record, not the page's focused one.
     */
    isAvailableForRecord: (id: ActionId, record: ActiveRecordRef) => boolean;
    /** Opens a shell-owned overlay (e.g. a create form). */
    openOverlay: (request: OverlayRequest) => void;
};

/** Default {@link AppAction.order} when none is supplied. */
export const DEFAULT_ACTION_ORDER = 100;

/**
 * Deterministic ordering used everywhere actions are listed: group rank, then explicit `order`, then
 * `id`. The final tiebreak is the locale-independent id (not the resolved label) so the canonical
 * order is identical across languages.
 *
 * @param a - the first action
 * @param b - the second action
 * @returns a negative, zero, or positive sort delta
 */
export function compareActions(a: AppAction, b: AppAction): number {
    const groupDelta = ACTION_GROUPS.indexOf(a.group) - ACTION_GROUPS.indexOf(b.group);
    if (groupDelta !== 0) return groupDelta;
    const orderDelta = (a.order ?? DEFAULT_ACTION_ORDER) - (b.order ?? DEFAULT_ACTION_ORDER);
    if (orderDelta !== 0) return orderDelta;
    return a.id.localeCompare(b.id);
}
