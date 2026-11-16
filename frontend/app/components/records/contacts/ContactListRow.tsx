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
 * Below 400px the name spans the row and can use two lines so full-width Japanese glyphs remain
 * readable. Wider lists keep a single-line name and trailing warmth cue. Long values stay bounded.
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
        <span className="grid min-w-0 grid-cols-[minmax(0,1fr)_auto] items-center gap-x-2">
            <span className="col-span-2 min-w-0 line-clamp-2 wrap-break-word whitespace-normal text-sm font-medium text-foreground min-[400px]:col-span-1 min-[400px]:line-clamp-none min-[400px]:truncate" title={contact.name}>
                {contact.name}
            </span>
            {secondary && <span className="col-start-1 row-start-2 mt-0.5 block min-w-0 truncate text-xs text-muted-foreground" title={secondary}>{secondary}</span>}
            <span className="col-start-2 row-start-2 mt-0.5 min-[400px]:row-span-2 min-[400px]:row-start-1 min-[400px]:mt-0">
                <WarmthPill temp={temperature} />
            </span>
        </span>
    );
}
