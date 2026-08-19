import { useTranslations } from 'next-intl';

import SectionHeader from '@/app/components/dashboard/SectionHeader';
import FirstInsightCard from '@/app/components/dashboard/activation/FirstInsightCard';
import FirstWarmthCard from '@/app/components/dashboard/activation/FirstWarmthCard';
import MissingEvidence from '@/app/components/dashboard/activation/MissingEvidence';
import SetupChecklist from '@/app/components/dashboard/activation/SetupChecklist';
import type { ActivationGap, ActivationInsight, ActivationStep } from '@/app/lib/activation';
import type { FirstRunJourney } from '@/app/lib/firstRunJourney';
import type { WarmthBandCounts } from '@/app/lib/types';

/**
 * The activation surface: what the workspace still needs beside what its data can already prove.
 * It is rendered only while a required setup step is outstanding, so it retires on its own once the
 * dashboard's regular signal widgets have something real to show. A workspace whose logged
 * interactions have produced warmth but no triage-worthy signal sees that first warmth reading
 * rather than being told nothing rises to a signal.
 */
export default function ActivationPanel({
    steps,
    journey,
    insight,
    warmthReadings,
    gaps,
    canCreateFollowUp,
}: {
    steps: ActivationStep[] | null;
    journey: FirstRunJourney | null;
    insight: ActivationInsight | null;
    /** Warmth band counts backed by recorded interactions, or null when none are proven. */
    warmthReadings: WarmthBandCounts | null;
    gaps: ActivationGap[];
    canCreateFollowUp: boolean;
}) {
    const t = useTranslations('DashboardActivation');
    const unavailable = gaps.includes('unavailable');
    const arrival = insight == null && !unavailable ? warmthReadings : null;
    const paired = (insight != null || arrival != null) && (steps != null || unavailable);

    return (
        <section aria-label={t('sectionTitle')}>
            <SectionHeader title={t('sectionTitle')} />
            <div className={paired || steps ? 'grid grid-cols-1 items-stretch gap-6 lg:grid-cols-2' : 'grid'}>
                {steps ? <SetupChecklist steps={steps} journey={journey} /> : null}
                {!steps && unavailable ? <MissingEvidence gaps={gaps} /> : null}
                {insight ? (
                    <FirstInsightCard insight={insight} canCreateFollowUp={canCreateFollowUp} />
                ) : arrival ? (
                    <FirstWarmthCard readings={arrival} />
                ) : !unavailable ? (
                    <MissingEvidence gaps={gaps} />
                ) : null}
            </div>
        </section>
    );
}
