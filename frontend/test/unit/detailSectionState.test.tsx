/** @vitest-environment jsdom */

import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { memo } from 'react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import DetailSectionState from '@/app/components/records/DetailSectionState';
import { DetailSectionsProvider, useDetailSections, type DetailSectionResults } from '@/app/hooks/useDetailSections';
import { ApiError } from '@/app/lib/api';
import * as sessionExpiry from '@/app/lib/sessionExpiry';
import en from '@/messages/en/records.json';
import ja from '@/messages/ja/records.json';

const mocks = vi.hoisted(() => ({ people: vi.fn(), deals: vi.fn(), refresh: vi.fn(), workspace: 1 }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh: mocks.refresh }) }));
vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => ({ activeWorkspaceId: mocks.workspace, switching: false }) }));
vi.mock('@/app/lib/renderedWorkspace', async (original) => ({
    ...await original<typeof import('@/app/lib/renderedWorkspace')>(), renderedWorkspaceId: () => mocks.workspace,
}));
vi.mock('@/app/lib/api', async (original) => ({
    ...await original<typeof import('@/app/lib/api')>(), getCompanyPeople: mocks.people, getCompanyDeals: mocks.deals,
}));

function Contents() {
    const state = useDetailSections();
    return <>
        <DetailSectionState title="Contacts" failure={state?.results.companyPeople?.kind === 'failed' ? state.results.companyPeople : null} sectionKeys={['companyPeople', 'companyDeals']}>
            <p>{state?.results.companyPeople?.kind === 'loaded' ? 'Successful empty contacts' : 'Private contact'}</p>
        </DetailSectionState>
        <p>People count: {state?.results.companyPeople?.kind === 'loaded' ? state.results.companyPeople.data.length : 'unknown'}</p>
        <p>Successfully loaded company history</p><button>Other work</button>
    </>;
}

beforeEach(() => { mocks.workspace = 1; mocks.people.mockReset().mockResolvedValue([]); mocks.deals.mockReset(); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.clearAllMocks(); });

