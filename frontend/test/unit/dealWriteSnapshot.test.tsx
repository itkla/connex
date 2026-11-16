import { act, createElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useInlineEdit, type InlineEdit } from '@/app/hooks/useInlineEdit';
import {
    latestDealSnapshot,
    submitDealDraftUpdates,
    submitInlineDealValue,
    toDealDraft,
} from '@/app/components/records/deals/dealEditConflict';
import type { Deal } from '@/app/lib/types';
import { installInteractiveDocument } from '@/test/unit/helpers/interactiveDocument';

const api = vi.hoisted(() => ({ value: vi.fn(), update: vi.fn() }));
vi.mock('@/app/lib/api', async () => ({
    ...await vi.importActual<typeof import('@/app/lib/api')>('@/app/lib/api'),
    updateDealValue: api.value,
    updateDeal: api.update,
}));

const base: Deal = {
    id: 7, version: 17, name: 'Renewal', value: 100, actualValue: 0, currency: 'JPY',
    company: 42, pipeline: 2, stage: 3, position: 0, createdAt: '', updatedAt: '',
};
const saved: Deal = { ...base, version: 18, value: 99999, company: null };

beforeEach(() => {
    api.value.mockReset().mockResolvedValue(saved);
    api.update.mockReset().mockResolvedValue(saved);
});
afterEach(() => vi.unstubAllGlobals());

describe('deal write snapshots', () => {
    it('seeds quick edit with the complete inline response and its advanced token', async () => {
        const { createRoot } = await import('react-dom/client');
        const installed = installInteractiveDocument();
        const root = createRoot(installed.container);
        let row = base;
        let editor: InlineEdit<Deal> | undefined;
        function Harness() {
            editor = useInlineEdit<Deal>((id, partial) => {
                if (row.id === id) row = { ...row, ...partial };
            });
            return null;
        }
        try {
            await act(async () => root.render(createElement(Harness)));
            if (!editor) throw new Error('Expected inline editor');
            const inline = editor;
            await act(async () => inline.commit(row, 'value', 99999,
                (full) => submitInlineDealValue(full, (response) => { row = response; })));
            expect(row).toBe(saved);
            expect(row.company).toBeNull();
            const draft = toDealDraft(row);
            draft.name = 'Edited after value';
            await submitDealDraftUpdates([{ dealId: row.id, draft }]);
            expect(api.update).toHaveBeenCalledWith(7, expect.objectContaining({
                expectedVersion: 18, value: 99999, name: 'Edited after value', company: null,
            }), {});
        } finally {
            await act(async () => root.unmount());
        }
    });

    it('uses newer close, reopen and owner responses until the parent refresh catches up', () => {
        expect(latestDealSnapshot(base, saved)).toBe(saved);
        const refreshed = { ...saved, version: 19 };
        expect(latestDealSnapshot(refreshed, saved)).toBe(refreshed);
        expect(latestDealSnapshot(base, { ...saved, id: 8 })).toBe(base);
        expect(latestDealSnapshot(base, null)).toBe(base);
        expect(latestDealSnapshot({ ...base, workspaceId: 7 }, { ...saved, workspaceId: 8 })).toEqual({ ...base, workspaceId: 7 });
    });
});
