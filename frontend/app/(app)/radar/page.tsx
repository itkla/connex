import type { Metadata } from 'next';
import { headers } from 'next/headers';
import { redirect } from 'next/navigation';
import { getTranslations } from 'next-intl/server';

import AccessDeniedPage from '@/app/components/AccessDeniedPage';
import { PageHeader } from '@/app/components/PageHeader';
import { PageShell } from '@/app/components/PageShell';
import RadarBoard from '@/app/components/radar/RadarBoard';
import SectionUnavailable from '@/app/components/SectionUnavailable';
import { getRadarResultFromCookie } from '@/app/lib/api';
import { classifyRadarReadFailure } from '@/app/lib/radar';

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations('Radar');
    return {
        title: t('metadata.title'),
        description: t('metadata.description'),
    };
}

/** Server entry that keeps permission refusal, unavailability, and an empty Radar distinct. */
export default async function RadarPage() {
    const cookie = (await headers()).get('cookie');
    if (!cookie) redirect('/auth/login');
    const [t, result] = await Promise.all([
        getTranslations('Radar'),
        getRadarResultFromCookie(cookie),
    ]);
    if (!result.ok) {
        const failure = classifyRadarReadFailure(result.status);
        if (failure === 'unauthenticated') redirect('/auth/login');
        if (failure === 'denied') return <AccessDeniedPage />;
    }

    return (
        <PageShell tier="wide">
            <PageHeader title={t('title')} description={result.ok ? undefined : t('description')} />
            {result.ok ? (
                <RadarBoard key={result.data.asOf} initialPayload={result.data} />
            ) : (
                <SectionUnavailable title={t('unavailable.title')} body={t('unavailable.routeBody')} />
            )}
        </PageShell>
    );
}
