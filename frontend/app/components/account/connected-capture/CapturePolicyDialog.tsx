'use client';

import { useMemo, useState } from 'react';
import { ShieldCheckIcon } from '@heroicons/react/24/outline';
import { useTranslations } from 'next-intl';

import CaptureDisclosures from '@/app/components/account/connected-capture/CaptureDisclosures';
import {
    CAPTURE_STREAMS,
    validateCapturePolicy,
} from '@/app/lib/connectedCapture';
import type {
    CaptureAdmissionMode,
    CaptureStream,
    ProviderCaptureOverview,
    ProviderCapturePolicy,
} from '@/app/lib/types';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
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
import { Switch } from '@/components/ui/switch';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from '@/components/ui/select';

const STREAM_KEYS: Record<CaptureStream, 'streams.calendar' | 'streams.mailInbox' | 'streams.mailSent'> = {
    calendar: 'streams.calendar',
    mail_inbox: 'streams.mailInbox',
    mail_sent: 'streams.mailSent',
};

function enabledStream(policy: ProviderCapturePolicy, stream: CaptureStream): boolean {
    if (stream === 'calendar') return policy.calendar;
    if (stream === 'mail_inbox') return policy.mailInbox;
    return policy.mailSent;
}

function workspaceAllowsStream(
    overview: ProviderCaptureOverview,
    stream: CaptureStream,
): boolean {
    if (stream === 'calendar') return overview.workspacePolicy.calendar;
    if (stream === 'mail_inbox') return overview.workspacePolicy.mailInbox;
    return overview.workspacePolicy.mailSent;
}

function isAdmissionMode(value: string): value is CaptureAdmissionMode {
    return value === 'manual' || value === 'review' || value === 'automatic';
}

/**
 * Collects the current user's explicit provider capture policy before any ingestion can begin.
 */
