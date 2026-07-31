'use client';

import { useRouter } from 'next/navigation';
import { useLocale, useTranslations } from 'next-intl';
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
import { formatCompactCurrency, formatShortDate } from '@/app/lib/utils';
import { type Company, type Deal, type DealRisk } from '@/app/lib/types';
import { isDealClosed } from './dealOutcome';
import DealRiskPill from './DealRiskPill';
import {
    recordDetailNavigationPath,
    type RecordReturnSelectionSnapshot,
} from '@/app/lib/recordReturnPath';

interface DealKanbanCardProps {
    deal: Deal;
    company?: Company;
    risk?: DealRisk | null;
    onQuickEdit?: () => void;
    onDelete?: () => void;
    returnSelection?: RecordReturnSelectionSnapshot;
}

/**
 * Compact deal card for the kanban board: a vertical layout sized for a narrow
 * column, mirroring the tasks board card. The full-width {@link DealCard} row is
 * for the list view — reusing it inside a column overflows and overlaps its text.
 * The column already encodes the stage, so stage/pipeline are omitted here.
 */
export default function DealKanbanCard({
    deal,
    company,
    risk,
    onQuickEdit,
    onDelete,
    returnSelection,
}: DealKanbanCardProps) {
    const router = useRouter();
    const t = useTranslations('DealsCard');
    const locale = useLocale();
    const open = () => router.push(recordDetailNavigationPath('deals', deal.id, returnSelection));
    const closed = isDealClosed(deal);
    const statusLabel = closed ? t('statusClosed') : t('statusOpen');

    return (
        <div
            onClick={open}
            className="group flex cursor-pointer flex-col gap-2.5 rounded-xl bg-card p-3.5 ring-1 ring-border transition duration-200 hover:-translate-y-0.5 hover:shadow-md active:scale-[0.98] active:shadow-sm"
        >
            <div className="flex items-start gap-2">
                <h3 className="min-w-0 flex-1 text-sm font-semibold leading-snug text-foreground line-clamp-2">
                    {deal.name}
                </h3>
                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <button
                            type="button"
                            aria-label={t('dealActions')}
                            onClick={(e) => e.stopPropagation()}
                            className="-mt-0.5 -mr-1 flex size-7 shrink-0 items-center justify-center rounded-full text-muted-foreground opacity-0 transition hover:bg-muted hover:text-foreground group-hover:opacity-100 focus:opacity-100 data-[state=open]:opacity-100"
                        >
                            <EllipsisVerticalIcon className="size-4" />
                        </button>
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
                            <span className="tabular-nums">{formatShortDate(deal.expectedCloseDate, locale)}</span>
                        </span>
                    )}
                </div>
            )}

            <div className="flex items-center justify-between gap-2">
                <span className="text-sm font-semibold tabular-nums text-foreground">
                    {formatCompactCurrency(deal.value, deal.currency || 'USD', locale)}
                </span>
                <div className="flex shrink-0 items-center gap-1.5">
                    <DealRiskPill risk={risk} />
                    <span
                        className={cn(
                            'rounded-full px-2 py-0.5 text-[10px] font-medium uppercase tracking-wider',
                            closed
                                ? 'bg-neutral-200 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-200'
                                : 'bg-brand-light text-brand-dark',
                        )}
                    >
                        {statusLabel}
                    </span>
                </div>
            </div>
        </div>
    );
}
