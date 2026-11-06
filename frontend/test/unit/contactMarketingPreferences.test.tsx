// @vitest-environment jsdom
import { act, type ReactNode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import ContactMarketingPreferences from '@/app/components/records/contacts/ContactMarketingPreferences';
import { marketingAddress } from '@/app/components/records/contacts/contactMarketingPreferences';
import { ApiError } from '@/app/lib/api';
import type { Contact, ContactMarketingStatus, ContactSuppression } from '@/app/lib/types';
import en from '@/messages/en/contacts.json';
import ja from '@/messages/ja/contacts.json';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const state = vi.hoisted(() => ({ workspaceId: 17, renderedId: 17, permissions: new Set<string>(), unavailable: false }));
const api = vi.hoisted(() => ({ consent: vi.fn(), status: vi.fn(), suppressions: vi.fn(), workspaceSuppressions: vi.fn(), set: vi.fn(), add: vi.fn(), remove: vi.fn(), restrict: vi.fn(), success: vi.fn(), error: vi.fn(), refresh: vi.fn() }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh: api.refresh }) }));
vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => ({ activeWorkspaceId: state.workspaceId }) }));
vi.mock('@/app/hooks/usePermissions', () => ({
    usePermissionCheck: (key: string) => state.unavailable ? 'unavailable' : state.permissions.has(key) ? 'granted' : 'denied',
}));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => api.error }));
vi.mock('@/app/lib/toast', () => ({ toastSuccess: api.success }));
vi.mock('@/components/ui/dialog-status-cover', () => ({ DialogStatusCover: () => null, resolveDialogStatus: () => 'idle' }));
vi.mock('@/app/lib/renderedWorkspace', () => ({
    renderedWorkspaceId: () => state.renderedId,
    workspacePinnedRequest: (id: number) => ({ headers: { 'X-Workspace-Id': String(id) } }),
}));
vi.mock('@/app/lib/api', async () => ({
    ...await vi.importActual<typeof import('@/app/lib/api')>('@/app/lib/api'),
    getPersonConsent: api.consent, getPersonMarketingStatus: api.status, getPersonSuppressions: api.suppressions, getSuppressions: api.workspaceSuppressions,
    setPersonConsent: api.set, createSuppression: api.add, deleteSuppression: api.remove,
    updateContactRestrictions: api.restrict,
}));
vi.mock('@/components/ui/dialog', () => {
    const Pass = ({ children }: { children?: ReactNode }) => <>{children}</>;
    return {
        Dialog: ({ open, children }: { open: boolean; children?: ReactNode }) => open ? <div role="dialog">{children}</div> : null,
        DialogContent: Pass, DialogDescription: Pass, DialogFooter: Pass, DialogHeader: Pass, DialogTitle: Pass, DialogClose: Pass,
    };
});

const contact: Contact = { id: 7, workspaceId: 17, name: 'Contact', email: ' Contact@Example.Invalid ', phone: '+81 90-1234-5678', title: '', imageUrl: '', createdAt: '', updatedAt: '' };
const status: ContactMarketingStatus = { personId: 7, privacyHold: false, channels: [{ channel: 'email', addressable: true, optedOut: false, consentRevoked: false, doNotContact: false }] };
const restriction: ContactSuppression = { id: 20, channel: 'email', address: 'old@example.invalid', reason: 'do_not_contact' };
const t = en.ContactMarketingPreferences;
let container: HTMLDivElement;
let root: Root;

beforeEach(() => {
    vi.resetAllMocks();
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
    state.workspaceId = 17;
    state.renderedId = 17;
    state.permissions = new Set(['CONSENT_MANAGE']);
    state.unavailable = false;
    api.consent.mockResolvedValue([]);
    api.status.mockResolvedValue(status);
    api.suppressions.mockResolvedValue({ items: [] });
    api.set.mockResolvedValue({});
    api.add.mockResolvedValue({});
    api.remove.mockResolvedValue(undefined);
    container = document.createElement('div');
    document.body.append(container);
    root = createRoot(container);
});
afterEach(async () => { await act(async () => root.unmount()); container.remove(); vi.unstubAllGlobals(); });

