'use client';

import { ArrowPathIcon } from '@heroicons/react/24/outline';
import { useRouter } from 'next/navigation';
import { useTransition } from 'react';

import { Button } from '@/components/ui/button';

/**
 * Retries workspace resolution without navigating away from the requested route. Server-rendered
 * failures refresh their payload; a mounted provider supplies an ordered authoritative re-read.
 */
export default function WorkspaceUnavailableRetry({
    label,
    pendingLabel,
    onRetry,
}: {
    label: string;
    pendingLabel: string;
    onRetry?: () => Promise<void>;
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
        >
            <ArrowPathIcon
                data-icon="inline-start"
                className={isRetrying ? 'animate-spin motion-reduce:animate-none' : undefined}
            />
            {isRetrying ? pendingLabel : label}
        </Button>
    );
}
