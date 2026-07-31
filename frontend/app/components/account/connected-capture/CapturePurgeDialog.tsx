'use client';

import { useState } from 'react';
import { ExclamationTriangleIcon } from '@heroicons/react/24/outline';
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

export type CaptureLifecycleMode = 'purge' | 'disconnect';

/**
 * Confirms active-workspace purge or purge-aware provider disconnect with explicit data scope.
 */
export default function CapturePurgeDialog({
    mode,
    providerName,
    captureEnabled,
    open,
    busy,
    onOpenChange,
    onConfirm,
}: {
    mode: CaptureLifecycleMode;
    providerName: string;
    captureEnabled: boolean;
    open: boolean;
    busy: boolean;
    onOpenChange: (open: boolean) => void;
    onConfirm: () => Promise<boolean>;
}) {
    const t = useTranslations('AccountCaptureLifecycle');
    const [acknowledged, setAcknowledged] = useState(false);
    const destructiveCaptureAction = captureEnabled || mode === 'purge';

    const confirm = async () => {
        if (destructiveCaptureAction && !acknowledged) return;
        if (await onConfirm()) onOpenChange(false);
    };

    return (
        <ResponsiveDialog open={open} onOpenChange={onOpenChange}>
            <ResponsiveDialogContent className="sm:max-w-lg" showCloseButton={!busy}>
                <ResponsiveDialogHeader className="px-4 pt-4 sm:px-0 sm:pt-0">
                    <ResponsiveDialogTitle>
                        {t(mode === 'purge' ? 'purgeTitle' : 'disconnectTitle', {
                            provider: providerName,
                        })}
                    </ResponsiveDialogTitle>
                    <ResponsiveDialogDescription>
                        {t(mode === 'purge' ? 'purgeDescription' : 'disconnectDescription', {
                            provider: providerName,
                        })}
                    </ResponsiveDialogDescription>
                </ResponsiveDialogHeader>

                <div className="grid gap-4 px-4 py-4 sm:px-0">
                    {destructiveCaptureAction ? (
                        <>
                            <Alert variant="destructive">
                                <ExclamationTriangleIcon aria-hidden />
                                <AlertTitle>
                                    {t(mode === 'purge' ? 'activeWorkspaceTitle' : 'allWorkspacesTitle')}
                                </AlertTitle>
                                <AlertDescription>
                                    {t(mode === 'purge'
                                        ? 'activeWorkspaceDescription'
                                        : 'allWorkspacesDescription')}
                                </AlertDescription>
                            </Alert>
                            <Label className="items-start leading-relaxed">
                                <Checkbox
                                    checked={acknowledged}
                                    disabled={busy}
                                    onCheckedChange={(checked) => setAcknowledged(checked === true)}
                                    aria-label={t('acknowledge')}
                                />
                                <span>{t('acknowledge')}</span>
                            </Label>
                        </>
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
                        disabled={busy || (destructiveCaptureAction && !acknowledged)}
                        onClick={confirm}
                    >
                        {busy
                            ? t(mode === 'purge' ? 'purging' : 'disconnecting')
                            : t(mode === 'purge' ? 'confirmPurge' : 'confirmDisconnect')}
                    </Button>
                </ResponsiveDialogFooter>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}
