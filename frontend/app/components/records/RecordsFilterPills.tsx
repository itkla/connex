'use client';

import { useMemo } from 'react';
import { useTranslations } from 'next-intl';
import { MultiSelectFilter } from '@/app/components/filters';
import {
    type ColumnDef,
    type ColumnFilterFacet,
    type FilterState,
    type SelectionId,
    deriveFilterOptions,
    toggleFilterValue,
} from './types';

interface Props<T extends { id: SelectionId }> {
    columns?: ColumnDef<T>[];
    items?: T[];
    facets?: ColumnFilterFacet[];
    filterState: FilterState;
    onChange: (next: FilterState) => void;
}

export default function RecordsFilterPills<T extends { id: SelectionId }>({
    columns,
    items,
    facets: providedFacets,
    filterState,
    onChange,
}: Props<T>) {
    const t = useTranslations('Filters');
    const facets = useMemo(
        () => providedFacets ?? deriveFilterOptions(columns ?? [], items ?? []),
        [providedFacets, columns, items],
    );

    if (facets.length === 0) return null;

    return (
        <>
            {facets.map((facet) => {
                const selected = new Set(filterState[facet.key] ?? []);
                return (
                    <MultiSelectFilter
                        key={facet.key}
                        label={facet.label}
                        ariaLabel={facet.label}
                        options={facet.options.map((o) => ({ value: o.key, label: o.label }))}
                        selected={selected}
                        onToggle={(v) => onChange(toggleFilterValue(filterState, facet.key, v))}
                        onClear={() => {
                            const next = { ...filterState };
                            delete next[facet.key];
                            onChange(next);
                        }}
                        clearLabel={t('clear')}
                        scroll
                    />
                );
            })}
        </>
    );
}
