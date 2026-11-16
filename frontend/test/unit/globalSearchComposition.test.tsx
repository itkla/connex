/** @vitest-environment jsdom */
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import GlobalSearch from '@/app/components/GlobalSearch';
import type { SearchResults } from '@/app/lib/types';
import enActions from '@/messages/en/actions.json';
import enMessages from '@/messages/en/common.json';
import jaActions from '@/messages/ja/actions.json';
import jaMessages from '@/messages/ja/common.json';

const mocks = vi.hoisted(() => ({ search: vi.fn(), push: vi.fn(), run: vi.fn() }));

vi.mock('next/navigation', () => ({
    useRouter: () => ({ push: mocks.push }),
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock('@/app/lib/api', () => ({ search: mocks.search }));
vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => ({ activeWorkspaceId: 7 }) }));
vi.mock('@/app/hooks/useActions', () => ({
    useActions: () => ({ run: mocks.run, pendingIds: new Set<string>() }),
    useAvailableActions: () => [],
}));

const results: SearchResults = {
    users: [], companies: [], deals: [], pipelines: [], tags: [], activities: [], notes: [], tasks: [],
    attachments: [], products: [], campaigns: [], reports: [], documentTemplates: [], documents: [], workflows: [],
    people: [{
        id: 42, name: '山田太郎', email: '', phone: '', title: '', imageUrl: '',
        createdAt: '2026-11-05T00:00:00Z', updatedAt: '2026-11-05T00:00:00Z',
    }],
};

beforeEach(() => {
    vi.clearAllMocks();
    mocks.search.mockResolvedValue(results);
    vi.stubGlobal('ResizeObserver', class {
        observe() {}
        unobserve() {}
        disconnect() {}
    });
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => setTimeout(() => callback(performance.now()), 0));
    vi.stubGlobal('cancelAnimationFrame', clearTimeout);
    vi.stubGlobal('matchMedia', (media: string) => ({
        media, matches: true, addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {},
    }));
    HTMLElement.prototype.scrollIntoView = vi.fn();
});

afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
});

async function beyondDebounce() {
    await act(async () => { await new Promise(resolve => setTimeout(resolve, 350)); });
}

describe.each([
    ['en', { ...enMessages, ...enActions }],
    ['ja', { ...jaMessages, ...jaActions }],
] as const)('global search composition in %s', (locale, messages) => {
    it('keeps a selected cmdk result inactive for native composition and Safari confirmation', async () => {
        render(<NextIntlClientProvider locale={locale} messages={messages}><GlobalSearch /></NextIntlClientProvider>);
        fireEvent.keyDown(document, { key: 'k', ctrlKey: true });
        const input = screen.getByRole('combobox');
        fireEvent.change(input, { target: { value: '山田' } });
        const result = await screen.findByRole('option', { name: '山田太郎' });
        await waitFor(() => expect(result.getAttribute('aria-selected')).toBe('true'));

        fireEvent.keyDown(input, { key: 'Enter', keyCode: 13, isComposing: true });
        fireEvent.keyDown(input, { key: 'Enter', keyCode: 229 });
        fireEvent.keyDown(input, { key: 'Escape', keyCode: 229 });
        expect(mocks.push).not.toHaveBeenCalled();
        expect(screen.getByRole('dialog')).toBeTruthy();
        expect(screen.getAllByRole('combobox')).toEqual([input]);
        expect(result.getAttribute('aria-selected')).toBe('true');

        fireEvent.keyDown(input, { key: 'Enter', keyCode: 13 });
        expect(mocks.push).toHaveBeenCalledExactlyOnceWith('/records/contacts/42');
    });

    it.each(['inline', 'palette'])('makes exactly one committed request after %s composition', async (mode) => {
        render(<NextIntlClientProvider locale={locale} messages={messages}><GlobalSearch /></NextIntlClientProvider>);
        if (mode === 'palette') fireEvent.keyDown(document, { key: 'k', ctrlKey: true });
        const input = screen.getByRole('combobox');
        fireEvent.compositionStart(input);
        fireEvent.change(input, { target: { value: '山田' } });
        fireEvent.keyDown(input, { key: 'Enter', keyCode: 13 });
        fireEvent.keyDown(input, { key: 'Escape', keyCode: 27 });
        await beyondDebounce();
        expect(mocks.search).not.toHaveBeenCalled();
        expect(mocks.push).not.toHaveBeenCalled();

        fireEvent.change(input, { target: { value: '山田 太郎' } });
        fireEvent.compositionEnd(input, { data: '山田 太郎' });
        await screen.findByRole('option', { name: '山田太郎' });
        await beyondDebounce();
        expect(mocks.search).toHaveBeenCalledExactlyOnceWith('山田 太郎', expect.objectContaining({ signal: expect.any(AbortSignal) }));
    });
});
