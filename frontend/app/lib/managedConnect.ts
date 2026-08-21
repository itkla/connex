import type {
    ConnectedAccountMode,
    ConnectedAccountProvider,
    InstanceCapabilities,
    ManagedPairingStatusValue,
} from '@/app/lib/types';

/** Same-origin path that serves the local connect helper script for download. */
export const MANAGED_HELPER_PATH = '/api/account/connections/native/helper';

/** Operator guide for the Connex-managed OAuth identity, its threat model, and its runbook. */
export const MANAGED_OAUTH_DOC_URL =
    'https://github.com/itkla/connex/blob/main/docs/CONNECTED_ACCOUNTS_MANAGED_OAUTH.md';

/** How often the browser asks the instance whether the local helper has finished its half. */
export const MANAGED_PAIRING_POLL_INTERVAL_MS = 2000;

/**
 * Terminal outcomes a managed pairing can end in. Every value except `expired` and `unknown` is a
 * machine code the backend returns; `expired` is derived in the browser when the pairing window
 * runs out, and `unknown` is the fail-closed bucket for a code this build does not recognize.
 */
export const MANAGED_PAIRING_FAILURES = [
    'managed_identity_unavailable',
    'invalid_redirect_uri',
    'state',
    'denied',
    'exchange',
    'no_offline_access',
    'connection_conflict',
    'retained_data_reset_required',
    'superseded',
    'expired',
    'unknown',
] as const;

export type ManagedPairingFailure = (typeof MANAGED_PAIRING_FAILURES)[number];

/** Reports the credential mode an instance uses for one provider, defaulting to operator-supplied. */
export function connectedAccountMode(
    capabilities: InstanceCapabilities,
    provider: ConnectedAccountProvider,
): ConnectedAccountMode {
    return capabilities.connectedAccountModes?.[provider] ?? 'custom';
}

/**
 * Reports the honest managed-mode gap: the instance is configured to use the Connex-owned OAuth
 * application, but that application is not usable in this build, so no connect attempt can succeed.
 */
export function managedIdentityUnavailable(
    capabilities: InstanceCapabilities,
    provider: ConnectedAccountProvider,
): boolean {
    return connectedAccountMode(capabilities, provider) === 'managed'
        && !capabilities.connectedAccounts[provider];
}

/** The statuses that mean the local helper is still working, so polling should continue. */
export type ManagedPairingActiveStatus = Extract<
    ManagedPairingStatusValue,
    'pending' | 'prepared' | 'exchanging'
>;

/** Reports whether a pairing is still in flight and therefore still worth polling. */
export function managedPairingActive(
    status: ManagedPairingStatusValue,
): status is ManagedPairingActiveStatus {
    return status === 'pending' || status === 'prepared' || status === 'exchanging';
}

/** Maps a backend failure code onto a known outcome, so an unrecognized code still renders honestly. */
export function managedPairingFailure(code: string | null | undefined): ManagedPairingFailure {
    return MANAGED_PAIRING_FAILURES.find((failure) => failure === code) ?? 'unknown';
}

/** Milliseconds left before the pairing code stops being claimable, never negative. */
export function managedPairingRemainingMs(expiresAt: string, now: number): number {
    const expiry = Date.parse(expiresAt);
    if (Number.isNaN(expiry)) return 0;
    return Math.max(0, expiry - now);
}

/** Formats a remaining pairing window as `m:ss`, rounding up so the last second is still shown. */
export function formatManagedPairingRemaining(remainingMs: number): string {
    const totalSeconds = Math.ceil(Math.max(0, remainingMs) / 1000);
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    return `${minutes}:${String(seconds).padStart(2, '0')}`;
}
