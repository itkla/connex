import type { Metadata } from 'next';
import { headers } from 'next/headers';
import { redirect } from 'next/navigation';

import {
    getContactsFromCookie,
    getCurrentUserFromCookie,
    getIntroductions,
    getIntroSuggestionsFromCookie,
} from '@/app/lib/api';
import type { IntroductionRecord, Page } from '@/app/lib/types';
import IntroductionsBoard from '@/app/components/introductions/IntroductionsBoard';

export const metadata: Metadata = {
    title: 'Introductions',
    description: 'People in your network worth introducing to each other',
};

export default async function IntroductionsPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) {
        redirect('/auth/login');
    }

    const init = { headers: { cookie: cookie ?? '' }, cache: 'no-store' } as const;

    const [suggestions, lineage, contacts] = await Promise.all([
        getIntroSuggestionsFromCookie(cookie, 40),
        getIntroductions({ page: 1, size: 50 }, init).catch(
            () => ({ items: [], total: 0 }) as Page<IntroductionRecord>,
        ),
        getContactsFromCookie(cookie),
    ]);

    return (
        <IntroductionsBoard
            initialSuggestions={suggestions}
            initialLineage={lineage.items}
            initialLineageTotal={lineage.total}
            contacts={contacts}
        />
    );
}
