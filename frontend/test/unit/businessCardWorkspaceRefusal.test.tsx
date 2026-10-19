/** @vitest-environment jsdom */
import { webcrypto } from 'node:crypto';

import { act, useLayoutEffect, useState } from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { createTranslator, NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { BusinessCardCapture } from '@/app/components/records/contacts/BusinessCardCapture';
import { useBusinessCardCapture } from '@/app/components/records/contacts/useBusinessCardCapture';
import { importBusinessCard } from '@/app/lib/api';
import { bindRenderedWorkspace, releaseRenderedWorkspace, rememberWorkspaceNames, workspacePinnedRequest } from '@/app/lib/renderedWorkspace';
import type { BusinessCardImportDraft, CreateContactPayload, Workspace } from '@/app/lib/types';
import contactsEn from '@/messages/en/contacts.json';
import contactsJa from '@/messages/ja/contacts.json';
import errorsEn from '@/messages/en/errors.json';
import errorsJa from '@/messages/ja/errors.json';
import { deferred } from './helpers/workspaceChainBackend';

vi.mock('@/app/hooks/useWorkspace', () => ({
    useWorkspace: () => ({ activeWorkspaceId: 9 }),
}));

const WORKSPACE_ID = 9;
const WORKSPACE_NAME = 'Northwind Field Team';
const CONTACT_NAME = 'Entered before revocation';
const SCAN = '/api/business-cards/scan';
const RESERVATION = '/api/business-cards/import/reservation';
const IMPORT = '/api/business-cards/import';
const shell = {};
const workspace: Workspace = {
    id: WORKSPACE_ID,
    name: WORKSPACE_NAME,
    slug: 'northwind-field-team',
    timezone: null,
    identityVersion: 0,
    role: 'member',
    orgId: 1,
    orgName: 'Northwind',
    orgIdentityVersion: 0,
    orgRole: null,
};

let currentCapture: ReturnType<typeof useBusinessCardCapture> | undefined;
let locksDescriptor: PropertyDescriptor | undefined;

function capture(): ReturnType<typeof useBusinessCardCapture> {
    if (!currentCapture) throw new Error('Business-card capture did not render');
    return currentCapture;
}

function Capture() {
    const [payload, setPayload] = useState<CreateContactPayload>({
        name: CONTACT_NAME, email: '', phone: '', title: '',
    });
    const businessCard = useBusinessCardCapture({ active: true, payload, setPayload });
    useLayoutEffect(() => {
        currentCapture = businessCard;
    });
    return (
        <>
            <input aria-label="Contact name" readOnly value={payload.name} />
            <BusinessCardCapture
                scanAvailable={businessCard.scanAvailable}
                file={businessCard.file}
                previewUrl={businessCard.previewUrl}
                result={businessCard.result}
                status={businessCard.status}
                requestError={businessCard.requestError}
                importError={businessCard.importError}
                workspaceUnavailableMessage={businessCard.workspaceUnavailableMessage}
                requiresExactImportRetry={businessCard.requiresExactImportRetry}
                disabled={false}
                onCancelScan={businessCard.cancelScan}
                onRetryScan={businessCard.retryScan}
            />
        </>
    );
}

function json(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
        status, headers: { 'Content-Type': 'application/json' },
    });
}

