"use client";

import { useCallback, useEffect, useSyncExternalStore } from "react";
import { usePathname, useSearchParams } from "next/navigation";

import { currentPrivilegedMfaReadiness, observePrivilegedMfaConfinementDestination, privilegedMfaConfinementFor, subscribePrivilegedMfaConfinement, type PrivilegedMfaReadiness } from "@/app/lib/api";

/** Observes shared policy readiness without issuing another standing request. */
export function usePrivilegedMfaReadiness(): PrivilegedMfaReadiness {
    return useSyncExternalStore(subscribePrivilegedMfaConfinement, currentPrivilegedMfaReadiness, () => "loading");
}

/** Observes known passkey confinement without starting another standing request. */
export function usePrivilegedMfaConfinement(): boolean {
    const pathname = usePathname() ?? "";
    const mfa = useSearchParams()?.get("mfa") ?? null;
    const snapshot = useCallback(() => privilegedMfaConfinementFor(pathname, mfa), [pathname, mfa]);
    useEffect(() => { observePrivilegedMfaConfinementDestination(); }, [pathname, mfa]);
    return useSyncExternalStore(subscribePrivilegedMfaConfinement, snapshot, snapshot);
}
