'use client';

import { ArrowDownIcon, ArrowUpIcon } from '@heroicons/react/24/outline';
import { useTranslations } from 'next-intl';
import { IconButton } from '@/components/ui/icon-button';

/** Explicit stage ordering works with a pointer, touch, Enter, or Space. */
export function StageOrderControls({ id, index, count, disabled, onMove }: {
    id: string;
    index: number;
    count: number;
    disabled: boolean;
    onMove: (to: number, direction: 'up' | 'down') => void;
}) {
    const t = useTranslations('PipelineStageOrder');
    return (
        <div className="flex shrink-0 flex-col">
            <IconButton id={`${id}-up`} type="button" variant="ghost" size="icon-inline" label={t('moveUp')}
                className="aria-disabled:opacity-50"
                aria-disabled={disabled || index === 0}
                onClick={() => { if (!disabled && index > 0) onMove(index - 1, 'up'); }}>
                <ArrowUpIcon className="size-4" />
            </IconButton>
            <IconButton id={`${id}-down`} type="button" variant="ghost" size="icon-inline" label={t('moveDown')}
                className="aria-disabled:opacity-50"
                aria-disabled={disabled || index === count - 1}
                onClick={() => { if (!disabled && index < count - 1) onMove(index + 1, 'down'); }}>
                <ArrowDownIcon className="size-4" />
            </IconButton>
        </div>
    );
}
