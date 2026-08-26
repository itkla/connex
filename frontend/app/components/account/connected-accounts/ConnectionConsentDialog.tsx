'use client';

import { ArrowTopRightOnSquareIcon } from '@heroicons/react/24/outline';
import { useTranslations } from 'next-intl';

import type { ConnectedAccountProvider } from '@/app/lib/types';
import { Button } from '@/components/ui/button';
import {
    ResponsiveDialog,
    ResponsiveDialogClose,
    ResponsiveDialogContent,
    ResponsiveDialogDescription,
    ResponsiveDialogFooter,
    ResponsiveDialogHeader,
    ResponsiveDialogTitle,
} from '@/components/ui/responsive-dialog';

/**
 * The one expectation step between a Connect action and provider authorization.
 *
 * It exists to set three expectations the provider's own consent screen does not: that the account
 * password never reaches Connex, that connecting alone reads nothing, and that the connection can
 * be undone. It is deliberately the last step: its primary button starts authorization rather than
 * advancing to another screen, which is what keeps any Connect action two clicks from the
 * provider's authorization page.
 *
 * @param captureEnabled whether this instance captures mail and calendar for the provider, which
 * decides whether the "nothing is read yet" expectation is true and therefore shown
 */
export default function ConnectionConsentDialog({
    provider,
    providerName,
    captureEnabled,
    open,
    busy,
    onOpenChange,
    onConfirm,
}: {
    provider: ConnectedAccountProvider;
    providerName: string;
    captureEnabled: boolean;
    open: boolean;
    busy: boolean;
    onOpenChange: (open: boolean) => void;
    onConfirm: () => void;
}) {
    const t = useTranslations('AccountConnectionConsent');
    const tConnections = useTranslations('AccountConnections');

    return (
        <ResponsiveDialog open={open} onOpenChange={onOpenChange}>
            <ResponsiveDialogContent className="sm:max-w-md" showCloseButton={!busy}>
                <ResponsiveDialogHeader className="px-4 pt-4 sm:px-0 sm:pt-0">
                    <ResponsiveDialogTitle>
                        {t('title', { provider: providerName })}
                    </ResponsiveDialogTitle>
                    <ResponsiveDialogDescription>
                        {tConnections(`value_${provider}`)}
                    </ResponsiveDialogDescription>
                </ResponsiveDialogHeader>

                <ul className="grid gap-3 px-4 py-4 text-sm text-muted-foreground sm:px-0">
                    <li>{t('expectationAuthorization', { provider: providerName })}</li>
                    {captureEnabled ? <li>{t('expectationNoCaptureYet')}</li> : null}
                    <li>{t('expectationReversible')}</li>
                </ul>

                <ResponsiveDialogFooter className="border-t border-border px-4 py-4 sm:border-0 sm:px-0 sm:py-0">
                    <ResponsiveDialogClose asChild>
                        <Button type="button" variant="outline" disabled={busy}>
                            {t('cancel')}
                        </Button>
                    </ResponsiveDialogClose>
                    <Button type="button" disabled={busy} onClick={onConfirm}>
                        <ArrowTopRightOnSquareIcon data-icon="inline-start" />
                        {busy
                            ? t('continuing', { provider: providerName })
                            : t('continue', { provider: providerName })}
                    </Button>
                </ResponsiveDialogFooter>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}
