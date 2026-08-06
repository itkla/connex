'use client';

import { useLocale, useTranslations } from 'next-intl';

import { cn } from '@/lib/utils';
import { formatDateTime, riskContainerClasses, riskTextClass } from '@/app/lib/utils';
import type { DealRisk } from '@/app/lib/types';

import { riskFactorIcon, useRiskText } from './dealRisk';

/**
 * Informational list of a deal's risk factors on the deal-detail page — the sibling of
 * {@link EntityNotificationBanner}. Each factor is a bordered, severity-tinted row with an icon and
 * a localized sentence. Clear and unavailable states remain distinct and every valid assessment
 * exposes its freshness. There is no dismiss action; signals clear when their source state resolves.
 */
export default function DealRiskPanel({
    risk,
    className,
}: {
    risk?: DealRisk | null;
    className?: string;
}) {
    const t = useTranslations('DealRisk');
    const locale = useLocale();
    const { factorText } = useRiskText();

    if (!risk) {
        return (
            <section
                aria-label={t('panelTitle')}
                className={cn('rounded-2xl border border-border bg-card px-5 py-4', className)}
            >
                <h2 className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                    {t('panelTitle')}
                </h2>
                <p className="mt-2 text-sm text-muted-foreground">{t('unavailable')}</p>
            </section>
        );
    }

    return (
        <section aria-label={t('panelTitle')} className={cn('grid gap-3', className)}>
            <h2 className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                {t('panelTitle')}
            </h2>
            {risk.level === 'none' || risk.factors.length === 0 ? (
                <p className="rounded-xl border border-border bg-card px-4 py-3 text-sm text-muted-foreground">
                    {t('clear')}
                </p>
            ) : (
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
            )}
            <p className="text-xs text-muted-foreground">
                {t('assessedAt', { date: formatDateTime(risk.assessedAt, locale) })}
            </p>
        </section>
    );
}
