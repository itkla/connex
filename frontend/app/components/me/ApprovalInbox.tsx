import Link from 'next/link';
import { getLocale, getTranslations } from 'next-intl/server';
import { ArrowTopRightOnSquareIcon, ClockIcon, ShieldCheckIcon } from '@heroicons/react/24/outline';

import { Badge } from '@/components/ui/badge';
import { EmptyState } from '@/app/components/EmptyState';
import SectionUnavailable from '@/app/components/SectionUnavailable';
import SectionHeader from '@/app/components/dashboard/SectionHeader';
import type { ApprovalInboxItem } from '@/app/lib/types';
import { formatUtcDateTime } from '@/app/lib/utils';

type Props = {
    items: ApprovalInboxItem[] | null;
};

/** Server-projected approval work for the current member. */
export default async function ApprovalInbox({ items }: Props) {
    const t = await getTranslations('MePage');
    const locale = await getLocale();

    return (
        <section>
            <SectionHeader title={t('approvalInboxTitle')} />
            {items === null ? (
                <SectionUnavailable
                    title={t('approvalInboxLoadFailedTitle')}
                    body={t('approvalInboxLoadFailedBody')}
                />
            ) : items.length === 0 ? (
                <EmptyState
                    icon={ShieldCheckIcon}
                    title={t('approvalInboxEmptyTitle')}
                    body={t('approvalInboxEmptyBody')}
                    tone="muted"
                    className="py-12"
                />
            ) : (
                <div className="overflow-hidden rounded-2xl border border-border bg-card">
                    <ul className="divide-y divide-border">
                        {items.map((item) => (
                            <li key={`${item.approvalId}:${item.stepId}`}>
                                <Link
                                    href={`/records/deals/${item.dealId}#deal-documents`}
                                    className="group flex items-center gap-4 px-5 py-4 transition-colors hover:bg-muted/50"
                                >
                                    <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-muted text-muted-foreground">
                                        <ShieldCheckIcon className="size-4" aria-hidden="true" />
                                    </span>
                                    <span className="min-w-0 flex-1">
                                        <span className="flex flex-wrap items-center gap-2">
                                            <span className="truncate text-sm font-medium text-foreground">
                                                {item.documentTitle}
                                            </span>
                                            {item.escalated && (
                                                <Badge variant="outline">{t('approvalInboxEscalated')}</Badge>
                                            )}
                                        </span>
                                        <span className="mt-0.5 block truncate text-xs text-muted-foreground">
                                            {t('approvalInboxMeta', {
                                                deal: item.dealName,
                                                step: item.stepName?.trim()
                                                    || t('approvalInboxStep', { number: item.stepOrder }),
                                                version: item.version,
                                            })}
                                        </span>
                                    </span>
                                    <span className="hidden shrink-0 items-center gap-1.5 text-xs text-muted-foreground sm:flex">
                                        <ClockIcon className="size-3.5" aria-hidden="true" />
                                        {item.dueAt
                                            ? t('approvalInboxDue', {
                                                date: formatUtcDateTime(item.dueAt, locale),
                                            })
                                            : t('approvalInboxNoDeadline')}
                                    </span>
                                    <ArrowTopRightOnSquareIcon
                                        className="size-4 shrink-0 text-muted-foreground transition-colors group-hover:text-foreground"
                                        aria-hidden="true"
                                    />
                                </Link>
                            </li>
                        ))}
                    </ul>
                </div>
            )}
        </section>
    );
}
