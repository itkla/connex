import { headers } from 'next/headers';
import { redirect } from 'next/navigation';
import { getTranslations } from 'next-intl/server';

import ReportsBoard from '@/app/components/reports/ReportsBoard';
import {
    getCurrentUserFromCookie,
    getEffectivePermissionsFromCookie,
    getReportsFromCookie,
    getReportTemplatesFromCookie,
} from '@/app/lib/api';

export async function generateMetadata() {
    const t = await getTranslations('Reports');
    return { title: t('metadata.title'), description: t('metadata.description') };
}

export default async function ReportsPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) redirect('/auth/login');

    const [templates, reports, effectivePermissions] = await Promise.all([
        getReportTemplatesFromCookie(cookie),
        getReportsFromCookie(cookie),
        getEffectivePermissionsFromCookie(cookie),
    ]);

    return (
        <ReportsBoard
            templates={templates}
            initialReports={reports}
            effectivePermissions={effectivePermissions}
        />
    );
}
