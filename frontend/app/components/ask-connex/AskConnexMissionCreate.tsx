'use client';

import { useId, useState } from 'react';
import { useTranslations } from 'next-intl';

import ConfirmDiscardDialog from '@/app/components/ConfirmDiscardDialog';
import { useUnsavedChangesGuard } from '@/app/hooks/useUnsavedChangesGuard';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { SegmentedControl } from '@/components/ui/segmented-control';
import type { AiAgentMissionType } from '@/app/lib/types';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import type { AskConnexMissionProblem } from '@/app/lib/askConnexMissions';

/** Confirms the bounded request against the selected record before creation. */
export default function AskConnexMissionCreate({ recordLabel, initialGoal, disabled, problem, onCreate }: {
    recordLabel: string;
    initialGoal: string;
    disabled: boolean;
    problem: AskConnexMissionProblem | null;
    onCreate: (goal: string, missionType: AiAgentMissionType) => Promise<boolean>;
}) {
    const t = useTranslations('AskConnex.missions');
    const id = useId();
    const [open, setOpen] = useState(false);
    const [missionType, setMissionType] = useState<AiAgentMissionType>('record_review_v1');
    const preparing = missionType === 'record_preparation_v1';
    const [goal, setGoal] = useState('');
    const [originalGoal, setOriginalGoal] = useState('');
    const [saving, setSaving] = useState(false);
    const [failed, setFailed] = useState(false);
    const guard = useUnsavedChangesGuard({ isDirty: open && (goal !== originalGoal || preparing),
        onClose: () => setOpen(false), enabled: !saving });
    async function submit() {
        if (saving || disabled || !goal.trim() || goal.trim().length > 16_000) return;
        setSaving(true);
        setFailed(false);
        try {
            if (await onCreate(goal.trim(), missionType)) setOpen(false);
            else setFailed(true);
        }
        finally { setSaving(false); }
    }
    return (
        <>
            <Button type="button" variant="ghost" size="inline" disabled={disabled} onClick={() => {
                const value = initialGoal.trim() || t('defaultGoal');
                setMissionType('record_review_v1');
                setGoal(value);
                setOriginalGoal(value);
                setFailed(false);
                setOpen(true);
            }}>{t('create')}</Button>
            <Dialog open={open} onOpenChange={(value) => { if (!saving) guard.onOpenChange(value); }}>
                <DialogContent aria-busy={saving}>
                    <DialogHeader>
                        <DialogTitle>{t(preparing ? 'preparation.createTitle' : 'createTitle')}</DialogTitle>
                        <DialogDescription>{t('scope', { record: recordLabel })}</DialogDescription>
                    </DialogHeader>
                    <SegmentedControl value={missionType} ariaLabel={t('mode')} options={[
                        { value: 'record_review_v1', label: t('title'), disabled: saving },
                        { value: 'record_preparation_v1', label: t('preparation.title'), disabled: saving },
                    ]} onChange={(next) => {
                        setMissionType(next);
                        if (goal === originalGoal || goal === t('defaultGoal') || goal === t('preparation.defaultGoal')) {
                            setGoal(next === 'record_preparation_v1' ? t('preparation.defaultGoal') : initialGoal.trim() || t('defaultGoal'));
                        }
                    }} />
                    <div className="space-y-2">
                        <Label htmlFor={id}>{t('goal')}</Label>
                        <Textarea id={id} value={goal} maxLength={16_000} disabled={saving}
                            onChange={(event) => setGoal(event.target.value)} />
                        <p className="text-sm text-muted-foreground">{t(preparing ? 'preparation.bounds' : 'bounds')}</p>
                        {problem || failed ? <p role="alert" className="text-sm text-destructive">{t(`problems.${problem ?? 'failed'}`)}</p> : null}
                    </div>
                    <DialogFooter>
                        <Button type="button" variant="outline" disabled={saving} onClick={guard.requestClose}>{t('close')}</Button>
                        <Button type="button" disabled={saving || disabled || !goal.trim()} onClick={() => void submit()}>
                            {saving ? t('creating') : t(preparing ? 'preparation.start' : 'start')}
                        </Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>
            <ConfirmDiscardDialog {...guard.confirm} />
        </>
    );
}
