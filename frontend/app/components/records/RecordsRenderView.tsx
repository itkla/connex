'use client';

import { useMemo, useState, type ReactNode } from 'react';
import { useRouter } from 'next/navigation';
import { ChevronDownIcon, ChevronUpIcon, ChevronUpDownIcon } from '@heroicons/react/24/outline';
import { Checkbox } from '@/components/ui/checkbox';
import { toast } from 'sonner';
import { useTranslations } from 'next-intl';

import { copyToClipboard } from '@/app/lib/utils';
import { type ColumnDef, type CardCallbacks, type DisplayMode, type SelectionId } from './types';

type SortDirection = 'asc' | 'desc';

interface Props<T extends { id: SelectionId; name?: string }> {
    data: T[];
    columns: ColumnDef<T>[];
    renderCard: (item: T, callbacks: CardCallbacks<T>) => ReactNode;
    renderAvatar?: (item: T) => ReactNode;
    detailPath?: (item: T) => string;
    onRowClick?: (item: T) => void;
    displayMode: DisplayMode;
    selectedIds: Set<SelectionId>;
    onSelectedIdsChange: (ids: Set<SelectionId>) => void;
    onQuickEdit?: (item: T) => void;
    onDelete?: (item: T) => void;
    gridClassName?: string;
    entityLabel: string;
}

export default function RecordsRenderView<T extends { id: SelectionId; name?: string }>({
    data,
    columns,
    renderCard,
    renderAvatar,
    detailPath,
    onRowClick,
    displayMode,
    selectedIds,
    onSelectedIdsChange,
    onQuickEdit,
    onDelete,
    gridClassName = 'grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4 justify-center pt-8',
    entityLabel,
}: Props<T>) {
    const router = useRouter();
    const t = useTranslations('RecordsRenderView');
    const [sortKey, setSortKey] = useState<string | null>(null);
    const [sortDirection, setSortDirection] = useState<SortDirection>('asc');

    const sortedData = useMemo(() => {
        if (!sortKey) return data;
        const col = columns.find((c) => c.key === sortKey);
        if (!col?.getSortValue) return data;
        const dir = sortDirection === 'asc' ? 1 : -1;
        return [...data].sort((a, b) => {
            const av = col.getSortValue!(a);
            const bv = col.getSortValue!(b);
            if (av == null && bv == null) return 0;
            if (av == null) return 1;
            if (bv == null) return -1;
            if (typeof av === 'number' && typeof bv === 'number') return (av - bv) * dir;
            return String(av).localeCompare(String(bv), undefined, { sensitivity: 'base' }) * dir;
        });
    }, [data, columns, sortKey, sortDirection]);

    const handleSort = (key: string) => {
        if (sortKey === key) {
            setSortDirection((d) => (d === 'asc' ? 'desc' : 'asc'));
        } else {
            setSortKey(key);
            setSortDirection('asc');
        }
    };

    const allSelected = sortedData.length > 0 && selectedIds.size === sortedData.length;
    const someSelected = selectedIds.size > 0 && !allSelected;

    const toggleAll = (checked: boolean) => {
        onSelectedIdsChange(checked ? new Set(sortedData.map((item) => item.id)) : new Set());
    };

    const toggleOne = (id: SelectionId, checked: boolean) => {
        const next = new Set(selectedIds);
        if (checked) next.add(id);
        else next.delete(id);
        onSelectedIdsChange(next);
    };

    if (displayMode === 'grid') {
        return (
            <div className={gridClassName}>
                {sortedData.map((item) => (
                    <div key={item.id}>
                        {renderCard(item, { onQuickEdit, onDelete })}
                    </div>
                ))}
            </div>
        );
    }

    return (
        <div className="flex flex-col gap-4 justify-center pt-8">
            <table className="w-full text-left">
                <thead>
                    <tr>
                        <th className="px-4 py-2 w-10">
                            <Checkbox
                                checked={someSelected ? 'indeterminate' : allSelected}
                                onCheckedChange={(checked) => toggleAll(checked === true)}
                                aria-label={t('selectAllAria', { entityLabel })}
                                className="data-[state=checked]:bg-brand data-[state=checked]:border-brand-light"
                            />
                        </th>
                        {renderAvatar && <th className="px-4 py-2 w-10 h-10"> </th>}
                        {columns.map((col) => {
                            const active = sortKey === col.key;
                            const sortable = !!col.getSortValue;
                            const Icon = active
                                ? sortDirection === 'asc'
                                    ? ChevronUpIcon
                                    : ChevronDownIcon
                                : ChevronUpDownIcon;
                            return (
                                <th key={col.key} className={`px-4 py-2 ${col.widthClass ?? ''}`}>
                                    {sortable ? (
                                        <button
                                            type="button"
                                            onClick={() => handleSort(col.key)}
                                            aria-sort={active ? (sortDirection === 'asc' ? 'ascending' : 'descending') : 'none'}
                                            className="inline-flex items-center gap-1 font-semibold cursor-ns-resize"
                                        >
                                            {col.label}
                                            <Icon className={`h-3.5 w-3.5 ${active ? '' : 'opacity-40'}`} aria-hidden="true" />
                                        </button>
                                    ) : (
                                        <span className="font-semibold">{col.label}</span>
                                    )}
                                </th>
                            );
                        })}
                    </tr>
                </thead>
                <tbody>
                    {sortedData.map((item) => {
                        const isSelected = selectedIds.has(item.id);

                        // TODO: if item is company, render avatar as rounded square; if contact, render avatar as circle
                        return (
                            <tr
                                key={item.id}
                                data-state={isSelected ? 'selected' : undefined}
                                className={`border-b border-gray-200 hover:bg-brand-light transition-colors duration-300 data-[state=selected]:bg-brand-light/60 ${onRowClick || detailPath ? 'cursor-pointer' : ''}`}
                                onClick={() => {
                                    if (onRowClick) onRowClick(item);
                                    else if (detailPath) router.push(detailPath(item));
                                }}
                            >
                                <td className="px-4 py-2" onClick={(e) => e.stopPropagation()}>
                                    <Checkbox
                                        checked={isSelected}
                                        onCheckedChange={(checked) => toggleOne(item.id, checked === true)}
                                        aria-label={t('selectItemAria', { name: item.name ?? entityLabel })}
                                        className="data-[state=checked]:bg-brand data-[state=checked]:border-brand-light"
                                    />
                                </td>
                                {renderAvatar && <td className="px-4 py-2">{renderAvatar(item)}</td>}
                                {columns.map((col) => {
                                    const content = col.render ? col.render(item) : (item as unknown as Record<string, ReactNode>)[col.key];
                                    if (col.copyable) {
                                        const { label, getValue } = col.copyable;
                                        return (
                                            <td
                                                key={col.key}
                                                className="px-4 py-2 hover:text-brand transition-colors duration-300"
                                                onClick={(e) => {
                                                    e.stopPropagation();
                                                    const v = getValue(item) ?? '';
                                                    copyToClipboard(v, label)
                                                        ? toast.success(t('copiedToast', { label }))
                                                        : toast.error(t('copyFailedToast', { label: label.toLowerCase() }));
                                                }}
                                            >
                                                {content}
                                            </td>
                                        );
                                    }
                                    return (
                                        <td key={col.key} className="px-4 py-2">
                                            {content}
                                        </td>
                                    );
                                })}
                            </tr>
                        );
                    })}
                </tbody>
            </table>
        </div>
    );
}