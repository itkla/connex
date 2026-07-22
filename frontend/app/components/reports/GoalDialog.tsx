'use client';

import { useState } from 'react';
import { useTranslations } from 'next-intl';

import type {
    ReportGoal,
    ReportGoalInput,
    ReportGoalPeriodType,
    WorkspaceMember,
} from '@/app/lib/types';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
    ResponsiveDialog,
    ResponsiveDialogClose,
    ResponsiveDialogContent,
    ResponsiveDialogDescription,
    ResponsiveDialogFooter,
    ResponsiveDialogHeader,
    ResponsiveDialogTitle,
} from '@/components/ui/responsive-dialog';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from '@/components/ui/select';

type GoalDialogProps = {
    open: boolean;
    editing: ReportGoal | null;
    owners: WorkspaceMember[];
    onOpenChange: (open: boolean) => void;
    onSubmit: (payload: ReportGoalInput) => Promise<void>;
};

/** Creates or replaces one deterministic revenue goal. */
export default function GoalDialog({
    open,
    editing,
    owners,
    onOpenChange,
    onSubmit,
}: GoalDialogProps) {
    const [saving, setSaving] = useState(false);

    const handleOpenChange = (next: boolean) => {
        if (!next && saving) return;
        onOpenChange(next);
    };

    const handleSubmit = async (payload: ReportGoalInput) => {
        setSaving(true);
        try {
            await onSubmit(payload);
            onOpenChange(false);
        } finally {
            setSaving(false);
        }
    };

    return (
        <ResponsiveDialog open={open} onOpenChange={handleOpenChange}>
            <ResponsiveDialogContent className="sm:max-w-lg">
                {open ? (
                    <GoalForm
                        key={editing ? `edit-${editing.id}` : 'new'}
                        editing={editing}
                        owners={owners}
                        saving={saving}
                        onSubmit={handleSubmit}
                    />
                ) : null}
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}

function GoalForm({
    editing,
    owners,
    saving,
    onSubmit,
}: {
    editing: ReportGoal | null;
    owners: WorkspaceMember[];
    saving: boolean;
    onSubmit: (payload: ReportGoalInput) => Promise<void>;
}) {
    const t = useTranslations('Reports');
    const [scope, setScope] = useState(editing?.ownerId == null ? 'workspace' : String(editing.ownerId));
    const [periodType, setPeriodType] = useState<ReportGoalPeriodType>(editing?.periodType ?? 'month');
    const [period, setPeriod] = useState(editing?.periodStart.slice(0, 7) ?? '');
    const [target, setTarget] = useState(editing ? String(editing.targetValue) : '');
    const [currency, setCurrency] = useState(editing?.currency ?? 'USD');
    const [error, setError] = useState<{ fieldId: string; message: string } | null>(null);

    const fail = (fieldId: string, message: string) => {
        setError({ fieldId, message });
        requestAnimationFrame(() => document.getElementById(fieldId)?.focus());
    };

    const submit = () => {
        setError(null);
        const ownerId = scope === 'workspace' ? null : Number(scope);
        const month = Number(period.slice(5, 7));
        const normalizedCurrency = currency.trim().toUpperCase();
        if (ownerId != null && (!Number.isInteger(ownerId) || !owners.some((owner) => owner.id === ownerId))) {
            fail('goal-scope', t('goals.validation.owner'));
            return;
        }
        if (!/^\d{4}-\d{2}$/.test(period)) {
            fail('goal-period', t('goals.validation.period'));
            return;
        }
        if (periodType === 'quarter' && ![1, 4, 7, 10].includes(month)) {
            fail('goal-period', t('goals.validation.quarter'));
            return;
        }
        if (!/^\d{1,13}(?:\.\d{1,2})?$/.test(target)) {
            fail('goal-target', t('goals.validation.target'));
            return;
        }
        if (!/^[A-Z]{3,8}$/.test(normalizedCurrency)) {
            fail('goal-currency', t('goals.validation.currency'));
            return;
        }
        const targetValue = Number(target);
        if (!Number.isFinite(targetValue) || targetValue < 0) {
            fail('goal-target', t('goals.validation.target'));
            return;
        }
        void onSubmit({
            ownerId,
            metric: 'won_revenue',
            periodType,
            periodStart: `${period}-01`,
            targetValue,
            currency: normalizedCurrency,
        }).catch(() => undefined);
    };

    return (
        <form
            onSubmit={(event) => {
                event.preventDefault();
                submit();
            }}
            className="grid gap-5"
        >
            <ResponsiveDialogHeader>
                <ResponsiveDialogTitle>
                    {editing ? t('goals.editTitle') : t('goals.createTitle')}
                </ResponsiveDialogTitle>
                <ResponsiveDialogDescription>{t('goals.formDescription')}</ResponsiveDialogDescription>
            </ResponsiveDialogHeader>

            <div className="grid gap-4 sm:grid-cols-2">
                <div className="grid gap-1.5 sm:col-span-2">
                    <Label htmlFor="goal-scope">{t('goals.scope')}</Label>
                    <Select value={scope} onValueChange={setScope} disabled={saving}>
                        <SelectTrigger
                            id="goal-scope"
                            className="w-full"
                            aria-invalid={error?.fieldId === 'goal-scope'}
                            aria-describedby={error?.fieldId === 'goal-scope' ? 'goal-form-error' : undefined}
                        >
                            <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                            <SelectItem value="workspace">{t('goals.workspaceWide')}</SelectItem>
                            {owners.map((owner) => (
                                <SelectItem key={owner.id} value={String(owner.id)}>
                                    {owner.displayName}
                                </SelectItem>
                            ))}
                        </SelectContent>
                    </Select>
                </div>

                <div className="grid gap-1.5">
                    <Label htmlFor="goal-period-type">{t('goals.periodType')}</Label>
                    <Select
                        value={periodType}
                        onValueChange={(value) => {
                            if (value === 'month' || value === 'quarter') setPeriodType(value);
                        }}
                        disabled={saving}
                    >
                        <SelectTrigger id="goal-period-type" className="w-full">
                            <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                            <SelectItem value="month">{t('goals.month')}</SelectItem>
                            <SelectItem value="quarter">{t('goals.quarter')}</SelectItem>
                        </SelectContent>
                    </Select>
                </div>

                <div className="grid gap-1.5">
                    <Label htmlFor="goal-period">{t('goals.period')}</Label>
                    <Input
                        id="goal-period"
                        type="month"
                        value={period}
                        onChange={(event) => setPeriod(event.target.value)}
                        disabled={saving}
                        required
                        aria-invalid={error?.fieldId === 'goal-period'}
                        aria-describedby={error?.fieldId === 'goal-period' ? 'goal-form-error' : undefined}
                    />
                </div>

                <div className="grid gap-1.5 sm:col-span-2">
                    <Label htmlFor="goal-metric">{t('goals.metric')}</Label>
                    <Input id="goal-metric" value={t('measure.won_revenue')} disabled />
                </div>

                <div className="grid gap-1.5">
                    <Label htmlFor="goal-target">{t('goals.target')}</Label>
                    <Input
                        id="goal-target"
                        inputMode="decimal"
                        value={target}
                        onChange={(event) => setTarget(event.target.value)}
                        placeholder={t('goals.targetPlaceholder')}
                        disabled={saving}
                        autoFocus
                        required
                        aria-invalid={error?.fieldId === 'goal-target'}
                        aria-describedby={error?.fieldId === 'goal-target' ? 'goal-form-error' : undefined}
                    />
                </div>

                <div className="grid gap-1.5">
                    <Label htmlFor="goal-currency">{t('goals.currency')}</Label>
                    <Input
                        id="goal-currency"
                        value={currency}
                        onChange={(event) => setCurrency(event.target.value.toUpperCase())}
                        maxLength={8}
                        disabled={saving}
                        required
                        aria-invalid={error?.fieldId === 'goal-currency'}
                        aria-describedby={error?.fieldId === 'goal-currency' ? 'goal-form-error' : undefined}
                    />
                </div>
            </div>

            {error ? (
                <p id="goal-form-error" role="alert" aria-live="assertive" className="text-sm text-destructive">
                    {error.message}
                </p>
            ) : null}

            <ResponsiveDialogFooter>
                <ResponsiveDialogClose asChild>
                    <Button type="button" variant="outline" disabled={saving}>{t('common.cancel')}</Button>
                </ResponsiveDialogClose>
                <Button type="submit" variant="brand" disabled={saving}>
                    {saving ? t('common.saving') : editing ? t('goals.saveChanges') : t('goals.createGoal')}
                </Button>
            </ResponsiveDialogFooter>
        </form>
    );
}
