'use client';

import { useTranslations } from 'next-intl';
import {
    AdjustmentsHorizontalIcon,
    CheckIcon,
} from '@heroicons/react/24/outline';

import { Button } from '@/components/ui/button';
import {
    ResponsiveDialog,
    ResponsiveDialogClose,
    ResponsiveDialogContent,
    ResponsiveDialogDescription,
    ResponsiveDialogFooter,
    ResponsiveDialogHeader,
    ResponsiveDialogTitle,
    ResponsiveDialogTrigger,
} from '@/components/ui/responsive-dialog';
import { cn } from '@/lib/utils';

export interface TaskFilterSheetSection {
    label: string;
    options: {
        value: string;
        label: string;
        total: number;
    }[];
    selected: ReadonlySet<string>;
    onToggle: (value: string) => void;
}

/**
 * The phone filter surface for task dimensions. It reuses the task browser's derived options and
 * state callbacks so the desktop pills and mobile sheet apply the same assignee, contact, deal, and
 * company filters.
 */
export default function TaskFilterSheet({
    sections,
    activeCount,
    hasActiveFilters,
    onClearAll,
}: {
    sections: TaskFilterSheetSection[];
    activeCount: number;
    hasActiveFilters: boolean;
    onClearAll: () => void;
}) {
    const t = useTranslations('Filters');

    return (
        <ResponsiveDialog>
            <ResponsiveDialogTrigger asChild>
                <Button
                    type="button"
                    variant="outline"
                    size="toolbar"
                    aria-label={t('filterAria')}
                    aria-pressed={activeCount > 0}
                    className={cn(
                        'shrink-0 text-xs md:hidden',
                        activeCount > 0 &&
                            'border-brand-dark/20 bg-brand-light/70 text-foreground hover:bg-brand-light/80',
                    )}
                >
                    <AdjustmentsHorizontalIcon className="size-4 shrink-0" aria-hidden />
                    <span className="truncate">{t('filter')}</span>
                    {activeCount > 0 && (
                        <span className="grid size-4 shrink-0 place-items-center rounded-full bg-brand text-[10px] font-semibold leading-none text-brand-foreground tabular-nums">
                            {activeCount}
                        </span>
                    )}
                </Button>
            </ResponsiveDialogTrigger>
            <ResponsiveDialogContent
                scrollable={false}
                showCloseButton={false}
                className="flex max-h-[85dvh] flex-col gap-0 p-0"
            >
                <ResponsiveDialogHeader className="shrink-0 border-b border-border px-4 py-3">
                    <ResponsiveDialogTitle>{t('filter')}</ResponsiveDialogTitle>
                    <ResponsiveDialogDescription className="sr-only">
                        {t('filterDescription')}
                    </ResponsiveDialogDescription>
                </ResponsiveDialogHeader>

                <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-2">
                    {sections.map((section) => section.options.length > 0 ? (
                        <section key={section.label} className="border-b border-border px-2 py-3 last:border-b-0">
                            <h3 className="px-2 pb-1 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
                                {section.label}
                            </h3>
                            <div className="flex flex-col">
                                {section.options.map((option) => {
                                    const selected = section.selected.has(option.value);
                                    return (
                                        <Button
                                            key={option.value}
                                            type="button"
                                            aria-pressed={selected}
                                            onClick={() => section.onToggle(option.value)}
                                            variant={selected ? 'secondary' : 'ghost'}
                                            size="page"
                                            className={cn(
                                                'h-auto min-h-11 w-full justify-start text-left font-normal',
                                                selected &&
                                                    'bg-brand-light/60 font-medium text-brand-dark hover:bg-brand-light/70',
                                            )}
                                        >
                                            <span className="min-w-0 flex-1 truncate">{option.label}</span>
                                            <span className="shrink-0 text-xs tabular-nums text-muted-foreground">
                                                {option.total}
                                            </span>
                                            <CheckIcon
                                                className={cn(
                                                    'size-4 shrink-0 text-brand-dark',
                                                    !selected && 'invisible',
                                                )}
                                                aria-hidden
                                            />
                                        </Button>
                                    );
                                })}
                            </div>
                        </section>
                    ) : null)}
                </div>

                <ResponsiveDialogFooter className="shrink-0 flex-row items-center justify-between gap-2 border-t border-border px-4 py-3 pb-[max(0.75rem,env(safe-area-inset-bottom))]">
                    <Button variant="ghost" disabled={!hasActiveFilters} onClick={onClearAll}>
                        {t('clearAll')}
                    </Button>
                    <ResponsiveDialogClose asChild>
                        <Button>{t('done')}</Button>
                    </ResponsiveDialogClose>
                </ResponsiveDialogFooter>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}
