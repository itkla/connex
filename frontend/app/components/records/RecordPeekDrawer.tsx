'use client';

import { memo, useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useLocale, useTranslations } from 'next-intl';
import {
    ArrowTopRightOnSquareIcon,
    BoltIcon,
    BuildingOffice2Icon,
    CheckCircleIcon,
    ChevronDownIcon,
    ChevronUpIcon,
    DocumentTextIcon,
    LinkIcon,
} from '@heroicons/react/24/outline';

import {
    Drawer,
    DrawerContent,
    DrawerDescription,
    DrawerHeader,
    DrawerTitle,
} from '@/components/ui/drawer';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { useActionRecord, useActions } from '@/app/hooks/useActions';
import type { PeekTarget, PeekType } from '@/app/hooks/useRecordPeek';
import { RECORD_PATHS } from '@/app/lib/actions/seedActions';
import { useIsMobile } from '@/app/hooks/useIsMobile';
import { ApiError, getCompanyById, getCompanyEngagement, getContactById, getActivitiesForDeal, getDealSummary, getTasksForDeal } from '@/app/lib/api';
import { formatCurrency, formatRelativeTime } from '@/app/lib/utils';
import ContactAvatar from '@/app/components/records/contacts/ContactAvatar';
import CompanyAvatar from '@/app/components/records/companies/CompanyAvatar';
import DealAvatar from '@/app/components/records/deals/DealAvatar';
import type { Activity, Company, CompanyEngagement, Contact, DealSummary, Task } from '@/app/lib/types';

type Props = {
    target: PeekTarget | null;
    browserType: PeekType;
    onClose: () => void;
    onPrev: () => void;
    onNext: () => void;
    hasPrev: boolean;
    hasNext: boolean;
    position: { index: number; total: number } | null;
};

type PeekData =
    | { kind: 'person'; contact: Contact }
    | { kind: 'company'; company: Company; engagement: CompanyEngagement | null }
    | { kind: 'deal'; summary: DealSummary; tasks: Task[]; activities: Activity[] };

/**
 * Right-side (bottom-sheet on mobile) drawer that shows a triage summary of a company, person, or
 * deal without leaving the list. Content loads lazily per record with skeleton/error/not-found
 * states; the loaded record is published to the action registry so add-note/task/activity and
 * copy-link light up and prefill. Prev/next steps through the visible order.
 */
