"use client";

import { useCallback, useEffect, useState, useSyncExternalStore } from "react";

import type { PasskeyRegistrationRequirements } from "@/app/lib/types";
import {
    privilegedMfaConfinementReason,
    settlePrivilegedMfaStanding,
    subscribeClientRequestIdentityInvalidation,
    subscribePrivilegedMfaConfinement,
} from "@/app/lib/api";

/** The signed-in account's privileged-MFA standing as this surface last read it (#1534). */
export type PrivilegedMfaStandingState = {
    /** The last successful read, or null before one has completed. */
    requirements: PasskeyRegistrationRequirements | null;
    /** Whether the browser is confined, from the server's refusal, a step URL or the last read. */
    confined: boolean;
    /** Reads the standing again and settles confinement on it. */
    refresh: () => Promise<void>;
};

/**
 * Keeps a surface's view of the account's own privileged-MFA standing current (#1534). The standing
 * is read when the surface mounts, when its tab becomes visible again and whenever the browser's
 * request identity changes, and every read settles browser-local confinement. A failed read keeps
 * the previous view and any confinement.
 * @returns the latest requirements, whether the browser is confined, and a manual refresh
 */
export function usePrivilegedMfaStanding(): PrivilegedMfaStandingState {
    const [requirements, setRequirements] = useState<PasskeyRegistrationRequirements | null>(null);
    const confinedHere = useSyncExternalStore(
        subscribePrivilegedMfaConfinement,
        () => privilegedMfaConfinementReason() !== null,
        () => false,
    );

    const refresh = useCallback(async () => {
        const read = await settlePrivilegedMfaStanding("check");
        if (read) setRequirements(read);
    }, []);

    useEffect(() => {
        let active = true;
        const recheck = () => {
            if (document.visibilityState !== "visible") return;
            void settlePrivilegedMfaStanding("check").then((read) => {
                if (active && read) setRequirements(read);
            });
        };
        recheck();
        document.addEventListener("visibilitychange", recheck);
        const unsubscribe = subscribeClientRequestIdentityInvalidation(recheck);
        return () => {
            active = false;
            document.removeEventListener("visibilitychange", recheck);
            unsubscribe();
        };
    }, []);

    const standing = requirements?.standing;
    return {
        requirements,
        confined: confinedHere || standing === "ENROLLMENT_REQUIRED" || standing === "ATTESTATION_REQUIRED",
        refresh,
    };
}
