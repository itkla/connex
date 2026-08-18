'use client';

import { useTranslations } from 'next-intl';
import { ViewColumnsIcon } from '@heroicons/react/24/outline';
import { ChevronDownIcon } from '@heroicons/react/24/solid';

import {
    DropdownMenu,
    DropdownMenuTrigger,
    DropdownMenuContent,
    DropdownMenuLabel,
    DropdownMenuCheckboxItem,
    DropdownMenuSeparator,
    DropdownMenuItem,
} from '@/components/ui/dropdown-menu';
import type { ColumnToggle } from '@/app/hooks/useColumnVisibility';

/**
 * Toolbar control that toggles which record-table columns are shown. Mirrors the sort-menu trigger so the
 * table toolbar stays visually consistent; only meaningful in table mode. The record's identity column is
 * never offered here, so it can never be hidden.
 */
export default function ColumnVisibilityMenu({
    toggles,
    onColumnVisibleChange,
    onReset,
    hiddenCount,
}: {
    toggles: ColumnToggle[];
    onColumnVisibleChange: (key: string, visible: boolean) => void;
    onReset: () => void;
    hiddenCount: number;
}) {
    const t = useTranslations('RecordColumns');
    if (toggles.length === 0) return null;

    return (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <button
                    type="button"
                    aria-label={t('ariaLabel')}
                    className="group/columns inline-flex h-9 items-center gap-1.5 rounded-full bg-muted px-3 text-xs font-medium text-muted-foreground ring-1 ring-border transition hover:text-foreground aria-expanded:text-foreground"
                >
                    <ViewColumnsIcon className="size-3.5" />
                    <span>{t('label')}</span>
                    {hiddenCount > 0 && (
                        <span className="flex min-w-4 items-center justify-center rounded-full bg-brand px-1 text-[10px] font-semibold text-brand-foreground tabular-nums">
                            {hiddenCount}
                        </span>
                    )}
                    <ChevronDownIcon
                        aria-hidden="true"
                        className="size-3.5 opacity-70 transition-transform duration-(--motion-micro) group-aria-expanded/columns:rotate-180 motion-reduce:transition-none"
                    />
                </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-52">
                <DropdownMenuLabel>{t('title')}</DropdownMenuLabel>
                <DropdownMenuSeparator />
                {toggles.map((toggle) => (
                    <DropdownMenuCheckboxItem
                        key={toggle.key}
                        checked={toggle.visible}
                        disabled={toggle.locked}
                        onCheckedChange={(checked) => onColumnVisibleChange(toggle.key, checked === true)}
                        onSelect={(event) => event.preventDefault()}
                    >
                        <span className="flex-1">{toggle.label}</span>
                        {toggle.locked && <span className="text-xs text-muted-foreground">{t('sorted')}</span>}
                    </DropdownMenuCheckboxItem>
                ))}
                {hiddenCount > 0 && (
                    <>
                        <DropdownMenuSeparator />
                        <DropdownMenuItem onSelect={onReset}>{t('reset')}</DropdownMenuItem>
                    </>
                )}
            </DropdownMenuContent>
        </DropdownMenu>
    );
}
