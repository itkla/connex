'use client';

import { useState } from 'react';
import {
    ExclamationTriangleIcon,
    InformationCircleIcon,
} from '@heroicons/react/24/outline';
import { useTranslations } from 'next-intl';

import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
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

export type CaptureLifecycleMode = 'purge' | 'disconnect' | 'reset';

/**
 * Separates ordinary credential-only disconnect from current-workspace erasure and the explicit
 * all-workspace reset. Only destructive modes require acknowledgement, and the reset names its
 * global scope separately so it cannot be mistaken for the current-workspace operation.
 */
export default function CapturePurgeDialog({
    mode,
    providerName,
    open,
    busy,
    onOpenChange,
    onConfirm,
}: {
    mode: CaptureLifecycleMode;
    providerName: string;
    open: boolean;
    busy: boolean;
    onOpenChange: (open: boolean) => void;
    onConfirm: () => Promise<boolean>;
}) {
    const t = useTranslations('AccountCaptureLifecycle');
    const [acknowledged, setAcknowledged] = useState(false);
    const destructive = mode !== 'disconnect';

    const confirm = async () => {
        if (destructive && !acknowledged) return;
        if (await onConfirm()) onOpenChange(false);
    };

    return (
        <ResponsiveDialog open={open} onOpenChange={onOpenChange}>
            <ResponsiveDialogContent className="sm:max-w-lg" showCloseButton={!busy}>
                <ResponsiveDialogHeader className="px-4 pt-4 sm:px-0 sm:pt-0">
                    <ResponsiveDialogTitle>
                        {t(mode === 'purge'
                            ? 'purgeTitle'
                            : mode === 'reset'
                                ? 'resetTitle'
                                : 'disconnectTitle', {
                            provider: providerName,
                        })}
                    </ResponsiveDialogTitle>
                    <ResponsiveDialogDescription>
                        {t(mode === 'purge'
                            ? 'purgeDescription'
                            : mode === 'reset'
                                ? 'resetDescription'
                                : 'disconnectDescription', {
                            provider: providerName,
                        })}
                    </ResponsiveDialogDescription>
                </ResponsiveDialogHeader>

                <div className="grid gap-4 px-4 py-4 sm:px-0">
                    <Alert variant={destructive ? 'destructive' : 'default'}>
                        {destructive ? (
                            <ExclamationTriangleIcon aria-hidden />
                        ) : (
                            <InformationCircleIcon aria-hidden />
                        )}
                        <AlertTitle>
                            {t(mode === 'purge'
                                ? 'activeWorkspaceTitle'
                                : mode === 'reset'
                                    ? 'allWorkspacesTitle'
                                    : 'disconnectRetentionTitle')}
                        </AlertTitle>
                        <AlertDescription>
                            {t(mode === 'purge'
                                ? 'activeWorkspaceDescription'
                                : mode === 'reset'
                                    ? 'allWorkspacesDescription'
                                    : 'disconnectRetentionDescription')}
                        </AlertDescription>
                    </Alert>
                    <p className="text-sm text-muted-foreground">
                        {t(mode === 'purge'
                            ? 'purgeRetained'
                            : mode === 'reset'
                                ? 'resetRetained'
                                : 'disconnectRetained')}
                    </p>
                    {destructive ? (
                        <Label className="items-start leading-relaxed">
                            <Checkbox
                                checked={acknowledged}
                                disabled={busy}
                                onCheckedChange={(checked) => setAcknowledged(checked === true)}
                                aria-label={t(mode === 'reset' ? 'resetAcknowledge' : 'acknowledge', {
                                    provider: providerName,
                                })}
                            />
                            <span>
                                {t(mode === 'reset' ? 'resetAcknowledge' : 'acknowledge', {
                                    provider: providerName,
                                })}
                            </span>
                        </Label>
                    ) : null}
                </div>

                <ResponsiveDialogFooter className="border-t border-border px-4 py-4 sm:border-0 sm:px-0 sm:py-0">
                    <ResponsiveDialogClose asChild>
                        <Button type="button" variant="outline" disabled={busy}>
                            {t('cancel')}
                        </Button>
                    </ResponsiveDialogClose>
                    <Button
                        type="button"
                        variant="destructive"
                        disabled={busy || (destructive && !acknowledged)}
                        onClick={confirm}
                    >
                        {busy
                            ? t(mode === 'purge'
                                ? 'purging'
                                : mode === 'reset'
                                    ? 'resetting'
                                    : 'disconnecting')
                            : t(mode === 'purge'
                                ? 'confirmPurge'
                                : mode === 'reset'
                                    ? 'confirmReset'
                                    : 'confirmDisconnect')}
                    </Button>
                </ResponsiveDialogFooter>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}
