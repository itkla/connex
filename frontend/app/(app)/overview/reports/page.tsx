import { headers } from 'next/headers';
import { redirect } from 'next/navigation';
import { getTranslations } from 'next-intl/server';

import ReportsBoard from '@/app/components/reports/ReportsBoard';
import PermissionsUnavailablePage from '@/app/components/PermissionsUnavailablePage';
import {
    getCurrentUserFromCookie,
    getEffectivePermissionsResultFromCookie,
    getReportComposerAvailabilityResultFromCookie,
    getReports,
    getReportTemplates,
} from '@/app/lib/api';

export async function generateMetadata() {
    const t = await getTranslations('Reports');
    return { title: t('metadata.title'), description: t('metadata.description') };
}

export default async function ReportsPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) redirect('/auth/login');
    const init = { headers: { cookie: cookie ?? '' } } as const;

    const [templates, reports, permissionsResult, composerAvailabilityResult] = await Promise.all([
        getReportTemplates(init),
        getReports(init),
        getEffectivePermissionsResultFromCookie(cookie),
        getReportComposerAvailabilityResultFromCookie(cookie),
    ]);
    if (!permissionsResult.ok) return <PermissionsUnavailablePage />;
    const effectivePermissions = permissionsResult.data;

    return (
        <ReportsBoard
            templates={templates}
            initialReports={reports}
            effectivePermissions={effectivePermissions}
            currentUserId={user.id}
            composerAvailable={composerAvailabilityResult.ok && composerAvailabilityResult.data.available}
        />
    );
}
