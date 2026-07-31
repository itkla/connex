'use client';

import { ExclamationTriangleIcon } from '@heroicons/react/24/outline';
import Link from 'next/link';
import { useTranslations } from 'next-intl';

import type {
    DuplicateCandidate,
    DuplicatePreflightResponse,
} from '@/app/lib/types';
import type {
    DuplicatePreflightKind,
    DuplicatePreflightStatus,
} from '@/app/hooks/useDuplicatePreflight';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';

function recordHref(kind: DuplicatePreflightKind, id: number): string {
    return kind === 'company' ? `/records/companies/${id}` : `/records/contacts/${id}`;
}

function context(candidate: DuplicateCandidate): string[] {
    return [
        candidate.title,
        candidate.companyName,
        candidate.industry,
        candidate.website,
    ].filter((value): value is string => Boolean(value));
}

/**
 * Shows canonical duplicate evidence and requires an explicit acknowledgement before a new record is created.
 */
export default function DuplicatePreflightWarning({
    id,
    kind,
    status,
    response,
    acknowledged = false,
    onAcknowledgedChange,
    onRetry,
    personResolution,
}: {
    id?: string;
    kind: DuplicatePreflightKind;
    status: DuplicatePreflightStatus;
    response: DuplicatePreflightResponse | null;
    acknowledged?: boolean;
    onAcknowledgedChange?: (checked: boolean) => void;
    onRetry?: () => void;
    personResolution?: {
        selected: 'existing' | 'create' | null;
        disabled?: boolean;
        onSelectedChange: (selected: 'existing' | 'create') => void;
    };
}) {
    const t = useTranslations('DuplicateWarning');

    if (status === 'idle') return null;
    if (status === 'checking') {
        return <p id={id} className="text-xs text-muted-foreground" role="status">{t('checking')}</p>;
    }
    if (status === 'error') {
        return (
            <div id={id} className="flex items-center gap-2 text-xs text-destructive" role="alert">
                <span>{t('failed')}</span>
                {onRetry && (
                    <Button type="button" variant="ghost" size="xs" onClick={onRetry}>{t('retry')}</Button>
                )}
            </div>
        );
    }
    if (!response || response.candidates.length === 0 && !response.truncated) return null;

    return (
        <section id={id} className="grid gap-2 rounded-lg border border-warning/40 bg-warning/5 p-3" aria-label={t('heading')}>
            <div className="flex items-start gap-2 text-sm text-foreground">
                <ExclamationTriangleIcon className="mt-0.5 size-4 shrink-0 text-warning" aria-hidden="true" />
                <div>
                    <p className="font-medium">{t('heading')}</p>
                    <p className="text-xs text-muted-foreground">{t('description')}</p>
                </div>
            </div>
            <ul className="grid gap-1.5">
                {response.candidates.map((candidate) => {
                    const details = context(candidate);
                    return (
                        <li key={`${candidate.recordType}-${candidate.recordId}`} className="rounded-md border bg-background px-2.5 py-2">
                            <div className="flex min-w-0 flex-wrap items-center gap-1.5">
                                <Link
                                    href={recordHref(kind, candidate.recordId)}
                                    target="_blank"
                                    rel="noopener noreferrer"
                                    className="truncate text-xs font-medium underline underline-offset-2 hover:text-foreground"
                                >
                                    {candidate.name}
                                </Link>
                                <Badge variant={candidate.strength === 'STRONG' ? 'secondary' : 'outline'} className="h-5 px-1.5 text-[10px]">
                                    {t(`strength.${candidate.strength}`)}
                                </Badge>
                                {!candidate.ownedByActiveWorkspace && (
                                    <span className="text-[11px] text-muted-foreground">{t('shared')}</span>
                                )}
                            </div>
                            {details.length > 0 && (
                                <p className="truncate text-[11px] text-muted-foreground">{details.join(' · ')}</p>
                            )}
                            <p className="text-[11px] text-muted-foreground">
                                {candidate.matches
                                    .map((match) => t(`evidence.${match.kind}`))
                                    .join(' · ')}
                            </p>
                        </li>
                    );
                })}
            </ul>
            {response.truncated && (
                <p className="text-xs text-muted-foreground">
                    {t('truncated')}
                </p>
            )}
            {personResolution && !response.truncated ? (
                <div className="grid gap-1.5" role="group" aria-label={t('cardResolution')}>
                    <Button
                        type="button"
                        variant={personResolution.selected === 'existing' ? 'secondary' : 'outline'}
                        size="sm"
                        className="h-auto justify-start px-3 py-2 text-left"
                        aria-pressed={personResolution.selected === 'existing'}
                        disabled={personResolution.disabled}
                        onClick={() => personResolution.onSelectedChange('existing')}
                    >
                        <span className="grid gap-0.5">
                            <span>{t('useExisting')}</span>
                            <span className="text-xs font-normal text-muted-foreground">
                                {t('useExistingDescription')}
                            </span>
                        </span>
                    </Button>
                    <Button
                        type="button"
                        variant={personResolution.selected === 'create' ? 'secondary' : 'outline'}
                        size="sm"
                        className="h-auto justify-start px-3 py-2 text-left"
                        aria-pressed={personResolution.selected === 'create'}
                        disabled={personResolution.disabled}
                        onClick={() => personResolution.onSelectedChange('create')}
                    >
                        <span className="grid gap-0.5">
                            <span>{t('createSeparate')}</span>
                            <span className="text-xs font-normal text-muted-foreground">
                                {t('createSeparateDescription')}
                            </span>
                        </span>
                    </Button>
                </div>
            ) : onAcknowledgedChange && !response.truncated ? (
                <label className="flex items-start gap-2 text-xs text-foreground">
                    <Checkbox
                        checked={acknowledged}
                        onCheckedChange={(checked) => onAcknowledgedChange(checked === true)}
                        aria-label={t('acknowledge')}
                    />
                    <span>{t('acknowledge')}</span>
                </label>
            ) : null}
        </section>
    );
}
