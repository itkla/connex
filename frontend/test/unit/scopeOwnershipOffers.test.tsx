import { act, useEffect } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useScopeOwnershipOffers, type ScopeOwnershipOffers } from '@/app/hooks/useScopeOwnershipOffers';
import type { OwnershipOffer } from '@/app/lib/types';
import { installInteractiveDocument } from '@/test/unit/helpers/interactiveDocument';

const load = vi.fn<(scopeId: number) => Promise<OwnershipOffer[]>>();
const onError = vi.fn<(error: unknown) => void>();

const scope = { id: 5, enabled: true };

function OffersProbe({ onValue }: { onValue: (value: ScopeOwnershipOffers) => void }) {
    const value = useScopeOwnershipOffers(scope.id, scope.enabled, load, onError);
    useEffect(() => {
        onValue(value);
    });
    return null;
}

const mountedRoots: Array<{ unmount: () => void }> = [];

async function mountOffers() {
    const { createRoot } = await import('react-dom/client');
    const installed = installInteractiveDocument();
    const root = createRoot(installed.container);
    mountedRoots.push(root);
    let latest: ScopeOwnershipOffers | null = null;
    const tree = () => <OffersProbe onValue={(value) => { latest = value; }} />;
    await act(async () => root.render(tree()));
    return {
        current(): ScopeOwnershipOffers {
            if (latest === null) throw new Error('useScopeOwnershipOffers did not render');
            return latest;
        },
        async rerender(): Promise<void> {
            await act(async () => root.render(tree()));
        },
    };
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void; reject: (reason: Error) => void } {
    let resolver: ((value: T) => void) | null = null;
    let rejecter: ((reason: Error) => void) | null = null;
    const promise = new Promise<T>((resolve, reject) => {
        resolver = resolve;
        rejecter = reject;
    });
    return {
        promise,
        resolve(value) {
            if (resolver === null) throw new Error('Deferred promise was not initialized');
            resolver(value);
        },
        reject(reason) {
            if (rejecter === null) throw new Error('Deferred promise was not initialized');
            rejecter(reason);
        },
    };
}

