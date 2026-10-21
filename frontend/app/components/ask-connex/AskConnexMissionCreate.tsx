'use client';

import { useId, useReducer } from 'react';
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

type MissionCreateState = {
    open: boolean;
    missionType: AiAgentMissionType;
    goal: string;
    originalGoal: string;
    saving: boolean;
    failed: boolean;
};

type MissionCreateAction =
    | { type: 'open'; goal: string }
    | { type: 'close' }
    | { type: 'changeGoal'; goal: string }
    | { type: 'changeMissionType'; missionType: AiAgentMissionType; goal: string }
    | { type: 'startSubmission' }
    | { type: 'submissionResult'; created: boolean }
    | { type: 'finishSubmission' };

function missionCreateReducer(state: MissionCreateState, action: MissionCreateAction): MissionCreateState {
    switch (action.type) {
        case 'open': return { ...state, open: true, missionType: 'record_review_v1',
            goal: action.goal, originalGoal: action.goal, failed: false };
        case 'close': return { ...state, open: false };
        case 'changeGoal': return { ...state, goal: action.goal };
        case 'changeMissionType': return { ...state, missionType: action.missionType, goal: action.goal };
        case 'startSubmission': return { ...state, saving: true, failed: false };
        case 'submissionResult': return { ...state, open: action.created ? false : state.open, failed: !action.created };
        case 'finishSubmission': return { ...state, saving: false };
    }
}

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
    const [{ open, missionType, goal, originalGoal, saving, failed }, dispatch] = useReducer(missionCreateReducer, {
        open: false, missionType: 'record_review_v1', goal: '', originalGoal: '', saving: false, failed: false,
    });
    const preparing = missionType === 'record_preparation_v1';
    const guard = useUnsavedChangesGuard({ isDirty: open && (goal !== originalGoal || preparing),
        onClose: () => dispatch({ type: 'close' }), enabled: !saving });
    async function submit() {
        if (saving || disabled || !goal.trim() || goal.trim().length > 16_000) return;
        dispatch({ type: 'startSubmission' });
        try {
            dispatch({ type: 'submissionResult', created: await onCreate(goal.trim(), missionType) });
        }
        finally { dispatch({ type: 'finishSubmission' }); }
    }
    return (
        <>
            <Button type="button" variant="ghost" size="inline" disabled={disabled} onClick={() => {
                dispatch({ type: 'open', goal: initialGoal.trim() || t('defaultGoal') });
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
                        const replaceGoal = goal === originalGoal || goal === t('defaultGoal') || goal === t('preparation.defaultGoal');
                        dispatch({ type: 'changeMissionType', missionType: next, goal: replaceGoal
                            ? next === 'record_preparation_v1' ? t('preparation.defaultGoal') : initialGoal.trim() || t('defaultGoal')
                            : goal });
                    }} />
                    <div className="space-y-2">
                        <Label htmlFor={id}>{t('goal')}</Label>
                        <Textarea id={id} value={goal} maxLength={16_000} disabled={saving}
                            onChange={(event) => dispatch({ type: 'changeGoal', goal: event.target.value })} />
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
