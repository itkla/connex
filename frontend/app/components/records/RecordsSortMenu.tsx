'use client';

import { useTranslations } from 'next-intl';
import { ChevronDownIcon, ChevronUpDownIcon, ChevronUpIcon } from '@heroicons/react/24/outline';

import { Button } from '@/components/ui/button';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { sortOptionsFromColumns, type ColumnDef } from './types';

type SortDirection = 'asc' | 'desc';

export default function RecordsSortMenu<T>({
    columns,
    sortKey,
    sortDirection,
    onSortChange,
}: {
    columns: ColumnDef<T>[];
    sortKey: string | null;
    sortDirection: SortDirection;
    onSortChange: (key: string) => void;
}) {
    const t = useTranslations('RecordsRenderView');
    const options = sortOptionsFromColumns(columns);
    if (options.length === 0) return null;
    const active = options.find((o) => o.key === sortKey);

    return (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <Button
                    type="button"
                    variant="ghost"
                    size="page"
                    menu
                    aria-label={t('sortBy')}
                    className="bg-muted px-3 text-xs text-muted-foreground ring-1 ring-border shadow-none hover:text-foreground aria-expanded:text-foreground"
                >
                    <ChevronUpDownIcon className="size-3.5" />
                    <span>{active ? `${t('sortBy')}: ${active.label}` : t('sortBy')}</span>
                </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
                {options.map((opt) => {
                    const isActive = sortKey === opt.key;
                    return (
                        <DropdownMenuItem key={opt.key} onSelect={() => onSortChange(opt.key)}>
                            <span className="flex-1">{opt.label}</span>
                            {isActive &&
                                (sortDirection === 'asc' ? (
                                    <ChevronUpIcon className="size-4 text-brand-dark" />
                                ) : (
                                    <ChevronDownIcon className="size-4 text-brand-dark" />
                                ))}
                        </DropdownMenuItem>
                    );
                })}
            </DropdownMenuContent>
        </DropdownMenu>
    );
}
