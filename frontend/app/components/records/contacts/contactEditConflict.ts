import { updateContact } from '@/app/lib/api';
import type { Contact, UpdateContactPayload } from '@/app/lib/types';
import { changedRecordFields } from '@/app/lib/recordEditConflict';
import type { ContactDraft } from './QuickEditSheet';

/** Persisted contact fields that can change while an edit remains open. */
export const CONTACT_CONFLICT_FIELDS = [
    'name', 'email', 'phone', 'title', 'companyId', 'ownerId', 'imageUrl', 'riskExcluded',
    'introExcluded', 'suspendedAt', 'provisionCeasedAt', 'archivedAt', 'lifecycleStage',
    'lifecycleChangedAt', 'disqualifiedReason', 'qualificationNotes', 'leadSource',
    'leadSourceDetail', 'referrerPersonId', 'firstResponseDueAt', 'firstRespondedAt', 'firstResponseBreachedAt',
] as const satisfies readonly (keyof Contact)[];

/** Names changes against the snapshot that seeded the form, rather than the edited draft. */
export function changedContactFields(base: Contact, current: Contact): (typeof CONTACT_CONFLICT_FIELDS)[number][] {
    return changedRecordFields(base, current, CONTACT_CONFLICT_FIELDS);
}

/** Captures editable fields and the concurrency token from the same contact snapshot. */
export function toContactDraft(contact: Contact): ContactDraft {
    return {
        expectedVersion: contact.version,
        name: contact.name ?? '', email: contact.email ?? '',
        phone: contact.phone ?? '', title: contact.title ?? '',
    };
}

/** Compares editable text independently of an acknowledged version change. */
export function contactDraftChanged(base: ContactDraft, draft: ContactDraft): boolean {
    return base.name !== draft.name || base.email !== draft.email || base.phone !== draft.phone || base.title !== draft.title;
}

/** Preserves input made during an in-flight save while advancing its acknowledged token. */
export function reconcileSavedContactDraft(current: ContactDraft, submitted: ContactDraft, saved: Contact): ContactDraft {
    return current === submitted ? toContactDraft(saved) : { ...current, expectedVersion: saved.version };
}

/** Submits a whole-record edit against its immutable base and captured workspace. */
export function submitContactDraftUpdate(base: Contact, draft: ContactDraft, init: RequestInit): Promise<Contact> {
    const payload: UpdateContactPayload = {
        expectedVersion: draft.expectedVersion,
        name: draft.name.trim(), email: draft.email.trim() || undefined,
        phone: draft.phone.trim() || undefined, title: draft.title.trim() || undefined,
        companyId: base.companyId ?? base.company?.id ?? null,
    };
    return updateContact(base.id, payload, init);
}

/** Conditions company-only UI actions on the full snapshot they must send to the legacy PUT. */
export function submitContactCompanyUpdate(base: Contact, companyId: number | null, init: RequestInit): Promise<Contact> {
    return updateContact(base.id, {
        expectedVersion: base.version,
        name: base.name, email: base.email || undefined, phone: base.phone || undefined,
        title: base.title || undefined, companyId,
    }, init);
}
