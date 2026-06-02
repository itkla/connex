import Link from 'next/link';
import { PresentationChartLineIcon } from '@heroicons/react/24/outline';
import { useTranslations } from 'next-intl';

export default function FirstRun() {
    const t = useTranslations('AnalyticsPage');
    return (
        <div className="flex flex-col items-center justify-center rounded-2xl bg-white px-6 py-20 text-center ring-1 ring-black/5">
            <span className="flex size-14 items-center justify-center rounded-2xl bg-brand-light text-brand-dark">
                <PresentationChartLineIcon className="size-7" />
            </span>
            <h2 className="mt-6 text-xl font-semibold text-neutral-900">{t('emptyTitle')}</h2>
            <p className="mt-2 max-w-md text-sm text-neutral-500">{t('emptyBody')}</p>
            <div className="mt-6 flex flex-wrap items-center justify-center gap-3">
                <Link
                    href="/records/deals"
                    className="inline-flex items-center rounded-lg bg-brand px-4 py-2 text-sm font-medium text-white transition hover:bg-brand-hover"
                >
                    {t('emptyCreateDeal')}
                </Link>
                <Link
                    href="/records/pipelines"
                    className="inline-flex items-center rounded-lg bg-neutral-100 px-4 py-2 text-sm font-medium text-neutral-700 ring-1 ring-black/5 transition hover:bg-neutral-200"
                >
                    {t('emptySetupPipeline')}
                </Link>
            </div>
        </div>
    );
}