import { expect, test } from '@playwright/test';

import type {
    CaptureOverview,
    CaptureReviewPage,
    ProviderCaptureOverview,
    ProviderConnection,
} from '@/app/lib/types';
import { useLocale } from './support/locale';
import { message } from './support/messages';

const CONNECTION: ProviderConnection = {
    provider: 'google',
    status: 'connected',
    providerAccountEmail: 'capture-evaluator@example.com',
    grantedScopes: 'calendar mail',
    hasCredential: true,
    createdAt: '2026-07-30T08:00:00Z',
    updatedAt: '2026-07-30T08:00:00Z',
};

const BASE_OVERVIEW: ProviderCaptureOverview = {
    provider: 'google',
    userPolicy: {
        enabled: false,
        calendar: false,
        mailInbox: false,
        mailSent: false,
        backfillDays: 90,
        includeBodies: false,
        admissionMode: 'review',
        reviewBeforeCapture: true,
        excludedPeople: [],
        excludedConversations: [],
        version: 0,
        updatedAt: null,
    },
    workspacePolicy: {
        allowed: true,
        calendar: true,
        mailInbox: true,
        mailSent: true,
        maxBackfillDays: 120,
        bodyCaptureAllowed: false,
        reviewRequired: false,
        excludePrivateEvents: true,
        excludeInternalOnly: false,
        excludedDomains: [],
        version: 1,
        updatedAt: null,
    },
    effectivePolicy: {
        enabled: false,
        calendar: false,
        mailInbox: false,
        mailSent: false,
        backfillDays: 90,
        includeBodies: false,
        admissionMode: 'review',
        restrictionCodes: ['user_disabled'],
    },
    streams: [],
    reviewCount: 1,
    pendingApprovalCount: 0,
    activationReady: false,
    retainedData: true,
    accountResetAvailable: false,
    disclosures: {
        scopes: ['calendar.readonly', 'mail.readonly'],
        admittedFields: ['occurred_at', 'participants', 'subject'],
        materialExclusions: ['attachments', 'raw_mime', 'remote_images'],
        visibility: ['workspace_activity_evidence'],
        retention: ['retained_on_disconnect', 'erased_on_request'],
    },
    purge: { active: false, status: 'idle', errorCode: null },
};

const REVIEW_PAGE: CaptureReviewPage = {
    items: [{
        id: 91,
        version: 4,
        interactionId: 801,
        interactionVersion: 2,
        provider: 'google',
        stream: 'calendar',
        interactionType: 'meeting',
        subject: 'Evaluator review meeting',
        occurredAt: '2026-07-30T09:00:00Z',
        participantRole: 'attendee',
        displayName: 'Aiko Tanaka',
        email: 'aiko@example.com',
        matchState: 'multiple_matches',
        heldReason: 'multiple_matches',
        candidates: [{ personId: 11, name: 'Aiko Tanaka' }],
        allowedActions: ['attach', 'ignore'],
    }],
    total: 1,
    page: 1,
    size: 20,
};

const APPROVAL_PAGE: CaptureReviewPage = {
    items: [{
        id: 92,
        version: 2,
        interactionId: 802,
        interactionVersion: 3,
        provider: 'google',
        stream: 'mail_sent',
        interactionType: 'email',
        subject: 'Approval review message',
        occurredAt: '2026-07-30T09:30:00Z',
        participantRole: 'recipient',
        displayName: 'Aiko Tanaka',
        email: 'aiko@example.com',
        matchState: 'matched',
        heldReason: 'approval_required',
        candidates: [],
        allowedActions: [],
    }],
    total: 1,
    page: 1,
    size: 20,
};

function overviewResponse(provider: ProviderCaptureOverview): CaptureOverview {
    return { providers: [provider] };
}

