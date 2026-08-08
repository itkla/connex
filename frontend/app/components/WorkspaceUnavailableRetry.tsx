'use client';

import { ArrowPathIcon } from '@heroicons/react/24/outline';
import { useRouter } from 'next/navigation';
import { useTransition } from 'react';

import { Button } from '@/components/ui/button';

/** Retries the server-rendered workspace lookup without navigating away from the requested route. */
export default function WorkspaceUnavailableRetry({
    label,
    pendingLabel,
}: {
    label: string;
    pendingLabel: string;
}) {
    const router = useRouter();
    const [isRetrying, startTransition] = useTransition();

    return (
        <Button onClick={() => startTransition(() => router.refresh())} disabled={isRetrying}>
            <ArrowPathIcon
                data-icon="inline-start"
                className={isRetrying ? 'animate-spin motion-reduce:animate-none' : undefined}
            />
            {isRetrying ? pendingLabel : label}
        </Button>
    );
}
