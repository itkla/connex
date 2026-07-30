import { useTranslations } from 'next-intl';

import SectionHeader from '@/app/components/dashboard/SectionHeader';
import FirstInsightCard from '@/app/components/dashboard/activation/FirstInsightCard';
import MissingEvidence from '@/app/components/dashboard/activation/MissingEvidence';
import SetupChecklist from '@/app/components/dashboard/activation/SetupChecklist';
import type { ActivationGap, ActivationInsight, ActivationStep } from '@/app/lib/activation';

/**
 * The activation surface: what the workspace still needs beside what its data can already prove.
 * It is rendered only while a required setup step is outstanding, so it retires on its own once the
 * dashboard's regular signal widgets have something real to show.
 */
export default function ActivationPanel({
    steps,
    insight,
    gaps,
    canCreateFollowUp,
}: {
    steps: ActivationStep[] | null;
    insight: ActivationInsight | null;
    gaps: ActivationGap[];
    canCreateFollowUp: boolean;
}) {
    const t = useTranslations('DashboardActivation');
    const unavailable = gaps.includes('unavailable');
    const paired = insight != null && (steps != null || unavailable);

    return (
        <section aria-label={t('sectionTitle')}>
            <SectionHeader title={t('sectionTitle')} />
            <div className={paired || steps ? 'grid grid-cols-1 items-stretch gap-6 lg:grid-cols-2' : 'grid'}>
                {steps ? <SetupChecklist steps={steps} /> : null}
                {!steps && unavailable ? <MissingEvidence gaps={gaps} /> : null}
                {insight ? (
                    <FirstInsightCard insight={insight} canCreateFollowUp={canCreateFollowUp} />
                ) : !unavailable ? (
                    <MissingEvidence gaps={gaps} />
                ) : null}
            </div>
        </section>
    );
}
