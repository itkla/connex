import type { Metadata } from 'next';
import { headers } from 'next/headers';
import { redirect } from 'next/navigation';
import { getTranslations } from 'next-intl/server';

import {
    getContactsFromCookie,
    getCurrentUserFromCookie,
    getIntroductions,
    getIntroSuggestionsFromCookie,
} from '@/app/lib/api';
import type { IntroductionRecord, Page } from '@/app/lib/types';
import IntroductionsBoard from '@/app/components/introductions/IntroductionsBoard';
import Rise from '@/app/components/motion/Rise';

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

    const t = await getTranslations('Introductions');
    const init = { headers: { cookie: cookie ?? '' }, cache: 'no-store' } as const;

    const [suggestions, lineage, contacts] = await Promise.all([
        getIntroSuggestionsFromCookie(cookie, 40),
        getIntroductions({ page: 1, size: 50 }, init).catch(
            () => ({ items: [], total: 0 }) as Page<IntroductionRecord>,
        ),
        getContactsFromCookie(cookie),
    ]);

    return (
        <div className="min-h-screen bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-7xl flex-col gap-10">
                <Rise delay={0}>
                    <header className="px-6">
                        <h1 className="text-2xl font-semibold tracking-tight text-foreground">{t('pageTitle')}</h1>
                        <p className="mt-1 max-w-2xl text-sm text-muted-foreground">{t('pageSubtitle')}</p>
                    </header>
                </Rise>

                <IntroductionsBoard
                    initialSuggestions={suggestions}
                    initialLineage={lineage.items}
                    contacts={contacts}
                />
            </div>
        </div>
    );
}
