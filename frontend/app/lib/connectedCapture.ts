import type {
    Activity,
    CaptureHealthStatus,
    CaptureStream,
    ConnectedAccountProvider,
    InstanceCapabilities,
    ProviderCapturePolicy,
    WorkspaceCapturePolicy,
} from '@/app/lib/types';

export const CAPTURE_STREAMS = [
    'calendar',
    'mail_inbox',
    'mail_sent',
] as const satisfies readonly CaptureStream[];

export const CAPTURE_PANELS = [
    'policy',
    'workspace-policy',
    'reviews',
    'purge',
] as const;

export type CapturePanel = (typeof CAPTURE_PANELS)[number];

export type CaptureRouteState = {
    provider: ConnectedAccountProvider | null;
    panel: CapturePanel | null;
    reviewId: number | null;
    page: number;
};

export type CapturePolicyValidation = {
    valid: boolean;
    backfillDaysValid: boolean;
    hasEnabledStream: boolean;
};

function isProvider(value: string | null): value is ConnectedAccountProvider {
    return value === 'google' || value === 'microsoft';
}

function isPanel(value: string | null): value is CapturePanel {
    return CAPTURE_PANELS.some((panel) => panel === value);
}

function positiveInteger(value: string | null): number | null {
    if (value == null || !/^[1-9]\d*$/.test(value)) return null;
    const parsed = Number(value);
    return Number.isSafeInteger(parsed) ? parsed : null;
}

/** Parses and validates the capture-specific state stored in the account-connections query string. */
export function parseCaptureRouteState(searchParams: URLSearchParams): CaptureRouteState {
    const providerValue = searchParams.get('provider');
    const provider = isProvider(providerValue) ? providerValue : null;
    const panelValue = searchParams.get('panel');
    const panel = provider && isPanel(panelValue) ? panelValue : null;
    const reviewId = panel === 'reviews' ? positiveInteger(searchParams.get('review')) : null;
    const page = panel === 'reviews' ? positiveInteger(searchParams.get('page')) ?? 1 : 1;
    return { provider, panel, reviewId, page };
}

/** Returns the canonical internal account URL while preserving unrelated validated callback state. */
export function captureConnectionsHref(
    current: URLSearchParams,
    next: Partial<CaptureRouteState>,
): string {
    const resolved = { ...parseCaptureRouteState(current), ...next };
    const params = new URLSearchParams(current.toString());
    params.delete('provider');
    params.delete('panel');
    params.delete('review');
    params.delete('page');

    if (resolved.provider) {
        params.set('provider', resolved.provider);
        if (resolved.panel) {
            params.set('panel', resolved.panel);
            if (resolved.panel === 'reviews') {
                if (resolved.reviewId) params.set('review', String(resolved.reviewId));
                if (resolved.page > 1) params.set('page', String(resolved.page));
            }
        }
    }

    const query = params.toString();
    return query ? `/account/connections?${query}` : '/account/connections';
}

/** Removes only the OAuth callback fields and canonicalizes capture panel parameters. */
export function connectionsHrefWithoutOAuthCallback(current: URLSearchParams): string {
    const params = new URLSearchParams(current.toString());
    params.delete('connected');
    params.delete('error');
    return captureConnectionsHref(params, parseCaptureRouteState(params));
}

/** Reports whether the provider's capture surface is enabled independently of OAuth custody. */
export function providerCaptureEnabled(
    capabilities: InstanceCapabilities,
    provider: ConnectedAccountProvider,
): boolean {
    return capabilities.connectedCapture[provider];
}

/** Builds the privacy-preserving first policy shown before a user has saved capture settings. */
export function defaultCapturePolicy(
    workspacePolicy: WorkspaceCapturePolicy,
): ProviderCapturePolicy {
    return {
        enabled: false,
        calendar: false,
        mailInbox: false,
        mailSent: false,
        backfillDays: Math.min(90, workspacePolicy.maxBackfillDays, 180),
        includeBodies: false,
        admissionMode: 'review',
        reviewBeforeCapture: true,
        excludedPeople: [],
        excludedConversations: [],
        version: 0,
        updatedAt: null,
    };
}

/** Validates the user policy against the product and workspace history-window limits. */
export function validateCapturePolicy(
    policy: ProviderCapturePolicy,
    workspacePolicy: WorkspaceCapturePolicy,
): CapturePolicyValidation {
    const maxBackfillDays = Math.min(180, workspacePolicy.maxBackfillDays);
    const backfillDaysValid = Number.isInteger(policy.backfillDays)
        && policy.backfillDays >= 1
        && policy.backfillDays <= maxBackfillDays;
    const hasEnabledStream = policy.calendar || policy.mailInbox || policy.mailSent;
    return {
        valid: backfillDaysValid
            && (!policy.enabled || (workspacePolicy.allowed && hasEnabledStream)),
        backfillDaysValid,
        hasEnabledStream,
    };
}

/** Returns whether a health state represents an active server-side operation worth polling. */
export function isCaptureOperationActive(status: CaptureHealthStatus): boolean {
    return status === 'queued'
        || status === 'backfilling'
        || status === 'syncing'
        || status === 'retrying'
        || status === 'purging';
}

/** Returns a bounded determinate progress value, or null when the provider supplied no estimate. */
export function captureProgress(
    processedItems: number,
    estimatedTotal?: number | null,
): { value: number; max: number } | null {
    if (estimatedTotal == null || estimatedTotal <= 0) return null;
    return {
        value: Math.min(Math.max(processedItems, 0), estimatedTotal),
        max: estimatedTotal,
    };
}

/** Identifies canonical activities projected from provider-owned capture records. */
export function isProviderOwnedActivity(activity: Activity): boolean {
    return activity.captureEvidence?.editable === false;
}
