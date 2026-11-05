/** @vitest-environment jsdom */

import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import DealLineItems from '@/app/components/records/deals/DealLineItems';
import BrowserAccountBridge from '@/app/components/BrowserAccountBridge';
import { adoptBrowserAccount, invalidateAccountStorage, resetBrowserAccount } from '@/app/lib/browserAccountStorage';
import { bindRenderedWorkspace, releaseRenderedWorkspace } from '@/app/lib/renderedWorkspace';
import en from '@/messages/en/deals.json';
import type { DealLineItem, DealLineItemsResponse } from '@/app/lib/types';
import { json, NEXT, ORIGIN, shell, stubBackend, switchWorkspace } from './helpers/workspaceChainBackend';

const state = vi.hoisted(() => ({ workspace: 9, pathname: '/records/deals/7', refresh: vi.fn(), error: vi.fn() }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh: state.refresh }), usePathname: () => state.pathname }));
vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => ({ activeWorkspaceId: state.workspace }) }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => state.error }));

const totals = { currency: 'USD', subtotal: 100, tax: 10, oneTimeTotal: 110, recurringTotal: 0, grandTotal: 110 };
const line: DealLineItem = { id: 4, dealId: 7, name: 'Origin service', unitPrice: 100, quantity: 1,
    taxRate: 10, billingFrequency: 'one_time', position: 0, currency: 'USD', lineSubtotal: 100,
    lineTax: 10, lineTotal: 110, createdAt: '', updatedAt: '' };
const empty: DealLineItemsResponse = { items: [], totals: { ...totals, subtotal: 0, tax: 0, oneTimeTotal: 0, grandTotal: 0 } };

function view(initial = empty, recordWorkspace: number | null = ORIGIN) {
    return <NextIntlClientProvider locale="en" messages={en}>
        <DealLineItems dealId={7} dealWorkspaceId={recordWorkspace} dealCurrency="USD" initial={initial} />
    </NextIntlClientProvider>;
}

function compose() {
    fireEvent.click(screen.getByRole('button', { name: 'Add line' }));
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Unit price' }), { target: { value: '100' } });
}

beforeEach(() => {
    state.workspace = ORIGIN;
    state.pathname = '/records/deals/7';
    state.refresh.mockReset();
    state.error.mockReset();
    window.history.replaceState(null, '', state.pathname);
    adoptBrowserAccount(7);
    bindRenderedWorkspace(shell, ORIGIN);
    document.cookie = `connex_workspace=${ORIGIN}; path=/`;
});
afterEach(() => {
    cleanup();
    releaseRenderedWorkspace(shell);
    window.localStorage.clear();
    vi.unstubAllGlobals();
});

