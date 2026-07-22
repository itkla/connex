'use client';

import { useCallback } from 'react';
import { useLocale, useTranslations } from 'next-intl';

/**
 * Localizes the deterministic dimension labels the report engine emits (status,
 * warmth, risk, trend, and bucketed dates) into the active locale.
 *
 * The engine returns raw tokens like `Won revenue · won` or `Deals · 2026-02`;
 * the value after the final ` · ` separator is the part that needs translating.
 * Both the document view and the widget renderer share this so the mapping only
 * lives in one place.
 */
export function useReportLabels() {
    const t = useTranslations('Reports');
    const locale = useLocale();

    const localizeValue = useCallback(
        (value: string): string => {
            if (/^\d{4}-\d{2}(?:-\d{2})?$/.test(value)) {
                const date = new Date(`${value}${value.length === 7 ? '-01' : ''}T00:00:00Z`);
                return new Intl.DateTimeFormat(locale, {
                    timeZone: 'UTC',
                    year: 'numeric',
                    month: 'short',
                    ...(value.length === 10 ? { day: 'numeric' } : {}),
                }).format(date);
            }
            switch (value.trim().toLowerCase().replaceAll(' ', '_')) {
                case 'open': return t('status.open');
                case 'won': return t('status.won');
                case 'lost': return t('status.lost');
                case 'todo': return t('status.todo');
                case 'in_progress': return t('status.in_progress');
                case 'done': return t('status.done');
                case 'hot': return t('warmth.hot');
                case 'warm': return t('warmth.warm');
                case 'cool': return t('warmth.cool');
                case 'cold': return t('warmth.cold');
                case 'high': return t('risk.high');
                case 'medium': return t('risk.medium');
                case 'low': return t('risk.low');
                case 'rising': return t('trend.rising');
                case 'steady': return t('trend.steady');
                case 'cooling': return t('trend.cooling');
                case 'total': return t('label.total');
                case 'unassigned': return t('label.unassigned');
                case 'unspecified': return t('label.unspecified');
                case 'other': return t('label.other');
                case 'workspace-wide': return t('label.workspaceWide');
                default: return value;
            }
        },
        [locale, t],
    );

    const localizeLabel = useCallback(
        (label: string): string => {
            const separator = label.lastIndexOf(' · ');
            const prefix = separator >= 0 ? label.slice(0, separator + 3) : '';
            const value = separator >= 0 ? label.slice(separator + 3) : label;
            return prefix + localizeValue(value);
        },
        [localizeValue],
    );

    return { localizeValue, localizeLabel };
}
