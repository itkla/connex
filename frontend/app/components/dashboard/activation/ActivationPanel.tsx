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
}: {
    steps: ActivationStep[];
    insight: ActivationInsight | null;
    gaps: ActivationGap[];
}) {
    const t = useTranslations('DashboardActivation');

    return (
        <section aria-label={t('sectionTitle')}>
            <SectionHeader title={t('sectionTitle')} />
            <div className="grid grid-cols-1 items-stretch gap-6 lg:grid-cols-2">
                <SetupChecklist steps={steps} />
                {insight ? <FirstInsightCard insight={insight} /> : <MissingEvidence gaps={gaps} />}
            </div>
        </section>
    );
}
