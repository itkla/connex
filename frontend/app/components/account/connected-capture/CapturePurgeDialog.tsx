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
 * Confirms active-workspace erasure, or the provider disconnect that erases before it revokes.
 *
 * Both confirmations state what survives as plainly as what does not, and both require the reader
 * to acknowledge the erasure first. Disconnect is unconditionally destructive because the server
 * makes it so: it pages every workspace and purges this provider's captured data before it deletes
 * the credential, and it does not consult the instance's capture switch on the way. Gating the
 * warning on that switch would let an instance that turned capture off after data was captured
 * promise a reader that nothing is deleted while everything is, so the warning is not gated at all.
 *
 * What survives is stated alongside: contacts, companies, deals, notes, and tasks are untouched.
 * Erasing captured data stays a separately named action reached from its own disclosure, so it can
 * never be performed by a reader who only meant to stop syncing.
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

    const confirm = async () => {
        if (!acknowledged) return;
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
                    <p className="text-sm text-muted-foreground">
                        {t(mode === 'purge' ? 'purgeRetained' : 'disconnectRetained')}
                    </p>
                    <Label className="items-start leading-relaxed">
                        <Checkbox
                            checked={acknowledged}
                            disabled={busy}
                            onCheckedChange={(checked) => setAcknowledged(checked === true)}
                            aria-label={t('acknowledge')}
                        />
                        <span>{t('acknowledge')}</span>
                    </Label>
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
                        disabled={busy || !acknowledged}
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
