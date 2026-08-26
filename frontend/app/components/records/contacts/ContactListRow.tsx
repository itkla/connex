'use client';

import WarmthPill from '@/app/components/records/WarmthPill';
import type { Contact, RelationshipTemperature } from '@/app/lib/types';

function secondaryLine(contact: Contact): string {
    const parts = [contact.title, contact.company?.name].filter((part) => !!part?.trim());
    return parts.length > 0 ? parts.join(' · ') : contact.email?.trim() ?? '';
}

/**
 * A contact as one row of the viewport-forced mobile list: the name over the single secondary fact
 * worth scanning for (role at employer, falling back to the email address when neither is recorded),
 * with warmth as the trailing decision cue because relationship temperature is what a phone user is
 * triaging by.
 *
 * Both text lines truncate on one line each, so a long Japanese company name shortens the line
 * rather than wrapping the row or widening the list.
 */
export default function ContactListRow({
    contact,
    temperature,
}: {
    contact: Contact;
    temperature?: RelationshipTemperature;
}) {
    const secondary = secondaryLine(contact);
    return (
        <span className="flex min-w-0 items-center gap-2">
            <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium text-foreground">{contact.name}</span>
                {secondary && <span className="mt-0.5 block truncate text-xs text-muted-foreground">{secondary}</span>}
            </span>
            <span className="shrink-0">
                <WarmthPill temp={temperature} />
            </span>
        </span>
    );
}
