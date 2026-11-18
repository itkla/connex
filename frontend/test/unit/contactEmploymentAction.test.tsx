/** @vitest-environment jsdom */

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ComponentProps } from 'react';
import { ContactEmploymentSection } from '@/app/components/records/DetailCollections';
import type ChangeCompanyDialog from '@/app/components/records/contacts/ChangeCompanyDialog';
import type { DetailSectionResults } from '@/app/hooks/useDetailSections';
import type { Contact } from '@/app/lib/types';
import enContacts from '@/messages/en/contacts.json';
import jaContacts from '@/messages/ja/contacts.json';

const state = vi.hoisted(() => ({ allowed: true, results: {} as DetailSectionResults }));
vi.mock('@/app/hooks/usePermissions', () => ({ usePermission: (permission: string) => permission === 'PERSON_UPDATE' && state.allowed }));
vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => ({ activeWorkspaceId: 7 }) }));
vi.mock('@/app/hooks/useDetailSections', () => ({ useDetailSections: () => ({ results: state.results }) }));
vi.mock('@/app/components/records/DetailSectionState', () => ({ default: ({ children }: { children: React.ReactNode }) => children }));
vi.mock('@/app/components/records/contacts/ChangeCompanyDialog', () => ({
    default: ({ open, onOpenChange, contacts }: ComponentProps<typeof ChangeCompanyDialog>) => open
        ? <div role="dialog" aria-label="Existing company change"><span>{contacts[0].name}</span><button onClick={() => onOpenChange(false)}>Close</button></div>
        : null,
}));

const contact: Contact = { id: 42, workspaceId: 7, companyId: 9, name: 'Contact', email: '', phone: '', title: '', imageUrl: '', createdAt: '', updatedAt: '' };

beforeEach(() => {
    state.allowed = true;
    state.results = { contactEmployment: { kind: 'loaded', data: [
        { id: 1, personId: 42, companyId: 9, companyName: 'Current company', current: true, startedAt: '2026-11-01T00:00:00Z' },
    ] } };
});
afterEach(cleanup);

function section(person = contact, locale: 'en' | 'ja' = 'en') {
    return <NextIntlClientProvider locale={locale} messages={locale === 'en' ? enContacts : jaContacts} timeZone="UTC">
        <ContactEmploymentSection contact={person} />
    </NextIntlClientProvider>;
}

describe('employment history company action', () => {
    it.each(['en', 'ja'] as const)('opens the existing company-change dialog from the %s card', (locale) => {
        const copy = locale === 'en' ? enContacts : jaContacts;
        render(section(contact, locale));
        expect(screen.getByRole('heading', { name: copy.ContactsPage.employmentHistory })).toBeTruthy();
        expect(screen.getByRole('link', { name: 'Current company' }).getAttribute('href')).toBe('/records/companies/9');
        fireEvent.click(screen.getByRole('button', { name: copy.ContactsActionsMenu.changeCompany }));
        expect(screen.getByRole('dialog', { name: 'Existing company change' }).textContent).toContain(contact.name);
        fireEvent.click(screen.getByRole('button', { name: 'Close' }));
        expect(screen.queryByRole('dialog')).toBeNull();
    });

    it('offers association even when employment history is empty', () => {
        state.results = { contactEmployment: { kind: 'loaded', data: [] } };
        render(section({ ...contact, companyId: undefined }));
        expect(screen.getByText(enContacts.ContactsPage.noEmploymentHistory)).toBeTruthy();
        fireEvent.click(screen.getByRole('button', { name: enContacts.ContactsActionsMenu.associateWithCompany }));
        expect(screen.getByRole('dialog')).toBeTruthy();
    });

    it.each(['read-only', 'shared', 'failed history'] as const)('withholds the action for %s', (reason) => {
        state.allowed = reason !== 'read-only';
        if (reason === 'failed history') state.results = { contactEmployment: { kind: 'failed', status: 503 } };
        render(section(reason === 'shared' ? { ...contact, workspaceId: 8 } : contact));
        expect(screen.queryByRole('button')).toBeNull();
    });
});
