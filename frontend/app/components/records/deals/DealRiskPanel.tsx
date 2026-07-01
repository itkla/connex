'use client';

import { useTranslations } from 'next-intl';

import { cn } from '@/lib/utils';
import { riskContainerClasses, riskTextClass } from '@/app/lib/utils';
import type { DealRisk } from '@/app/lib/types';

import { riskFactorIcon, useRiskText } from './dealRisk';

/**
 * Informational list of a deal's risk factors on the deal-detail page — the sibling of
 * {@link EntityNotificationBanner}. Each factor is a bordered, severity-tinted row with an icon and
 * a localized sentence. Renders nothing when the deal is not at risk. There is no dismiss action;
 * the signals clear on their own once the underlying condition resolves.
 */
export default function DealRiskPanel({ risk }: { risk?: DealRisk | null }) {
    const t = useTranslations('DealRisk');
    const { factorText } = useRiskText();

    if (!risk || risk.level === 'none' || risk.factors.length === 0) return null;

    return (
        <section aria-label={t('panelTitle')} className="mt-8 grid gap-3">
            <h2 className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                {t('panelTitle')}
            </h2>
            <ul className="grid gap-2">
                {risk.factors.map((factor, index) => {
                    const Icon = riskFactorIcon(factor.code);
                    return (
                        <li
                            key={`${factor.code}-${index}`}
                            className={cn(
                                'flex items-center gap-3 rounded-lg border px-4 py-3',
                                riskContainerClasses(factor.severity),
                            )}
                        >
                            <Icon className={cn('size-5 shrink-0', riskTextClass(factor.severity))} aria-hidden />
                            <p className="min-w-0 flex-1 text-sm text-foreground">{factorText(factor)}</p>
                        </li>
                    );
                })}
            </ul>
        </section>
    );
}
