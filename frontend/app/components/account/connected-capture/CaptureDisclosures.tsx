'use client';

import { InformationCircleIcon } from '@heroicons/react/24/outline';
import { useTranslations } from 'next-intl';

import type { CaptureDisclosures } from '@/app/lib/types';

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

const VISIBILITY_KEYS = {
    owner_only: 'visibility.ownerOnly',
    workspace_members_with_record_access: 'visibility.recordAccess',
    workspace_activity_evidence: 'visibility.workspaceActivityEvidence',
} as const;

const RETENTION_KEYS = {
    until_purged: 'retention.untilPurged',
    until_purged_or_disconnected: 'retention.untilPurgedOrDisconnected',
    purged_on_disconnect: 'retention.purgedOnDisconnect',
    purged_on_account_deletion: 'retention.purgedOnAccountDeletion',
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
 * Explains provider scopes, admitted data, exclusions, visibility, and retention before capture.
 */
export default function CaptureDisclosures({
    disclosures,
}: {
    disclosures: CaptureDisclosures;
}) {
    const t = useTranslations('AccountCaptureDisclosure');

    return (
        <details className="group rounded-lg border border-border bg-muted/30">
            <summary className="flex cursor-pointer list-none items-start gap-2 px-3 py-2.5 text-sm font-medium text-foreground outline-none focus-visible:ring-2 focus-visible:ring-ring/50">
                <InformationCircleIcon className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden />
                <span>{t('summary')}</span>
            </summary>
            <div className="grid gap-4 border-t border-border px-3 py-3 text-xs">
                <section>
                    <h4 className="font-medium text-foreground">{t('scope')}</h4>
                    <ul className="mt-1 grid gap-1 text-muted-foreground">
                        {disclosures.scopes.map((scope) => (
                            <li key={scope} className="break-all font-mono">{scope}</li>
                        ))}
                    </ul>
                </section>
                <section>
                    <h4 className="font-medium text-foreground">{t('data')}</h4>
                    <ul className="mt-1 grid gap-1 text-muted-foreground">
                        {disclosures.admittedFields.map((field) => (
                            <li key={field}>
                                {translatedCode(field, ADMITTED_FIELD_KEYS, 'admittedField.unknown', t)}
                            </li>
                        ))}
                    </ul>
                </section>
                <section>
                    <h4 className="font-medium text-foreground">{t('excluded')}</h4>
                    <ul className="mt-1 grid gap-1 text-muted-foreground">
                        {disclosures.materialExclusions.map((exclusion) => (
                            <li key={exclusion}>
                                {translatedCode(exclusion, EXCLUSION_KEYS, 'materialExclusion.unknown', t)}
                            </li>
                        ))}
                    </ul>
                </section>
                <dl className="grid gap-3 sm:grid-cols-2">
                    <div>
                        <dt className="font-medium text-foreground">{t('visibility')}</dt>
                        <dd className="mt-1">
                            <ul className="grid gap-1 text-muted-foreground">
                                {disclosures.visibility.map((visibility) => (
                                    <li key={visibility}>
                                        {translatedCode(
                                            visibility,
                                            VISIBILITY_KEYS,
                                            'visibility.unknown',
                                            t,
                                        )}
                                    </li>
                                ))}
                            </ul>
                        </dd>
                    </div>
                    <div>
                        <dt className="font-medium text-foreground">{t('retention')}</dt>
                        <dd className="mt-1">
                            <ul className="grid gap-1 text-muted-foreground">
                                {disclosures.retention.map((retention) => (
                                    <li key={retention}>
                                        {translatedCode(
                                            retention,
                                            RETENTION_KEYS,
                                            'retention.unknown',
                                            t,
                                        )}
                                    </li>
                                ))}
                            </ul>
                        </dd>
                    </div>
                </dl>
            </div>
        </details>
    );
}
