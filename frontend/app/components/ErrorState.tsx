'use client';

import { useEffect, useTransition } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { ArrowPathIcon, ExclamationTriangleIcon } from '@heroicons/react/24/outline';

import Rise from '@/app/components/motion/Rise';
import { Button } from '@/components/ui/button';

/**
 * Props Next.js passes to a route segment `error.tsx` boundary component.
 * `unstable_retry` (Next 16.2+) re-fetches and re-renders the failed segment,
 * while `reset` only re-renders the boundary's children from client state.
 */
export type SegmentErrorProps = {
    error: Error & { digest?: string };
    reset: () => void;
    unstable_retry?: () => void;
};

/**
 * Shared error-boundary fallback rendered by segment `error.tsx` files. Shows a
 * calm recovery state with a retry affordance, an optional go-back action, and
 * the error digest as a support reference. Must stay a Client Component.
 * @param error the error forwarded by the boundary, including the server digest
 * @param retry callback that attempts to recover the segment, preferably `unstable_retry`
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
        console.error(error);
    }, [error]);

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
                        <Button variant="ghost" onClick={() => router.back()}>
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
