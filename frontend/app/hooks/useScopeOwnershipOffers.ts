"use client";

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";

import type { OwnershipOffer } from "@/app/lib/types";

/** One scope's last applied offer list, so a scope switch never shows another scope's offers. */
type LoadedOffers = { scopeId: number | null; offers: OwnershipOffer[] };

const NO_OFFERS: OwnershipOffer[] = [];

/** A scope's open ownership offers and the changes that keep them in step with the server. */
export type ScopeOwnershipOffers = {
    /** Open offers of the current scope; empty for non-owners and until the first read lands. */
    openOffers: OwnershipOffer[];
    /** The open offer addressed to each offeree. */
    openOfferByOfferee: ReadonlyMap<number, OwnershipOffer>;
    /** Shows an offer the caller just created, then re-reads the scope. */
    remember: (offer: OwnershipOffer) => void;
    /** Hides an offer the caller just closed, then re-reads the scope. */
    forget: (offerId: string) => void;
    /** Re-reads after a change the server may have applied to offers as a side effect. */
    refresh: () => void;
};

/**
 * Reads a scope's pending ownership offers while the caller may see them. Every read is
 * sequenced: a newer read supersedes any response still in flight, so a slow list can never
 * overwrite a just-created or just-withdrawn offer. Local changes show at once and are followed by
 * a fresh read, which also drops offers the server closed as a side effect, such as an offeree's
 * removal. Reads and changes are fenced to the scope on screen, so a mutation that finishes after
 * a scope switch neither supersedes nor replaces the new scope's list. A failed read keeps the
 * last list instead of blanking it and is reported once, so a failure never looks like an empty list.
 * @param scopeId the workspace or organization id
 * @param enabled whether the caller may read the scope's offers
 * @param load the scope's offer list request
 * @param onError reports a failed read of the scope on screen; superseded reads stay silent
 * @returns the scope's open offers and their mutations
 */
export function useScopeOwnershipOffers(
    scopeId: number | null | undefined,
    enabled: boolean,
    load: (scopeId: number) => Promise<OwnershipOffer[]>,
    onError: (error: unknown) => void,
): ScopeOwnershipOffers {
    const [loaded, setLoaded] = useState<LoadedOffers>({ scopeId: null, offers: NO_OFFERS });
    const sequence = useRef(0);
    const activeScope = useRef<number | null>(null);
    const reportError = useRef(onError);
    useLayoutEffect(() => { reportError.current = onError; });

    const read = useCallback((id: number) => {
        if (activeScope.current !== id) return;
        const request = ++sequence.current;
        load(id).then((offers) => {
            if (activeScope.current === id && sequence.current === request) setLoaded({ scopeId: id, offers });
        }, (error: unknown) => {
            if (activeScope.current === id && sequence.current === request) reportError.current(error);
        });
    }, [load]);

    useEffect(() => {
        if (!scopeId || !enabled) return;
        activeScope.current = scopeId;
        read(scopeId);
        return () => {
            activeScope.current = null;
        };
    }, [scopeId, enabled, read]);

    const refresh = useCallback(() => {
        if (scopeId && enabled) read(scopeId);
    }, [scopeId, enabled, read]);

    const remember = useCallback((offer: OwnershipOffer) => {
        if (!scopeId || activeScope.current !== scopeId) return;
        setLoaded((current) => ({
            scopeId,
            offers: [
                ...(current.scopeId === scopeId ? current.offers : NO_OFFERS)
                    .filter((existing) => existing.offereeUserId !== offer.offereeUserId),
                offer,
            ],
        }));
        refresh();
    }, [scopeId, refresh]);

    const forget = useCallback((offerId: string) => {
        if (!scopeId || activeScope.current !== scopeId) return;
        setLoaded((current) => current.scopeId === scopeId
            ? { scopeId, offers: current.offers.filter((existing) => existing.id !== offerId) }
            : current);
        refresh();
    }, [scopeId, refresh]);

    const openOffers = useMemo(
        () => (enabled && loaded.scopeId === scopeId ? loaded.offers : NO_OFFERS)
            .filter((offer) => offer.state === "OPEN"),
        [enabled, loaded, scopeId],
    );
    const openOfferByOfferee = useMemo(
        () => new Map(openOffers.map((offer) => [offer.offereeUserId, offer] as const)),
        [openOffers],
    );

    return { openOffers, openOfferByOfferee, remember, forget, refresh };
}
