import type { ReactNode } from 'react';

// test code - move to lib/types.ts or elsewhere later

export type SelectionId = string | number;

/**
 * What a record row's or card's removal affordance actually does. Contacts and companies are
 * archived rather than deleted (#854), and the archived scope restores instead, so the label and
 * icon are chosen from this rather than always saying delete.
 */
export type RecordRemoveIntent = 'delete' | 'archive' | 'restore';
/** A display mode the user can pick and that is safe to persist or share in the `view` query param. */
export type SelectableDisplayMode = 'grid' | 'table' | 'kanban';

/**
 * Every mode {@link RecordsRenderView} can render, including `list` — the row layout forced below the
 * `md` breakpoint so phones get a task-adaptive list instead of a horizontally scrolling desktop table.
 */
export type DisplayMode = SelectableDisplayMode | 'list';
export type SortValue = string | number | null;

export type FilterFacetValue = string | number;

export interface FilterDef<T> {
    getValue: (item: T) => FilterFacetValue | FilterFacetValue[] | null | undefined;
    formatValue?: (value: FilterFacetValue) => string;
    emptyLabel?: string;
}

export interface ColumnDef<T> {
    key: string;
    label: string;
    getSortValue?: (item: T) => SortValue;
    sortable?: boolean;
    render?: (item: T) => ReactNode;
    renderHeader?: () => ReactNode;
    copyable?: {
        label: string;
        getValue: (item: T) => string | undefined | null;
    };
    /**
     * Makes the cell editable in place: double-click (or keyboard-activate) shows an input that saves the
     * single field via {@link editable.save}, optimistically reflected and reverted on failure. Takes
     * precedence over {@link copyable}; the record identity/name column is intentionally left non-editable
     * so its cell keeps opening the record.
     */
    editable?: {
        getValue: (item: T) => string | undefined | null;
        save: (item: T, next: string) => Promise<void>;
        inputType?: 'text' | 'url' | 'tel';
        validate?: (next: string) => string | null;
        /**
         * Suppresses in-place editing for a single row, returning the localized reason to surface
         * instead. Returning `null` leaves the cell editable. Used where the server owns the value
         * and would reject an edit, so the affordance is withheld rather than failing on commit.
         */
        disabled?: (item: T) => string | null;
    };
    filter?: FilterDef<T>;
    widthClass?: string;
}

export type FilterState = Record<string, string[]>;

/** One choice offered by a sort control: the column to order by and its user-facing label. */
export interface SortOption {
    key: string;
    label: string;
}

/** Whether a column can be ordered by — it must opt in and know how to produce a sort value. */
export function isSortableColumn<T>(column: ColumnDef<T>): boolean {
    return column.sortable !== false && !!column.getSortValue;
}

/**
 * Derives the options a sort control should offer from a column set, so the table headers, the
 * desktop sort menu and the mobile filter sheet always agree on which columns are sortable.
 */
export function sortOptionsFromColumns<T>(columns: ColumnDef<T>[]): SortOption[] {
    return columns.flatMap((column) => (isSortableColumn(column) ? [{ key: column.key, label: column.label }] : []));
}

export interface FilterOption {
    key: string;
    label: string;
}

export interface ColumnFilterFacet {
    key: string;
    label: string;
    options: FilterOption[];
}

export const FILTER_EMPTY = '__empty__';

function optionKeys<T>(filter: FilterDef<T>, item: T): string[] {
    const raw = filter.getValue(item);
    const values = Array.isArray(raw) ? raw : [raw];
    const keys = values
        .filter((v): v is FilterFacetValue => v != null && v !== '')
        .map((v) => String(v));
    return keys.length > 0 ? keys : [FILTER_EMPTY];
}

export function deriveFilterOptions<T>(columns: ColumnDef<T>[], items: T[]): ColumnFilterFacet[] {
    const facets: ColumnFilterFacet[] = [];
    for (const col of columns) {
        if (!col.filter) continue;
        const labels = new Map<string, string>();
        for (const item of items) {
            const raw = col.filter.getValue(item);
            const values = Array.isArray(raw) ? raw : [raw];
            let hasValue = false;
            for (const v of values) {
                if (v == null || v === '') continue;
                hasValue = true;
                const key = String(v);
                if (!labels.has(key)) labels.set(key, col.filter.formatValue ? col.filter.formatValue(v) : key);
            }
            if (!hasValue && col.filter.emptyLabel && !labels.has(FILTER_EMPTY)) {
                labels.set(FILTER_EMPTY, col.filter.emptyLabel);
            }
        }
        if (labels.size === 0) continue;
        const options = Array.from(labels, ([key, label]) => ({ key, label })).sort((a, b) => {
            if (a.key === FILTER_EMPTY) return 1;
            if (b.key === FILTER_EMPTY) return -1;
            return a.label.localeCompare(b.label, undefined, { sensitivity: 'base' });
        });
        facets.push({ key: col.key, label: col.label, options });
    }
    return facets;
}

export function applyRecordFilters<T>(items: T[], columns: ColumnDef<T>[], filterState: FilterState): T[] {
    const active = columns.filter((c) => c.filter && filterState[c.key]?.length);
    if (active.length === 0) return items;
    return items.filter((item) =>
        active.every((col) => {
            const selected = filterState[col.key];
            return optionKeys(col.filter!, item).some((k) => selected.includes(k));
        }),
    );
}

export function countActiveFilters(filterState: FilterState): number {
    return Object.values(filterState).reduce((sum, keys) => sum + keys.length, 0);
}

export function facetChips(
    facets: ColumnFilterFacet[],
    filterState: FilterState,
    onChange: (next: FilterState) => void,
): { id: string; label: string; onRemove: () => void }[] {
    const chips: { id: string; label: string; onRemove: () => void }[] = [];
    for (const facet of facets) {
        for (const key of filterState[facet.key] ?? []) {
            const opt = facet.options.find((o) => o.key === key);
            chips.push({
                id: `${facet.key}:${key}`,
                label: opt ? opt.label : key,
                onRemove: () => onChange(toggleFilterValue(filterState, facet.key, key)),
            });
        }
    }
    return chips;
}

export function toggleFilterValue(state: FilterState, columnKey: string, optionKey: string): FilterState {
    const current = state[columnKey] ?? [];
    const next = current.includes(optionKey)
        ? current.filter((k) => k !== optionKey)
        : [...current, optionKey];
    const result = { ...state, [columnKey]: next };
    if (next.length === 0) delete result[columnKey];
    return result;
}

export interface CardCallbacks<T> {
    onQuickEdit?: (item: T) => void;
    onDelete?: (item: T) => void;
}

/**
 * Narrows a persisted or URL-supplied value to a mode the user may actually choose.
 *
 * `list` is deliberately excluded: it is forced by viewport below the `md` breakpoint rather than
 * picked, so accepting it here would let a phone-shaped session overwrite the desktop preference
 * stored in `localStorage` or shared through the `view` query param.
 */
export function isSelectableDisplayMode(value: unknown): value is SelectableDisplayMode {
    return value === 'grid' || value === 'table' || value === 'kanban';
}