function stubBackend(refusedPath: string, permissionDenied = false) {
    const writes: Array<{ path: string; workspace: string | null }> = [];
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
        const path = input instanceof Request ? input.url : String(input);
        if (init?.method === 'POST') {
            writes.push({ path, workspace: new Headers(init.headers).get('X-Workspace-Id') });
        }
        if (path === refusedPath) {
            return json(permissionDenied
                ? { code: 'BUSINESS_CARD_PERMISSION_DENIED', error: 'Business-card permission is required' }
                : { code: 'FORBIDDEN', error: `Not a member of workspace ${WORKSPACE_ID}` }, 403);
        }
        switch (path) {
            case '/api/auth/csrf':
                return json({ token: 'csrf-token', headerName: 'X-XSRF-TOKEN', requestIdentity: 'identity' });
            case '/api/auth/me':
                return json({ id: 7 });
            case '/api/business-cards/availability':
                return json({ scanning: true, importing: true });
            case '/api/permissions/effective':
                return json(['PERSON_CREATE', 'ATTACHMENT_CREATE', 'COMPANY_CREATE']);
            case SCAN:
                return json({ fields: { name: {}, email: {}, phone: {}, title: {} }, company: {}, warnings: [] });
            case RESERVATION:
                return json({ expiresAt: new Date(Date.now() + 60_000).toISOString() });
            default:
                throw new Error(`Unexpected request: ${path}`);
        }
    }));
    return writes;
}

beforeEach(() => {
    vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
    document.cookie = 'connex_workspace=5; path=/';
    bindRenderedWorkspace(shell, WORKSPACE_ID);
    rememberWorkspaceNames([workspace]);
    vi.stubGlobal('crypto', webcrypto);
    vi.stubGlobal('URL', class extends URL {
        static createObjectURL() { return 'blob:business-card'; }
        static revokeObjectURL() {}
    });
    locksDescriptor = Object.getOwnPropertyDescriptor(navigator, 'locks');
    Object.defineProperty(navigator, 'locks', {
        configurable: true,
        value: { request: async (_name: string, options: LockOptions, operation: () => unknown) => {
            options.signal?.throwIfAborted();
            return operation();
        } },
    });
});

afterEach(() => {
    cleanup();
    currentCapture = undefined;
    releaseRenderedWorkspace(shell);
    document.cookie = 'connex_workspace=; path=/; max-age=0';
    window.localStorage.clear();
    if (locksDescriptor) Object.defineProperty(navigator, 'locks', locksDescriptor);
    else Reflect.deleteProperty(navigator, 'locks');
    vi.unstubAllGlobals();
});

async function refuseOperation(path: string, file: File) {
    await waitFor(() => {
        expect(capture().available).toBe(true);
        expect(capture().recoveryStatus).toBe('ready');
    });
    act(() => capture().selectFile(file));
    await waitFor(() => expect(capture().status).toBe(path === SCAN ? 'error' : 'ready'));
    if (path === SCAN) return;

    act(() => capture().selectCompanyMode('none'));
    let draft: BusinessCardImportDraft | undefined;
    await act(async () => { draft = await capture().prepareImportDraft(false); });
    if (path === RESERVATION) {
        expect(draft).toBeUndefined();
        return;
    }
    if (!draft) throw new Error('Business-card import draft was not prepared');
    const error = await importBusinessCard(draft).then(() => null, (failure: unknown) => failure);
    act(() => capture().captureImportError(error));
}

