'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import type { MouseEvent, ReactNode } from 'react';

import { consumeRecordHistoryReturn } from '@/app/lib/recordReturnPath';

/** Uses browser history for known list-origin visits and the validated href for direct visits. */
export default function RecordReturnLink({
    href,
    children,
    className,
    ariaLabel,
}: {
    href: string;
    children: ReactNode;
    className?: string;
    ariaLabel?: string;
}) {
    const router = useRouter();

    const handleClick = (event: MouseEvent<HTMLAnchorElement>) => {
        if (
            event.defaultPrevented
            || event.button !== 0
            || event.metaKey
            || event.ctrlKey
            || event.shiftKey
            || event.altKey
            || !consumeRecordHistoryReturn(href)
        ) {
            return;
        }
        event.preventDefault();
        router.back();
    };

    return (
        <Link
            href={href}
            aria-label={ariaLabel}
            className={className}
            onClick={handleClick}
        >
            {children}
        </Link>
    );
}
