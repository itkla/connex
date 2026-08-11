'use client';

import { ArrowPathIcon } from '@heroicons/react/24/outline';
import { useRouter } from 'next/navigation';
import { useTransition, type ComponentProps } from 'react';

import { Button } from '@/components/ui/button';

/**
 * Retries workspace resolution without navigating away from the requested route. Server-rendered
 * failures refresh their payload; a mounted provider supplies an ordered authoritative re-read.
 */
export default function WorkspaceUnavailableRetry({
    label,
    pendingLabel,
    onRetry,
    variant,
    size,
    className,
}: {
    label: string;
    pendingLabel: string;
    onRetry?: () => Promise<void>;
    variant?: ComponentProps<typeof Button>['variant'];
    size?: ComponentProps<typeof Button>['size'];
    className?: string;
}) {
    const router = useRouter();
    const [isRetrying, startTransition] = useTransition();

    return (
        <Button
            onClick={() => startTransition(async () => {
                if (onRetry) {
                    await onRetry();
                } else {
                    router.refresh();
                }
            })}
            disabled={isRetrying}
            variant={variant}
            size={size}
            className={className}
        >
            <ArrowPathIcon
                data-icon="inline-start"
                className={isRetrying ? 'animate-spin motion-reduce:animate-none' : undefined}
            />
            {isRetrying ? pendingLabel : label}
        </Button>
    );
}
