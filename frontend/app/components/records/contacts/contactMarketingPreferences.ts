import type { Contact, SuppressionEntry } from '@/app/lib/types';

export const MARKETING_CHANNELS = ['email', 'sms', 'line', 'whatsapp'] as const;
export type MarketingChannel = (typeof MARKETING_CHANNELS)[number];

/** Mirrors the existing delivery address contract for identifying restrictions in the workspace list. */
export function marketingAddress(contact: Contact, channel: MarketingChannel): string | null {
    if (channel === 'email') return contact.email?.trim().toLowerCase() || null;
    if (channel !== 'sms') return null;
    const phone = contact.phone?.trim() ?? '';
    const digits = phone.replace(/[^0-9]/g, '');
    return digits.length < 7 ? null : `${phone.startsWith('+') ? '+' : ''}${digits}`;
}

/** Includes person-sticky restrictions and restrictions on the current address, as dispatch does. */
export function contactSuppressions(contact: Contact, channel: MarketingChannel, entries: SuppressionEntry[]) {
    const address = marketingAddress(contact, channel);
    return entries.filter((entry) => entry.channel === channel
        && (entry.personId === contact.id || (address !== null && entry.address === address)));
}
