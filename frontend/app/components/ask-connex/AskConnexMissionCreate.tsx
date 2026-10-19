'use client';

import { useId, useState } from 'react';
import { useTranslations } from 'next-intl';

import ConfirmDiscardDialog from '@/app/components/ConfirmDiscardDialog';
import { useUnsavedChangesGuard } from '@/app/hooks/useUnsavedChangesGuard';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import type { AskConnexMissionProblem } from '@/app/lib/askConnexMissions';

/** Confirms the bounded, read-only request against the selected record before creation. */
export default function AskConnexMissionCreate({ recordLabel, initialGoal, disabled, problem, onCreate }: {
    recordLabel: string;
    initialGoal: string;
    disabled: boolean;
    problem: AskConnexMissionProblem | null;
    onCreate: (goal: string) => Promise<boolean>;
}) {
    const t = useTranslations('AskConnex.missions');
    const id = useId();
    const [open, setOpen] = useState(false);
    const [goal, setGoal] = useState('');
    const [originalGoal, setOriginalGoal] = useState('');
    const [saving, setSaving] = useState(false);
    const [failed, setFailed] = useState(false);
    const guard = useUnsavedChangesGuard({ isDirty: open && goal !== originalGoal,
        onClose: () => setOpen(false), enabled: !saving });
    async function submit() {
        if (saving || disabled || !goal.trim() || goal.trim().length > 16_000) return;
        setSaving(true);
        setFailed(false);
        try {
            if (await onCreate(goal.trim())) setOpen(false);
            else setFailed(true);
        }
        finally { setSaving(false); }
    }
    return (
        <>
            <Button type="button" variant="ghost" size="inline" disabled={disabled} onClick={() => {
                const value = initialGoal.trim() || t('defaultGoal');
                setGoal(value);
                setOriginalGoal(value);
                setFailed(false);
                setOpen(true);
            }}>{t('create')}</Button>
            <Dialog open={open} onOpenChange={(value) => { if (!saving) guard.onOpenChange(value); }}>
                <DialogContent aria-busy={saving}>
                    <DialogHeader>
                        <DialogTitle>{t('createTitle')}</DialogTitle>
                        <DialogDescription>{t('scope', { record: recordLabel })}</DialogDescription>
                    </DialogHeader>
                    <div className="space-y-2">
                        <Label htmlFor={id}>{t('goal')}</Label>
                        <Textarea id={id} value={goal} maxLength={16_000} disabled={saving}
                            onChange={(event) => setGoal(event.target.value)} />
                        <p className="text-sm text-muted-foreground">{t('bounds')}</p>
                        {problem || failed ? <p role="alert" className="text-sm text-destructive">{t(`problems.${problem ?? 'failed'}`)}</p> : null}
                    </div>
                    <DialogFooter>
                        <Button type="button" variant="outline" disabled={saving} onClick={guard.requestClose}>{t('close')}</Button>
                        <Button type="button" disabled={saving || disabled || !goal.trim()} onClick={() => void submit()}>
                            {saving ? t('creating') : t('start')}
                        </Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>
            <ConfirmDiscardDialog {...guard.confirm} />
        </>
    );
}
