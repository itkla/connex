'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import {
    ArrowDownTrayIcon,
    ArrowPathIcon,
    CheckCircleIcon,
    CheckIcon,
    ClipboardDocumentIcon,
    ExclamationTriangleIcon,
    InformationCircleIcon,
} from '@heroicons/react/24/outline';
import { useTranslations } from 'next-intl';

import { usePasskeyStepUpErrorHandler } from '@/app/hooks/usePasskeyStepUpError';
import {
    ApiError,
    beginManagedPairing,
    cancelManagedPairing,
    getManagedPairingStatus,
} from '@/app/lib/api';
import {
    MANAGED_HELPER_PATH,
    MANAGED_OAUTH_DOC_URL,
    MANAGED_PAIRING_POLL_INTERVAL_MS,
    formatManagedPairingRemaining,
    managedPairingActive,
    managedPairingFailure,
    managedPairingRemainingMs,
    type ManagedPairingActiveStatus,
    type ManagedPairingFailure,
} from '@/app/lib/managedConnect';
import { toastError } from '@/app/lib/toast';
import type {
    ConnectedAccountProvider,
    ManagedPairingSession,
    ManagedPairingStatus,
} from '@/app/lib/types';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import {
    ResponsiveDialog,
    ResponsiveDialogContent,
    ResponsiveDialogDescription,
    ResponsiveDialogFooter,
    ResponsiveDialogHeader,
    ResponsiveDialogTitle,
} from '@/components/ui/responsive-dialog';

type ManagedConnectPhase =
    | { kind: 'intro' }
    | { kind: 'starting' }
    | { kind: 'pairing'; session: ManagedPairingSession; status: ManagedPairingActiveStatus }
    | { kind: 'completed' }
    | { kind: 'failed'; failure: ManagedPairingFailure };

type CopyField = 'code' | 'command';

const COPY_FEEDBACK_MS = 1500;

function terminalPhase(status: ManagedPairingStatus): ManagedConnectPhase {
    if (status.status === 'completed') return { kind: 'completed' };
    if (status.status === 'failed') {
        return { kind: 'failed', failure: managedPairingFailure(status.errorCode) };
    }
    return { kind: 'failed', failure: 'expired' };
}

/**
 * Guides one user through the Connex-managed connect flow for a provider.
 *
 * The browser only ever holds a pairing code: the authorization itself runs in a helper process on
 * the user's own machine, which claims the code, binds a loopback port, and hands the provider's
 * response back to this installation. This dialog therefore issues the pairing handle, shows the
 * code and helper command, polls the instance for the helper's progress while the window is open,
 * and cancels the pairing when the user closes it so a stale code cannot be claimed later.
 *
 * @param provider the provider being connected
 * @param providerName the provider's display name
 * @param open whether the dialog is showing
 * @param onOpenChange closes the dialog; the caller unmounts it, which stops polling
 * @param onConnected notifies the caller that the connection list changed; must be referentially
 *   stable, because polling restarts whenever it changes
 */
