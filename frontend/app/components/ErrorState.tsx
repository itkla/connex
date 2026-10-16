'use client';

import { useEffect, useTransition } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { ArrowPathIcon, ExclamationTriangleIcon } from '@heroicons/react/24/outline';

import Rise from '@/app/components/motion/Rise';
import { Button } from '@/components/ui/button';
import { reportBoundaryErrorWithConsole } from '@/app/lib/clientErrorReporter';

/**
 * Props Next.js passes to a route segment `error.tsx` or `global-error.tsx` boundary component.
 * `retry` (stable since Next 16.3, which renamed it from `unstable_retry`) refreshes the router and
 * resets the boundary in one transition, so it re-fetches the segment's server data and re-renders
 * it; `reset` only re-renders the boundary's children from the client state they already hold.
 *
 * Every boundary under `app/(app)` recovers with `retry`. The root `app/error.tsx` and
 * `app/global-error.tsx` recover with `reset`: they are the only boundaries that sit above a
 * one-time-link entry route, and a refresh re-publishes whatever canonical URL the router holds,
 * which in the residual cases `test/unit/oneTimeLinkEntryGuard.test.ts` documents can still be the
 * pre-strip URL carrying the `#token=` bearer (#1781). That guard fails CI if a boundary above an
 * entry route reads `retry`.
 *
 * Next type-checks pages, layouts and route handlers but not error boundaries, so nothing except
 * this declaration ties the shape to Next. `test/unit/errorBoundaryRetry.test.tsx` mounts Next's own
 * `ErrorBoundaryHandler` and fails if the props it passes stop matching these members.
 */
export type SegmentErrorProps = {
    error: Error & { digest?: string };
    reset: () => void;
    retry: () => void;
};

/**
 * Shared error-boundary fallback rendered by segment `error.tsx` files. Shows a
 * calm recovery state with a retry affordance, an optional go-back action, and
 * the error digest as a support reference. Must stay a Client Component.
 * @param error the error forwarded by the boundary, including the server digest
 * @param retry callback the "Try again" control runs: Next's `retry` for an app-shell boundary,
 * which re-fetches the segment, or `reset` for a boundary that must not refresh the router
 * @param showBack whether to offer a go-back action alongside retry
 */
export default function ErrorState({
    error,
    retry,
    showBack = true,
}: {
    error: Error & { digest?: string };
    retry: () => void;
    showBack?: boolean;
}) {
    const t = useTranslations('ErrorState');
    const router = useRouter();
    const [isRetrying, startTransition] = useTransition();

    useEffect(() => {
        reportBoundaryErrorWithConsole(error);
    }, [error]);

    const goBack = () => {
        if (window.history.length > 1) {
            router.back();
        } else {
            router.push('/dashboard');
        }
    };

    return (
        <div className="flex min-h-[60vh] items-center justify-center px-6 py-16">
            <Rise className="flex w-full max-w-md flex-col items-center text-center">
                <div className="flex size-14 items-center justify-center rounded-2xl bg-destructive/10 text-destructive">
                    <ExclamationTriangleIcon className="size-7" />
                </div>
                <h2 className="mt-5 text-lg font-semibold text-foreground">{t('title')}</h2>
                <p className="mt-1.5 max-w-sm text-sm text-muted-foreground">{t('body')}</p>
                <div className="mt-6 flex items-center gap-2">
                    <Button onClick={() => startTransition(() => retry())} disabled={isRetrying}>
                        <ArrowPathIcon
                            data-icon="inline-start"
                            className={isRetrying ? 'animate-spin motion-reduce:animate-none' : undefined}
                        />
                        {isRetrying ? t('retrying') : t('retry')}
                    </Button>
                    {showBack ? (
                        <Button variant="ghost" onClick={goBack}>
                            {t('back')}
                        </Button>
                    ) : null}
                </div>
                {error.digest ? (
                    <p className="mt-6 font-mono text-xs text-muted-foreground select-all">
                        {t('reference', { digest: error.digest })}
                    </p>
                ) : null}
            </Rise>
        </div>
    );
}
