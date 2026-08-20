import type { CalendarEvent } from '@/app/lib/calendar';

/** The contact and deal a calendar entry touches, whichever kind of entry it is. */
export function linkedIds(event: CalendarEvent): { personId: number | null; dealId: number | null } {
    switch (event.kind) {
        case 'task':
        case 'activity':
            return { personId: event.raw.personId ?? null, dealId: event.raw.dealId ?? null };
        case 'note':
            return { personId: event.raw.person ?? null, dealId: event.raw.deal ?? null };
        case 'deal':
            return { personId: null, dealId: null };
    }
}
