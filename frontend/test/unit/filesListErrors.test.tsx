/** @vitest-environment jsdom */

import type { ReactNode } from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import FilesBrowser from '@/app/components/library/files/FilesBrowser';
import { TooltipProvider } from '@/components/ui/tooltip';
import { getAttachmentFacets, getAttachmentsPage, getTags } from '@/app/lib/api';
import type { Attachment, Page } from '@/app/lib/types';
import attachments from '@/messages/en/attachments.json';
import common from '@/messages/en/common.json';
import records from '@/messages/en/records.json';

vi.mock('next/navigation', () => ({
    usePathname: () => '/library/files',
    useSearchParams: () => new URLSearchParams('q=initial'),
}));
vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => ({ activeWorkspaceId: 7 }) }));
vi.mock('@/app/lib/api', async (importOriginal) => ({
    ...await importOriginal<typeof import('@/app/lib/api')>(),
    getAttachmentsPage: vi.fn(),
    getAttachmentFacets: vi.fn(),
    getTags: vi.fn(),
}));
vi.mock('motion/react', async (importOriginal) => ({
    ...await importOriginal<typeof import('motion/react')>(),
    useReducedMotion: () => true,
}));
vi.mock('@/app/components/records/DeleteRecordDialog', () => ({ default: () => null }));
vi.mock('@/app/components/library/files/FileDetailSheet', () => ({ default: () => null }));
vi.mock('@/app/components/records/RecordsRenderView', () => ({
    default: ({ loading, emptyState }: { loading: boolean; emptyState: ReactNode }) => loading ? null : emptyState,
}));

beforeEach(() => {
    vi.mocked(getAttachmentsPage).mockReset().mockResolvedValue({ items: [], total: 0 });
    vi.mocked(getTags).mockResolvedValue([]);
});
afterEach(cleanup);

describe.each([0, 1])('Files filters when library facets report %i files', (total) => {
    it('keeps the focused search after a failed filter without retained rows and lets Escape clear it', async () => {
        let reject: (error: Error) => void = () => { throw new Error('Promise not initialized'); };
        const failed = new Promise<Page<Attachment>>((_, onReject) => { reject = onReject; });
        vi.mocked(getAttachmentFacets).mockResolvedValue({
            total, totalSize: 0, sources: [], kinds: [], tags: [], orphaned: 0,
        });
        render(
            <NextIntlClientProvider locale="en" messages={{ ...common, ...attachments, ...records }} timeZone="UTC">
                <TooltipProvider><FilesBrowser /></TooltipProvider>
            </NextIntlClientProvider>,
        );
        await waitFor(() => expect(screen.getByText(attachments.LibraryFiles.emptyTitle)).toBeDefined());
        const search = screen.getByRole('textbox', { name: attachments.LibraryFiles.searchPlaceholder });
        if (!(search instanceof HTMLInputElement)) throw new Error('Missing file search');
        vi.mocked(getAttachmentsPage).mockReturnValueOnce(failed);

        search.focus();
        fireEvent.change(search, { target: { value: 'unavailable' } });
        await waitFor(() => expect(getAttachmentsPage).toHaveBeenLastCalledWith(expect.objectContaining({ q: 'unavailable' })));
        await act(async () => reject(new Error('Unavailable')));

        expect(screen.getByRole('alert').textContent).toContain(records.RecordListState.title);
        expect(screen.queryByText(attachments.LibraryFiles.emptyTitle)).toBeNull();
        expect(screen.getByRole('textbox', { name: attachments.LibraryFiles.searchPlaceholder })).toBe(search);
        expect(document.activeElement).toBe(search);

        fireEvent.keyDown(search, { key: 'Escape' });
        await waitFor(() => expect(getAttachmentsPage).toHaveBeenLastCalledWith(expect.objectContaining({ q: undefined })));
        await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
        expect(screen.getByText(attachments.LibraryFiles.emptyTitle)).toBeDefined();
    });
});
