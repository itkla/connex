"use client";

import { useCallback, useLayoutEffect, useRef } from "react";

import {
    currentAccountBinding,
    isCurrentBinding,
    subscribeAccountGeneration,
    type AccountBinding,
} from "@/app/lib/browserAccountStorage";

/**
 * Binds a mounted cache to the confirmed account and drops it on every revocation. A revocation
 * that keeps the same account re-binds the cache, so a recoverable transition leaves the tab able
 * to persist again; a revocation that confirms another account revokes it for good.
 * @param onInvalidate drops the cached data the caller acquired under the revoked binding
 * @returns a predicate a writer calls before persisting
 */
export function useAccountStorageGeneration(onInvalidate: () => void): () => boolean {
    const binding = useRef<AccountBinding | null>(null);
    const invalidate = useRef(onInvalidate);
    useLayoutEffect(() => { invalidate.current = onInvalidate; });
    useLayoutEffect(() => {
        binding.current = currentAccountBinding();
        return subscribeAccountGeneration((next) => {
            const bound = binding.current;
            binding.current = bound !== null && bound.identity === next.identity ? next : null;
            invalidate.current();
        });
    }, []);
    return useCallback(() => binding.current !== null && isCurrentBinding(binding.current), []);
}
