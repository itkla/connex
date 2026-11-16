// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ContactMarketingPreferences from '@/app/components/records/contacts/ContactMarketingPreferences';
import type { Contact, ContactMarketingStatus } from '@/app/lib/types';
import en from '@/messages/en/contacts.json';
import ja from '@/messages/ja/contacts.json';
import enCommon from '@/messages/en/common.json';
import jaCommon from '@/messages/ja/common.json';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const api = vi.hoisted(() => ({ consent: vi.fn(), status: vi.fn(), suppressions: vi.fn(), set: vi.fn(), remove: vi.fn(), refresh: vi.fn(), error: vi.fn() }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh: api.refresh }) }));
vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => ({ activeWorkspaceId: 17 }) }));
vi.mock('@/app/hooks/usePermissions', () => ({ usePermissionCheck: (key: string) => key === 'CONSENT_MANAGE' ? 'granted' : 'denied' }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => api.error }));
vi.mock('@/app/lib/toast', () => ({ toastSuccess: vi.fn() }));
vi.mock('@/app/lib/renderedWorkspace', () => ({ renderedWorkspaceId: () => 17, workspacePinnedRequest: () => ({ headers: { 'X-Workspace-Id': '17' } }) }));
vi.mock('@/app/lib/api', async () => ({
    ...await vi.importActual<typeof import('@/app/lib/api')>('@/app/lib/api'),
    getPersonConsent: api.consent, getPersonMarketingStatus: api.status, getPersonSuppressions: api.suppressions,
    setPersonConsent: api.set, deleteSuppression: api.remove,
}));

const contact: Contact = { id: 7, workspaceId: 17, name: 'Contact', email: 'contact@example.invalid', phone: '', title: '', imageUrl: '', createdAt: '', updatedAt: '' };
const status: ContactMarketingStatus = { personId: 7, privacyHold: false, channels: [{ channel: 'email', addressable: true, optedOut: false, consentRevoked: false, doNotContact: false }] };

