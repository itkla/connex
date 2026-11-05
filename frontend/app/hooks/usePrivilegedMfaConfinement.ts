"use client";

import { useCallback, useEffect, useSyncExternalStore } from "react";
import { usePathname, useSearchParams } from "next/navigation";

import { observePrivilegedMfaConfinementDestination, privilegedMfaConfinementFor, subscribePrivilegedMfaConfinement } from "@/app/lib/api";

/** Observes known passkey confinement without starting another standing request. */
export function usePrivilegedMfaConfinement(): boolean {
    const pathname = usePathname() ?? "";
    const mfa = useSearchParams()?.get("mfa") ?? null;
    const snapshot = useCallback(() => privilegedMfaConfinementFor(pathname, mfa), [pathname, mfa]);
    useEffect(() => { observePrivilegedMfaConfinementDestination(); }, [pathname, mfa]);
    return useSyncExternalStore(subscribePrivilegedMfaConfinement, snapshot, snapshot);
}