function offer(id: string, offereeUserId: number, state: OwnershipOffer['state'] = 'OPEN'): OwnershipOffer {
    return {
        id,
        scopeKind: 'WORKSPACE',
        orgId: 3,
        workspaceId: scope.id,
        offerorUserId: 7,
        offereeUserId,
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

function openIds(value: ScopeOwnershipOffers): string[] {
    return value.openOffers.map((open) => open.id);
}

beforeEach(() => {
    scope.id = 5;
    scope.enabled = true;
    load.mockReset();
    onError.mockReset();
});

afterEach(async () => {
    for (const root of mountedRoots.splice(0)) await act(async () => root.unmount());
    vi.unstubAllGlobals();
});

describe('scope ownership offers', () => {
    it('keeps a just-created offer when the first list answers after it', async () => {
        const firstRead = deferred<OwnershipOffer[]>();
        const reconcile = deferred<OwnershipOffer[]>();
        load.mockReturnValueOnce(firstRead.promise).mockReturnValueOnce(reconcile.promise);
        const mounted = await mountOffers();

        await act(async () => mounted.current().remember(offer('41', 11)));
        expect(openIds(mounted.current())).toEqual(['41']);
        expect(load, 'a local change re-reads the scope').toHaveBeenCalledTimes(2);

        await act(async () => firstRead.resolve([]));
        expect(openIds(mounted.current()), 'the superseded first list must not erase the offer').toEqual(['41']);

        await act(async () => reconcile.resolve([offer('41', 11)]));
        expect(openIds(mounted.current())).toEqual(['41']);
        expect(mounted.current().openOfferByOfferee.get(11)?.id).toBe('41');
    });

    it('drops an offer the server closed when a member is removed', async () => {
        load.mockResolvedValueOnce([offer('41', 11), offer('42', 12)]);
        const mounted = await mountOffers();
        expect(openIds(mounted.current())).toEqual(['41', '42']);

        load.mockResolvedValueOnce([offer('42', 12)]);
        await act(async () => mounted.current().refresh());
        expect(openIds(mounted.current())).toEqual(['42']);
        expect(mounted.current().openOfferByOfferee.has(11)).toBe(false);
    });

    it('hides a withdrawn offer at once and keeps it hidden against an earlier read', async () => {
        load.mockResolvedValueOnce([offer('41', 11)]);
        const mounted = await mountOffers();
        const earlierRead = deferred<OwnershipOffer[]>();
        const reconcile = deferred<OwnershipOffer[]>();
        load.mockReturnValueOnce(earlierRead.promise).mockReturnValueOnce(reconcile.promise);

        await act(async () => mounted.current().refresh());
        await act(async () => mounted.current().forget('41'));
        expect(openIds(mounted.current())).toEqual([]);

        await act(async () => earlierRead.resolve([offer('41', 11)]));
        expect(openIds(mounted.current()), 'a read issued before the withdrawal must not revive it').toEqual([]);

        await act(async () => reconcile.resolve([]));
        expect(openIds(mounted.current())).toEqual([]);
    });

    it('keeps the last list when a re-read fails and reports the failure once', async () => {
        load.mockResolvedValueOnce([offer('41', 11)]);
        const mounted = await mountOffers();
        const failedRead = deferred<OwnershipOffer[]>();
        load.mockReturnValueOnce(failedRead.promise);
        const failure = new Error('backend unavailable');

        await act(async () => mounted.current().refresh());
        await act(async () => failedRead.reject(failure));
        expect(openIds(mounted.current())).toEqual(['41']);
        expect(onError).toHaveBeenCalledTimes(1);
        expect(onError).toHaveBeenCalledWith(failure);
    });

    it('stays silent when a superseded or previous-scope read fails', async () => {
        const supersededRead = deferred<OwnershipOffer[]>();
        const previousScopeRead = deferred<OwnershipOffer[]>();
        const currentScopeRead = deferred<OwnershipOffer[]>();
        load.mockReturnValueOnce(supersededRead.promise).mockReturnValueOnce(previousScopeRead.promise)
            .mockReturnValueOnce(currentScopeRead.promise);
        const mounted = await mountOffers();

        await act(async () => mounted.current().refresh());
        await act(async () => supersededRead.reject(new Error('superseded')));
        expect(onError, 'a superseded read must not report').not.toHaveBeenCalled();

        scope.id = 6;
        await mounted.rerender();
        await act(async () => previousScopeRead.reject(new Error('previous scope')));
        expect(onError, 'a previous scope read must not report').not.toHaveBeenCalled();

        await act(async () => currentScopeRead.resolve([offer('51', 13)]));
        expect(openIds(mounted.current())).toEqual(['51']);
        expect(onError).not.toHaveBeenCalled();
    });

    it('ignores the previous scope answering after a switch', async () => {
        const previousScope = deferred<OwnershipOffer[]>();
        const currentScope = deferred<OwnershipOffer[]>();
        load.mockReturnValueOnce(previousScope.promise).mockReturnValueOnce(currentScope.promise);
        const mounted = await mountOffers();

        scope.id = 6;
        await mounted.rerender();
        expect(load).toHaveBeenLastCalledWith(6);

        await act(async () => previousScope.resolve([offer('41', 11)]));
        expect(openIds(mounted.current())).toEqual([]);

        await act(async () => currentScope.resolve([offer('51', 13)]));
        expect(openIds(mounted.current())).toEqual(['51']);
    });

    it('ignores a change that finishes after the scope switched', async () => {
        load.mockResolvedValueOnce([offer('41', 11)]);
        const mounted = await mountOffers();
        const previousScope = mounted.current();
        const currentScope = deferred<OwnershipOffer[]>();
        load.mockReturnValueOnce(currentScope.promise);

        scope.id = 6;
        await mounted.rerender();
        expect(load).toHaveBeenCalledTimes(2);

        await act(async () => previousScope.forget('41'));
        await act(async () => previousScope.remember(offer('42', 12)));
        await act(async () => previousScope.refresh());
        expect(load, 'a late change from the previous scope must not read it again').toHaveBeenCalledTimes(2);

        await act(async () => currentScope.resolve([offer('51', 13)]));
        expect(openIds(mounted.current())).toEqual(['51']);
    });

    it('never reads for a caller who may not see the offers', async () => {
        scope.enabled = false;
        const mounted = await mountOffers();
        await act(async () => mounted.current().refresh());
        expect(load).not.toHaveBeenCalled();
        expect(openIds(mounted.current())).toEqual([]);
    });

    it('exposes only open offers', async () => {
        load.mockResolvedValueOnce([offer('41', 11), offer('42', 12, 'ACCEPTED'), offer('43', 13, 'EXPIRED')]);
        const mounted = await mountOffers();
        expect(openIds(mounted.current())).toEqual(['41']);
    });
});