beforeEach(() => {
    vi.resetAllMocks();
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
    api.consent.mockResolvedValue([]);
    api.status.mockResolvedValue(status);
    api.suppressions.mockResolvedValue({ items: [] });
    api.set.mockResolvedValue({});
    api.remove.mockResolvedValue(undefined);
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

async function mount(locale: 'en' | 'ja' = 'en') {
    const copy = locale === 'en' ? en : ja;
    const view = (record: Contact, marketingStatus: ContactMarketingStatus) => (
        <NextIntlClientProvider locale={locale} messages={{ ...(locale === 'en' ? enCommon : jaCommon), ...copy }}>
            <ContactMarketingPreferences contact={record} status={marketingStatus} />
        </NextIntlClientProvider>
    );
    const rendered = render(view(contact, status));
    const opener = await screen.findByRole('button', { name: copy.ContactMarketingPreferences.actions.granted });
    return {
        opener, copy: copy.ContactMarketingPreferences,
        panel: screen.getByRole('region', { name: copy.ContactMarketingPreferences.title }),
        refresh: (record: Contact, marketingStatus: ContactMarketingStatus) => rendered.rerender(view(record, marketingStatus)),
    };
}

describe('marketing confirmation focus with the real dialog primitive', () => {
    for (const locale of ['en', 'ja'] as const) {
        it(`keeps the next confirmation open across a late route refresh (${locale})`, async () => {
            const consent = [{ channel: 'email', purpose: 'marketing', status: 'granted', version: 3 }];
            api.consent.mockResolvedValue(consent);
            const { copy, refresh, panel } = await mount(locale);
            const opener = screen.getByRole('button', { name: copy.actions.revoked });
            fireEvent.click(opener);
            const dialog = await screen.findByRole('dialog', { name: copy.actions.revoked });
            const refreshedConsent = Promise.withResolvers<typeof consent>();
            api.consent.mockReturnValueOnce(refreshedConsent.promise);
            const blocked = { ...status, channels: [{ ...status.channels[0], doNotContact: true }] };
            api.status.mockResolvedValue(blocked);
            api.suppressions.mockResolvedValue({ items: [{ id: 20, channel: 'email', address: contact.email, reason: 'do_not_contact' }] });

            refresh({ ...contact, updatedAt: '2026-11-05T00:00:00Z' }, blocked);
            expect(screen.queryByRole('dialog', { name: copy.actions.revoked })).toBe(dialog);
            expect(api.consent).toHaveBeenCalledTimes(2);
            expect(within(dialog).getByRole('button', { name: copy.confirm }).hasAttribute('disabled')).toBe(false);
            api.set.mockImplementationOnce(async () => {
                api.consent.mockResolvedValue([{ ...consent[0], status: 'revoked', version: 4 }]);
                return {};
            });
            fireEvent.click(within(dialog).getByRole('button', { name: copy.confirm }));
            await waitFor(() => expect(api.set).toHaveBeenCalledExactlyOnceWith(7, expect.objectContaining({ status: 'revoked', expectedVersion: 3 })));
            await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
            await waitFor(() => expect(document.activeElement).toBe(panel));
            await act(async () => refreshedConsent.resolve(consent));
            expect(screen.getByText(copy.recordedConsent.replace('{status}', copy.statuses.revoked))).toBeDefined();
            expect(opener.hasAttribute('disabled')).toBe(true);
        });

        it.each(['cancel', 'escape'] as const)(`returns focus to the keyboard opener after %s (${locale})`, async (dismissal) => {
            const { opener, copy } = await mount(locale);
            act(() => opener.focus());
            fireEvent.click(opener, { detail: 0 });
            const dialog = await screen.findByRole('dialog', { name: copy.actions.granted });
            await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true));
            if (dismissal === 'cancel') fireEvent.click(within(dialog).getByRole('button', { name: copy.cancel }), { detail: 0 });
            else fireEvent.keyDown(document.activeElement ?? dialog, { key: 'Escape' });
            await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
            await waitFor(() => expect(document.activeElement).toBe(opener));
        });
    }

    it('focuses the stable panel when a late refresh supersedes the post-save read', async () => {
        const ownRead = Promise.withResolvers<{ channel: string; purpose: string; status: string; version: number }[]>();
        const refreshedRead = Promise.withResolvers<{ channel: string; purpose: string; status: string; version: number }[]>();
        api.set.mockImplementationOnce(async () => {
            api.consent.mockReturnValueOnce(ownRead.promise);
            return {};
        });
        const { opener, copy, panel, refresh } = await mount();
        fireEvent.click(opener);
        const dialog = await screen.findByRole('dialog', { name: copy.actions.granted });
        fireEvent.click(within(dialog).getByRole('button', { name: copy.confirm }));
        await waitFor(() => expect(api.consent).toHaveBeenCalledTimes(2));
        api.consent.mockReturnValueOnce(refreshedRead.promise);
        refresh({ ...contact, updatedAt: '2026-11-05T00:00:00Z' }, status);
        expect(api.consent).toHaveBeenCalledTimes(3);
        const granted = [{ channel: 'email', purpose: 'marketing', status: 'granted', version: 1 }];
        await act(async () => ownRead.resolve(granted));
        await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
        expect(opener.hasAttribute('disabled')).toBe(false);
        await waitFor(() => expect(document.activeElement).toBe(panel));
        await act(async () => refreshedRead.resolve(granted));
        expect(opener.hasAttribute('disabled')).toBe(true);
        expect(document.activeElement).toBe(panel);
    });

    it('uses the stable panel fallback when the saved consent action becomes disabled', async () => {
        api.set.mockImplementation(async () => {
            api.consent.mockResolvedValue([{ channel: 'email', purpose: 'marketing', status: 'granted', version: 1 }]);
            return {};
        });
        const { opener, copy, panel } = await mount();
        act(() => opener.focus());
        fireEvent.click(opener, { detail: 0 });
        const dialog = await screen.findByRole('dialog', { name: copy.actions.granted });
        fireEvent.click(within(dialog).getByRole('button', { name: copy.confirm }), { detail: 0 });
        await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
        expect(opener.getAttribute('disabled')).not.toBeNull();
        await waitFor(() => expect(document.activeElement).toBe(panel));
    });

    it('uses the stable panel fallback after removing the restriction that owned the opener', async () => {
        api.suppressions.mockResolvedValue({ items: [{ id: 20, channel: 'email', address: contact.email, reason: 'do_not_contact' }] });
        api.remove.mockImplementation(async () => { api.suppressions.mockResolvedValue({ items: [] }); });
        const { copy, panel } = await mount();
        const opener = screen.getByRole('button', { name: copy.remove });
        act(() => opener.focus());
        fireEvent.click(opener, { detail: 0 });
        const dialog = await screen.findByRole('dialog', { name: copy.remove });
        fireEvent.click(within(dialog).getByRole('button', { name: copy.confirm }), { detail: 0 });
        await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
        expect(opener.isConnected).toBe(false);
        await waitFor(() => expect(document.activeElement).toBe(panel));
    });
});