function RecordPeekDrawer({ target, browserType, onClose, onPrev, onNext, hasPrev, hasNext, position }: Props) {
    const t = useTranslations('RecordPeek');
    const locale = useLocale();
    const router = useRouter();
    const { run, getAction } = useActions();
    const isMobile = useIsMobile();

    const [data, setData] = useState<PeekData | null>(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<'notFound' | 'forbidden' | 'failed' | null>(null);

    const targetKey = target ? `${target.type}:${target.id}` : null;
    const [loadedKey, setLoadedKey] = useState<string | null>(null);
    if (targetKey !== loadedKey) {
        setLoadedKey(targetKey);
        setData(null);
        setError(null);
        setLoading(targetKey !== null);
    }

    const label = data ? recordLabel(data) : '';
    const actionRecord = useMemo(
        () => (target && data ? { type: target.type, id: target.id, label } : null),
        [target, data, label],
    );
    useActionRecord(actionRecord);

    useEffect(() => {
        if (!target) return;
        let cancelled = false;
        (async () => {
            try {
                const loaded = await loadPeek(target);
                if (!cancelled) setData(loaded);
            } catch (err) {
                if (cancelled) return;
                if (err instanceof ApiError && err.status === 404) setError('notFound');
                else if (err instanceof ApiError && err.status === 403) setError('forbidden');
                else setError('failed');
            } finally {
                if (!cancelled) setLoading(false);
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [target]);

    const openFull = () => {
        if (!target) return;
        const base = RECORD_PATHS[target.type];
        if (base) router.push(`${base}/${target.id}`);
    };

    return (
        <Drawer
            open={target !== null}
            onOpenChange={(open) => {
                if (!open) onClose();
            }}
            swipeDirection={isMobile ? 'down' : 'right'}
        >
            <DrawerContent data-record-peek="" className="flex w-full flex-col gap-0 sm:max-w-md">
                <DrawerHeader className="gap-3 border-b pr-12">
                    <div className="flex items-center justify-between gap-2">
                        <span className="text-xs font-medium uppercase tracking-[0.08em] text-muted-foreground">
                            {t(`type_${browserType}`)}
                        </span>
                        <div className="flex items-center gap-1">
                            {position && (
                                <span className="mr-1 text-xs tabular-nums text-muted-foreground">
                                    {position.index} / {position.total}
                                </span>
                            )}
                            <Button variant="ghost" size="icon-xs" aria-label={t('previous')} disabled={!hasPrev} onClick={onPrev}>
                                <ChevronUpIcon className="size-4" />
                            </Button>
                            <Button variant="ghost" size="icon-xs" aria-label={t('next')} disabled={!hasNext} onClick={onNext}>
                                <ChevronDownIcon className="size-4" />
                            </Button>
                        </div>
                    </div>
                    <div className="flex items-center gap-3">
                        <PeekAvatar target={target} data={data} />
                        <div className="min-w-0 flex-1">
                            <DrawerTitle className="truncate text-base">{label || (loading ? t('loading') : '')}</DrawerTitle>
                            <DrawerDescription className="truncate">{data ? recordSubtitle(data) : ''}</DrawerDescription>
                        </div>
                    </div>
                </DrawerHeader>

                <div className="flex flex-1 flex-col gap-5 overflow-y-auto p-4">
                    {loading && <PeekSkeleton />}

                    {error && (
                        <div className="flex flex-col items-center gap-3 rounded-xl border border-dashed border-border px-4 py-10 text-center">
                            <p className="text-sm text-muted-foreground">{t(`error_${error}`)}</p>
                            {error !== 'forbidden' && (hasNext || hasPrev) && (
                                <Button variant="outline" size="sm" onClick={hasNext ? onNext : onPrev}>
                                    {t('goToNext')}
                                </Button>
                            )}
                        </div>
                    )}

                    {data && !loading && <PeekBody data={data} locale={locale} t={t} />}
                </div>

                {target && !error && (
                    <div className="grid grid-cols-2 gap-2 border-t p-3 sm:grid-cols-4">
                        <Button variant="outline" size="sm" onClick={openFull} className="justify-start">
                            <ArrowTopRightOnSquareIcon className="size-4" />
                            {t('openFull')}
                        </Button>
                        {getAction('create.note') && (
                            <Button variant="ghost" size="sm" onClick={() => void run('create.note', { source: 'programmatic' })} className="justify-start">
                                <DocumentTextIcon className="size-4" />
                                {t('addNote')}
                            </Button>
                        )}
                        {getAction('create.task') && (
                            <Button variant="ghost" size="sm" onClick={() => void run('create.task', { source: 'programmatic' })} className="justify-start">
                                <CheckCircleIcon className="size-4" />
                                {t('createTask')}
                            </Button>
                        )}
                        {getAction('record.copy-link') && (
                            <Button variant="ghost" size="sm" onClick={() => void run('record.copy-link', { source: 'programmatic' })} className="justify-start">
                                <LinkIcon className="size-4" />
                                {t('copyLink')}
                            </Button>
                        )}
                    </div>
                )}
            </DrawerContent>
        </Drawer>
    );
}

export default memo(RecordPeekDrawer);

function PeekAvatar({ target, data }: { target: PeekTarget | null; data: PeekData | null }) {
    if (data?.kind === 'person') return <ContactAvatar contact={data.contact} type="large" />;
    if (data?.kind === 'company') return <CompanyAvatar company={data.company} type="large" />;
    if (target?.type === 'deal' || data?.kind === 'deal') return <DealAvatar type="large" />;
    return <span className="grid size-10 shrink-0 place-items-center rounded-full bg-muted text-muted-foreground"><BuildingOffice2Icon className="size-5" /></span>;
}

function PeekBody({ data, locale, t }: { data: PeekData; locale: string; t: ReturnType<typeof useTranslations> }) {
    if (data.kind === 'person') {
        const openTasks = (data.contact.tasks ?? []).filter((task) => !task.completed);
        const activities = data.contact.activities ?? [];
        return (
            <>
                <Facts
                    rows={[
                        [t('email'), data.contact.email || '—'],
                        [t('phone'), data.contact.phone || '—'],
                        [t('company'), data.contact.company?.name ?? '—'],
                        [t('openDeals'), String((data.contact.deals ?? []).length)],
                    ]}
                />
                <TaskSection tasks={openTasks} locale={locale} t={t} />
                <ActivitySection activities={activities} locale={locale} t={t} />
            </>
        );
    }
    if (data.kind === 'company') {
        const e = data.engagement;
        return (
            <Facts
                rows={[
                    [t('industry'), data.company.industry || '—'],
                    [t('website'), data.company.website || '—'],
                    [t('people'), e ? String(e.personCount) : '—'],
                    [t('openTasks'), e ? String(e.openTasks) : '—'],
                    [t('projectedRevenue'), e ? formatCurrency(e.projectedRevenue, e.currency, locale) : '—'],
                ]}
            />
        );
    }
    const openTasks = data.tasks.filter((task) => !task.completed);
    return (
        <>
            <Facts
                rows={[
                    [t('stage'), data.summary.stageName ?? '—'],
                    [t('value'), formatCurrency(data.summary.value, data.summary.currency, locale)],
                    [t('company'), data.summary.companyName ?? '—'],
                    [t('owner'), data.summary.ownerName ?? '—'],
                ]}
            />
            <TaskSection tasks={openTasks} locale={locale} t={t} />
            <ActivitySection activities={data.activities} locale={locale} t={t} />
        </>
    );
}

function Facts({ rows }: { rows: [string, string][] }) {
    return (
        <dl className="grid gap-2">
            {rows.map(([term, value]) => (
                <div key={term} className="flex items-baseline justify-between gap-4 text-sm">
                    <dt className="shrink-0 text-muted-foreground">{term}</dt>
                    <dd className="min-w-0 truncate text-right font-medium text-foreground">{value}</dd>
                </div>
            ))}
        </dl>
    );
}

function TaskSection({ tasks, locale, t }: { tasks: Task[]; locale: string; t: ReturnType<typeof useTranslations> }) {
    return (
        <section className="space-y-2">
            <h3 className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-[0.06em] text-muted-foreground">
                <CheckCircleIcon className="size-3.5" />
                {t('openTasksTitle')}
            </h3>
            {tasks.length === 0 ? (
                <p className="text-sm text-muted-foreground">{t('noOpenTasks')}</p>
            ) : (
                <ul className="space-y-1.5">
                    {tasks.slice(0, 5).map((task) => (
                        <li key={task.id} className="flex items-center justify-between gap-3 text-sm">
                            <span className="min-w-0 truncate text-foreground">{task.description}</span>
                            {task.dueDate && (
                                <span className="shrink-0 text-xs text-muted-foreground">{formatRelativeTime(task.dueDate, locale)}</span>
                            )}
                        </li>
                    ))}
                </ul>
            )}
        </section>
    );
}

function ActivitySection({ activities, locale, t }: { activities: Activity[]; locale: string; t: ReturnType<typeof useTranslations> }) {
    return (
        <section className="space-y-2">
            <h3 className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-[0.06em] text-muted-foreground">
                <BoltIcon className="size-3.5" />
                {t('latestActivityTitle')}
            </h3>
            {activities.length === 0 ? (
                <p className="text-sm text-muted-foreground">{t('noActivity')}</p>
            ) : (
                <ul className="space-y-2">
                    {activities.slice(0, 4).map((activity) => (
                        <li key={activity.id} className="space-y-0.5">
                            <p className="truncate text-sm text-foreground">{activity.subject}</p>
                            <p className="text-xs text-muted-foreground">
                                {activity.type}
                                {activity.timestamp ? ` · ${formatRelativeTime(activity.timestamp, locale)}` : ''}
                            </p>
                        </li>
                    ))}
                </ul>
            )}
        </section>
    );
}

function PeekSkeleton() {
    return (
        <div className="space-y-5">
            <div className="space-y-2">
                {Array.from({ length: 4 }, (_, i) => (
                    <div key={i} className="flex justify-between">
                        <Skeleton className="h-4 w-16" />
                        <Skeleton className="h-4 w-28" />
                    </div>
                ))}
            </div>
            <Skeleton className="h-4 w-24" />
            <Skeleton className="h-16 w-full rounded-lg" />
        </div>
    );
}

function recordLabel(data: PeekData): string {
    if (data.kind === 'person') return data.contact.name;
    if (data.kind === 'company') return data.company.name;
    return data.summary.name;
}

function recordSubtitle(data: PeekData): string {
    if (data.kind === 'person') return data.contact.title || data.contact.company?.name || '';
    if (data.kind === 'company') return data.company.industry || '';
    return `${data.summary.pipelineName ?? ''}${data.summary.stageName ? ` · ${data.summary.stageName}` : ''}`;
}

function sortActivities(activities: Activity[]): Activity[] {
    return [...activities].sort((a, b) => (b.timestamp ?? '').localeCompare(a.timestamp ?? ''));
}

async function loadPeek(target: PeekTarget): Promise<PeekData> {
    if (target.type === 'person') {
        const contact = await getContactById(target.id);
        contact.activities = sortActivities(contact.activities ?? []);
        return { kind: 'person', contact };
    }
    if (target.type === 'company') {
        const [company, engagement] = await Promise.all([
            getCompanyById(target.id),
            getCompanyEngagement(target.id).catch(() => null),
        ]);
        return { kind: 'company', company, engagement };
    }
    const [summary, tasks, activities] = await Promise.all([
        getDealSummary(target.id),
        getTasksForDeal(target.id).catch(() => [] as Task[]),
        getActivitiesForDeal(target.id).catch(() => [] as Activity[]),
    ]);
    return { kind: 'deal', summary, tasks, activities: sortActivities(activities) };
}
