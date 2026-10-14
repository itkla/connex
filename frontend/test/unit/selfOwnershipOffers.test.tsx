import { act, useEffect } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { SelfOwnershipOffers } from '@/app/hooks/useSelfOwnershipOffers';
import type { OwnershipOffer } from '@/app/lib/types';
import en from '@/messages/en/account.json';
import { installInteractiveDocument } from '@/test/unit/helpers/interactiveDocument';

const mocks = vi.hoisted(() => ({
    refresh: vi.fn(),
    getMyOwnershipOffers: vi.fn<() => Promise<OwnershipOffer[]>>(),
    acceptOwnershipOffer: vi.fn<(offerId: string, acknowledgeConfinement?: boolean) => Promise<unknown>>(),
    declineOwnershipOffer: vi.fn<(offerId: string) => Promise<void>>(),
    toastSuccess: vi.fn(),
    toastError: vi.fn(),
    toastInfo: vi.fn(),
    showApiError: vi.fn(),
}));

vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh: mocks.refresh }) }));
vi.mock('next-intl', async () => {
    const { createTranslator } = await vi.importActual<typeof import('next-intl')>('next-intl');
    const translators = {
        AccountInvites: createTranslator({ locale: 'en', messages: en, namespace: 'AccountInvites' }),
        PasskeyStepUp: createTranslator({ locale: 'en', messages: en, namespace: 'PasskeyStepUp' }),
    };
    return {
        useTranslations: (namespace: keyof typeof translators) => translators[namespace],
    };
});
vi.mock('@/app/lib/api', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@/app/lib/api')>()),
    getMyOwnershipOffers: mocks.getMyOwnershipOffers,
    acceptOwnershipOffer: mocks.acceptOwnershipOffer,
    declineOwnershipOffer: mocks.declineOwnershipOffer,
}));
vi.mock('@/app/lib/toast', () => ({
    toastSuccess: mocks.toastSuccess,
    toastError: mocks.toastError,
    toastInfo: mocks.toastInfo,
}));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => mocks.showApiError }));

function offer(id: string, state: OwnershipOffer['state'] = 'OPEN'): OwnershipOffer {
    return {
        id,
        scopeKind: 'ORGANIZATION',
        orgId: 3,
        workspaceId: null,
        offerorUserId: 7,
        offereeUserId: 11,
        state,
        createdAt: '2026-10-14T00:00:00Z',
        expiresAt: '2026-10-21T00:00:00Z',
        closedAt: null,
        closedByUserId: null,
        closureReason: null,
        scopeName: 'Acme',
        offerorDisplayName: 'Riley',
        offereeDisplayName: 'Dana',
        offereeEmail: 'dana@example.com',
    };
}

const mountedRoots: Array<{ unmount: () => void }> = [];

async function mountOffers() {
    const { useSelfOwnershipOffers } = await import('@/app/hooks/useSelfOwnershipOffers');
    const { createRoot } = await import('react-dom/client');
    const installed = installInteractiveDocument();
    const root = createRoot(installed.container);
    mountedRoots.push(root);
    let latest: SelfOwnershipOffers | null = null;
    function Probe() {
        const value = useSelfOwnershipOffers();
        useEffect(() => {
            latest = value;
        });
        return null;
    }
    await act(async () => root.render(<Probe />));
    return {
        current(): SelfOwnershipOffers {
            if (latest === null) throw new Error('useSelfOwnershipOffers did not render');
            return latest;
        },
    };
}

beforeEach(() => {
    for (const mock of Object.values(mocks)) mock.mockReset();
});

afterEach(async () => {
    for (const root of mountedRoots.splice(0)) await act(async () => root.unmount());
    vi.unstubAllGlobals();
});

describe('self ownership offers', () => {
    it('lists only open offers', async () => {
        mocks.getMyOwnershipOffers.mockResolvedValueOnce([offer('41'), offer('42', 'ACCEPTED')]);
        const mounted = await mountOffers();
        expect(mounted.current().offers.map((open) => open.id)).toEqual(['41']);
    });

    it('accepts an offer, removes it and refreshes the page', async () => {
        mocks.getMyOwnershipOffers.mockResolvedValueOnce([offer('41')]);
        mocks.acceptOwnershipOffer.mockResolvedValueOnce({});
        const mounted = await mountOffers();

        await act(async () => mounted.current().accept(mounted.current().offers[0]));

        expect(mocks.acceptOwnershipOffer).toHaveBeenCalledWith('41', false);
        expect(mounted.current().offers).toEqual([]);
        expect(mocks.toastSuccess).toHaveBeenCalledTimes(1);
        expect(mocks.refresh).toHaveBeenCalledTimes(1);
    });

    it('asks for the confinement acknowledgement and retries with it', async () => {
        const { ApiError } = await import('@/app/lib/api');
        mocks.getMyOwnershipOffers.mockResolvedValueOnce([offer('41')]);
        mocks.acceptOwnershipOffer
            .mockRejectedValueOnce(new ApiError('confinement', 409, 'PRIVILEGED_MFA_CONFINEMENT_ACKNOWLEDGEMENT_REQUIRED'))
            .mockResolvedValueOnce({});
        const mounted = await mountOffers();
        const pending = mounted.current().offers[0];

        await act(async () => mounted.current().accept(pending));
        expect(mounted.current().confinementOffer?.id).toBe('41');
        expect(mounted.current().offers.map((open) => open.id)).toEqual(['41']);
        expect(mocks.showApiError).not.toHaveBeenCalled();

        await act(async () => mounted.current().accept(pending, true));
        expect(mocks.acceptOwnershipOffer).toHaveBeenLastCalledWith('41', true);
        expect(mounted.current().confinementOffer).toBeNull();
        expect(mounted.current().offers).toEqual([]);
    });

    it('declines an offer without refreshing', async () => {
        mocks.getMyOwnershipOffers.mockResolvedValueOnce([offer('41')]);
        mocks.declineOwnershipOffer.mockResolvedValueOnce();
        const mounted = await mountOffers();

        await act(async () => mounted.current().decline(mounted.current().offers[0]));

        expect(mocks.declineOwnershipOffer).toHaveBeenCalledWith('41');
        expect(mounted.current().offers).toEqual([]);
        expect(mocks.refresh).not.toHaveBeenCalled();
    });

    it('reports a failed load instead of showing nothing', async () => {
        mocks.getMyOwnershipOffers.mockRejectedValueOnce(new Error('backend unavailable'));
        const mounted = await mountOffers();
        expect(mounted.current().offers).toEqual([]);
        expect(mocks.toastError).toHaveBeenCalledTimes(1);
    });
});
