import type { Contact } from '@/app/lib/types';

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