async function render(record = contact, locale: 'en' | 'ja' = 'en', currentStatus = status) {
    await act(async () => root.render(<NextIntlClientProvider locale={locale} messages={locale === 'en' ? en : ja}>
        <ContactMarketingPreferences contact={record} status={currentStatus} />
    </NextIntlClientProvider>));
}
function button(label: string): HTMLButtonElement {
    const found = [...container.querySelectorAll('button')].find((element) => element.textContent === label);
    if (!found) throw new Error(`Missing button: ${label}`);
    return found;
}
async function click(label: string) { await act(async () => button(label).click()); }
async function confirm(label: string) { await click(label); await click(t.confirm); }

describe('contact marketing preferences', () => {
    it('does not use the old restriction cursor while a contact snapshot is refreshing', async () => {
        api.suppressions.mockResolvedValueOnce({ items: [restriction], nextAfterId: 249 });
        await render();
        const freshPage = Promise.withResolvers<{ items: ContactSuppression[]; nextAfterId?: number }>();
        api.suppressions.mockReturnValueOnce(freshPage.promise);
        await render({ ...contact, email: 'changed@example.invalid' });
        expect(button(t.moreRestrictions).disabled).toBe(true);
        await click(t.moreRestrictions);
        expect(api.suppressions).toHaveBeenCalledTimes(2);
        await act(async () => freshPage.resolve({ items: [], nextAfterId: 50 }));
        expect(button(t.moreRestrictions).disabled).toBe(false);
        api.suppressions.mockResolvedValueOnce({ items: [] });
        await click(t.moreRestrictions);
        expect(api.suppressions).toHaveBeenLastCalledWith(7, 50, { headers: { 'X-Workspace-Id': '17' } });
    });

    it.each([null, 403])('ignores an obsolete restriction page after a contact snapshot refresh (status=%s)', async (statusCode) => {
        api.suppressions.mockResolvedValueOnce({ items: [restriction], nextAfterId: 20 });
        await render();
        const oldPage = Promise.withResolvers<{ items: ContactSuppression[]; nextAfterId?: number }>();
        api.suppressions.mockReturnValueOnce(oldPage.promise);
        await click(t.moreRestrictions);
        const freshEntry = { ...restriction, id: 21, address: 'changed@example.invalid' };
        api.suppressions.mockResolvedValueOnce({ items: [freshEntry] });
        await render({ ...contact, email: freshEntry.address });
        expect(container.textContent).toContain(freshEntry.address);
        await act(async () => {
            if (statusCode === null) oldPage.resolve({ items: [{ ...restriction, id: 22 }], nextAfterId: 22 });
            else oldPage.reject(new ApiError('Refused', statusCode));
        });
        expect(container.textContent).toContain(freshEntry.address);
        expect(container.textContent).not.toContain(restriction.address);
        expect(container.querySelectorAll('li')).toHaveLength(1);
        expect(container.textContent).not.toContain(t.moreRestrictions);
        expect(button(t.actions.granted).disabled).toBe(false);
        expect(api.error).not.toHaveBeenCalled();
    });

    it('does not report no restrictions while another channel occupies the loaded page', async () => {
        api.suppressions.mockResolvedValueOnce({ items: [{ ...restriction, channel: 'sms' }], nextAfterId: 20 });
        await render();
        expect(container.textContent).toContain(t.restrictionsIncomplete);
        expect(container.textContent).not.toContain(t.noRestrictions);
        api.suppressions.mockResolvedValueOnce({ items: [restriction] });
        await click(t.moreRestrictions);
        expect(container.textContent).toContain(restriction.address);
        expect(container.textContent).not.toContain(t.restrictionsIncomplete);
    });

    it.each([401, 403])('discards privileged rows and controls after a paging %s refusal', async (statusCode) => {
        api.suppressions.mockResolvedValueOnce({ items: [restriction], nextAfterId: 20 });
        await render();
        expect(container.textContent).toContain(restriction.address);
        api.suppressions.mockRejectedValueOnce(new ApiError('Refused', statusCode));
        await click(t.moreRestrictions);
        expect(container.textContent).not.toContain(restriction.address);
        expect(container.textContent).not.toContain(t.actions.granted);
        expect(container.textContent).toContain(statusCode === 403 ? t.permissionDenied : t.loadFailed);
    });

    it('is wired into contact details and states the actual opt-out basis in both languages', async () => {
        expect(readFileSync('app/(app)/records/contacts/[id]/page.tsx', 'utf8')).toContain('<ContactMarketingPreferences contact={contact} status={marketingStatus} />');
        for (const locale of ['en', 'ja'] as const) {
            await render(contact, locale);
            const copy = locale === 'en' ? t : ja.ContactMarketingPreferences;
            expect(container.textContent).toContain(copy.policy);
            expect(container.textContent).toContain(copy.dispatch);
            expect(container.textContent).toContain(copy.statuses.unknown);
            expect(container.textContent).toContain(copy.basis);
        }
        expect(api.consent).toHaveBeenCalledWith(7, expect.objectContaining({ headers: { 'X-Workspace-Id': '17' } }));
        expect(api.suppressions).toHaveBeenCalledWith(7, 0, expect.objectContaining({ headers: { 'X-Workspace-Id': '17' } }));
        expect(api.workspaceSuppressions).not.toHaveBeenCalled();
    });

    it('withholds privileged reads and controls without CONSENT_MANAGE while privacy uses PERSON_UPDATE', async () => {
        state.permissions = new Set(['PERSON_UPDATE']);
        await render();
        expect(container.textContent).toContain(t.permissionDenied);
        expect(button(t.privacyAction)).toBeDefined();
        expect(api.consent).not.toHaveBeenCalled();
        expect(api.suppressions).not.toHaveBeenCalled();
        expect(container.textContent).not.toContain(t.actions.granted);
        state.permissions = new Set(['CONSENT_MANAGE']);
        await render();
        expect(container.textContent).not.toContain(t.privacyAction);
        state.unavailable = true;
        await render();
        expect(container.textContent).toContain(t.permissionUnavailable);
        expect(container.textContent).not.toContain(t.actions.granted);
    });

    it('does not read or edit another workspace contact, including contacts without an owning id', async () => {
        await render({ ...contact, workspaceId: 18 });
        expect(container.textContent).toBe('');
        await render({ ...contact, workspaceId: undefined });
        expect(container.textContent).toBe('');
        expect(api.consent).not.toHaveBeenCalled();
        expect(api.suppressions).not.toHaveBeenCalled();
    });

    it('records opt-in separately and keeps a server-reported restriction absolute', async () => {
        api.consent.mockResolvedValue([{ channel: 'email', purpose: 'marketing', status: 'revoked', version: 2 }]);
        api.suppressions.mockResolvedValue({ items: [restriction] });
        api.status.mockResolvedValue({ ...status, channels: [{ ...status.channels[0], optedOut: true, doNotContact: true }] });
        await render();
        await click(t.actions.granted);
        expect(container.textContent).toContain(t.clearOptOutWarning);
        expect(api.set).not.toHaveBeenCalled();
        api.consent.mockResolvedValue([{ channel: 'email', purpose: 'marketing', status: 'granted', version: 3 }]);
        await click(t.confirm);
        expect(api.set).toHaveBeenCalledExactlyOnceWith(7, { channel: 'email', purpose: 'marketing', status: 'granted', source: 'manual', evidenceRef: null, capturedAt: null, expectedVersion: 2 });
        expect(api.remove).not.toHaveBeenCalled();
        expect(container.textContent).toContain(t.blocked);
        expect(container.textContent).toContain(t.statuses.granted);
    });

    it.each(['revoked', 'unknown'] as const)('records an explicit %s marketing state without touching another purpose', async (value) => {
        api.consent.mockResolvedValue([{ channel: 'email', purpose: 'marketing', status: 'granted', version: 3 }, { channel: 'sms', purpose: 'service', status: 'granted', version: 3 }]);
        await render();
        await confirm(t.actions[value]);
        expect(api.set).toHaveBeenCalledWith(7, expect.objectContaining({ purpose: 'marketing', channel: 'email', status: value }));
        expect(api.remove).not.toHaveBeenCalled();
    });

    it('adds a workspace-scoped person-sticky do-not-contact entry only after confirmation', async () => {
        await render();
        await click(t.restrict);
        expect(api.add).not.toHaveBeenCalled();
        await click(t.confirm);
        expect(api.add).toHaveBeenCalledExactlyOnceWith({ scope: 'workspace', channel: 'email', address: 'contact@example.invalid', personId: 7, reason: 'do_not_contact' });
        expect(api.set).not.toHaveBeenCalled();
    });

    it('removes only the explicitly selected matching entry and explains its shared-address effect', async () => {
        api.suppressions.mockResolvedValue({ items: [restriction] });
        await render();
        await click(t.remove);
        expect(container.querySelector('[role="dialog"]')?.textContent).toContain(t.removeWarning.replace('{name}', contact.name));
        expect(api.remove).not.toHaveBeenCalled();
        await click(t.confirm);
        expect(api.remove).toHaveBeenCalledExactlyOnceWith(20);
        expect(api.set).not.toHaveBeenCalled();
    });

    it('keeps failed and forbidden reads distinct from unknown consent and recovers by retrying', async () => {
        api.suppressions.mockRejectedValueOnce(new ApiError('Denied', 403));
        await render();
        expect(container.textContent).toContain(t.permissionDenied);
        expect(container.textContent).not.toContain(t.statuses.unknown);
        api.consent.mockRejectedValueOnce(new Error('Unavailable'));
        await click(t.retry);
        expect(container.textContent).toContain(t.loadFailed);
        expect(container.textContent).not.toContain(t.noRestrictions);
        await click(t.retry);
        expect(container.textContent).toContain(t.statuses.unknown);
    });

    it('blocks double submissions and refuses a confirmation after the rendered workspace changes', async () => {
        const pending = Promise.withResolvers<void>();
        api.set.mockReturnValue(pending.promise);
        await render();
        await confirm(t.actions.granted);
        expect(button(t.saving).disabled).toBe(true);
        await click(t.saving);
        expect(api.set).toHaveBeenCalledTimes(1);
        await act(async () => pending.resolve());
        await click(t.actions.revoked);
        state.renderedId = 18;
        await click(t.confirm);
        expect(api.set).toHaveBeenCalledTimes(1);
    });

    it('fails closed after a mutation refusal and does not report a successful save', async () => {
        api.set.mockRejectedValue(new ApiError('Denied', 403));
        await render();
        await confirm(t.actions.granted);
        expect(api.success).not.toHaveBeenCalled();
        expect(api.error).toHaveBeenCalled();
        expect(container.textContent).toContain(t.permissionDenied);
        expect(container.textContent).not.toContain(t.actions.granted);
    });

    it('re-reads a conflicting consent without replaying the write and requires a fresh opt-out-clearing confirmation', async () => {
        await render();
        await click(t.actions.granted);
        api.consent.mockResolvedValue([{ channel: 'email', purpose: 'marketing', status: 'revoked', version: 4 }]);
        api.set.mockRejectedValueOnce(new ApiError('Changed', 409, 'STALE_RECORD'));
        await click(t.confirm);
        expect(api.set).toHaveBeenCalledExactlyOnceWith(7, expect.objectContaining({ status: 'granted', expectedVersion: 0 }));
        expect(container.querySelector('[role="dialog"]')).toBeNull();
        expect(container.textContent).toContain(t.conflict);
        expect(container.textContent).toContain(t.statuses.revoked);
        expect(api.success).not.toHaveBeenCalled();
        await click(t.actions.granted);
        expect(container.textContent).toContain(t.clearOptOutWarning);
        expect(api.set).toHaveBeenCalledTimes(1);
        api.consent.mockResolvedValue([{ channel: 'email', purpose: 'marketing', status: 'granted', version: 5 }]);
        await click(t.confirm);
        expect(api.set).toHaveBeenNthCalledWith(2, 7, expect.objectContaining({ status: 'granted', expectedVersion: 4 }));
        expect(api.success).toHaveBeenCalledTimes(1);
    });

    it('resets the rejected-save busy state so a retry and a second confirmed save can complete', async () => {
        api.set.mockRejectedValueOnce(new Error('Unavailable'));
        await render();
        await confirm(t.actions.granted);
        expect(container.textContent).toContain(t.loadFailed);
        expect(api.success).not.toHaveBeenCalled();
        await click(t.retry);
        await click(t.actions.granted);
        expect(button(t.confirm).disabled).toBe(false);
        expect(container.textContent).not.toContain(t.saving);
        await click(t.confirm);
        expect(api.set).toHaveBeenCalledTimes(2);
        expect(api.success).toHaveBeenCalledTimes(1);
    });

    it('loads subsequent bounded contact pages and preserves the current rows after a rejected page request', async () => {
        api.suppressions.mockResolvedValueOnce({ items: [restriction], nextAfterId: 20 });
        await render();
        const rejectedPage = Promise.withResolvers<never>();
        api.suppressions.mockReturnValueOnce(rejectedPage.promise);
        await click(t.moreRestrictions);
        expect(button(t.moreRestrictions).disabled).toBe(true);
        await act(async () => rejectedPage.reject(new Error('Unavailable')));
        expect(button(t.moreRestrictions).disabled).toBe(false);
        expect(container.textContent).toContain(restriction.address);
        api.suppressions.mockResolvedValueOnce({ items: [restriction, { ...restriction, id: 21, address: 'next@example.invalid' }] });
        await click(t.moreRestrictions);
        expect(api.suppressions).toHaveBeenLastCalledWith(7, 20, { headers: { 'X-Workspace-Id': '17' } });
        expect(container.textContent).toContain('next@example.invalid');
        expect(container.querySelectorAll('li')).toHaveLength(2);
        expect(container.textContent).not.toContain(t.moreRestrictions);
        expect(api.workspaceSuppressions).not.toHaveBeenCalled();
    });

    it('rechecks eligibility after a server refresh changes privacy or the contact address', async () => {
        await render();
        expect(container.textContent).toContain(t.basis);
        const held = { ...status, privacyHold: true, suspendedAt: '2026-11-05T00:00:00Z' };
        api.status.mockResolvedValue(held);
        await render({ ...contact, suspendedAt: held.suspendedAt }, 'en', held);
        expect(container.textContent).toContain(t.privacyBlocked);
        expect(container.textContent).toContain(t.blocked);
        expect(container.textContent).not.toContain(t.basis);
        api.status.mockResolvedValue(status);
        await render({ ...contact, email: 'changed@example.invalid' });
        expect(api.status).toHaveBeenCalledTimes(3);
        expect(container.textContent).toContain(t.basis);
    });

    it('opens privacy controls with the latest hold after another entry point refreshed the contact', async () => {
        state.permissions.add('PERSON_UPDATE');
        await render();
        const held = { ...status, privacyHold: true, suspendedAt: '2026-11-05T00:00:00Z' };
        api.status.mockResolvedValue(held);
        await render({ ...contact, suspendedAt: held.suspendedAt }, 'en', held);
        await click(t.privacyAction);
        expect(container.querySelector('#restrict-suspend')?.getAttribute('aria-checked')).toBe('true');
        expect(button(en.ContactRestrictions.save).disabled).toBe(true);
    });

    it('does not imply an archived contact has no recorded delivery block', async () => {
        await render({ ...contact, archivedAt: '2026-11-05T00:00:00Z' });
        expect(container.textContent).toContain(t.archivedBlocked);
        expect(container.textContent).not.toContain(t.basis);
    });

    it.each([false, true])('submits only the privacy flag explicitly changed from suspended=%s', async (initialSuspended) => {
        state.permissions.add('PERSON_UPDATE');
        const record = initialSuspended ? { ...contact, suspendedAt: '2026-11-05T00:00:00Z' } : contact;
        api.restrict.mockRejectedValueOnce(new Error('Unavailable'));
        await render(record);
        await click(t.privacyAction);
        const changed = container.querySelector(initialSuspended ? '#restrict-suspend' : '#restrict-provision');
        if (!(changed instanceof HTMLButtonElement)) throw new Error('The privacy switch is missing');
        await act(async () => changed.click());
        await click(en.ContactRestrictions.save);
        expect(api.restrict).toHaveBeenCalledExactlyOnceWith(7, initialSuspended ? { suspended: false } : { provisionCeased: true });
    });
});

describe('restriction creation uses the existing delivery address contract', () => {
    it('uses canonical phone addresses and does not invent addresses for unsupported channels', () => {
        expect(marketingAddress(contact, 'sms')).toBe('+819012345678');
        expect(marketingAddress({ ...contact, phone: '123' }, 'sms')).toBeNull();
        expect(marketingAddress(contact, 'line')).toBeNull();
        expect(marketingAddress(contact, 'whatsapp')).toBeNull();
    });
});
