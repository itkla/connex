'use client';

import { useRouter } from 'next/navigation';
import { useLocale, useTranslations } from 'next-intl';
import {
    CalendarIcon,
    BuildingOffice2Icon,
} from '@heroicons/react/24/outline';
import { ChevronRightIcon } from '@heroicons/react/24/solid';
import { Button } from '@/components/ui/button';
import { formatCompactCurrency, formatShortDate } from '@/app/lib/utils';
import CompanyAvatar from '@/app/components/records/companies/CompanyAvatar';
import { type Company, type Deal, type DealRisk, type Pipeline, type Stage } from '@/app/lib/types';
import { isDealClosed } from './dealOutcome';
import DealRiskPill from './DealRiskPill';
import { Suspense } from 'react';
import {
    recordDetailNavigationPath,
    type RecordReturnSelectionSnapshot,
} from '@/app/lib/recordReturnPath';
import { RecordActionMenuTrigger, RecordContextMenu } from '@/app/components/records/RecordActionMenu';
import type { RecordMenuModel } from '@/app/components/records/types';
import { usePermission } from '@/app/hooks/usePermissions';

interface DealCardProps {
    deal: Deal;
    company?: Company;
    pipeline?: Pipeline;
    stage?: Stage;
    risk?: DealRisk | null;
    onQuickEdit?: () => void;
    onDelete?: () => void;
    menu?: RecordMenuModel;
    returnSelection?: RecordReturnSelectionSnapshot;
}

function dealStatus(deal: Deal): 'open' | 'closed' {
    return isDealClosed(deal) ? 'closed' : 'open';
}

export default function DealCard({
    deal,
    company,
    pipeline,
    stage,
    risk,
    onQuickEdit,
    onDelete,
    menu,
    returnSelection,
}: DealCardProps) {
    const router = useRouter();
    const canUpdate = usePermission('DEAL_UPDATE');
    const canDelete = usePermission('DEAL_DELETE');
    const t = useTranslations('DealsCard');
    const locale = useLocale();
    const open = () => router.push(recordDetailNavigationPath('deals', deal.id, returnSelection));
    const status = dealStatus(deal);
    const statusLabel = status === 'closed' ? t('statusClosed') : t('statusOpen');
    const baseMenu: RecordMenuModel = menu ?? {
        record: { type: 'deal', id: deal.id, label: deal.name },
        includeRecordActions: true,
        onQuickEdit,
        onRemove: onDelete,
        removeIntent: 'delete',
    };
    const menuModel: RecordMenuModel = {
        ...baseMenu,
        onQuickEdit: canUpdate ? baseMenu.onQuickEdit : undefined,
        onRemove: canDelete ? baseMenu.onRemove : undefined,
    };

    return (
        <RecordContextMenu model={menuModel}>
            <div
                className="group flex cursor-pointer items-center gap-4 rounded-2xl border border-border bg-card p-4 transition duration-200 hover:bg-muted hover:shadow-lg"
                onClick={open}
            >
            <Suspense fallback={<span className="size-16 shrink-0 rounded-2xl bg-muted ring-1 ring-border" />}>
            {company ? (
                <CompanyAvatar company={company} type="large" />
            ) : (
                <div className="flex size-16 shrink-0 items-center justify-center rounded-2xl bg-muted text-muted-foreground ring-1 ring-border">
                    <BuildingOffice2Icon className="size-7" />
                </div>
            )}
            </Suspense>

            <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2">
                    <h3 className="truncate text-base font-semibold text-foreground">
                        {deal.name}
                    </h3>
                    <span
                        className={
                            status === 'closed'
                                ? 'shrink-0 rounded-full bg-neutral-200 px-2 py-0.5 text-[10px] font-medium uppercase tracking-wider text-neutral-600 dark:bg-neutral-800 dark:text-neutral-200'
                                : 'shrink-0 rounded-full bg-brand-light px-2 py-0.5 text-[10px] font-medium uppercase tracking-wider text-brand-dark'
                        }
                    >
                        {statusLabel}
                    </span>
                    <DealRiskPill risk={risk} />
                </div>
                <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
                    {company && (
                        <span className="inline-flex items-center gap-1 truncate">
                            <BuildingOffice2Icon className="size-3.5" />
                            <span className="truncate">{company.name}</span>
                        </span>
                    )}
                    {stage && (
                        <span className="inline-flex items-center gap-1">
                            <span className="size-1.5 rounded-full bg-brand" />
                            {stage.name}
                            {pipeline && (
                                <span className="text-muted-foreground">· {pipeline.name}</span>
                            )}
                        </span>
                    )}
                    {deal.expectedCloseDate && (
                        <span className="inline-flex items-center gap-1">
                            <CalendarIcon className="size-3.5" />
                            {formatShortDate(deal.expectedCloseDate, locale)}
                        </span>
                    )}
                </div>
            </div>

            <div className="text-right">
                <div className="text-lg font-semibold text-foreground">
                    {formatCompactCurrency(deal.value, deal.currency || 'USD', locale)}
                </div>
                {deal.currency && (
                    <div className="text-[10px] uppercase tracking-wider text-muted-foreground">
                        {deal.currency}
                    </div>
                )}
            </div>

            <div onClick={(event) => event.stopPropagation()}>
                <RecordActionMenuTrigger
                    model={menuModel}
                    triggerClassName="size-8 shrink-0 opacity-0 group-hover:opacity-100"
                />
            </div>

            <Button
                variant="outline"
                size="sm"
                aria-label={t('openDealPage')}
                onClick={(e) => {
                    e.stopPropagation();
                    open();
                }}
                className="size-8 shrink-0 border-none bg-muted text-muted-foreground shadow-none hover:bg-muted/80 hover:text-foreground"
            >
                <ChevronRightIcon className="size-4" />
            </Button>
            </div>
        </RecordContextMenu>
    );
}