describe('business-card refusals stay bound to the named workspace', () => {
    it.each(['already aborted', 'during identity'])('cancels import preparation when the caller is %s', async (when) => {
        const writes = stubBackend('/unused');
        render(<NextIntlClientProvider locale="en" messages={{ ...contactsEn, ...errorsEn }}><Capture /></NextIntlClientProvider>);
        await waitFor(() => expect(capture().recoveryStatus).toBe('ready'));
        act(() => capture().selectFile(new File(['card'], 'card.png', { type: 'image/png' })));
        await waitFor(() => expect(capture().status).toBe('ready'));
        act(() => capture().selectCompanyMode('none'));
        const backend = vi.mocked(fetch).getMockImplementation();
        if (!backend) throw new Error('The business-card backend was not installed');
        const held = deferred<Response>();
        const started = deferred<void>();
        let identitySignal: AbortSignal | null | undefined;
        let holdIdentity = when === 'during identity';
        vi.mocked(fetch).mockImplementation(async (input, init) => {
            const path = input instanceof Request ? input.url : String(input);
            if (holdIdentity && path === '/api/auth/me') {
                holdIdentity = false;
                identitySignal = init?.signal;
                started.resolve();
                return held.promise;
            }
            return backend(input, init);
        });
        const controller = new AbortController();
        if (when === 'already aborted') controller.abort();
        const before = writes.length;
        let pending: Promise<BusinessCardImportDraft | undefined> | undefined;
        await act(async () => {
            pending = capture().prepareImportDraft(false, { type: 'create' }, workspacePinnedRequest(WORKSPACE_ID, { signal: controller.signal }));
            if (when === 'during identity') await started.promise;
        });
        let draft: BusinessCardImportDraft | undefined;
        await act(async () => {
            controller.abort();
            held.resolve(json({ id: 7 }));
            draft = await pending;
        });

        expect(writes.slice(before)).toEqual([]);
        expect(draft).toBeUndefined();
        if (when === 'during identity') expect(identitySignal?.aborted).toBe(true);
        expect(Object.keys(window.localStorage).filter((key) => key.startsWith('connex:business-card-import-recovery:'))).toEqual([]);
        expect(capture().requiresExactImportRetry).toBe(false);
    });

    it('refuses a prepared recovery context that differs from the submission origin before reservation', async () => {
        const writes = stubBackend('/unused');
        render(<NextIntlClientProvider locale="en" messages={{ ...contactsEn, ...errorsEn }}><Capture /></NextIntlClientProvider>);
        await waitFor(() => expect(capture().recoveryStatus).toBe('ready'));
        act(() => capture().selectFile(new File(['card'], 'card.png', { type: 'image/png' })));
        await waitFor(() => expect(capture().status).toBe('ready'));
        act(() => capture().selectCompanyMode('none'));
        const before = writes.length;
        await act(async () => {
            expect(await capture().prepareImportDraft(false, { type: 'create' }, workspacePinnedRequest(5))).toBeUndefined();
        });
        expect(writes.slice(before)).toEqual([]);
        expect(capture().importError).toBe('conflict');
    });
    const cases = (['en', 'ja'] as const).flatMap(locale =>
        [SCAN, RESERVATION, IMPORT].map(path => ({ locale, path })));

    it.each(cases)('shows the named refusal for $path in $locale and preserves the draft', async ({ locale, path }) => {
        const writes = stubBackend(path);
        const messages = locale === 'en'
            ? { ...contactsEn, ...errorsEn }
            : { ...contactsJa, ...errorsJa };
        const t = createTranslator({ locale, messages });
        render(<NextIntlClientProvider locale={locale} messages={messages}>
            <Capture />
        </NextIntlClientProvider>);
        const file = new File(['card image'], 'card.jpg', { type: 'image/jpeg' });

        await refuseOperation(path, file);

        expect(screen.getByText(t('ApiErrors.workspaceUnavailableNamed', { workspace: WORKSPACE_NAME }))).toBeTruthy();
        expect(screen.getByRole<HTMLInputElement>('textbox', { name: 'Contact name' }).value).toBe(CONTACT_NAME);
        expect(capture().file).toBe(file);
        expect(capture().requiresExactImportRetry).toBe(false);
        expect(writes.at(-1)).toEqual({ path, workspace: String(WORKSPACE_ID) });
        expect(writes.every(write => write.workspace === String(WORKSPACE_ID))).toBe(true);
        expect(screen.queryByText(`Not a member of workspace ${WORKSPACE_ID}`)).toBeNull();
    });

    it('keeps the permission message for a member who cannot scan', async () => {
        stubBackend(SCAN, true);
        render(<NextIntlClientProvider locale="en" messages={{ ...contactsEn, ...errorsEn }}>
            <Capture />
        </NextIntlClientProvider>);

        await refuseOperation(SCAN, new File(['card image'], 'card.jpg', { type: 'image/jpeg' }));

        expect(screen.getByText(contactsEn.ContactsNewContactDialog.cardErrorForbidden)).toBeTruthy();
        expect(capture().workspaceUnavailableMessage).toBeNull();
    });
});
