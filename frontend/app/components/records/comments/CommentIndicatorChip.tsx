'use client';

import { useTranslations } from 'next-intl';
import { ChatBubbleLeftIcon } from '@heroicons/react/24/outline';

import { cn } from '@/lib/utils';

/**
 * Quiet open-discussion marker for browser rows and Peek: a chat glyph with the
 * open-thread count. Renders nothing when the count is absent or zero so rows
 * without discussion stay clean.
 */
export default function CommentIndicatorChip({
    count,
    className,
}: {
    count: number | undefined;
    className?: string;
}) {
    const t = useTranslations('Comments');
    if (!count) return null;
    return (
        <span
            className={cn(
                'inline-flex shrink-0 items-center gap-1 rounded-full bg-muted px-1.5 py-0.5 text-[11px] font-medium text-muted-foreground',
                className,
            )}
            title={t('openThreads', { count })}
            aria-label={t('openThreads', { count })}
        >
            <ChatBubbleLeftIcon className="size-3" aria-hidden />
            {count}
        </span>
    );
}
