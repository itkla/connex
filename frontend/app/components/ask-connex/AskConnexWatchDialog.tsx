'use client';

import { useState } from 'react';
import { useLocale, useTranslations } from 'next-intl';

import { Button } from '@/components/ui/button';
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
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { createAiWatch } from '@/app/lib/api';
import { formatDate } from '@/app/lib/utils';
import type {
    AiWatch,
    AiWatchPayload,
    AiWatchSubjectKind,
    AiWatchType,
} from '@/app/lib/types';
import {
    ASK_CONNEX_WATCH_TYPES,
    askConnexWatchLimitsText,
    askConnexWatchSupports,
} from '@/app/components/ask-connex/askConnexWatch';

/** Warmth bands a cooling watch may be set to, coldest last. */
const BANDS = ['warm', 'cool', 'cold'] as const;

/** Day counts a silence watch may be set to. */
const DAY_CHOICES = [7, 14, 30, 60, 90] as const;

/** Risk levels a deal watch may be set to. */
const LEVELS = ['medium', 'high'] as const;

/**
 * The cooldown a watch is created with, mirroring the server's own default.
 *
 * The request omits it, so the server decides; this constant exists only so the dialog can state
 * what will apply. A member must never apply a contract with an unstated term in it.
 */
const DEFAULT_COOLDOWN_DAYS = 7;

/**
 * The typed watch contract, shown in full before anything is saved.
 *
 * A watch is only worth trusting if the member saw the exact condition before they applied it, so
 * this dialog is the only way one is created. There is no prose path: even when a request arrives in
 * natural language, it lands here as typed fields the member reads, edits, and explicitly applies.
 *
 * The preview line is assembled from the same fields the request carries, so what the member
 * confirms is literally what the server will evaluate.
 */
