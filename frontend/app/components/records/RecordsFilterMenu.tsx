'use client';

import { useMemo } from 'react';
import { useTranslations } from 'next-intl';
import { FunnelIcon, ChevronDownIcon } from '@heroicons/react/24/outline';
import {
    DropdownMenu,
    DropdownMenuTrigger,
    DropdownMenuContent,
    DropdownMenuLabel,
    DropdownMenuSeparator,
    DropdownMenuCheckboxItem,
    DropdownMenuSub,
    DropdownMenuSubTrigger,
    DropdownMenuSubContent,
} from '@/components/ui/dropdown-menu';
import {
    type ColumnDef,
    type FilterState,
    type SelectionId,
    countActiveFilters,
    deriveFilterOptions,
    toggleFilterValue,
} from './types';

interface Props<T extends { id: SelectionId }> {
    columns: ColumnDef<T>[];
    items: T[];
    filterState: FilterState;
    onChange: (next: FilterState) => void;
}

export default function RecordsFilterMenu<T extends { id: SelectionId }>({
    columns,
    items,
    filterState,
    onChange,
}: Props<T>) {
    const t = useTranslations('RecordsFilterMenu');
    const facets = useMemo(() => deriveFilterOptions(columns, items), [columns, items]);
    const activeCount = countActiveFilters(filterState);

    if (facets.length === 0) return null;

    return (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <button
                    type="button"
                    aria-label={t('filterAria')}
                    className={`flex items-center gap-2 rounded-full px-4 py-2 text-sm ring-1 transition ${
                        activeCount > 0
                            ? 'bg-brand-light text-brand ring-brand/20 hover:bg-brand-light/80'
                            : 'bg-neutral-100 text-neutral-700 ring-black/5 hover:bg-neutral-200'
                    }`}
                >
                    <FunnelIcon className={`size-4 ${activeCount > 0 ? 'text-brand' : 'text-neutral-500'}`} />
                    {activeCount > 0 && (
                        <span className="flex h-4 min-w-4 items-center justify-center rounded-full bg-brand px-1 text-xs font-semibold text-white">
                            {activeCount}
                        </span>
                    )}
                    <ChevronDownIcon className={`size-4 ${activeCount > 0 ? 'text-brand' : 'text-neutral-500'}`} />
                </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="min-w-48">
                <DropdownMenuLabel className="flex items-center justify-between gap-4">
                    {t('filterBy')}
                    {activeCount > 0 && (
                        <button
                            type="button"
                            className="text-xs font-normal text-brand hover:underline"
                            onClick={() => onChange({})}
                        >
                            {t('clearAll')}
                        </button>
                    )}
                </DropdownMenuLabel>
                <DropdownMenuSeparator />
                {facets.map((facet) => {
                    const selected = filterState[facet.key] ?? [];
                    return (
                        <DropdownMenuSub key={facet.key}>
                            <DropdownMenuSubTrigger>
                                {facet.label}
                                {selected.length > 0 && (
                                    <span className="ml-auto mr-1 text-xs text-brand">{selected.length}</span>
                                )}
                            </DropdownMenuSubTrigger>
                            <DropdownMenuSubContent className="max-h-72 overflow-y-auto">
                                {facet.options.map((option) => (
                                    <DropdownMenuCheckboxItem
                                        key={option.key}
                                        checked={selected.includes(option.key)}
                                        onSelect={(e) => {
                                            e.preventDefault();
                                            onChange(toggleFilterValue(filterState, facet.key, option.key));
                                        }}
                                    >
                                        {option.label}
                                    </DropdownMenuCheckboxItem>
                                ))}
                            </DropdownMenuSubContent>
                        </DropdownMenuSub>
                    );
                })}
            </DropdownMenuContent>
        </DropdownMenu>
    );
}