describe.each([{ locale: 'en', messages: en }, { locale: 'ja', messages: ja }])('isolated detail recovery in $locale', ({ locale, messages }) => {
    const initial: DetailSectionResults = { companyPeople: { kind: 'failed', status: 503, reference: 'support-ref' }, companyDeals: { kind: 'loaded', data: [] } };
    const view = (results = initial) => <NextIntlClientProvider locale={locale} messages={messages}>
        <DetailSectionsProvider recordId={7} workspaceId={1} initial={results}><Contents /></DetailSectionsProvider>
    </NextIntlClientProvider>;

    it('loads only failed dependencies, preserves history, announces recovery and hands off focused Retry', async () => {
        render(view());
        expect(mocks.people).not.toHaveBeenCalled();
        expect(screen.queryByText('Private contact')).toBeNull();
        expect(screen.getByRole('alert').textContent).toContain('support-ref');
        const retry = screen.getByRole('button', { name: messages.RecordListState.retry });
        retry.focus();
        fireEvent.click(retry);
        await screen.findByText('Successful empty contacts');
        expect(mocks.people).toHaveBeenCalledExactlyOnceWith(7, expect.objectContaining({ headers: { 'X-Workspace-Id': '1' } }));
        expect(mocks.deals).not.toHaveBeenCalled();
        expect(mocks.refresh).not.toHaveBeenCalled();
        expect(screen.getByText('Successfully loaded company history')).toBeDefined();
        expect(screen.queryByRole('alert')).toBeNull();
        expect(screen.getByRole('status').textContent).toBe(messages.RecordListState.sectionRecovered.replace('{section}', 'Contacts'));
        expect(document.activeElement).toBe(screen.getByRole('region', { name: 'Contacts' }));
    });

    it('keeps busy feedback and does not steal focus from another control after recovery', async () => {
        let resolve: (() => void) | undefined;
        mocks.people.mockImplementationOnce(() => new Promise<never[]>((done) => { resolve = () => done([]); }));
        render(view());
        const retry = screen.getByRole('button', { name: messages.RecordListState.retry });
        retry.focus();
        fireEvent.click(retry);
        expect(screen.getByRole('button', { name: messages.RecordListState.retrying }).hasAttribute('disabled')).toBe(true);
        const other = screen.getByRole('button', { name: 'Other work' });
        other.focus();
        await act(async () => { resolve?.(); });
        expect(document.activeElement).toBe(other);
        expect(screen.getByRole('status').textContent).toContain(messages.RecordListState.sectionRecovered.replace('{section}', 'Contacts'));
    });

    it('keeps a denied retry distinct and hides content until a successful retry', async () => {
        mocks.people.mockRejectedValueOnce(new ApiError('private', 403));
        render(view());
        fireEvent.click(screen.getByRole('button', { name: messages.RecordListState.retry }));
        await waitFor(() => expect(screen.getByRole('alert').textContent).toContain(messages.RecordListState.sectionDeniedTitle.replace('{section}', 'Contacts')));
        expect(screen.queryByText('Private contact')).toBeNull();
        expect(screen.queryByText('Successful empty contacts')).toBeNull();
        expect(screen.getByRole('status').textContent).toBe('');
        fireEvent.click(screen.getByRole('button', { name: messages.RecordListState.retry }));
        await screen.findByText('Successful empty contacts');
    });

    it('does not publish a late response into another workspace', async () => {
        let resolve: (() => void) | undefined;
        mocks.people.mockImplementationOnce(() => new Promise<never[]>((done) => { resolve = () => done([]); }));
        const { rerender } = render(view());
        fireEvent.click(screen.getByRole('button', { name: messages.RecordListState.retry }));
        mocks.workspace = 2;
        rerender(view());
        await act(async () => { resolve?.(); });
        expect(screen.queryByText('Successful empty contacts')).toBeNull();
        expect(screen.queryByRole('status')).toBeNull();
    });

    it('does not let an older retry replace a newer server snapshot', async () => {
        let resolve: (() => void) | undefined;
        mocks.people.mockImplementationOnce(() => new Promise<never[]>((done) => { resolve = () => done([]); }));
        const { rerender } = render(view());
        fireEvent.click(screen.getByRole('button', { name: messages.RecordListState.retry }));
        rerender(view({ ...initial, companyPeople: { kind: 'loaded', data: [{ id: 8, name: 'Newer contact', email: '', phone: '', title: '', imageUrl: '', createdAt: '', updatedAt: '' }] } }));
        await act(async () => { resolve?.(); });
        expect(screen.getByText('People count: 1')).toBeDefined();
    });

    it('uses the existing browser sign-in flow for an expired retry', async () => {
        const redirect = vi.spyOn(sessionExpiry, 'redirectToSignIn').mockReturnValue(true);
        mocks.people.mockRejectedValueOnce(new ApiError('', 403, undefined, undefined, undefined, true));
        render(view());
        fireEvent.click(screen.getByRole('button', { name: messages.RecordListState.retry }));
        await waitFor(() => expect(redirect).toHaveBeenCalledOnce());
        expect(screen.queryByText('Successful empty contacts')).toBeNull();
    });
});


it('preserves consumer identity when the provider parent renders without new section state', () => {
    const observed = vi.fn();
    const Probe = memo(function Probe() {
        observed(useDetailSections());
        return <p>Stable consumer</p>;
    });
    const initial: DetailSectionResults = { companyPeople: { kind: 'loaded', data: [] } };
    const child = <Probe />;
    const view = (revision: number) => <div data-revision={revision}>
        <DetailSectionsProvider recordId={7} workspaceId={1} initial={initial}>{child}</DetailSectionsProvider>
    </div>;
    const { rerender } = render(view(1));
    expect(observed).toHaveBeenCalledOnce();
    rerender(view(2));
    expect(observed).toHaveBeenCalledOnce();
});
