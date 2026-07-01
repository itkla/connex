'use client';

import { useCallback, type ComponentType, type SVGProps } from 'react';
import {
    CalendarDaysIcon,
    ClockIcon,
    ExclamationTriangleIcon,
    UserMinusIcon,
    UsersIcon,
} from '@heroicons/react/24/outline';
import { useTranslations } from 'next-intl';

import type { DealRiskFactor, DealRiskFactorCode, DealRiskSeverity } from '@/app/lib/types';

type IconComponent = ComponentType<SVGProps<SVGSVGElement>>;

const FACTOR_ICONS: Record<DealRiskFactorCode, IconComponent> = {
    close_overdue: CalendarDaysIcon,
    closing_soon_quiet: CalendarDaysIcon,
    stalled: ClockIcon,
    stakeholder_cold: UserMinusIcon,
    no_stakeholders: UsersIcon,
};

const LEVEL_LABEL_KEYS: Record<DealRiskSeverity, 'levelHigh' | 'levelMedium' | 'levelLow'> = {
    high: 'levelHigh',
    medium: 'levelMedium',
    low: 'levelLow',
};

/** Heroicon for a risk factor; falls back to the alert triangle for any unknown code. */
export function riskFactorIcon(code: DealRiskFactorCode): IconComponent {
    return FACTOR_ICONS[code] ?? ExclamationTriangleIcon;
}

function numberParam(value: unknown): number | null {
    return typeof value === 'number' ? value : null;
}

function stringParam(value: unknown): string | null {
    return typeof value === 'string' && value.length > 0 ? value : null;
}

/**
 * Localizes deal-risk copy: the level label for the pill and a human sentence per factor. Optional
 * params (role, days-since-touch) are composed in TS so the message catalogue stays simple.
 */
export function useRiskText() {
    const t = useTranslations('DealRisk');

    const levelLabel = useCallback(
        (severity: DealRiskSeverity): string => t(LEVEL_LABEL_KEYS[severity]),
        [t],
    );

    const factorText = useCallback((factor: DealRiskFactor): string => {
        const params = factor.params;
        switch (factor.code) {
            case 'close_overdue':
                return t('factorCloseOverdue', { days: numberParam(params.daysOverdue) ?? 0 });
            case 'closing_soon_quiet':
                return t('factorClosingSoonQuiet', { days: numberParam(params.daysUntilClose) ?? 0 });
            case 'stalled':
                return t('factorStalled', { days: numberParam(params.daysSinceTouch) ?? 0 });
            case 'no_stakeholders':
                return t('factorNoStakeholders');
            case 'stakeholder_cold': {
                const person = stringParam(params.person) ?? '';
                const role = stringParam(params.role);
                const contact = role ? `${role} ${person}` : person;
                const base =
                    stringParam(params.band) === 'cold'
                        ? t('factorStakeholderCold', { contact })
                        : t('factorStakeholderCooling', { contact });
                const days = numberParam(params.daysSinceTouch);
                return days != null ? base + t('factorSinceContact', { days }) : base;
            }
            default:
                return '';
        }
    }, [t]);

    return { levelLabel, factorText };
}
