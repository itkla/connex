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
import { ChevronRightIcon } from '@heroicons/react/24/solid';
import { Button } from '@/components/ui/button';
import { formatCompactCurrency, formatShortDate, parseMysqlDateTime } from '@/app/lib/utils';
import CompanyAvatar from '@/app/components/records/companies/CompanyAvatar';
import { type Company, type Contact, type Deal, type Pipeline, type Stage } from '@/app/lib/types';
import Chip from '@/app/components/Chip';
import ContactAvatar from '../contacts/ContactAvatar';
import { Suspense } from 'react';
import { Skeleton } from '@/components/ui/skeleton';
import { getCompanyPeople, getDealPeople } from '@/app/lib/api';

interface DealCardProps {
    deal: Deal;
    company?: Company;
    pipeline?: Pipeline;
    stage?: Stage;
    onQuickEdit?: () => void;
    onDelete?: () => void;
}

function dealStatus(deal: Deal): 'open' | 'closed' {
    const t = parseMysqlDateTime(deal.closedAt);
    if (!Number.isFinite(t)) return 'open';
    return t <= Date.now() ? 'closed' : 'open';
}

export default function DealCard({ deal, company, pipeline, stage, onQuickEdit, onDelete }: DealCardProps) {
    const router = useRouter();
    const t = useTranslations('DealsCard');
    const locale = useLocale();
    const open = () => router.push(`/records/deals/${deal.id}`);
    const status = dealStatus(deal);
    const statusLabel = status === 'closed' ? t('statusClosed') : t('statusOpen');

    // get the associated contact for the deal
    // let associatedContact: Contact | undefined;
    // if (company) {
    //     // look up the associated contact for the company using the company id
    //     const associatedContact: Contact[] = await getCompanyPeople(company.id);
    // } else {
    //     const associatedContactPromise = (async () => {
    //         const associatedContact: Contact[] = await getDealPeople(deal.id);
    //         return associatedContact[0] ?? { id: 0, name: 'Freelancer', imageUrl: '', email: '', phone: '', title: '', createdAt: '', updatedAt: '' };
    //     })();
    //     associatedContact = await associatedContactPromise;
    // }

    return (
        <div
            className="group flex cursor-pointer items-center gap-4 rounded-2xl bg-white p-4 ring-1 ring-black/5 transition duration-200 hover:bg-neutral-50 hover:ring-black/10 hover:shadow-[0_10px_30px_-12px_rgb(0_0_0/0.18)]"
            onClick={open}
        >
            {/* if company exists, show avatar; if not, assume freelancer and show a placeholder avatar */}
            <Suspense fallback={<span className="size-16 shrink-0 rounded-2xl bg-neutral-100 ring-1 ring-black/5" />}>
            {company ? (
                <CompanyAvatar company={company} type="large" />
            ) : (
                <div className="flex size-16 shrink-0 items-center justify-center rounded-2xl bg-neutral-100 text-neutral-400 ring-1 ring-black/5">
                    <BuildingOffice2Icon className="size-7" />
                </div>
            )}
            </Suspense>

            <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2">
                    <h3 className="truncate text-base font-semibold text-neutral-900">
                        {deal.name}
                    </h3>
                    <span
                        className={
                            status === 'closed'
                                ? 'shrink-0 rounded-full bg-neutral-200 px-2 py-0.5 text-[10px] font-medium uppercase tracking-wider text-neutral-600'
                                : 'shrink-0 rounded-full bg-brand-light px-2 py-0.5 text-[10px] font-medium uppercase tracking-wider text-brand-dark'
                        }
                    >
                        {statusLabel}
                    </span>
                    {/* <span>
                            {switch}
                    </span> */}
                </div>
                <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-neutral-500">
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
                                <span className="text-neutral-400">· {pipeline.name}</span>
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
                <div className="text-lg font-semibold text-neutral-900">
                    {formatCompactCurrency(deal.value, deal.currency || 'USD', locale)}
                </div>
                {deal.currency && (
                    <div className="text-[10px] uppercase tracking-wider text-neutral-500">
                        {deal.currency}
                    </div>
                )}
            </div>

            <DropdownMenu>
                <DropdownMenuTrigger asChild>
                    <button
                        type="button"
                        aria-label={t('dealActions')}
                        onClick={(e) => e.stopPropagation()}
                        className="flex size-8 shrink-0 items-center justify-center rounded-full text-neutral-400 opacity-0 transition hover:bg-neutral-100 hover:text-neutral-700 group-hover:opacity-100 focus:opacity-100 data-[state=open]:opacity-100"
                    >
                        <EllipsisVerticalIcon className="size-4" />
                    </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" onClick={(e) => e.stopPropagation()}>
                    <DropdownMenuItem onSelect={open}>
                        <EyeIcon className="size-4 text-neutral-500" />
                        {t('view')}
                    </DropdownMenuItem>
                    {onQuickEdit && (
                        <DropdownMenuItem
                            onSelect={(e) => {
                                e.preventDefault();
                                onQuickEdit();
                            }}
                        >
                            <PencilIcon className="size-4 text-neutral-500" />
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

            <Button
                variant="outline"
                size="sm"
                aria-label={t('openDealPage')}
                onClick={(e) => {
                    e.stopPropagation();
                    open();
                }}
                className="size-8 shrink-0 border-none bg-neutral-100 text-neutral-500 shadow-none hover:bg-neutral-200 hover:text-neutral-700"
            >
                <ChevronRightIcon className="size-4" />
            </Button>
        </div>
    );
}