export default function ManagedConnectDialog({
    provider,
    providerName,
    open,
    onOpenChange,
    onConnected,
}: {
    provider: ConnectedAccountProvider;
    providerName: string;
    open: boolean;
    onOpenChange: (open: boolean) => void;
    onConnected: () => void;
}) {
    const t = useTranslations('AccountManagedConnect');
    const handlePasskeyStepUpError = usePasskeyStepUpErrorHandler();
    const [phase, setPhase] = useState<ManagedConnectPhase>({ kind: 'intro' });
    const [copied, setCopied] = useState<CopyField | null>(null);
    const [now, setNow] = useState(() => Date.now());
    const [cancelFailed, setCancelFailed] = useState(false);
    const copyTimeout = useRef(0);

    const pairing = phase.kind === 'pairing' ? phase : null;
    const pairingExpiresAt = pairing?.session.expiresAt ?? null;
    const remainingMs = pairingExpiresAt
        ? managedPairingRemainingMs(pairingExpiresAt, now)
        : 0;

    useEffect(() => () => window.clearTimeout(copyTimeout.current), []);

    useEffect(() => {
        if (pairingExpiresAt === null) return;
        const ticker = window.setInterval(() => {
            setNow(Date.now());
        }, 1000);
        return () => window.clearInterval(ticker);
    }, [pairingExpiresAt]);

    useEffect(() => {
        if (!pairing) return;
        const controller = new AbortController();
        let cancelled = false;
        let timer = 0;

        const schedule = () => {
            if (cancelled) return;
            timer = window.setTimeout(() => {
                void poll();
            }, MANAGED_PAIRING_POLL_INTERVAL_MS);
        };

        const poll = async () => {
            try {
                const next = await getManagedPairingStatus(provider, {
                    signal: controller.signal,
                });
                if (cancelled) return;
                const status = next.status;
                if (!managedPairingActive(status)) {
                    setPhase(terminalPhase(next));
                    if (status === 'completed') onConnected();
                    return;
                }
                setPhase((current) =>
                    current.kind === 'pairing' && current.status !== status
                        ? { ...current, status }
                        : current);
                schedule();
            } catch {
                schedule();
            }
        };

        schedule();
        return () => {
            cancelled = true;
            controller.abort();
            window.clearTimeout(timer);
        };
    }, [onConnected, pairing, provider]);

    const start = useCallback(async () => {
        setPhase({ kind: 'starting' });
        try {
            const session = await beginManagedPairing(provider);
            setNow(Date.now());
            setPhase({ kind: 'pairing', session, status: 'pending' });
        } catch (error) {
            if (handlePasskeyStepUpError(error)) {
                setPhase({ kind: 'intro' });
                return;
            }
            const code = error instanceof ApiError ? error.code ?? error.message : null;
            setPhase({ kind: 'failed', failure: managedPairingFailure(code) });
        }
    }, [handlePasskeyStepUpError, provider]);

    const copy = async (value: string, field: CopyField) => {
        try {
            await navigator.clipboard.writeText(value);
            setCopied(field);
            window.clearTimeout(copyTimeout.current);
            copyTimeout.current = window.setTimeout(
                () => setCopied((current) => (current === field ? null : current)),
                COPY_FEEDBACK_MS,
            );
        } catch {
            toastError(t('copyFailed'));
        }
    };

    const close = (nextOpen: boolean) => {
        if (nextOpen) return;
        if (phase.kind !== 'pairing') {
            onOpenChange(false);
            return;
        }
        setCancelFailed(false);
        void cancelManagedPairing(provider)
            .then(() => onOpenChange(false))
            .catch(() => setCancelFailed(true));
    };

    const busy = phase.kind === 'starting';

    return (
        <ResponsiveDialog open={open} onOpenChange={close}>
            <ResponsiveDialogContent className="sm:max-w-xl" showCloseButton={!busy}>
                <ResponsiveDialogHeader className="px-4 pt-4 sm:px-0 sm:pt-0">
                    <ResponsiveDialogTitle>
                        {t('title', { provider: providerName })}
                    </ResponsiveDialogTitle>
                    <ResponsiveDialogDescription>
                        {t('description', { provider: providerName })}
                    </ResponsiveDialogDescription>
                </ResponsiveDialogHeader>

                <div className="grid gap-4 px-4 py-4 sm:px-0">
                    {phase.kind === 'intro' || phase.kind === 'starting' ? (
                        <section className="grid gap-2">
                            <h3 className="text-sm font-medium text-foreground">{t('whatHappens')}</h3>
                            <ul className="grid gap-2 text-sm text-muted-foreground">
                                {([
                                    'scopeMail',
                                    'scopeCalendar',
                                    'scopeIdentity',
                                    'localStorage',
                                    'noVendorData',
                                    'personalConnection',
                                    'codeExpiry',
                                ] as const).map((key) => (
                                    <li key={key} className="flex gap-2">
                                        <CheckIcon
                                            className="mt-0.5 size-4 shrink-0 text-muted-foreground"
                                            aria-hidden
                                        />
                                        <span>{t(key, { provider: providerName })}</span>
                                    </li>
                                ))}
                            </ul>
                        </section>
                    ) : null}

                    {pairing ? (
                        <div className="grid gap-4">
                            <section className="grid gap-2">
                                <h3 className="text-sm font-medium text-foreground">
                                    {t('stepCodeTitle')}
                                </h3>
                                <div className="flex flex-wrap items-center gap-2 rounded-xl border border-border bg-muted/40 px-3 py-2">
                                    <code className="min-w-0 flex-1 font-mono text-lg tracking-widest break-all text-foreground select-all">
                                        {pairing.session.pairingCode}
                                    </code>
                                    <Button
                                        type="button"
                                        variant="outline"
                                        size="sm"
                                        onClick={() => copy(pairing.session.pairingCode, 'code')}
                                    >
                                        {copied === 'code' ? (
                                            <CheckIcon data-icon="inline-start" />
                                        ) : (
                                            <ClipboardDocumentIcon data-icon="inline-start" />
                                        )}
                                        {copied === 'code' ? t('copied') : t('copyCode')}
                                    </Button>
                                </div>
                                <p className="text-xs text-muted-foreground">
                                    {t('stepCodeBody', {
                                        time: formatManagedPairingRemaining(remainingMs),
                                    })}
                                </p>
                            </section>

                            <section className="grid gap-2">
                                <h3 className="text-sm font-medium text-foreground">
                                    {t('stepHelperTitle')}
                                </h3>
                                <p className="text-xs text-muted-foreground">
                                    {t('stepHelperBody', { provider: providerName })}
                                </p>
                                <div className="flex flex-wrap items-center gap-2 rounded-xl border border-border bg-muted/40 px-3 py-2">
                                    <code className="min-w-0 flex-1 font-mono text-xs break-all text-foreground select-all">
                                        {pairing.session.helperCommand}
                                    </code>
                                    <Button
                                        type="button"
                                        variant="outline"
                                        size="sm"
                                        onClick={() => copy(pairing.session.helperCommand, 'command')}
                                    >
                                        {copied === 'command' ? (
                                            <CheckIcon data-icon="inline-start" />
                                        ) : (
                                            <ClipboardDocumentIcon data-icon="inline-start" />
                                        )}
                                        {copied === 'command' ? t('copied') : t('copyCommand')}
                                    </Button>
                                </div>
                                <div>
                                    <Button asChild type="button" variant="outline" size="sm">
                                        <a href={MANAGED_HELPER_PATH} download="connex-connect.mjs">
                                            <ArrowDownTrayIcon data-icon="inline-start" />
                                            {t('downloadHelper')}
                                        </a>
                                    </Button>
                                </div>
                            </section>

                            <section className="grid gap-2">
                                <h3 className="text-sm font-medium text-foreground">
                                    {t('stepStatusTitle')}
                                </h3>
                                <p
                                    className="flex items-center gap-2 text-sm text-muted-foreground"
                                    role="status"
                                >
                                    <ArrowPathIcon
                                        className="size-4 shrink-0 animate-spin motion-reduce:animate-none"
                                        aria-hidden
                                    />
                                    {t(`status.${pairing.status}`)}
                                </p>
                            </section>
                        </div>
                    ) : null}

                    {phase.kind === 'completed' ? (
                        <div className="flex gap-3 rounded-xl border border-border bg-muted/40 px-4 py-4">
                            <CheckCircleIcon className="size-5 shrink-0 text-brand" aria-hidden />
                            <div className="grid gap-1">
                                <p className="text-sm font-medium text-foreground">
                                    {t('completedTitle')}
                                </p>
                                <p className="text-sm text-muted-foreground">
                                    {t('completedBody', { provider: providerName })}
                                </p>
                            </div>
                        </div>
                    ) : null}

                    {phase.kind === 'failed' ? (
                        phase.failure === 'managed_identity_unavailable' ? (
                            <Alert>
                                <InformationCircleIcon aria-hidden />
                                <AlertTitle>{t('unavailableTitle')}</AlertTitle>
                                <AlertDescription>
                                    <p>{t('unavailableBody', { provider: providerName })}</p>
                                    <a
                                        className="text-primary underline underline-offset-4"
                                        href={MANAGED_OAUTH_DOC_URL}
                                        target="_blank"
                                        rel="noreferrer"
                                    >
                                        {t('docLink')}
                                    </a>
                                </AlertDescription>
                            </Alert>
                        ) : (
                            <Alert variant="destructive">
                                <ExclamationTriangleIcon aria-hidden />
                                <AlertTitle>{t('failedTitle')}</AlertTitle>
                                <AlertDescription>
                                    {t(`error.${phase.failure}`, { provider: providerName })}
                                </AlertDescription>
                            </Alert>
                        )
                    ) : null}
                </div>

                <ResponsiveDialogFooter className="border-t border-border px-4 py-4 sm:border-0 sm:px-0 sm:py-0">
                    {cancelFailed ? (
                        <p className="text-sm text-destructive" role="alert">
                            {t('cancelFailed')}
                        </p>
                    ) : null}
                    {phase.kind === 'completed' ? (
                        <Button type="button" onClick={() => close(false)}>
                            {t('done')}
                        </Button>
                    ) : (
                        <>
                            <Button
                                type="button"
                                variant="outline"
                                disabled={busy}
                                onClick={() => close(false)}
                            >
                                {phase.kind === 'failed' ? t('close') : t('cancel')}
                            </Button>
                            {phase.kind === 'failed' ? (
                                phase.failure === 'managed_identity_unavailable' ? null : (
                                    <Button type="button" onClick={() => void start()}>
                                        {t('retry')}
                                    </Button>
                                )
                            ) : pairing ? null : (
                                <Button type="button" disabled={busy} onClick={() => void start()}>
                                    {busy ? t('starting') : t('start')}
                                </Button>
                            )}
                        </>
                    )}
                </ResponsiveDialogFooter>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}