export default function CapturePolicyDialog({
    overview,
    open,
    saving,
    onOpenChange,
    onSave,
}: {
    overview: ProviderCaptureOverview;
    open: boolean;
    saving: boolean;
    onOpenChange: (open: boolean) => void;
    onSave: (policy: ProviderCapturePolicy) => Promise<boolean>;
}) {
    const t = useTranslations('AccountCapturePolicy');
    const initial = overview.userPolicy;
    const [policy, setPolicy] = useState<ProviderCapturePolicy>(initial);
    const validation = useMemo(
        () => validateCapturePolicy(policy, overview.workspacePolicy),
        [overview.workspacePolicy, policy],
    );
    const maxBackfillDays = Math.min(180, overview.workspacePolicy.maxBackfillDays);

    const setStream = (stream: CaptureStream, enabled: boolean) => {
        setPolicy((current) => ({
            ...current,
            calendar: stream === 'calendar' ? enabled : current.calendar,
            mailInbox: stream === 'mail_inbox' ? enabled : current.mailInbox,
            mailSent: stream === 'mail_sent' ? enabled : current.mailSent,
        }));
    };

    const save = async () => {
        if (!validation.valid) return;
        if (await onSave(policy)) onOpenChange(false);
    };

    return (
        <ResponsiveDialog open={open} onOpenChange={onOpenChange}>
            <ResponsiveDialogContent className="sm:max-w-2xl" showCloseButton={!saving}>
                <ResponsiveDialogHeader className="px-4 pt-4 sm:px-0 sm:pt-0">
                    <ResponsiveDialogTitle>{t('title')}</ResponsiveDialogTitle>
                    <ResponsiveDialogDescription>{t('description')}</ResponsiveDialogDescription>
                </ResponsiveDialogHeader>

                <div className="grid gap-5 px-4 py-4 sm:px-0">
                    {!overview.workspacePolicy.allowed ? (
                        <Alert>
                            <ShieldCheckIcon aria-hidden />
                            <AlertTitle>{t('workspaceBlockedTitle')}</AlertTitle>
                            <AlertDescription>{t('workspaceBlockedDescription')}</AlertDescription>
                        </Alert>
                    ) : null}

                    <div className="flex items-start justify-between gap-4 rounded-lg border border-border px-3 py-2.5">
                        <div className="grid gap-1">
                            <Label htmlFor={`capture-${overview.provider}-enabled`}>
                                {t('enabled')}
                            </Label>
                            <p className="text-xs text-muted-foreground">{t('enabledHint')}</p>
                        </div>
                        <Switch
                            id={`capture-${overview.provider}-enabled`}
                            checked={policy.enabled}
                            disabled={saving}
                            onCheckedChange={(checked) => setPolicy((current) => ({
                                ...current,
                                enabled: checked,
                            }))}
                        />
                    </div>

                    <fieldset className="grid gap-2" disabled={!policy.enabled || saving}>
                        <legend className="text-sm font-medium text-foreground">{t('streamsLabel')}</legend>
                        <p className="text-xs text-muted-foreground">{t('streamsDescription')}</p>
                        <div className="grid gap-2">
                            {CAPTURE_STREAMS.map((stream) => {
                                const workspaceAllows = workspaceAllowsStream(overview, stream);
                                const inputId = `capture-${overview.provider}-${stream}`;
                                return (
                                    <div
                                        key={stream}
                                        className="flex items-start justify-between gap-4 rounded-lg border border-border px-3 py-2.5"
                                    >
                                        <Label htmlFor={inputId} className="leading-snug">
                                            {t(STREAM_KEYS[stream])}
                                        </Label>
                                        <Switch
                                            id={inputId}
                                            checked={enabledStream(policy, stream)}
                                            disabled={!workspaceAllows || saving}
                                            onCheckedChange={(checked) => setStream(stream, checked)}
                                            aria-describedby={`${inputId}-hint`}
                                        />
                                        <span id={`${inputId}-hint`} className="sr-only">
                                            {workspaceAllows ? t('streamAvailable') : t('streamRestricted')}
                                        </span>
                                    </div>
                                );
                            })}
                        </div>
                        {policy.enabled && !validation.hasEnabledStream ? (
                            <p className="text-xs text-destructive" role="alert">
                                {t('validation.streamRequired')}
                            </p>
                        ) : null}
                    </fieldset>

                    <div className="grid gap-2">
                        <Label htmlFor={`capture-${overview.provider}-backfill`}>
                            {t('backfill.label')}
                        </Label>
                        <Input
                            id={`capture-${overview.provider}-backfill`}
                            type="number"
                            inputMode="numeric"
                            min={1}
                            max={maxBackfillDays}
                            value={policy.backfillDays}
                            disabled={!policy.enabled || saving}
                            aria-invalid={!validation.backfillDaysValid}
                            onChange={(event) => {
                                const value = Number(event.target.value);
                                setPolicy((current) => ({ ...current, backfillDays: value }));
                            }}
                        />
                        <p className="text-xs text-muted-foreground">
                            {t('backfill.hint', { max: maxBackfillDays })}
                        </p>
                        {!validation.backfillDaysValid ? (
                            <p className="text-xs text-destructive" role="alert">
                                {t('validation.backfill', { max: maxBackfillDays })}
                            </p>
                        ) : null}
                    </div>

                    <div className="grid gap-4 sm:grid-cols-2">
                        <div className="grid gap-2">
                            <Label htmlFor={`capture-${overview.provider}-excluded-people`}>
                                {t('exclusions.people')}
                            </Label>
                            <Input
                                id={`capture-${overview.provider}-excluded-people`}
                                value={policy.excludedPeople.join(', ')}
                                disabled={!policy.enabled || saving}
                                placeholder={t('exclusions.peoplePlaceholder')}
                                onChange={(event) => setPolicy((current) => ({
                                    ...current,
                                    excludedPeople: event.target.value
                                        .split(',')
                                        .map((value) => value.trim())
                                        .filter(Boolean),
                                }))}
                            />
                            <p className="text-xs text-muted-foreground">
                                {t('exclusions.peopleHint')}
                            </p>
                        </div>
                        <div className="grid gap-2">
                            <Label htmlFor={`capture-${overview.provider}-excluded-conversations`}>
                                {t('exclusions.conversations')}
                            </Label>
                            <Input
                                id={`capture-${overview.provider}-excluded-conversations`}
                                value={policy.excludedConversations.join(', ')}
                                disabled={!policy.enabled || saving}
                                placeholder={t('exclusions.conversationsPlaceholder')}
                                onChange={(event) => setPolicy((current) => ({
                                    ...current,
                                    excludedConversations: event.target.value
                                        .split(',')
                                        .map((value) => value.trim())
                                        .filter(Boolean),
                                }))}
                            />
                            <p className="text-xs text-muted-foreground">
                                {t('exclusions.conversationsHint')}
                            </p>
                        </div>
                    </div>

                    <div className="grid gap-2">
                        <div className="flex items-start justify-between gap-4 rounded-lg border border-border px-3 py-2.5">
                            <div className="grid gap-1">
                                <Label htmlFor={`capture-${overview.provider}-bodies`}>
                                    {t('includeBodies')}
                                </Label>
                                <p className="text-xs text-muted-foreground">{t('includeBodiesHint')}</p>
                            </div>
                            <Switch
                                id={`capture-${overview.provider}-bodies`}
                                checked={policy.includeBodies}
                                disabled={
                                    !policy.enabled
                                    || !overview.workspacePolicy.bodyCaptureAllowed
                                    || saving
                                }
                                onCheckedChange={(checked) => setPolicy((current) => ({
                                    ...current,
                                    includeBodies: checked,
                                }))}
                            />
                        </div>

                        <div className="grid gap-2 rounded-lg border border-border px-3 py-2.5">
                            <div className="grid gap-1">
                                <Label htmlFor={`capture-${overview.provider}-mode`}>
                                    {t('admissionMode.label')}
                                </Label>
                                <p className="text-xs text-muted-foreground">
                                    {t(`admissionMode.hint.${policy.admissionMode}`)}
                                </p>
                            </div>
                            <Select
                                value={policy.admissionMode}
                                disabled={!policy.enabled || saving}
                                onValueChange={(value) => {
                                    if (!isAdmissionMode(value)) return;
                                    setPolicy((current) => ({
                                        ...current,
                                        admissionMode: value,
                                        reviewBeforeCapture: value !== 'automatic',
                                    }));
                                }}
                            >
                                <SelectTrigger
                                    id={`capture-${overview.provider}-mode`}
                                    className="w-full"
                                >
                                    <SelectValue />
                                </SelectTrigger>
                                <SelectContent>
                                    <SelectItem value="manual">
                                        {t('admissionMode.manual')}
                                    </SelectItem>
                                    <SelectItem value="review">
                                        {t('admissionMode.review')}
                                    </SelectItem>
                                    <SelectItem
                                        value="automatic"
                                        disabled={overview.workspacePolicy.reviewRequired}
                                    >
                                        {t('admissionMode.automatic')}
                                    </SelectItem>
                                </SelectContent>
                            </Select>
                        </div>
                    </div>

                    {overview.effectivePolicy.restrictionCodes.length ? (
                        <Alert>
                            <ShieldCheckIcon aria-hidden />
                            <AlertTitle>{t('effectiveRestriction')}</AlertTitle>
                            <AlertDescription>
                                {t('effectiveRestrictionDescription')}
                            </AlertDescription>
                        </Alert>
                    ) : null}

                    <CaptureDisclosures disclosures={overview.disclosures} />
                </div>

                <ResponsiveDialogFooter className="border-t border-border px-4 py-4 sm:border-0 sm:px-0 sm:py-0">
                    <ResponsiveDialogClose asChild>
                        <Button type="button" variant="outline" disabled={saving}>
                            {t('cancel')}
                        </Button>
                    </ResponsiveDialogClose>
                    <Button
                        type="button"
                        disabled={!validation.valid || saving}
                        onClick={save}
                    >
                        {saving ? t('saving') : t('save')}
                    </Button>
                </ResponsiveDialogFooter>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}
