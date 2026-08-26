import { describe, expect, it } from 'vitest';

import {
    captureConnectionsHref,
    captureProgress,
    connectionsHrefWithoutOAuthCallback,
    defaultCapturePolicy,
    isCaptureOperationActive,
    isProviderOwnedActivity,
    parseCaptureRouteState,
    providerCaptureEnabled,
    validateCapturePolicy,
} from '@/app/lib/connectedCapture';
import type {
    Activity,
    InstanceCapabilities,
    WorkspaceCapturePolicy,
} from '@/app/lib/types';

const CAPABILITIES: InstanceCapabilities = {
    sso: false,
    socialLogin: { google: false, microsoft: false },
    connectedAccounts: { google: true, microsoft: true },
    connectedCapture: { google: true, microsoft: false },
    mailManaged: false,
    businessCardScanning: false,
    businessCardImport: false,
    campaignDelivery: false,
    privilegedMfaEnforced: true,
};

const WORKSPACE_POLICY: WorkspaceCapturePolicy = {
    allowed: true,
    calendar: true,
    mailInbox: true,
    mailSent: false,
    maxBackfillDays: 120,
    bodyCaptureAllowed: false,
    reviewRequired: true,
    excludePrivateEvents: true,
    excludeInternalOnly: false,
    excludedDomains: [],
    version: 3,
    updatedAt: null,
};

describe('connected capture route state', () => {
    it('accepts only provider-neutral capture panels and positive review state', () => {
        const parsed = parseCaptureRouteState(new URLSearchParams(
            'provider=google&panel=reviews&review=42&page=3',
        ));
        expect(parsed).toEqual({
            provider: 'google',
            panel: 'reviews',
            reviewId: 42,
            page: 3,
        });
    });

    it('drops invalid capture state while preserving OAuth callback fields', () => {
        const current = new URLSearchParams(
            'connected=google&provider=javascript%3Aalert(1)&panel=reviews&review=-1',
        );
        expect(captureConnectionsHref(current, parseCaptureRouteState(current))).toBe(
            '/settings/personal/connected-accounts?connected=google',
        );
    });

    it('removes only OAuth callback fields and keeps the validated panel state', () => {
        const current = new URLSearchParams(
            'connected=google&error=denied&provider=microsoft&panel=reviews&page=2',
        );
        expect(connectionsHrefWithoutOAuthCallback(current)).toBe(
            '/settings/personal/connected-accounts?provider=microsoft&panel=reviews&page=2',
        );
    });
});

describe('connected capture policy', () => {
    it('keeps capture capability separate from OAuth custody', () => {
        expect(providerCaptureEnabled(CAPABILITIES, 'google')).toBe(true);
        expect(providerCaptureEnabled(CAPABILITIES, 'microsoft')).toBe(false);
    });

    it('starts metadata-only, review-first, at the bounded 90-day default', () => {
        expect(defaultCapturePolicy(WORKSPACE_POLICY)).toMatchObject({
            enabled: false,
            calendar: false,
            mailInbox: false,
            mailSent: false,
            backfillDays: 90,
            includeBodies: false,
            admissionMode: 'review',
            reviewBeforeCapture: true,
        });
    });

    it('requires a selected source and enforces the workspace and 180-day caps', () => {
        const policy = {
            ...defaultCapturePolicy(WORKSPACE_POLICY),
            enabled: true,
        };
        expect(validateCapturePolicy(policy, WORKSPACE_POLICY).valid).toBe(false);

        const valid = {
            ...policy,
            calendar: true,
            backfillDays: 120,
        };
        expect(validateCapturePolicy(valid, WORKSPACE_POLICY).valid).toBe(true);
        expect(validateCapturePolicy({ ...valid, backfillDays: 121 }, WORKSPACE_POLICY).valid)
            .toBe(false);
    });
});

describe('connected capture health and provenance', () => {
    it('uses determinate progress only when a provider supplies a total', () => {
        expect(captureProgress(25, 100)).toEqual({ value: 25, max: 100 });
        expect(captureProgress(125, 100)).toEqual({ value: 100, max: 100 });
        expect(captureProgress(25, null)).toBeNull();
    });

    it('polls only active server operations', () => {
        expect(isCaptureOperationActive('queued')).toBe(true);
        expect(isCaptureOperationActive('backfilling')).toBe(true);
        expect(isCaptureOperationActive('syncing')).toBe(true);
        expect(isCaptureOperationActive('retrying')).toBe(true);
        expect(isCaptureOperationActive('idle')).toBe(false);
        expect(isCaptureOperationActive('paused')).toBe(false);
        expect(isCaptureOperationActive('intervention_required')).toBe(false);
    });

    it('marks provider projections as non-editable', () => {
        const manual: Activity = {
            id: 1,
            type: 'Meeting',
            subject: 'Manual activity',
            createdById: 1,
        };
        const captured: Activity = {
            ...manual,
            id: 2,
            captureEvidence: {
                provider: 'google',
                stream: 'calendar',
                sourceId: 'event-2',
                capturedAt: '2026-07-30T10:00:00Z',
                captureAsOf: '2026-07-30T10:00:00Z',
                visibility: 'workspace_members_with_record_access',
                admittedFields: ['event_time'],
                materialExclusions: ['attachments'],
                editable: false,
            },
        };

        expect(isProviderOwnedActivity(manual)).toBe(false);
        expect(isProviderOwnedActivity(captured)).toBe(true);
    });
});
