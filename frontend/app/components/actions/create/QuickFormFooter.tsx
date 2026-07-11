'use client';

import type { ReactNode } from 'react';
import { useTranslations } from 'next-intl';

import { Button } from '@/components/ui/button';

/**
 * Shared footer for the in-panel quick-create forms: a "More details" escape hatch to the full dialog
 * on the left and the primary submit on the right. The submit content (label or spinner) is passed as
 * children so each form owns its pending state.
 */
export default function QuickFormFooter({
    onMoreDetails,
    submitDisabled,
    children,
}: {
    onMoreDetails: () => void;
    submitDisabled: boolean;
    children: ReactNode;
}) {
    const t = useTranslations('Actions');
    return (
        <div className="mt-1 flex items-center justify-between gap-3">
            <button
                type="button"
                onClick={onMoreDetails}
                className="rounded-md text-sm font-medium text-muted-foreground underline-offset-4 transition-colors hover:text-foreground hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand"
            >
                {t('quickCreate.moreDetails')}
            </button>
            <Button
                type="submit"
                disabled={submitDisabled}
                className="min-w-24 bg-brand text-brand-foreground shadow-sm transition hover:bg-brand-hover hover:shadow-md"
            >
                {children}
            </Button>
        </div>
    );
}
