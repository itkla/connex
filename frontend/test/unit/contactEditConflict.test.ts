import { describe, expect, it, vi } from 'vitest';
import { changedContactFields, contactDraftChanged, reconcileSavedContactDraft, submitContactCompanyUpdate, submitContactDraftUpdate, toContactDraft } from '@/app/components/records/contacts/contactEditConflict';
import type { Contact } from '@/app/lib/types';
import en from '@/messages/en/contacts.json';
import ja from '@/messages/ja/contacts.json';

const api = vi.hoisted(() => ({ update: vi.fn() }));
vi.mock('@/app/lib/api', async () => ({
    ...await vi.importActual<typeof import('@/app/lib/api')>('@/app/lib/api'),
    updateContact: api.update,
}));

const base: Contact = {
    id: 7, workspaceId: 17, version: 4, name: 'Contact', email: '', phone: '111',
    title: 'Director', companyId: 2, imageUrl: '', createdAt: '', updatedAt: '',
};
const origin = { headers: { 'X-Workspace-Id': '17' } };

describe('contact edit snapshots', () => {
    it('submits the original token and company with the edited text in the captured workspace', async () => {
        const draft = { ...toContactDraft(base), phone: '222' };
        const saved = { ...base, version: 5, phone: '222' };
        api.update.mockResolvedValueOnce(saved);
        expect(await submitContactDraftUpdate(base, draft, origin)).toBe(saved);
        expect(api.update).toHaveBeenLastCalledWith(7, {
            expectedVersion: 4, name: 'Contact', email: '', phone: '222', title: 'Director', companyId: 2,
        }, origin);
    });

    it('keeps company actions conditional so stale text cannot overwrite a second writer', async () => {
        api.update.mockResolvedValueOnce({ ...base, version: 5, companyId: null });
        await submitContactCompanyUpdate(base, null, origin);
        expect(api.update).toHaveBeenLastCalledWith(7, expect.objectContaining({ expectedVersion: 4, companyId: null }), origin);
    });

    it('keeps new input typed during a save and adopts the acknowledged version', () => {
        const submitted = { ...toContactDraft(base), phone: '222' };
        const newer = { ...submitted, title: 'VP', name: 'New input' };
        const saved = { ...base, version: 5, phone: '222' };
        expect(reconcileSavedContactDraft(newer, submitted, saved)).toEqual({ ...newer, expectedVersion: 5 });
        expect(reconcileSavedContactDraft(submitted, submitted, saved)).toEqual(toContactDraft(saved));
        expect(contactDraftChanged(toContactDraft(saved), reconcileSavedContactDraft(submitted, submitted, saved))).toBe(false);
        expect(contactDraftChanged(toContactDraft(saved), reconcileSavedContactDraft(newer, submitted, saved))).toBe(true);
    });

    it('compares the base rather than edited phone text and names the competing title change', () => {
        expect(changedContactFields(base, { ...base, version: 5, title: 'VP' })).toEqual(['title']);
        expect(changedContactFields(base, { ...base, version: 5, ownerId: 3, companyId: undefined, imageUrl: '/new' }))
            .toEqual(['companyId', 'ownerId', 'imageUrl']);
    });

    it('covers every changed-field label in English and natural contact Japanese', () => {
        expect(ja.ContactsEditConflict.title).toContain('連絡先');
        expect(Object.keys(en.ContactsEditConflict.fields)).toEqual(Object.keys(ja.ContactsEditConflict.fields));
        for (const field of changedContactFields(base, { ...base, title: 'VP', phone: '222' })) {
            expect(en.ContactsEditConflict.fields[field]).toBeTruthy();
            expect(ja.ContactsEditConflict.fields[field]).toBeTruthy();
        }
    });
});
