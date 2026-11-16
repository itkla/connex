'use client';

import { useRouter } from 'next/navigation';
import Link from 'next/link';
import type { ReactNode } from 'react';
import { useTimeZone, useLocale, useTranslations } from 'next-intl';
import {
    DropdownMenu,
    DropdownMenuTrigger,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
} from '@/components/ui/dropdown-menu';
import {
    EllipsisVerticalIcon,
    EyeIcon,
    PencilIcon,
    TrashIcon,
    CalendarIcon,
    BuildingOffice2Icon,
} from '@heroicons/react/24/outline';
import { cn } from '@/lib/utils';
import { IconButton } from '@/components/ui/icon-button';
import { formatCompactCurrency, formatShortDate } from '@/app/lib/utils';
import { type Company, type Deal, type DealRisk } from '@/app/lib/types';
import { isDealClosed } from './dealOutcome';
import DealCloseDate from './DealCloseDate';
import DealRiskPill from './DealRiskPill';
import {
    recordDetailNavigationPath,
    type RecordReturnSelectionSnapshot,
} from '@/app/lib/recordReturnPath';

interface DealKanbanCardProps {
    deal: Deal;
    timezone: string;
    company?: Company;
    risk?: DealRisk | null;
    onQuickEdit?: () => void;
    onDelete?: () => void;
    returnSelection?: RecordReturnSelectionSnapshot;
    dragHandle?: ReactNode;
}

/**
 * Compact deal card for the kanban board: a vertical layout sized for a narrow
 * column, mirroring the tasks board card. The full-width {@link DealCard} row is
 * for the list view — reusing it inside a column overflows and overlaps its text.
 * The column already encodes the stage, so stage/pipeline are omitted here.
 */
export default function DealKanbanCard({
    deal,
    timezone,
    company,
    risk,
    onQuickEdit,
    onDelete,
    returnSelection,
    dragHandle,
}: DealKanbanCardProps) {
    const timeZone = useTimeZone();
    const router = useRouter();
    const t = useTranslations('DealsCard');
    const locale = useLocale();
    const open = () => router.push(recordDetailNavigationPath('deals', deal.id, returnSelection));
    const closed = isDealClosed(deal);
    const statusLabel = deal.won === true ? t('statusWon') : deal.won === false ? t('statusLost') : t('statusOpen');

    return (
        <div
            className="group relative flex flex-col gap-2.5 rounded-xl bg-card p-3.5 ring-1 ring-border"
        >
            <div className="flex items-start gap-2">
                <div className="min-w-0 flex-1 text-sm font-semibold leading-snug text-foreground line-clamp-2">
                    <Link href={recordDetailNavigationPath('deals', deal.id, returnSelection)} className="after:absolute after:inset-0 after:rounded-xl focus-visible:outline-none">
                        {deal.name}
                    </Link>
                </div>
                <div className="relative z-10 -mt-1 flex shrink-0 items-center">
                    {dragHandle}
                    <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                            <IconButton
                                label={t('dealActions')}
                                variant="ghost"
                                size="icon-toolbar"
                            >
                                <EllipsisVerticalIcon className="size-4" />
                            </IconButton>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end" onClick={(e) => e.stopPropagation()}>
                            <DropdownMenuItem onSelect={open}>
                                <EyeIcon className="size-4 text-muted-foreground" />
                                {t('view')}
                            </DropdownMenuItem>
                            {onQuickEdit && (
                                <DropdownMenuItem
                                    onSelect={(e) => {
                                        e.preventDefault();
                                        onQuickEdit();
                                    }}
                                >
                                    <PencilIcon className="size-4 text-muted-foreground" />
                                    {t('quickEdit')}
                                </DropdownMenuItem>
                            )}
                            {onDelete && (
                                <>
                                    <DropdownMenuSeparator />
                                    <DropdownMenuItem
                                        variant="destructive"
                                        onSelect={(e) => {
                                            e.preventDefault();
                                            onDelete();
                                        }}
                                    >
                                        <TrashIcon className="size-4" />
                                        {t('delete')}
                                    </DropdownMenuItem>
                                </>
                            )}
                        </DropdownMenuContent>
                    </DropdownMenu>
                </div>
            </div>

            {(company || deal.expectedCloseDate) && (
                <div className="flex flex-wrap items-center gap-1.5">
                    {company && (
                        <span className="inline-flex max-w-full items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground ring-1 ring-inset ring-border">
                            <BuildingOffice2Icon className="size-3 shrink-0" />
                            <span className="truncate">{company.name}</span>
                        </span>
                    )}
                    {deal.expectedCloseDate && (
                        <span className="inline-flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground ring-1 ring-inset ring-border">
                            <CalendarIcon className="size-3 shrink-0" />
                            <span className="tabular-nums">{formatShortDate(deal.expectedCloseDate, locale, timeZone)}</span>
                        </span>
                    )}
                </div>
            )}

            <DealCloseDate deal={deal} timezone={timezone} />
            <div className="flex items-center justify-between gap-2">
                <span className="text-sm font-semibold tabular-nums text-foreground">
                    {formatCompactCurrency(deal.value, deal.currency || 'USD', locale)}
                </span>
                <div className="flex shrink-0 items-center gap-1.5">
                    <DealRiskPill risk={risk} className="relative z-10" focusable />
                    <span
                        className={cn(
                            'rounded-full px-2 py-0.5 text-[10px] font-medium uppercase tracking-wider',
                            closed
                                ? 'bg-neutral-200 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-200'
                                : 'bg-brand-light text-brand-text',
                        )}
                    >
                        {statusLabel}
                    </span>
                </div>
            </div>
        </div>
    );
}
