'use client';

import Link from 'next/link';
import { useTranslations } from 'next-intl';
import { ExclamationTriangleIcon } from '@heroicons/react/24/outline';

import type { DuplicateKind, DuplicateMatch } from '@/app/hooks/useDuplicateNameCheck';

function recordHref(kind: DuplicateKind, id: number): string {
    return kind === 'company' ? `/records/companies/${id}` : `/records/contacts/${id}`;
}

/**
 * Non-blocking amber hint shown under a name field during creation when records with a matching name already
 * exist. Each match links to its record in a new tab so the half-filled form is preserved. Renders nothing
 * when there are no matches.
 */
export default function DuplicateNameWarning({
    kind,
    matches,
    total,
}: {
    kind: DuplicateKind;
    matches: DuplicateMatch[];
    total: number;
}) {
    const t = useTranslations('DuplicateWarning');
    if (matches.length === 0) return null;
    const extra = total - matches.length;

    return (
        <div className="flex items-start gap-1.5 text-sm text-amber-600 dark:text-amber-500" role="status">
            <ExclamationTriangleIcon className="mt-0.5 size-3.5 shrink-0" />
            <div className="min-w-0">
                <span>{t('heading')} </span>
                {matches.map((match, index) => (
                    <span key={match.id}>
                        <Link
                            href={recordHref(kind, match.id)}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="font-medium underline underline-offset-2 hover:text-foreground"
                        >
                            {match.name}
                        </Link>
                        {match.detail && <span className="text-muted-foreground"> · {match.detail}</span>}
                        {index < matches.length - 1 && <span>, </span>}
                    </span>
                ))}
                {extra > 0 && <span className="text-muted-foreground"> {t('more', { count: extra })}</span>}
            </div>
        </div>
    );
}
