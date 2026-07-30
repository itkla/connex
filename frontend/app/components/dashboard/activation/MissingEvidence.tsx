'use client';

import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { ExclamationCircleIcon, MinusSmallIcon } from '@heroicons/react/24/outline';

import type { ActivationGap } from '@/app/lib/activation';
import { Button } from '@/components/ui/button';

/**
 * What stands in place of the first-insight card when the workspace cannot yet justify a signal.
 * It states the missing inputs by name and shows no score, band, pill, or chart, because a score
 * without evidence would be a fabricated judgement rather than a reading of the data.
 */
export default function MissingEvidence({ gaps }: { gaps: ActivationGap[] }) {
    const t = useTranslations('DashboardActivation');
    const router = useRouter();
    const unavailable = gaps.includes('unavailable');
    const noSignal = gaps.includes('noSignal');
    const title = unavailable
        ? t('missing.unavailableTitle')
        : noSignal
            ? t('missing.noSignalTitle')
            : t('missing.title');
    const body = unavailable
        ? t('missing.unavailableBody')
        : noSignal
            ? t('missing.noSignalBody')
            : t('missing.body');

    return (
        <div className="flex h-full flex-col overflow-hidden rounded-2xl border border-dashed border-border bg-card/40">
            <div className="flex items-center gap-2.5 border-b border-dashed border-border px-5 py-3.5">
                <ExclamationCircleIcon className="size-5 shrink-0 text-muted-foreground" aria-hidden />
                <h3 className="min-w-0 text-sm font-medium text-foreground">{title}</h3>
            </div>

            <div className="flex flex-1 flex-col gap-4 px-5 py-4">
                <p className="max-w-prose text-sm text-muted-foreground">{body}</p>

                {unavailable ? (
                    <div>
                        <Button type="button" size="sm" variant="outline" onClick={() => router.refresh()}>
                            {t('missing.retry')}
                        </Button>
                    </div>
                ) : null}

                {!unavailable && !noSignal ? <div className="min-w-0">
                    <h4 className="text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase">
                        {t('missing.gapsTitle')}
                    </h4>
                    <ul className="mt-2 grid gap-2">
                        {gaps.map((gap) => (
                            <li
                                key={gap}
                                className="flex items-center gap-3 rounded-lg border border-dashed border-border px-3.5 py-2.5"
                            >
                                <MinusSmallIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                                <p className="min-w-0 flex-1 text-sm text-muted-foreground">
                                    {t(`missing.gap.${gap}`)}
                                </p>
                            </li>
                        ))}
                    </ul>
                </div> : null}
            </div>
        </div>
    );
}
