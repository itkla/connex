'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useTranslations } from 'next-intl';
import { CalendarDaysIcon, CheckIcon } from '@heroicons/react/24/outline';

import type { Contact, RelationshipTemperature } from '@/app/lib/types';
import ContactAvatar from '@/app/components/records/contacts/ContactAvatar';
import WarmthPill from '@/app/components/records/WarmthPill';
import { createTask } from '@/app/lib/api';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { followUpDueDate } from '@/app/lib/followUp';
import { toastSuccess } from '@/app/lib/toast';

export type CoolingItem = { contact: Contact; temp: RelationshipTemperature };

/**
 * Dashboard widget: contacts whose relationship was warm but has gone quiet, with the predicted date
 * each goes cold and a one-click follow-up that auto-schedules a touchpoint before then.
 */
export default function CoolingRelationships({
    items,
    currentUserId,
}: {
    items: CoolingItem[];
    currentUserId: number;
}) {
    const t = useTranslations('CoolingRelationships');
    const showApiError = useApiErrorToast('CoolingRelationships');
    const [scheduled, setScheduled] = useState<Set<number>>(new Set());
    const [busyId, setBusyId] = useState<number | null>(null);

    const schedule = async (item: CoolingItem) => {
        if (busyId != null || scheduled.has(item.contact.id)) return;
        setBusyId(item.contact.id);
        try {
            await createTask({
                description: t('followUpWith', { name: item.contact.name }),
                dueDate: followUpDueDate(item.temp.goesColdAt, Date.now()),
                assignedToId: currentUserId,
                personId: item.contact.id,
            });
            setScheduled((prev) => new Set(prev).add(item.contact.id));
            toastSuccess(t('scheduled'));
        } catch (err) {
            showApiError(err, 'scheduleFailed');
        } finally {
            setBusyId(null);
        }
    };

    return (
        <div className="flex h-full flex-col overflow-hidden rounded-2xl border border-border bg-card">
            {items.length === 0 ? (
                <p className="flex-1 px-4 py-10 text-center text-sm text-muted-foreground">{t('empty')}</p>
            ) : (
                <ul className="flex-1 divide-y divide-border">
                    {items.map(({ contact, temp }) => {
                        const isScheduled = scheduled.has(contact.id);
                        return (
                            <li key={contact.id} className="flex items-center gap-3 px-4 py-2.5">
                                <Link
                                    href={`/records/contacts/${contact.id}`}
                                    className="flex min-w-0 flex-1 items-center gap-3 transition-opacity hover:opacity-80"
                                >
                                    <ContactAvatar contact={contact} type="small" />
                                    <div className="min-w-0 flex-1">
                                        <p className="truncate text-sm font-medium text-foreground">{contact.name}</p>
                                        <p className="truncate text-xs text-muted-foreground">
                                            {temp.daysUntilCold != null
                                                ? t('goesColdIn', { days: temp.daysUntilCold })
                                                : temp.daysSinceTouch != null
                                                  ? t('quiet', { days: temp.daysSinceTouch })
                                                  : (contact.company?.name ?? t('noCompany'))}
                                        </p>
                                    </div>
                                </Link>
                                <WarmthPill temp={temp} />
                                <button
                                    type="button"
                                    onClick={() => schedule({ contact, temp })}
                                    disabled={busyId != null || isScheduled}
                                    aria-label={t('scheduleFollowUp')}
                                    title={t('scheduleFollowUp')}
                                    className="shrink-0 rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground disabled:opacity-60"
                                >
                                    {isScheduled ? (
                                        <CheckIcon className="size-4 text-warmth-hot" />
                                    ) : (
                                        <CalendarDaysIcon className="size-4" />
                                    )}
                                </button>
                            </li>
                        );
                    })}
                </ul>
            )}
        </div>
    );
}
