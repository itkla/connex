import type { CampaignAudienceRecordType } from '@/app/lib/types';

/**
 * The campaign permissions the backend actually checks, resolved once from the viewer's effective
 * permissions so every control gates on the same answer.
 *
 * Kept as a mirror of the server rules rather than a convenience: `CAMPAIGN_MANAGE` alone is not
 * enough for the audience operations, because those read person records and the backend guards
 * person data separately with `CONSENT_MANAGE`. The built-in `member` role holds `CAMPAIGN_VIEW`
 * and neither of the others, so a control gated on the wrong one 403s for every ordinary member.
 */
export type CampaignAccess = {
    /** Create, update, delete, and audience authoring require `CAMPAIGN_MANAGE`. */
    manage: boolean;
    /** Queue, pause, and cancel require `CAMPAIGN_SEND`. */
    send: boolean;
    /** Reading or materializing person audiences requires `CONSENT_MANAGE`. */
    consent: boolean;
};

const CONSENT_GUARDED_RECORD_TYPE: CampaignAudienceRecordType = 'person';

/**
 * Resolves the campaign permissions from a viewer's effective permission keys.
 * @param permissions the viewer's effective permission keys
 */
export function resolveCampaignAccess(permissions: readonly string[]): CampaignAccess {
    return {
        manage: permissions.includes('CAMPAIGN_MANAGE'),
        send: permissions.includes('CAMPAIGN_SEND'),
        consent: permissions.includes('CONSENT_MANAGE'),
    };
}

/**
 * Whether an audience of this record type reaches person data, which the backend guards with
 * `CONSENT_MANAGE`. Mirrors `CampaignService.requireConsentAccess`.
 * @param recordType the audience record type
 */
export function needsConsentAccess(recordType: CampaignAudienceRecordType): boolean {
    return recordType === CONSENT_GUARDED_RECORD_TYPE;
}

/**
 * Whether the viewer may estimate this audience. Estimating needs only `CAMPAIGN_VIEW` for company
 * and deal audiences, but reaches person data — and so needs `CONSENT_MANAGE` — for the person
 * audiences the UI defaults to.
 * @param access the resolved campaign permissions
 * @param recordType the audience record type
 */
export function canEstimateAudience(
    access: CampaignAccess,
    recordType: CampaignAudienceRecordType,
): boolean {
    return !needsConsentAccess(recordType) || access.consent;
}

/**
 * Whether the viewer may freeze a snapshot of this audience: managing the campaign, plus consent
 * access when the audience reaches person data.
 * @param access the resolved campaign permissions
 * @param recordType the audience record type
 */
export function canFreezeSnapshot(
    access: CampaignAccess,
    recordType: CampaignAudienceRecordType,
): boolean {
    return access.manage && canEstimateAudience(access, recordType);
}

/**
 * Whether the viewer may materialize a send. The backend requires `CONSENT_MANAGE` unconditionally
 * here, because a send always resolves person recipients.
 * @param access the resolved campaign permissions
 */
export function canCreateSend(access: CampaignAccess): boolean {
    return access.manage && access.consent;
}

/**
 * Whether the viewer may push an audience export, which reaches person data on the same terms as a
 * send.
 * @param access the resolved campaign permissions
 */
export function canCreateExport(access: CampaignAccess): boolean {
    return access.manage && access.consent;
}
