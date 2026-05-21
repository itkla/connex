import type { ReactNode } from 'react';

// test code - move to lib/types.ts or elsewhere later

export type SelectionId = string | number;
export type DisplayMode = 'grid' | 'table';
export type SortValue = string | number | null;

export interface ColumnDef<T> {
    key: string;
    label: string;
    getSortValue?: (item: T) => SortValue;
    render?: (item: T) => ReactNode;
    copyable?: {
        label: string;
        getValue: (item: T) => string | undefined | null;
    };
    widthClass?: string;
}

export interface CardCallbacks<T> {
    onQuickEdit?: (item: T) => void;
    onDelete?: (item: T) => void;
}

export function isDisplayMode(value: unknown): value is DisplayMode {
    return value === 'grid' || value === 'table';
}
