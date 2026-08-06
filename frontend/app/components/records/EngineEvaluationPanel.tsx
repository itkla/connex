'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';

import { Switch } from '@/components/ui/switch';
import SectionHeader from '@/app/components/dashboard/SectionHeader';
import { updateContactEvaluation, updateDealEvaluation } from '@/app/lib/api';
import { toastError } from '@/app/lib/toast';
import { cn } from '@/lib/utils';

type PanelProps =
    | { kind: 'contact'; id: number; riskExcluded: boolean; introExcluded: boolean; className?: string }
    | { kind: 'deal'; id: number; riskExcluded: boolean; className?: string };

/**
 * Per-record engine-evaluation opt-outs (issue #358): switches that include or exclude the record
 * from risk and introduction engine evaluation. A switch is on while the record is evaluated;
 * flipping it saves optimistically and reverts with a toast when the save fails. Warmth display
 * and plain date reminders are unaffected by these settings.
 */
export default function EngineEvaluationPanel(props: PanelProps) {
    const t = useTranslations('EngineEvaluation');
    const router = useRouter();
    const [riskIncluded, setRiskIncluded] = useState(!props.riskExcluded);
    const [introIncluded, setIntroIncluded] = useState(
        props.kind === 'contact' ? !props.introExcluded : true,
    );
    const [saving, setSaving] = useState(false);

    const save = async (key: 'risk' | 'intro', included: boolean) => {
        const apply = key === 'risk' ? setRiskIncluded : setIntroIncluded;
        apply(included);
        setSaving(true);
        try {
            if (props.kind === 'deal') {
                await updateDealEvaluation(props.id, { riskExcluded: !included });
            } else if (key === 'risk') {
                await updateContactEvaluation(props.id, { riskExcluded: !included });
            } else {
                await updateContactEvaluation(props.id, { introExcluded: !included });
            }
            router.refresh();
        } catch (err) {
            apply(!included);
            toastError(err instanceof Error ? err.message : t('updateFailed'));
        } finally {
            setSaving(false);
        }
    };

    const rows =
        props.kind === 'contact'
            ? [
                  {
                      key: 'risk' as const,
                      label: t('contactRiskLabel'),
                      hint: t('contactRiskHint'),
                      checked: riskIncluded,
                  },
                  {
                      key: 'intro' as const,
                      label: t('contactIntroLabel'),
                      hint: t('contactIntroHint'),
                      checked: introIncluded,
                  },
              ]
            : [
                  {
                      key: 'risk' as const,
                      label: t('dealRiskLabel'),
                      hint: t('dealRiskHint'),
                      checked: riskIncluded,
                  },
              ];

    return (
        <div className={cn('mt-6', props.className)}>
            <SectionHeader title={t('title')} />
            <div className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                {rows.map((row) => (
                    <div key={row.key} className="flex items-center justify-between gap-4 px-6 py-4">
                        <div className="min-w-0">
                            <p className="text-sm font-medium text-foreground">{row.label}</p>
                            <p
                                id={`engine-evaluation-${props.kind}-${props.id}-${row.key}-hint`}
                                className="mt-0.5 text-xs text-muted-foreground"
                            >
                                {row.hint}
                            </p>
                        </div>
                        <Switch
                            checked={row.checked}
                            disabled={saving}
                            onCheckedChange={(value) => void save(row.key, value)}
                            aria-label={row.label}
                            aria-describedby={`engine-evaluation-${props.kind}-${props.id}-${row.key}-hint`}
                        />
                    </div>
                ))}
            </div>
        </div>
    );
}
