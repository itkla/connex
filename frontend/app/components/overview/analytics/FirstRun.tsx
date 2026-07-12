import Link from 'next/link';
import { PresentationChartLineIcon } from '@heroicons/react/24/outline';
import { useTranslations } from 'next-intl';

export default function FirstRun() {
    const t = useTranslations('AnalyticsPage');
    return (
        <div className="flex flex-col items-center justify-center rounded-2xl bg-card px-6 py-20 text-center ring-1 ring-border">
            <span className="flex size-14 items-center justify-center rounded-2xl bg-brand-light text-brand-dark">
                <PresentationChartLineIcon className="size-7" />
            </span>
            <h2 className="mt-6 text-xl font-semibold text-foreground">{t('emptyTitle')}</h2>
            <p className="mt-2 max-w-md text-sm text-muted-foreground">{t('emptyBody')}</p>
            <div className="mt-6 flex flex-wrap items-center justify-center gap-3">
                <Link
                    href="/records/deals"
                    className="inline-flex items-center rounded-lg bg-brand px-4 py-2 text-sm font-medium text-brand-foreground transition hover:bg-brand-hover"
                >
                    {t('emptyCreateDeal')}
                </Link>
                <Link
                    href="/records/pipelines"
                    className="inline-flex items-center rounded-lg bg-muted px-4 py-2 text-sm font-medium text-foreground ring-1 ring-border transition hover:bg-muted/80"
                >
                    {t('emptySetupPipeline')}
                </Link>
            </div>
        </div>
    );
}
