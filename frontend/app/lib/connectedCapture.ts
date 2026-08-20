import type {
    Activity,
    CaptureHealthStatus,
    CaptureStream,
    ConnectedAccountProvider,
    InstanceCapabilities,
    ProviderCapturePolicy,
    ProviderCaptureOverview,
    ProviderConnection,
    WorkspaceCapturePolicy,
} from '@/app/lib/types';

export const CAPTURE_STREAMS = [
    'calendar',
    'mail_inbox',
    'mail_sent',
] as const satisfies readonly CaptureStream[];

export const CAPTURE_PANELS = [
    'manage',
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

const PENDING_AUTHORIZATION_KEY = 'connex.connectedAccounts.pendingAuthorization';

/**
 * Records which provider this browser handed off to before authorization leaves the app.
 *
 * Authorization is a full-page departure and the provider's own error return carries only
 * `?error=<code>`, never which provider failed. Without this, a denied Google authorization comes
 * back indistinguishable from a denied Microsoft one and the journey cannot resume where it left.
 * Session storage is the right lifetime: it survives the round trip and dies with the tab.
 */
export function rememberPendingAuthorization(provider: ConnectedAccountProvider): void {
    try {
        window.sessionStorage.setItem(PENDING_AUTHORIZATION_KEY, provider);
    } catch {
        return;
    }
}

/** Reads and clears the provider this browser handed off to, or null when there was no handoff. */
export function takePendingAuthorization(): ConnectedAccountProvider | null {
    try {
        const stored = window.sessionStorage.getItem(PENDING_AUTHORIZATION_KEY);
        window.sessionStorage.removeItem(PENDING_AUTHORIZATION_KEY);
        return isProvider(stored) ? stored : null;
    } catch {
        return null;
    }
}

/** Reports whether the provider's capture surface is enabled independently of OAuth custody. */
export function providerCaptureEnabled(
    capabilities: InstanceCapabilities,
    provider: ConnectedAccountProvider,
): boolean {
    return capabilities.connectedCapture[provider];
}

/**
 * Reports whether the provider has a journey at all: either its OAuth custody or its capture
 * surface is switched on. A provider whose capture is off is still connectable and still
 * manageable, so the manage drawer answers to this rather than to capture alone.
 */
export function providerJourneyEnabled(
    capabilities: InstanceCapabilities,
    provider: ConnectedAccountProvider,
): boolean {
    return capabilities.connectedAccounts[provider] || capabilities.connectedCapture[provider];
}

/**
 * Reports whether a panel reads capture data and therefore needs the capture capability. Only the
 * manage drawer survives a capture-disabled provider; every other panel edits or lists something
 * the capture surface owns.
 */
export function capturePanelRequiresCapture(panel: CapturePanel): boolean {
    return panel !== 'manage';
}

/**
 * The state one provider's card is in. `disconnected` and `authorizing` describe a provider with no
 * stored connection; the rest are read from the connection row and its stream health.
 *
 * The consent step is deliberately absent: it is an overlay above a card that is still
 * `disconnected`, so opening it changes nothing underneath and dismissing it restores nothing.
 */
export type ProviderJourneyState =
    | 'disconnected'
    | 'authorizing'
    | 'connected'
    | 'syncing'
    | 'paused'
    | 'attention'
    | 'disconnecting';

/**
 * Derives the card state from the connection row, its capture health, and whether this browser has
 * an authorization handoff in flight. Ordered by precedence: a durable disconnect outranks an
 * authorization failure, which outranks a pause, which outranks live sync activity.
 *
 * @param connection the stored connection, or null when the provider has never been connected
 * @param capture the provider's capture overview, or null when capture is off or not yet loaded
 * @param authorizing whether this browser has started an authorization that has not returned
 */
export function providerJourneyState(
    connection: ProviderConnection | null,
    capture: ProviderCaptureOverview | null,
    authorizing: boolean,
): ProviderJourneyState {
    if (!connection) return authorizing ? 'authorizing' : 'disconnected';
    if (connection.status === 'disconnecting' || connection.status === 'purge_failed') {
        return 'disconnecting';
    }
    if (connection.status === 'error' || connection.status === 'revoked') return 'attention';
    if (capture?.streams.some((stream) => stream.status === 'intervention_required')) {
        return 'attention';
    }
    if (connection.status === 'paused') return 'paused';
    if (capture?.streams.some((stream) => isCaptureOperationActive(stream.status))) {
        return 'syncing';
    }
    return 'connected';
}

/**
 * The most recent moment any of the provider's streams last succeeded, or null when none has.
 *
 * `ProviderConnection.lastSyncAt` is not this value: the column survives from an earlier connection
 * model and no write path sets it, so reading it would report "never" on a provider that is
 * syncing. Stream success is the only recorded truth about when capture last ran.
 */
export function lastCaptureSuccessAt(capture: ProviderCaptureOverview | null): string | null {
    if (!capture) return null;
    let latest: string | null = null;
    let latestValue = Number.NEGATIVE_INFINITY;
    for (const stream of capture.streams) {
        if (!stream.lastSuccessAt) continue;
        const parsed = Date.parse(stream.lastSuccessAt);
        if (Number.isNaN(parsed) || parsed <= latestValue) continue;
        latestValue = parsed;
        latest = stream.lastSuccessAt;
    }
    return latest;
}

/** Reports whether the provider's authorization must be renewed before capture can resume. */
export function needsReauthorization(capture: ProviderCaptureOverview | null): boolean {
    return capture?.effectivePolicy.restrictionCodes.some(
        (code) => code === 'not_connected'
            || code === 'connection_error'
            || code === 'connection_revoked',
    ) ?? false;
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