for (const locale of ['en', 'ja'] as const) {
    test(`connected capture policy and review flow in ${locale} @mobile`, async ({ page }) => {
        let captureRequests = 0;
        let overview = BASE_OVERVIEW;
        let reviews = REVIEW_PAGE;
        let approvedInteractionId: number | null = null;
        let resetRequested = false;
        let resetPolls = 0;
        let resetCaptureFailures = 0;
        let resetConnectionPolls = 0;
        let connected = true;

        await page.route('**/api/account/connections**', async (route) => {
            const request = route.request();
            const url = new URL(request.url());
            const path = url.pathname;

            if (request.method() === 'GET' && path === '/api/account/connections') {
                if (resetRequested) resetConnectionPolls += 1;
                const resetting = resetRequested && resetConnectionPolls < 2;
                await route.fulfill({
                    json: resetting
                        ? [{ ...CONNECTION, status: 'disconnecting' }]
                        : connected ? [CONNECTION] : [],
                });
                return;
            }
            if (request.method() === 'GET' && path === '/api/account/connections/capture') {
                captureRequests += 1;
                if (resetRequested) {
                    resetPolls += 1;
                    if (resetPolls === 2) {
                        resetCaptureFailures += 1;
                        await route.fulfill({
                            status: 503,
                            contentType: 'application/json',
                            body: JSON.stringify({ message: 'temporary failure' }),
                        });
                        return;
                    }
                    if (resetPolls >= 3) {
                        overview = {
                            ...overview,
                            accountResetAvailable: false,
                            purge: { active: false, status: 'idle', errorCode: null },
                        };
                    }
                }
                await route.fulfill({ json: overviewResponse(overview) });
                return;
            }
            if (request.method() === 'PUT' && path.endsWith('/google/capture-policy')) {
                overview = {
                    ...overview,
                    userPolicy: {
                        enabled: true,
                        calendar: true,
                        mailInbox: false,
                        mailSent: false,
                        backfillDays: 30,
                        includeBodies: false,
                        admissionMode: 'review',
                        reviewBeforeCapture: true,
                        excludedPeople: [],
                        excludedConversations: [],
                        version: 1,
                        updatedAt: '2026-07-30T10:00:00Z',
                    },
                    effectivePolicy: {
                        enabled: true,
                        calendar: true,
                        mailInbox: false,
                        mailSent: false,
                        backfillDays: 30,
                        includeBodies: false,
                        admissionMode: 'review',
                        restrictionCodes: [],
                    },
                };
                await route.fulfill({ json: overview });
                return;
            }
            if (request.method() === 'GET' && path.endsWith('/google/reviews')) {
                await route.fulfill({ json: reviews });
                return;
            }
            if (request.method() === 'POST' && path.endsWith('/google/reviews/91')) {
                overview = { ...overview, reviewCount: 0, pendingApprovalCount: 1 };
                reviews = APPROVAL_PAGE;
                await route.fulfill({ json: overview });
                return;
            }
            if (request.method() === 'POST' && path.endsWith('/google/captured/802/approve')) {
                approvedInteractionId = 802;
                overview = { ...overview, pendingApprovalCount: 0 };
                reviews = { ...APPROVAL_PAGE, items: [], total: 0 };
                await route.fulfill({ json: overview });
                return;
            }
            if (request.method() === 'DELETE' && path.endsWith('/google/captured-data')) {
                overview = { ...overview, retainedData: false };
                await route.fulfill({
                    json: { active: false, status: 'idle', errorCode: null },
                });
                return;
            }
            if (request.method() === 'DELETE' && path.endsWith('/google/retained-data')) {
                resetRequested = true;
                overview = {
                    ...overview,
                    purge: { active: true, status: 'disconnecting', errorCode: null },
                };
                await route.fulfill({ status: 202, body: '' });
                return;
            }
            if (request.method() === 'DELETE' && path === '/api/account/connections/google') {
                connected = false;
                overview = { ...overview, accountResetAvailable: true };
                await route.fulfill({ status: 204, body: '' });
                return;
            }
            await route.continue();
        });

        await useLocale(page, locale);
        await page.goto('/account/connections');

        const configure = page.getByRole('button', {
            name: message(locale, 'account', 'AccountCaptureProvider.configure'),
        });
        await expect.poll(() => captureRequests).toBeGreaterThan(0);
        await expect(configure).toBeVisible();
        await configure.click();
        await expect(page.getByRole('heading', {
            name: message(locale, 'account', 'AccountCapturePolicy.title'),
        })).toBeVisible();
        await page.getByLabel(
            message(locale, 'account', 'AccountCapturePolicy.enabled'),
        ).click();
        await page.getByLabel(
            message(locale, 'account', 'AccountCapturePolicy.streams.calendar'),
        ).click();
        await page.getByLabel(
            message(locale, 'account', 'AccountCapturePolicy.backfill.label'),
        ).fill('30');
        await page.getByRole('button', {
            name: message(locale, 'account', 'AccountCapturePolicy.save'),
        }).click();
        await expect(page.getByText(
            message(locale, 'account', 'AccountCapturePolicy.saved'),
        )).toBeVisible();

        await page.goto('/account/connections?provider=google&panel=reviews');
        await expect(page.getByText('Evaluator review meeting')).toBeVisible();
        await page.getByText('Evaluator review meeting').click();
        await page.getByRole('button', {
            name: message(locale, 'account', 'AccountCaptureReviews.attach'),
        }).click();
        await expect(page.getByText(
            message(locale, 'account', 'AccountCaptureReviews.resolved'),
        )).toBeVisible();
        await page.getByText('Approval review message').click();
        await page.getByRole('button', {
            name: message(locale, 'account', 'AccountCaptureReviews.approve'),
        }).click();
        await expect(page.getByText(
            message(locale, 'account', 'AccountCaptureReviews.approved'),
        )).toBeVisible();
        expect(approvedInteractionId).toBe(802);

        await page.goto('/account/connections');
        await page.getByRole('button', {
            name: message(locale, 'account', 'AccountConnections.manage'),
        }).click();
        await page.getByRole('button', {
            name: message(locale, 'account', 'AccountConnections.disconnect'),
        }).click();
        await page.getByRole('button', {
            name: message(locale, 'account', 'AccountCaptureLifecycle.confirmDisconnect'),
        }).click();
        await expect(page.getByText(
            message(locale, 'account', 'AccountConnections.retainedDataNote'),
        )).toBeVisible();
        await page.getByRole('button', {
            name: message(locale, 'account', 'AccountCaptureProvider.purge'),
        }).click();
        await page.getByLabel(
            message(locale, 'account', 'AccountCaptureLifecycle.acknowledge'),
        ).click();
        await page.getByRole('button', {
            name: message(locale, 'account', 'AccountCaptureLifecycle.confirmPurge'),
        }).click();
        await expect(page.getByText(
            message(locale, 'account', 'AccountCaptureLifecycle.purgeStarted'),
        )).toBeVisible();

        await page.reload();
        await expect(page.getByText(
            message(locale, 'account', 'AccountConnections.accountResetNote'),
        )).toBeVisible();
        await page.getByRole('button', {
            name: message(locale, 'account', 'AccountConnections.resetProviderAccount'),
        }).click();
        const providerName = message(locale, 'account', 'AccountConnections.provider_google');
        await page.getByLabel(
            message(locale, 'account', 'AccountCaptureLifecycle.resetAcknowledge')
                .replace('{provider}', providerName),
        ).click();
        await page.getByRole('button', {
            name: message(locale, 'account', 'AccountCaptureLifecycle.confirmReset'),
        }).click();
        await expect(page.getByText(
            message(locale, 'account', 'AccountCaptureLifecycle.resetStarted'),
        )).toBeVisible();
        expect(resetRequested).toBe(true);
        await expect(page.getByRole('button', {
            name: message(locale, 'account', 'AccountConnections.resetProviderAccount'),
        })).toHaveCount(0, { timeout: 15_000 });
        await expect(page.getByRole('button', {
            name: message(locale, 'account', 'AccountConnections.connectProvider')
                .replace('{provider}', providerName),
        })).toBeVisible();
        expect(resetCaptureFailures).toBe(1);
        expect(resetPolls).toBeGreaterThanOrEqual(3);
        expect(resetConnectionPolls).toBeGreaterThanOrEqual(2);
    });
}
