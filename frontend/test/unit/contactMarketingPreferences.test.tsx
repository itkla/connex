// @vitest-environment jsdom
import { act, type ReactNode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import ContactMarketingPreferences from '@/app/components/records/contacts/ContactMarketingPreferences';
import { contactSuppressions, marketingAddress } from '@/app/components/records/contacts/contactMarketingPreferences';
import { ApiError } from '@/app/lib/api';
import type { Contact, ContactMarketingStatus, SuppressionEntry } from '@/app/lib/types';
import en from '@/messages/en/contacts.json';
import ja from '@/messages/ja/contacts.json';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const state = vi.hoisted(() => ({ workspaceId: 17, renderedId: 17, permissions: new Set<string>(), unavailable: false }));
const api = vi.hoisted(() => ({ consent: vi.fn(), status: vi.fn(), suppressions: vi.fn(), set: vi.fn(), add: vi.fn(), remove: vi.fn(), success: vi.fn(), error: vi.fn(), refresh: vi.fn() }));
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
    getPersonConsent: api.consent, getPersonMarketingStatus: api.status, getSuppressions: api.suppressions,
    setPersonConsent: api.set, createSuppression: api.add, deleteSuppression: api.remove,
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
const restriction: SuppressionEntry = { id: 20, scope: 'workspace', channel: 'email', address: 'old@example.invalid', personId: 7, reason: 'do_not_contact', note: null, createdById: null, createdAt: '' };
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
    api.suppressions.mockResolvedValue([]);
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
        api.consent.mockResolvedValue([{ channel: 'email', purpose: 'marketing', status: 'revoked' }]);
        api.suppressions.mockResolvedValue([restriction]);
        api.status.mockResolvedValue({ ...status, channels: [{ ...status.channels[0], optedOut: true, doNotContact: true }] });
        await render();
        await click(t.actions.granted);
        expect(container.textContent).toContain(t.clearOptOutWarning);
        expect(api.set).not.toHaveBeenCalled();
        api.consent.mockResolvedValue([{ channel: 'email', purpose: 'marketing', status: 'granted' }]);
        await click(t.confirm);
        expect(api.set).toHaveBeenCalledExactlyOnceWith(7, { channel: 'email', purpose: 'marketing', status: 'granted', source: 'manual', evidenceRef: null, capturedAt: null });
        expect(api.remove).not.toHaveBeenCalled();
        expect(container.textContent).toContain(t.blocked);
        expect(container.textContent).toContain(t.statuses.granted);
    });

    it.each(['revoked', 'unknown'] as const)('records an explicit %s marketing state without touching another purpose', async (value) => {
        api.consent.mockResolvedValue([{ channel: 'email', purpose: 'marketing', status: 'granted' }, { channel: 'sms', purpose: 'service', status: 'granted' }]);
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
        api.suppressions.mockResolvedValue([restriction, { ...restriction, id: 21, personId: 8, address: 'other@example.invalid' }]);
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
});

describe('restriction matching for the existing delivery address contract', () => {
    it('keeps person-linked old addresses and current address-only restrictions, without mixing channels or contacts', () => {
        const addressOnly = { ...restriction, id: 22, personId: null, address: 'contact@example.invalid' };
        expect(contactSuppressions(contact, 'email', [restriction, addressOnly,
            { ...restriction, id: 23, personId: 9 }, { ...restriction, id: 24, channel: 'sms' }]).map((entry) => entry.id)).toEqual([20, 22]);
        expect(marketingAddress(contact, 'sms')).toBe('+819012345678');
        expect(marketingAddress({ ...contact, phone: '123' }, 'sms')).toBeNull();
        expect(marketingAddress(contact, 'line')).toBeNull();
        expect(marketingAddress(contact, 'whatsapp')).toBeNull();
    });
});
