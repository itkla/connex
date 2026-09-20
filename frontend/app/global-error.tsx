'use client';

import { useEffect } from 'react';
import { ArrowPathIcon, ExclamationTriangleIcon } from '@heroicons/react/24/outline';

import { Button } from '@/components/ui/button';
import type { SegmentErrorProps } from '@/app/components/ErrorState';
import { reportBoundaryErrorWithConsole } from '@/app/lib/clientErrorReporter';
import './globals.css';

/**
 * Root error boundary that replaces the entire root layout when it fails.
 * Runs without the i18n and theme providers, so it renders its own html and
 * body with static bilingual copy and design-system tokens from globals.css.
 *
 * It recovers with `reset` rather than Next's router-refreshing `retry`: the
 * one-time-link entry routes sit under this boundary, and a refresh there
 * re-publishes whatever canonical URL the router holds.
 */
export default function GlobalError({ error, reset }: SegmentErrorProps) {
    useEffect(() => {
        reportBoundaryErrorWithConsole(error);
    }, [error]);

    return (
        <html lang="en" className="h-full antialiased">
            <body className="flex min-h-full flex-col bg-background text-foreground">
                <main className="flex flex-1 items-center justify-center px-6 py-16">
                    <div className="flex w-full max-w-md flex-col items-center text-center">
                        <div className="flex size-14 items-center justify-center rounded-2xl bg-destructive/10 text-destructive">
                            <ExclamationTriangleIcon className="size-7" />
                        </div>
                        <h1 className="mt-5 text-lg font-semibold">Something went wrong</h1>
                        <p className="mt-1.5 max-w-sm text-sm text-muted-foreground">
                            We couldn&apos;t load Connex. The issue is usually temporary.
                        </p>
                        <p className="max-w-sm text-sm text-muted-foreground">
                            問題が発生しました。一時的な問題の可能性があります。
                        </p>
                        <Button className="mt-6" onClick={() => reset()}>
                            <ArrowPathIcon data-icon="inline-start" />
                            Try again / 再試行
                        </Button>
                        {error.digest ? (
                            <p className="mt-6 font-mono text-xs text-muted-foreground select-all">
                                Reference / 参照コード: {error.digest}
                            </p>
                        ) : null}
                    </div>
                </main>
            </body>
        </html>
    );
}
