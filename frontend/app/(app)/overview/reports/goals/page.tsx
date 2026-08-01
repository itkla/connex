import { headers } from 'next/headers';
import { redirect } from 'next/navigation';
import { getTranslations } from 'next-intl/server';

import GoalsBoard from '@/app/components/reports/GoalsBoard';
import AccessDeniedPage from '@/app/components/AccessDeniedPage';
import PermissionsUnavailablePage from '@/app/components/PermissionsUnavailablePage';
import {
    getActiveWorkspaceMembersResultFromCookie,
    getCurrentUserFromCookie,
    getEffectivePermissionsResultFromCookie,
    getGoalsResultFromCookie,
} from '@/app/lib/api';

export async function generateMetadata() {
    const t = await getTranslations('Reports');
    return { title: t('metadata.goalsTitle'), description: t('metadata.goalsDescription') };
}

export default async function GoalsPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) redirect('/auth/login');

    const permissionsResult = await getEffectivePermissionsResultFromCookie(cookie);
    if (!permissionsResult.ok) return <PermissionsUnavailablePage />;
    const effectivePermissions = permissionsResult.data;
    if (!effectivePermissions.includes('GOAL_READ')) return <AccessDeniedPage />;

    const [goalsResult, ownersResult] = await Promise.all([
        getGoalsResultFromCookie(cookie),
        getActiveWorkspaceMembersResultFromCookie(cookie),
    ]);

    return (
        <GoalsBoard
            key={`${goalsResult.ok ? 'goals' : 'goals-error'}-${ownersResult.ok ? 'owners' : 'owners-error'}`}
            initialGoals={goalsResult.ok ? goalsResult.data : []}
            owners={ownersResult.ok ? ownersResult.data : []}
            canManage={effectivePermissions.includes('GOAL_MANAGE')}
            goalsFailed={!goalsResult.ok}
            ownersFailed={!ownersResult.ok}
        />
    );
}
