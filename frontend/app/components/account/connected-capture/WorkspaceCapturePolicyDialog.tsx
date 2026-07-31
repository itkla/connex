'use client';

import { useMemo, useState } from 'react';
import { ShieldCheckIcon } from '@heroicons/react/24/outline';
import { useTranslations } from 'next-intl';

import { CAPTURE_STREAMS } from '@/app/lib/connectedCapture';
import type {
    CaptureStream,
    ProviderCaptureOverview,
    WorkspaceCapturePolicy,
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

const STREAM_KEYS: Record<CaptureStream, 'streams.calendar' | 'streams.mailInbox' | 'streams.mailSent'> = {
    calendar: 'streams.calendar',
    mail_inbox: 'streams.mailInbox',
    mail_sent: 'streams.mailSent',
};

function enabledStream(policy: WorkspaceCapturePolicy, stream: CaptureStream): boolean {
    if (stream === 'calendar') return policy.calendar;
    if (stream === 'mail_inbox') return policy.mailInbox;
    return policy.mailSent;
}

/**
 * Lets members with WORKSPACE_SETTINGS narrow the capture envelope for the active workspace.
 */
export default function WorkspaceCapturePolicyDialog({
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
    onSave: (policy: WorkspaceCapturePolicy) => Promise<boolean>;
}) {
    const t = useTranslations('AccountWorkspaceCapturePolicy');
    const [policy, setPolicy] = useState<WorkspaceCapturePolicy>({
        ...overview.workspacePolicy,
        excludePrivateEvents: true,
    });
    const valid = useMemo(
        () => Number.isInteger(policy.maxBackfillDays)
            && policy.maxBackfillDays >= 1
            && policy.maxBackfillDays <= 180
            && (!policy.allowed || policy.calendar || policy.mailInbox || policy.mailSent),
        [policy],
    );

    const setStream = (stream: CaptureStream, enabled: boolean) => {
        setPolicy((current) => ({
            ...current,
            calendar: stream === 'calendar' ? enabled : current.calendar,
            mailInbox: stream === 'mail_inbox' ? enabled : current.mailInbox,
            mailSent: stream === 'mail_sent' ? enabled : current.mailSent,
        }));
    };

    const save = async () => {
        if (!valid) return;
        if (await onSave(policy)) onOpenChange(false);
    };

    return (
        <ResponsiveDialog open={open} onOpenChange={onOpenChange}>
            <ResponsiveDialogContent className="sm:max-w-xl" showCloseButton={!saving}>
                <ResponsiveDialogHeader className="px-4 pt-4 sm:px-0 sm:pt-0">
                    <ResponsiveDialogTitle>{t('title')}</ResponsiveDialogTitle>
                    <ResponsiveDialogDescription>{t('description')}</ResponsiveDialogDescription>
                </ResponsiveDialogHeader>

                <div className="grid gap-5 px-4 py-4 sm:px-0">
                    <Alert>
                        <ShieldCheckIcon aria-hidden />
                        <AlertTitle>{t('narrowOnlyTitle')}</AlertTitle>
                        <AlertDescription>{t('narrowOnlyDescription')}</AlertDescription>
                    </Alert>

                    <div className="flex items-start justify-between gap-4 rounded-lg border border-border px-3 py-2.5">
                        <div className="grid gap-1">
                            <Label htmlFor={`workspace-capture-${overview.provider}-enabled`}>
                                {t('captureAllowed')}
                            </Label>
                            <p className="text-xs text-muted-foreground">{t('captureAllowedHint')}</p>
                        </div>
                        <Switch
                            id={`workspace-capture-${overview.provider}-enabled`}
                            checked={policy.allowed}
                            disabled={saving}
                            onCheckedChange={(checked) => setPolicy((current) => ({
                                ...current,
                                allowed: checked,
                            }))}
                        />
                    </div>

                    <fieldset className="grid gap-2" disabled={!policy.allowed || saving}>
                        <legend className="text-sm font-medium text-foreground">{t('streamsLabel')}</legend>
                        <div className="grid gap-2">
                            {CAPTURE_STREAMS.map((stream) => {
                                const inputId = `workspace-capture-${overview.provider}-${stream}`;
                                return (
                                    <div
                                        key={stream}
                                        className="flex items-center justify-between gap-4 rounded-lg border border-border px-3 py-2.5"
                                    >
                                        <Label htmlFor={inputId}>{t(STREAM_KEYS[stream])}</Label>
                                        <Switch
                                            id={inputId}
                                            checked={enabledStream(policy, stream)}
                                            onCheckedChange={(checked) => setStream(stream, checked)}
                                        />
                                    </div>
                                );
                            })}
                        </div>
                    </fieldset>

                    <div className="grid gap-2">
                        <Label htmlFor={`workspace-capture-${overview.provider}-backfill`}>
                            {t('maxBackfill')}
                        </Label>
                        <Input
                            id={`workspace-capture-${overview.provider}-backfill`}
                            type="number"
                            inputMode="numeric"
                            min={1}
                            max={180}
                            value={policy.maxBackfillDays}
                            disabled={!policy.allowed || saving}
                            aria-invalid={!valid}
                            onChange={(event) => setPolicy((current) => ({
                                ...current,
                                maxBackfillDays: Number(event.target.value),
                            }))}
                        />
                        <p className="text-xs text-muted-foreground">{t('maxBackfillHint')}</p>
                    </div>

                    <div className="grid gap-2">
                        <div className="flex items-start justify-between gap-4 rounded-lg border border-border px-3 py-2.5">
                            <div className="grid gap-1">
                                <Label htmlFor={`workspace-capture-${overview.provider}-bodies`}>
                                    {t('includeBodiesAllowed')}
                                </Label>
                                <p className="text-xs text-muted-foreground">
                                    {t('includeBodiesAllowedHint')}
                                </p>
                            </div>
                            <Switch
                                id={`workspace-capture-${overview.provider}-bodies`}
                                checked={policy.bodyCaptureAllowed}
                                disabled={!policy.allowed || saving}
                                onCheckedChange={(checked) => setPolicy((current) => ({
                                    ...current,
                                    bodyCaptureAllowed: checked,
                                }))}
                            />
                        </div>

                        <div className="flex items-start justify-between gap-4 rounded-lg border border-border px-3 py-2.5">
                            <div className="grid gap-1">
                                <Label htmlFor={`workspace-capture-${overview.provider}-review`}>
                                    {t('reviewRequired')}
                                </Label>
                                <p className="text-xs text-muted-foreground">{t('reviewRequiredHint')}</p>
                            </div>
                            <Switch
                                id={`workspace-capture-${overview.provider}-review`}
                                checked={policy.reviewRequired}
                                disabled={!policy.allowed || saving}
                                onCheckedChange={(checked) => setPolicy((current) => ({
                                    ...current,
                                    reviewRequired: checked,
                                }))}
                            />
                        </div>

                        <div className="flex items-start justify-between gap-4 rounded-lg border border-border px-3 py-2.5">
                            <div className="grid gap-1">
                                <Label htmlFor={`workspace-capture-${overview.provider}-private-events`}>
                                    {t('excludePrivateEvents')}
                                </Label>
                                <p className="text-xs text-muted-foreground">
                                    {t('excludePrivateEventsHint')}
                                </p>
                            </div>
                            <Switch
                                id={`workspace-capture-${overview.provider}-private-events`}
                                checked
                                disabled
                            />
                        </div>

                        <div className="flex items-start justify-between gap-4 rounded-lg border border-border px-3 py-2.5">
                            <div className="grid gap-1">
                                <Label htmlFor={`workspace-capture-${overview.provider}-internal-only`}>
                                    {t('excludeInternalOnly')}
                                </Label>
                                <p className="text-xs text-muted-foreground">
                                    {t('excludeInternalOnlyHint')}
                                </p>
                            </div>
                            <Switch
                                id={`workspace-capture-${overview.provider}-internal-only`}
                                checked={policy.excludeInternalOnly}
                                disabled={!policy.allowed || saving}
                                onCheckedChange={(checked) => setPolicy((current) => ({
                                    ...current,
                                    excludeInternalOnly: checked,
                                }))}
                            />
                        </div>
                    </div>

                    <div className="grid gap-2">
                        <Label htmlFor={`workspace-capture-${overview.provider}-domains`}>
                            {t('excludedDomains')}
                        </Label>
                        <Input
                            id={`workspace-capture-${overview.provider}-domains`}
                            value={policy.excludedDomains.join(', ')}
                            disabled={!policy.allowed || saving}
                            placeholder={t('excludedDomainsPlaceholder')}
                            onChange={(event) => setPolicy((current) => ({
                                ...current,
                                excludedDomains: event.target.value
                                    .split(',')
                                    .map((value) => value.trim())
                                    .filter(Boolean),
                            }))}
                        />
                        <p className="text-xs text-muted-foreground">
                            {t('excludedDomainsHint')}
                        </p>
                    </div>

                    {!valid ? (
                        <p className="text-xs text-destructive" role="alert">
                            {t('validation')}
                        </p>
                    ) : null}
                </div>

                <ResponsiveDialogFooter className="border-t border-border px-4 py-4 sm:border-0 sm:px-0 sm:py-0">
                    <ResponsiveDialogClose asChild>
                        <Button type="button" variant="outline" disabled={saving}>
                            {t('cancel')}
                        </Button>
                    </ResponsiveDialogClose>
                    <Button type="button" disabled={!valid || saving} onClick={save}>
                        {saving ? t('saving') : t('save')}
                    </Button>
                </ResponsiveDialogFooter>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}
