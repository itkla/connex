import { afterEach, describe, expect, it, vi } from 'vitest';

import { getAiAssistantToolCalls } from '@/app/lib/api';
import { askConnexToolCardAffordances } from '@/app/lib/askConnex';
import { observeProposalReviewClock, proposalReviewNow } from '@/app/lib/proposalReviewClock';
import { askConnexCard, askConnexChange } from '@/test/unit/helpers/askConnexToolCards';

const SERVER_NOW = Date.parse('2026-10-16T12:00:00Z');

describe('assistant server-relative review clock', () => {
    afterEach(() => {
        vi.restoreAllMocks();
        vi.unstubAllGlobals();
    });

    it.each([-86_400_000, 86_400_000])('uses response time for action guards with device skew %i', async (skew) => {
        vi.stubGlobal('window', { location: { pathname: '/ask-connex', search: '' } });
        vi.spyOn(Date, 'now').mockReturnValue(SERVER_NOW + skew);
        const elapsed = vi.spyOn(performance, 'now').mockReturnValue(100);
        const card = askConnexCard({
            change: askConnexChange(), reviewExpiresAt: new Date(SERVER_NOW + 1_000).toISOString(),
        });
        const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify([card]), {
            headers: { Date: new Date(SERVER_NOW).toUTCString() },
        }));
        vi.stubGlobal('fetch', fetch);

        expect(await getAiAssistantToolCalls(1, { pendingOnly: true })).toEqual([card]);
        expect(proposalReviewNow()).toBe(SERVER_NOW);
        elapsed.mockReturnValue(1_099);
        expect(askConnexToolCardAffordances(card, proposalReviewNow())).toEqual(['reject', 'approve']);
        elapsed.mockReturnValue(1_100);
        expect(askConnexToolCardAffordances(card, proposalReviewNow())).toEqual([]);
        expect(fetch).toHaveBeenCalledOnce();
    });

    it('ignores absent and invalid response dates without substituting device time', () => {
        const elapsed = vi.spyOn(performance, 'now').mockReturnValue(0);
        observeProposalReviewClock(new Headers({ Date: new Date(SERVER_NOW).toUTCString() }));
        elapsed.mockReturnValue(500);
        observeProposalReviewClock(new Headers());
        observeProposalReviewClock(new Headers({ Date: 'invalid' }));
        expect(proposalReviewNow()).toBe(SERVER_NOW + 500);
    });
});
