import type { Metadata } from 'next';
import { headers } from 'next/headers';
import { redirect } from 'next/navigation';
import { getTranslations } from 'next-intl/server';

import {
    getContactsFromCookie,
    getCurrentUserFromCookie,
    getIntroductionsResultFromCookie,
    getIntroOverviewResultFromCookie,
} from '@/app/lib/api';
import IntroductionsBoard from '@/app/components/introductions/IntroductionsBoard';

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations('IntroductionsLayout');
    return {
        title: t('title'),
        description: t('description'),
    };
}

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

    const [overviewResult, lineageResult, contacts] = await Promise.all([
        getIntroOverviewResultFromCookie(cookie, 40, 20),
        getIntroductionsResultFromCookie(cookie, { page: 1, size: 50 }),
        getContactsFromCookie(cookie),
    ]);

    return (
        <IntroductionsBoard
            key={`${overviewResult.ok ? 'o' : 'ox'}-${lineageResult.ok ? 'l' : 'lx'}`}
            initialSuggestions={overviewResult.ok ? overviewResult.data.suggestions : []}
            suggestionsFailed={!overviewResult.ok}
            suggestionsEmptyReason={
                overviewResult.ok ? overviewResult.data.suggestionsEmptyReason : 'unavailable_data'
            }
            initialPaths={overviewResult.ok ? overviewResult.data.paths : []}
            pathsFailed={!overviewResult.ok}
            pathsEmptyReason={overviewResult.ok ? overviewResult.data.pathsEmptyReason : 'unavailable_data'}
            asOf={overviewResult.ok ? overviewResult.data.asOf : null}
            initialLineage={lineageResult.ok ? lineageResult.data.items : []}
            initialLineageTotal={lineageResult.ok ? lineageResult.data.total : 0}
            lineageFailed={!lineageResult.ok}
            contacts={contacts}
        />
    );
}
