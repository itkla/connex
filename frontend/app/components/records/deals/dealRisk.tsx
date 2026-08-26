'use client';

import { useCallback, type ComponentType, type ReactNode, type SVGProps } from 'react';
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

/** The contact a cooling-stakeholder factor is about, when the engine named one. */
export type RiskFactorContact = {
    personId: number | null;
    /** The stakeholder as the sentence refers to them: their role and name, or just their name. */
    label: string;
};

/** The contact named by a cooling-stakeholder factor, or null for every other factor code. */
export function riskFactorContact(factor: DealRiskFactor): RiskFactorContact | null {
    if (factor.code !== 'stakeholder_cold') return null;
    const person = stringParam(factor.params.person) ?? '';
    if (!person) return null;
    const role = stringParam(factor.params.role);
    return {
        personId: numberParam(factor.params.personId),
        label: role ? `${role} ${person}` : person,
    };
}

/**
 * Localizes deal-risk copy: the level label for the pill, a human sentence per factor, and the same
 * sentence as nodes so a surface can make the stakeholder it names a link. Optional params (role,
 * days-since-touch) are composed in TS so the message catalogue stays simple.
 */
export function useRiskText() {
    const t = useTranslations('DealRisk');

    const levelLabel = useCallback(
        (severity: DealRiskSeverity): string => t(LEVEL_LABEL_KEYS[severity]),
        [t],
    );

    const stakeholderKey = useCallback(
        (factor: DealRiskFactor) => stringParam(factor.params.band) === 'cold'
            ? 'factorStakeholderCold'
            : 'factorStakeholderCooling',
        [],
    );

    const sinceContact = useCallback((factor: DealRiskFactor): string => {
        const days = numberParam(factor.params.daysSinceTouch);
        return days != null ? t('factorSinceContact', { days }) : '';
    }, [t]);

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
                const contact = riskFactorContact(factor);
                const base = t.markup(stakeholderKey(factor), {
                    contact: contact?.label ?? '',
                    person: (chunks) => chunks,
                });
                return base + sinceContact(factor);
            }
            default:
                return '';
        }
    }, [sinceContact, stakeholderKey, t]);

    /**
     * The same sentence as {@link factorText}, with the named stakeholder rendered by
     * `renderContact` so a panel can link the contact the signal cites instead of naming them flat.
     */
    const factorNode = useCallback((
        factor: DealRiskFactor,
        renderContact: (contact: RiskFactorContact, label: ReactNode) => ReactNode,
    ): ReactNode => {
        const contact = factor.code === 'stakeholder_cold' ? riskFactorContact(factor) : null;
        if (!contact) return factorText(factor);
        return (
            <>
                {t.rich(stakeholderKey(factor), {
                    contact: contact.label,
                    person: (chunks) => renderContact(contact, chunks),
                })}
                {sinceContact(factor)}
            </>
        );
    }, [factorText, sinceContact, stakeholderKey, t]);

    return { levelLabel, factorText, factorNode };
}