export default function AskConnexWatchDialog({
    open,
    onOpenChange,
    subjectKind,
    subjectId,
    subjectLabel,
    onCreated,
}: {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    subjectKind: AiWatchSubjectKind;
    subjectId: number;
    subjectLabel: string;
    onCreated?: (watch: AiWatch) => void;
}) {
    const t = useTranslations('AskConnex.commandCenter');
    const locale = useLocale();
    const showApiError = useApiErrorToast('AskConnex.commandCenter');
    const available = ASK_CONNEX_WATCH_TYPES.filter(
        (type) => askConnexWatchSupports(type, subjectKind),
    );
    const [watchType, setWatchType] = useState<AiWatchType>(available[0] ?? 'commitment_overdue');
    const [band, setBand] = useState<string>('cold');
    const [days, setDays] = useState<number>(30);
    const [level, setLevel] = useState<string>('high');
    const [saving, setSaving] = useState(false);

    if (available.length === 0) return null;

    const payload: AiWatchPayload = {
        watchType,
        subjectKind,
        subjectId,
        thresholdBand: watchType === 'relationship_cooling' ? band : null,
        thresholdDays: watchType === 'no_interaction' ? days : null,
        thresholdLevel: watchType === 'deal_risk_threshold' ? level : null,
    };

    const preview = previewText();
    const limits = askConnexWatchLimitsText(
        { cooldownDays: DEFAULT_COOLDOWN_DAYS, expiresOn: null },
        t,
        (isoDate) => formatDate(isoDate, locale),
    );

    async function apply() {
        setSaving(true);
        try {
            const created = await createAiWatch(payload);
            onCreated?.(created);
            onOpenChange(false);
        } catch (error) {
            showApiError(error, 'watchFailed');
        } finally {
            setSaving(false);
        }
    }

    function previewText(): string {
        if (watchType === 'relationship_cooling') {
            return t('watchTrigger.cooling', { band: t(`band.${band}`) });
        }
        if (watchType === 'no_interaction') return t('watchTrigger.noInteraction', { days });
        if (watchType === 'deal_risk_threshold') {
            return t('watchTrigger.dealRisk', { level: t(`level.${level}`) });
        }
        return t('watchTrigger.commitmentOverdue');
    }

    return (
        <ResponsiveDialog open={open} onOpenChange={onOpenChange}>
            <ResponsiveDialogContent className="sm:max-w-md">
                <ResponsiveDialogHeader>
                    <ResponsiveDialogTitle>{t('createTitle')}</ResponsiveDialogTitle>
                    <ResponsiveDialogDescription>
                        {t('createDescription', { record: subjectLabel })}
                    </ResponsiveDialogDescription>
                </ResponsiveDialogHeader>
                <div className="space-y-4 px-4 pb-2 sm:px-0">
                    <div className="space-y-1.5">
                        <Label htmlFor="ask-connex-watch-type">{t('createTypeLabel')}</Label>
                        <Select
                            value={watchType}
                            onValueChange={(value) => setWatchType(value as AiWatchType)}
                        >
                            <SelectTrigger id="ask-connex-watch-type" className="w-full">
                                <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                                {available.map((type) => (
                                    <SelectItem key={type} value={type}>
                                        {t(`watchTypeName.${type}`)}
                                    </SelectItem>
                                ))}
                            </SelectContent>
                        </Select>
                    </div>
                    {watchType === 'relationship_cooling' ? (
                        <div className="space-y-1.5">
                            <Label htmlFor="ask-connex-watch-band">{t('createBandLabel')}</Label>
                            <Select value={band} onValueChange={setBand}>
                                <SelectTrigger id="ask-connex-watch-band" className="w-full">
                                    <SelectValue />
                                </SelectTrigger>
                                <SelectContent>
                                    {BANDS.map((value) => (
                                        <SelectItem key={value} value={value}>
                                            {t(`band.${value}`)}
                                        </SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
                        </div>
                    ) : null}
                    {watchType === 'no_interaction' ? (
                        <div className="space-y-1.5">
                            <Label htmlFor="ask-connex-watch-days">{t('createDaysLabel')}</Label>
                            <Select
                                value={String(days)}
                                onValueChange={(value) => setDays(Number(value))}
                            >
                                <SelectTrigger id="ask-connex-watch-days" className="w-full">
                                    <SelectValue />
                                </SelectTrigger>
                                <SelectContent>
                                    {DAY_CHOICES.map((value) => (
                                        <SelectItem key={value} value={String(value)}>
                                            {t('createDaysOption', { days: value })}
                                        </SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
                        </div>
                    ) : null}
                    {watchType === 'deal_risk_threshold' ? (
                        <div className="space-y-1.5">
                            <Label htmlFor="ask-connex-watch-level">{t('createLevelLabel')}</Label>
                            <Select value={level} onValueChange={setLevel}>
                                <SelectTrigger id="ask-connex-watch-level" className="w-full">
                                    <SelectValue />
                                </SelectTrigger>
                                <SelectContent>
                                    {LEVELS.map((value) => (
                                        <SelectItem key={value} value={value}>
                                            {t(`level.${value}`)}
                                        </SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
                        </div>
                    ) : null}
                    <div className="rounded-lg bg-muted/60 px-3 py-2">
                        <p className="text-xs font-medium text-muted-foreground">
                            {t('createPreviewLabel')}
                        </p>
                        <p className="mt-0.5 text-sm text-foreground">{preview}</p>
                        <p className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs text-muted-foreground">
                            <span>{limits.cooldown}</span>
                            <span aria-hidden>·</span>
                            <span>{limits.expiry}</span>
                        </p>
                        <p className="mt-1 text-xs text-muted-foreground">
                            {t('createPreviewNote')}
                        </p>
                    </div>
                </div>
                <ResponsiveDialogFooter>
                    <ResponsiveDialogClose asChild>
                        <Button variant="outline">{t('createCancel')}</Button>
                    </ResponsiveDialogClose>
                    <Button type="button" onClick={() => void apply()} disabled={saving}>
                        {t('createApply')}
                    </Button>
                </ResponsiveDialogFooter>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}