describe('deal line preview and persistence workspace chains', () => {
    it('pins catalog reads when the cookie differs from the rendered record workspace', async () => {
        stubBackend();
        document.cookie = `connex_workspace=${NEXT}; path=/`;
        render(view());
        await waitFor(() => {
            const call = vi.mocked(fetch).mock.calls.find(([input]) => String(input).includes('/api/products'));
            expect(call).toBeDefined();
            expect(new Headers(call?.[1]?.headers).get('X-Workspace-Id')).toBe(String(ORIGIN));
        });
    });

    it.each([NEXT, null])('withholds editing and catalog reads when loaded workspace %s differs from the shell', async (recordWorkspace) => {
        const backend = stubBackend();
        render(view({ items: [line], totals }, recordWorkspace));
        expect(screen.queryByRole('region', { name: 'Line items' })).toBeNull();
        expect(backend.requests).toEqual([]);
        expect(vi.mocked(fetch).mock.calls.some(([input]) => String(input).includes('/api/products'))).toBe(false);
    });

    it('captures the account after the authenticated shell first adopts it', async () => {
        resetBrowserAccount();
        const backend = stubBackend('/api/deals/7/line-items');
        render(<><BrowserAccountBridge userId={7} />{view()}</>);
        compose();
        fireEvent.click(screen.getByRole('button', { name: 'Save line' }));
        await backend.started.promise;
        expect(backend.requests[0].workspace).toBe(String(ORIGIN));
        await act(async () => backend.held.resolve(json({ items: [line], totals, manualValueToReplace: 1000 })));
        await screen.findByRole('dialog');
    });
    it.each(['new zero-value line', 'existing derived line'])('abandons %s persistence after a workspace switch during preview', async (kind) => {
        const path = kind === 'new zero-value line' ? '/api/deals/7/line-items' : '/api/deals/7/line-items/4';
        const backend = stubBackend(path, (requestPath) => requestPath === path ? json({ items: [line], totals }) : undefined);
        const mounted = render(view(kind === 'new zero-value line' ? empty : { items: [line], totals }));
        if (kind === 'new zero-value line') {
            compose();
            fireEvent.click(screen.getByRole('button', { name: 'Save line' }));
        } else {
            const tax = screen.getByRole('spinbutton', { name: 'Tax rate (%)' });
            fireEvent.change(tax, { target: { value: '8' } });
            fireEvent.blur(tax);
        }
        await backend.started.promise;
        expect(backend.requests.map(({ workspace }) => workspace)).toEqual([String(ORIGIN)]);
        switchWorkspace();
        await act(async () => backend.held.resolve(json({ items: [line], totals })));
        state.workspace = NEXT;
        mounted.rerender(view());
        expect(backend.requests.map(({ workspace }) => workspace)).toEqual([String(ORIGIN)]);
        expect(state.refresh).not.toHaveBeenCalled();
        expect(screen.queryByRole('region', { name: 'Line items' })).toBeNull();
    });

    it.each(['account', 'route', 'unmount'])('abandons persistence when the %s changes during preview', async (transition) => {
        const backend = stubBackend('/api/deals/7/line-items');
        const mounted = render(view());
        compose();
        fireEvent.click(screen.getByRole('button', { name: 'Save line' }));
        await backend.started.promise;
        await act(async () => {
            if (transition === 'account') invalidateAccountStorage();
            if (transition === 'route') {
                state.pathname = '/records/deals/8';
                window.history.replaceState(null, '', state.pathname);
                mounted.rerender(view());
            }
            if (transition === 'unmount') mounted.unmount();
            backend.held.resolve(json({ items: [line], totals }));
        });
        expect(backend.requests).toHaveLength(1);
        expect(backend.requests[0].workspace).toBe(String(ORIGIN));
        expect(backend.requests[0].init.signal?.aborted).toBe(true);
        expect(state.refresh).not.toHaveBeenCalled();
    });

    it('keeps preview and confirmed persistence pinned to the draft workspace', async () => {
        const backend = stubBackend('/api/deals/7/line-items', () => json({ items: [line], totals }));
        render(view());
        compose();
        fireEvent.click(screen.getByRole('button', { name: 'Save line' }));
        await backend.started.promise;
        await act(async () => backend.held.resolve(json({ items: [line], totals, manualValueToReplace: 1000 })));
        await screen.findByRole('dialog');
        fireEvent.click(screen.getByRole('button', { name: 'Replace value and save' }));
        await waitFor(() => expect(state.refresh).toHaveBeenCalledOnce());
        expect(backend.requests.map(({ workspace }) => workspace)).toEqual([String(ORIGIN), String(ORIGIN)]);
        expect(backend.requests[1].init.body).toContain('"confirmValueReplacement":true');
        for (const request of backend.requests) {
            const payload: unknown = JSON.parse(String(request.init.body));
            expect(payload).not.toHaveProperty('expectedVersion');
        }
    });

    it('invalidates confirmation and retry when a composed draft loses its workspace', async () => {
        const backend = stubBackend('/api/deals/7/line-items');
        render(view());
        compose();
        fireEvent.click(screen.getByRole('button', { name: 'Save line' }));
        await backend.started.promise;
        await act(async () => backend.held.resolve(json({ items: [line], totals, manualValueToReplace: 1000 })));
        await screen.findByRole('dialog');
        switchWorkspace();
        fireEvent.click(screen.getByRole('button', { name: 'Replace value and save' }));
        fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
        fireEvent.click(screen.getByRole('button', { name: 'Save line' }));
        expect(backend.requests).toHaveLength(1);
        expect(state.refresh).not.toHaveBeenCalled();
    });
});
