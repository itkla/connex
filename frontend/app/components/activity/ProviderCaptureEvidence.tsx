'use client';

import { CircleStackIcon } from '@heroicons/react/24/outline';
import { useLocale, useTranslations } from 'next-intl';

import type { CapturedActivityEvidence } from '@/app/lib/types';
import { formatDateTime } from '@/app/lib/utils';
import { Badge } from '@/components/ui/badge';

const ADMITTED_FIELD_KEYS = {
    provider_source_id: 'admittedField.providerSourceId',
    occurred_at: 'admittedField.occurredAt',
    event_time: 'admittedField.eventTime',
    title: 'admittedField.title',
    participants: 'admittedField.participants',
    organizer: 'admittedField.organizer',
    location: 'admittedField.location',
    message_time: 'admittedField.messageTime',
    subject: 'admittedField.subject',
    sender: 'admittedField.sender',
    recipients: 'admittedField.recipients',
    body: 'admittedField.body',
} as const;

const EXCLUSION_KEYS = {
    attachments: 'materialExclusion.attachments',
    raw_mime: 'materialExclusion.rawMime',
    remote_images: 'materialExclusion.remoteImages',
    arbitrary_folders: 'materialExclusion.arbitraryFolders',
    arbitrary_calendars: 'materialExclusion.arbitraryCalendars',
    private_events: 'materialExclusion.privateEvents',
    unmatched_participants: 'materialExclusion.unmatchedParticipants',
    full_search: 'materialExclusion.fullSearch',
    outbound_actions: 'materialExclusion.outboundActions',
    non_primary_calendars: 'materialExclusion.nonPrimaryCalendars',
    body: 'materialExclusion.body',
} as const;

function translatedCode(
    value: string,
    keys: Record<string, string>,
    fallbackKey: string,
    t: ReturnType<typeof useTranslations>,
): string {
    return t(keys[value] ?? fallbackKey, { code: value });
}

/**
 * Shows the provider provenance and admitted-data boundary for a canonical captured activity.
 */
export default function ProviderCaptureEvidence({
    evidence,
    compact = false,
}: {
    evidence: CapturedActivityEvidence;
    compact?: boolean;
}) {
    const t = useTranslations('CapturedEvidence');
    const tDisclosure = useTranslations('AccountCaptureDisclosure');
    const tConnections = useTranslations('AccountConnections');
    const locale = useLocale();

    if (compact) {
        return (
            <div className="mt-1 flex min-w-0 flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                <Badge variant="outline">
                    {t('capturedBy', {
                        provider: tConnections(`provider_${evidence.provider}`),
                    })}
                </Badge>
                <span className="break-all font-mono">{evidence.sourceId}</span>
            </div>
        );
    }

    return (
        <section className="rounded-2xl border border-border bg-card p-5" aria-label={t('title')}>
            <div className="flex items-start gap-3">
                <CircleStackIcon className="mt-0.5 size-5 shrink-0 text-muted-foreground" aria-hidden />
                <div className="min-w-0">
                    <h2 className="text-sm font-semibold text-foreground">{t('title')}</h2>
                    <p className="mt-1 text-xs text-muted-foreground">{t('description')}</p>
                </div>
            </div>

            <dl className="mt-4 grid gap-3 text-sm sm:grid-cols-2">
                <div>
                    <dt className="text-xs text-muted-foreground">{t('provider')}</dt>
                    <dd className="mt-1 text-foreground">
                        {tConnections(`provider_${evidence.provider}`)}
                    </dd>
                </div>
                <div>
                    <dt className="text-xs text-muted-foreground">{t('stream')}</dt>
                    <dd className="mt-1 text-foreground">{t(`streamValue.${evidence.stream}`)}</dd>
                </div>
                <div>
                    <dt className="text-xs text-muted-foreground">{t('sourceId')}</dt>
                    <dd className="mt-1 break-all font-mono text-xs text-foreground">
                        {evidence.sourceId}
                    </dd>
                </div>
                <div>
                    <dt className="text-xs text-muted-foreground">{t('capturedAt')}</dt>
                    <dd className="mt-1 tabular-nums text-foreground">
                        {formatDateTime(evidence.capturedAt, locale)}
                    </dd>
                </div>
                <div>
                    <dt className="text-xs text-muted-foreground">{t('captureAsOf')}</dt>
                    <dd className="mt-1 tabular-nums text-foreground">
                        {formatDateTime(evidence.captureAsOf, locale)}
                    </dd>
                </div>
                <div>
                    <dt className="text-xs text-muted-foreground">{tDisclosure('visibility')}</dt>
                    <dd className="mt-1 text-foreground">
                        {tDisclosure(
                            evidence.visibility === 'owner_only'
                                ? 'visibility.ownerOnly'
                                : evidence.visibility === 'workspace_members_with_record_access'
                                    ? 'visibility.recordAccess'
                                    : evidence.visibility === 'workspace_activity_evidence'
                                        ? 'visibility.workspaceActivityEvidence'
                                    : 'visibility.unknown',
                            { code: evidence.visibility },
                        )}
                    </dd>
                </div>
            </dl>

            <div className="mt-4 grid gap-4 border-t border-border pt-4 sm:grid-cols-2">
                <section>
                    <h3 className="text-xs font-medium text-foreground">{tDisclosure('data')}</h3>
                    <ul className="mt-1 grid gap-1 text-xs text-muted-foreground">
                        {evidence.admittedFields.map((field) => (
                            <li key={field}>
                                {translatedCode(
                                    field,
                                    ADMITTED_FIELD_KEYS,
                                    'admittedField.unknown',
                                    tDisclosure,
                                )}
                            </li>
                        ))}
                    </ul>
                </section>
                <section>
                    <h3 className="text-xs font-medium text-foreground">{tDisclosure('excluded')}</h3>
                    <ul className="mt-1 grid gap-1 text-xs text-muted-foreground">
                        {evidence.materialExclusions.map((exclusion) => (
                            <li key={exclusion}>
                                {translatedCode(
                                    exclusion,
                                    EXCLUSION_KEYS,
                                    'materialExclusion.unknown',
                                    tDisclosure,
                                )}
                            </li>
                        ))}
                    </ul>
                </section>
            </div>

            <p className="mt-4 text-xs text-muted-foreground">{t('readOnly')}</p>
        </section>
    );
}
