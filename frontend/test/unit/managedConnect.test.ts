import { describe, expect, it } from 'vitest';

import {
    connectedAccountMode,
    formatManagedPairingRemaining,
    managedIdentityUnavailable,
    managedPairingActive,
    managedPairingFailure,
    managedPairingRemainingMs,
} from '@/app/lib/managedConnect';
import type { InstanceCapabilities } from '@/app/lib/types';

const BASE_CAPABILITIES: InstanceCapabilities = {
    sso: false,
    socialLogin: { google: false, microsoft: false },
    connectedAccounts: { google: true, microsoft: true },
    connectedCapture: { google: false, microsoft: false },
    mailManaged: false,
    businessCardScanning: false,
    businessCardImport: false,
    campaignDelivery: false,
};

describe('connectedAccountMode', () => {
    it('reports operator-supplied credentials when the instance reports no mode', () => {
        expect(connectedAccountMode(BASE_CAPABILITIES, 'google')).toBe('custom');
    });

    it('reports the mode the instance declared for each provider', () => {
        const capabilities: InstanceCapabilities = {
            ...BASE_CAPABILITIES,
            connectedAccountModes: { google: 'managed', microsoft: 'custom' },
        };
        expect(connectedAccountMode(capabilities, 'google')).toBe('managed');
        expect(connectedAccountMode(capabilities, 'microsoft')).toBe('custom');
    });
});

describe('managedIdentityUnavailable', () => {
    it('is false while the managed application is usable', () => {
        const capabilities: InstanceCapabilities = {
            ...BASE_CAPABILITIES,
            connectedAccountModes: { google: 'managed', microsoft: 'managed' },
        };
        expect(managedIdentityUnavailable(capabilities, 'google')).toBe(false);
    });

    it('is true when managed mode is selected but the provider stays unavailable', () => {
        const capabilities: InstanceCapabilities = {
            ...BASE_CAPABILITIES,
            connectedAccounts: { google: false, microsoft: true },
            connectedAccountModes: { google: 'managed', microsoft: 'managed' },
        };
        expect(managedIdentityUnavailable(capabilities, 'google')).toBe(true);
    });

    it('never blames managed mode for a disabled custom provider', () => {
        const capabilities: InstanceCapabilities = {
            ...BASE_CAPABILITIES,
            connectedAccounts: { google: false, microsoft: false },
        };
        expect(managedIdentityUnavailable(capabilities, 'google')).toBe(false);
    });
});

describe('managedPairingActive', () => {
    it('keeps polling only while the helper can still finish', () => {
        expect(managedPairingActive('pending')).toBe(true);
        expect(managedPairingActive('prepared')).toBe(true);
        expect(managedPairingActive('exchanging')).toBe(true);
        expect(managedPairingActive('completed')).toBe(false);
        expect(managedPairingActive('failed')).toBe(false);
        expect(managedPairingActive('none')).toBe(false);
    });
});

describe('managedPairingFailure', () => {
    it('keeps the machine codes the backend documents', () => {
        expect(managedPairingFailure('managed_identity_unavailable'))
            .toBe('managed_identity_unavailable');
        expect(managedPairingFailure('invalid_redirect_uri')).toBe('invalid_redirect_uri');
        expect(managedPairingFailure('no_offline_access')).toBe('no_offline_access');
        expect(managedPairingFailure('connection_conflict')).toBe('connection_conflict');
        expect(managedPairingFailure('retained_data_reset_required'))
            .toBe('retained_data_reset_required');
        expect(managedPairingFailure('superseded')).toBe('superseded');
    });

    it('falls back to the unknown outcome for anything it cannot name', () => {
        expect(managedPairingFailure('something_new')).toBe('unknown');
        expect(managedPairingFailure(null)).toBe('unknown');
        expect(managedPairingFailure(undefined)).toBe('unknown');
    });
});

describe('managedPairingRemainingMs', () => {
    it('measures the window that is left', () => {
        const now = Date.parse('2026-01-01T00:00:00.000Z');
        expect(managedPairingRemainingMs('2026-01-01T00:10:00.000Z', now)).toBe(600_000);
    });

    it('never reports a negative or unparseable window', () => {
        const now = Date.parse('2026-01-01T00:00:00.000Z');
        expect(managedPairingRemainingMs('2025-12-31T23:59:00.000Z', now)).toBe(0);
        expect(managedPairingRemainingMs('not-a-date', now)).toBe(0);
    });
});

describe('formatManagedPairingRemaining', () => {
    it('renders minutes and padded seconds, rounding the last second up', () => {
        expect(formatManagedPairingRemaining(600_000)).toBe('10:00');
        expect(formatManagedPairingRemaining(65_400)).toBe('1:06');
        expect(formatManagedPairingRemaining(1)).toBe('0:01');
        expect(formatManagedPairingRemaining(0)).toBe('0:00');
        expect(formatManagedPairingRemaining(-5_000)).toBe('0:00');
    });
});
