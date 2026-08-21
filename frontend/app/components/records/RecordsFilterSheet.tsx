'use client';

import { type ReactNode } from 'react';
import { useTranslations } from 'next-intl';
import {
    AdjustmentsHorizontalIcon,
    ArrowDownIcon,
    ArrowUpIcon,
    CheckIcon,
    UserIcon,
    UserMinusIcon,
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
import {
    MEMBER_SCOPE_MAX_MEMBERS,
    MEMBER_SCOPE_ME,
    MEMBER_SCOPE_UNASSIGNED,
    interpretMemberScope,
    toggleMemberScopeMember,
    toggleMemberScopeSentinel,
} from '@/app/components/filters';
import type { WorkspaceMember } from '@/app/lib/types';
import {
    countActiveFilters,
    sortOptionsFromColumns,
    toggleFilterValue,
    type ColumnDef,
    type ColumnFilterFacet,
    type FilterState,
} from './types';

type SortDirection = 'asc' | 'desc';

/** The owner/member scope section, matching the props of the desktop `MemberScopeFilter` pill. */
export interface FilterSheetOwnerScope {
    values: string[] | undefined;
    onChange: (values: string[]) => void;
    members: WorkspaceMember[];
    counts?: Map<string, number>;
}

interface Props<T> {
    columns: ColumnDef<T>[];
    sortKey: string | null;
    sortDirection: SortDirection;
    onSortChange: (key: string) => void;
    facets: ColumnFilterFacet[];
    filterState: FilterState;
    countedFilterState?: FilterState;
    onFilterStateChange: (next: FilterState) => void;
    ownerScope?: FilterSheetOwnerScope;
    mobileControls?: ReactNode;
    hasAdditionalFilters?: boolean;
    hasActiveFilters: boolean;
    onClearAll: () => void;
}

function Section({ title, children }: { title: string; children: ReactNode }) {
    return (
        <section className="border-b border-border px-2 py-3 last:border-b-0">
            <h3 className="px-2 pb-1 text-xs font-semibold tracking-wide text-muted-foreground uppercase">{title}</h3>
            <div className="flex flex-col">{children}</div>
        </section>
    );
}

function OptionRow({
    label,
    selected,
    disabled,
    icon,
    count,
    indicator,
    onSelect,
}: {
    label: string;
    selected: boolean;
    disabled?: boolean;
    icon?: ReactNode;
    count?: number;
    indicator?: ReactNode;
    onSelect: () => void;
}) {
    return (
        <button
            type="button"
            aria-pressed={selected}
            disabled={disabled}
            onClick={onSelect}
            className={cn(
                'flex min-h-11 w-full items-center gap-2 rounded-lg px-2 text-left text-sm transition-colors motion-reduce:transition-none',
                selected ? 'bg-brand-light/60 font-medium text-brand-dark' : 'text-foreground hover:bg-muted',
                disabled && 'opacity-50',
            )}
        >
            {icon}
            <span className="min-w-0 flex-1 truncate">{label}</span>
            {typeof count === 'number' && (
                <span className="shrink-0 text-xs tabular-nums text-muted-foreground">{count}</span>
            )}
            {indicator ?? (
                <CheckIcon className={cn('size-4 shrink-0 text-brand-dark', !selected && 'invisible')} aria-hidden />
            )}
        </button>
    );
}

/**
 * The phone replacement for the record toolbar's facet pills, owner pill and sort menu: one trigger
 * showing the active-filter count that opens the same options in a bottom sheet.
 *
 * It reuses the desktop controls' data rather than restating it — sort options come from
 * {@link sortOptionsFromColumns}, facet options from the caller's `facets`, and owner selection from
 * the shared member-scope toggles — so the two surfaces cannot drift apart. Presentation is the
 * established {@link ResponsiveDialog} idiom, which commits to drawer-or-dialog once per open.
 * The trigger is hidden from `md` up, where the full toolbar is shown instead.
 */
export default function RecordsFilterSheet<T>({
    columns,
    sortKey,
    sortDirection,
    onSortChange,
    facets,
    filterState,
    countedFilterState,
    onFilterStateChange,
    ownerScope,
    mobileControls,
    hasAdditionalFilters = false,
    hasActiveFilters,
    onClearAll,
}: Props<T>) {
    const t = useTranslations('Filters');
    const ts = useTranslations('MemberScope');

    const sortOptions = sortOptionsFromColumns(columns);
    const activeCount = countActiveFilters(countedFilterState ?? filterState);
    const triggerActive = activeCount > 0 || hasAdditionalFilters;
    const scope = interpretMemberScope(ownerScope?.values);
    const memberCap = scope.mode === 'members' && scope.memberIds.length >= MEMBER_SCOPE_MAX_MEMBERS;

    return (
        <ResponsiveDialog>
            <ResponsiveDialogTrigger asChild>
                <Button
                    type="button"
                    variant="outline"
                    size="toolbar"
                    aria-label={t('filterSortAria')}
                    aria-pressed={triggerActive}
                    className={cn(
                        'shrink-0 text-xs md:hidden',
                        triggerActive &&
                            'border-brand-dark/20 bg-brand-light/70 text-foreground hover:bg-brand-light/80',
                    )}
                >
                    <AdjustmentsHorizontalIcon className="size-4 shrink-0" aria-hidden />
                    <span className="truncate">{t('filterSort')}</span>
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
                    <ResponsiveDialogTitle>{t('filterSort')}</ResponsiveDialogTitle>
                    <ResponsiveDialogDescription className="sr-only">
                        {t('filterSortDescription')}
                    </ResponsiveDialogDescription>
                </ResponsiveDialogHeader>

                <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-2">
                    {mobileControls && (
                        <div className="flex flex-wrap items-center gap-2 border-b border-border px-2 py-3">
                            {mobileControls}
                        </div>
                    )}

                    {sortOptions.length > 0 && (
                        <Section title={t('sortBy')}>
                            {sortOptions.map((option) => {
                                const active = option.key === sortKey;
                                return (
                                    <OptionRow
                                        key={option.key}
                                        label={option.label}
                                        selected={active}
                                        onSelect={() => onSortChange(option.key)}
                                        indicator={
                                            active ? (
                                                <span className="flex shrink-0 items-center gap-1 text-xs font-medium text-brand-dark">
                                                    {sortDirection === 'asc' ? (
                                                        <>
                                                            {t('sortAscending')}
                                                            <ArrowUpIcon className="size-3.5" aria-hidden />
                                                        </>
                                                    ) : (
                                                        <>
                                                            {t('sortDescending')}
                                                            <ArrowDownIcon className="size-3.5" aria-hidden />
                                                        </>
                                                    )}
                                                </span>
                                            ) : (
                                                <span className="size-4 shrink-0" aria-hidden />
                                            )
                                        }
                                    />
                                );
                            })}
                        </Section>
                    )}

                    {ownerScope && (
                        <Section title={ts('label')}>
                            <OptionRow
                                label={ts('me')}
                                selected={scope.mode === 'me'}
                                icon={<UserIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />}
                                onSelect={() =>
                                    ownerScope.onChange(
                                        toggleMemberScopeSentinel(ownerScope.values, MEMBER_SCOPE_ME),
                                    )
                                }
                            />
                            <OptionRow
                                label={ts('unassigned')}
                                selected={scope.mode === 'unassigned'}
                                count={ownerScope.counts?.get(MEMBER_SCOPE_UNASSIGNED)}
                                icon={<UserMinusIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />}
                                onSelect={() =>
                                    ownerScope.onChange(
                                        toggleMemberScopeSentinel(ownerScope.values, MEMBER_SCOPE_UNASSIGNED),
                                    )
                                }
                            />
                            {ownerScope.members.map((member) => {
                                const selected = scope.mode === 'members' && scope.memberIds.includes(member.id);
                                return (
                                    <OptionRow
                                        key={member.id}
                                        label={member.displayName}
                                        selected={selected}
                                        disabled={!selected && memberCap}
                                        count={ownerScope.counts?.get(String(member.id))}
                                        onSelect={() =>
                                            ownerScope.onChange(toggleMemberScopeMember(ownerScope.values, member.id))
                                        }
                                    />
                                );
                            })}
                        </Section>
                    )}

                    {facets.map((facet) => (
                        <Section key={facet.key} title={facet.label}>
                            {facet.options.map((option) => (
                                <OptionRow
                                    key={option.key}
                                    label={option.label}
                                    selected={(filterState[facet.key] ?? []).includes(option.key)}
                                    onSelect={() =>
                                        onFilterStateChange(toggleFilterValue(filterState, facet.key, option.key))
                                    }
                                />
                            ))}
                        </Section>
                    ))}
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
