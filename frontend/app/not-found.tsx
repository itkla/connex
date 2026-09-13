import { headers } from 'next/headers';
import { getTranslations } from 'next-intl/server';

import LandingFooter from '@/app/components/landing/LandingFooter';
import LandingNav from '@/app/components/landing/LandingNav';
import NotFoundState from '@/app/components/NotFoundState';
import { resolvePreLaunch } from '@/app/lib/landingMode';

/**
 * Application-wide 404. Next.js renders the root `not-found` for any URL that matches
 * no route at all, so this one is reached by logged-out visitors too — it offers the
 * public start page rather than a workspace destination that would bounce them
 * through login, inside the same public chrome the legal pages carry so a mistyped
 * URL still reaches the legal notices and the language switcher.
 */
export default async function RootNotFound() {
    const preLaunch = resolvePreLaunch({ host: (await headers()).get('host') });
    const t = await getTranslations('NotFound');
    const nav = await getTranslations('CommonHome');

    return (
        <div className="font-body flex min-h-screen flex-col bg-background text-foreground">
            <LandingNav ctaHref="/auth/register" ctaLabel={nav('ctaGetStarted')} preLaunch={preLaunch} />
            <main className="flex-1">
                <NotFoundState
                    title={t('title')}
                    body={t('site.body')}
                    actions={[{ href: '/', label: t('site.home') }]}
                />
            </main>
            <LandingFooter showLogin={!preLaunch} />
        </div>
    );
}
