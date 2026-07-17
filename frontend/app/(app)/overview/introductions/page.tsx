import type { Metadata } from 'next';
import { headers } from 'next/headers';
import { redirect } from 'next/navigation';

import {
    getContactsFromCookie,
    getCurrentUserFromCookie,
    getIntroductionsResultFromCookie,
    getIntroSuggestionsResultFromCookie,
    getWarmPathsResultFromCookie,
} from '@/app/lib/api';
import IntroductionsBoard from '@/app/components/introductions/IntroductionsBoard';

export const metadata: Metadata = {
    title: 'Introductions',
    description: 'People in your network worth introducing to each other',
};

/**
 * Server entry for the Introductions page. The suggestions and lineage fetches are failure-aware
 * so the board can render distinct error states instead of presenting a backend fault as an empty
 * workspace. The board is keyed on which fetches succeeded: recovering via the retry (a
 * failed → loaded transition) remounts the board so its state re-seeds from the fresh server data,
 * while refreshes that stay in the same state intentionally preserve the board's optimistic
 * client state.
 */
export default async function IntroductionsPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) {
        redirect('/auth/login');
    }

    const [suggestionsResult, pathsResult, lineageResult, contacts] = await Promise.all([
        getIntroSuggestionsResultFromCookie(cookie, 40),
        getWarmPathsResultFromCookie(cookie, 20),
        getIntroductionsResultFromCookie(cookie, { page: 1, size: 50 }),
        getContactsFromCookie(cookie),
    ]);

    return (
        <IntroductionsBoard
            key={`${suggestionsResult.ok ? 'q' : 'qx'}-${pathsResult.ok ? 'p' : 'px'}-${lineageResult.ok ? 'l' : 'lx'}`}
            initialSuggestions={suggestionsResult.ok ? suggestionsResult.data : []}
            suggestionsFailed={!suggestionsResult.ok}
            initialPaths={pathsResult.ok ? pathsResult.data : []}
            pathsFailed={!pathsResult.ok}
            initialLineage={lineageResult.ok ? lineageResult.data.items : []}
            initialLineageTotal={lineageResult.ok ? lineageResult.data.total : 0}
            lineageFailed={!lineageResult.ok}
            contacts={contacts}
        />
    );
}
