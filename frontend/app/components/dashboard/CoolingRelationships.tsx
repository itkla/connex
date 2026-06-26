'use client';

import Link from 'next/link';
import { useTranslations } from 'next-intl';

import type { Contact, RelationshipTemperature } from '@/app/lib/types';
import ContactAvatar from '@/app/components/records/contacts/ContactAvatar';
import TemperaturePill from '@/app/components/records/TemperaturePill';

export type CoolingItem = { contact: Contact; temp: RelationshipTemperature };

/**
 * Dashboard widget: contacts whose relationship was warm but has gone quiet, surfaced so the
 * user can re-engage before they go cold. Each row deep-links to the contact record.
 */
export default function CoolingRelationships({ items }: { items: CoolingItem[] }) {
    const t = useTranslations('CoolingRelationships');

    return (
        <div className="flex h-full flex-col overflow-hidden rounded-2xl border border-border bg-card">
            {items.length === 0 ? (
                <p className="flex-1 px-4 py-10 text-center text-sm text-muted-foreground">{t('empty')}</p>
            ) : (
                <ul className="flex-1 divide-y divide-border">
                    {items.map(({ contact, temp }) => (
                        <li key={contact.id}>
                            <Link
                                href={`/records/contacts/${contact.id}`}
                                className="flex items-center gap-3 px-4 py-2.5 transition-colors hover:bg-muted/50"
                            >
                                <ContactAvatar contact={contact} type="small" />
                                <div className="min-w-0 flex-1">
                                    <p className="truncate text-sm font-medium text-foreground">{contact.name}</p>
                                    <p className="truncate text-xs text-muted-foreground">
                                        {contact.company?.name ?? contact.title ?? t('noCompany')}
                                    </p>
                                </div>
                                <span className="shrink-0 text-xs tabular-nums text-muted-foreground">
                                    {temp.daysSinceTouch != null ? t('quiet', { days: temp.daysSinceTouch }) : null}
                                </span>
                                <TemperaturePill temp={temp} />
                            </Link>
                        </li>
                    ))}
                </ul>
            )}
        </div>
    );
}
