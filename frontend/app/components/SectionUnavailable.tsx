'use client';

import { useTransition } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { ArrowPathIcon, ExclamationTriangleIcon } from '@heroicons/react/24/outline';

import { Button } from '@/components/ui/button';

/**
 * Bounded "couldn't load" state for a single section of an otherwise healthy page.
 *
 * Deliberately compact rather than borrowing {@link ErrorState}'s full-page tile: it
 * occupies one widget cell, so a large centred alarm would overstate a partial
 * failure. It carries no entrance animation of its own because the surrounding
 * widget frame already animates, and a page with several failed sections would
 * otherwise cascade.
 *
 * Renders honestly: this is shown *instead of* content, never alongside a
 * fabricated empty state.
 * @param title optional override for the localized default heading
 * @param body optional override for the localized default explanation
 * @param onReset extra callback invoked before the refresh, used by {@link SectionBoundary} to clear its caught error
 */
export default function SectionUnavailable({
    title,
    body,
    onReset,
}: {
    title?: string;
    body?: string;
    onReset?: () => void;
}) {
    const t = useTranslations('SectionUnavailable');
    const router = useRouter();
    const [isRetrying, startTransition] = useTransition();

    const retry = () => {
        startTransition(() => {
            onReset?.();
            router.refresh();
        });
    };

    return (
        <div
            role="status"
            className="flex h-full flex-col items-start justify-center gap-3 rounded-2xl border border-border bg-card px-6 py-8"
        >
            <div className="flex items-center gap-2 text-muted-foreground">
                <ExclamationTriangleIcon className="size-5 shrink-0" aria-hidden />
                <p className="text-sm font-semibold text-foreground">{title ?? t('title')}</p>
            </div>
            <p className="max-w-sm text-sm text-muted-foreground">{body ?? t('body')}</p>
            <Button variant="outline" size="sm" onClick={retry} disabled={isRetrying}>
                <ArrowPathIcon
                    data-icon="inline-start"
                    className={isRetrying ? 'animate-spin motion-reduce:animate-none' : undefined}
                />
                {isRetrying ? t('retrying') : t('retry')}
            </Button>
        </div>
    );
}
