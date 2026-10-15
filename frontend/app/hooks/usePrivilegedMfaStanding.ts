"use client";

import { useCallback, useEffect, useState, useSyncExternalStore } from "react";

import type { PasskeyRegistrationRequirements } from "@/app/lib/types";
import {
    currentPrivilegedMfaRequirements,
    privilegedMfaConfinementReason,
    settlePrivilegedMfaStanding,
    subscribeClientRequestIdentityInvalidation,
    subscribePrivilegedMfaConfinement,
    type PrivilegedMfaConfinementReason,
    type PrivilegedMfaSettlement,
    type PrivilegedMfaSettleOutcome,
} from "@/app/lib/api";

/** The account's privileged-MFA standing as a surface sees it (#1534). */
export type PrivilegedMfaStandingState = {
    /** The newest decisive read, shared by every surface in the page, or null before one. */
    requirements: PasskeyRegistrationRequirements | null;
    /**
     * `loading` until a decisive read exists; `failed` when this surface's reads could not provide
     * one, or when the read right after a ceremony failed, so the ceremony's outcome can be retried.
     */
    status: "loading" | "ready" | "failed";
    /** Whether the browser is confined, from the server's refusal, a step URL or the newest read. */
    confined: boolean;
    /** Why the browser is confined, even when no read has decided yet, or null when it is not. */
    reason: PrivilegedMfaConfinementReason | null;
    /** Reads the standing again and settles confinement on it. */
    refresh: (settlement?: PrivilegedMfaSettlement) => Promise<PrivilegedMfaSettleOutcome>;
};

/**
 * Keeps a surface's view of the account's own privileged-MFA standing current (#1534). The standing
 * is read when the surface mounts, when its tab becomes visible again and whenever the browser's
 * request identity changes, and every read settles browser-local confinement. Only the newest
 * decisive read reaches the view, so a slow, older read never replaces a newer answer.
 * @returns the decisive requirements, their status, whether the browser is confined, and a refresh
 */
export function usePrivilegedMfaStanding(): PrivilegedMfaStandingState {
    const requirements = useSyncExternalStore(
        subscribePrivilegedMfaConfinement,
        currentPrivilegedMfaRequirements,
        () => null,
    );
    const reason = useSyncExternalStore(
        subscribePrivilegedMfaConfinement,
        privilegedMfaConfinementReason,
        () => null,
    );
    const [lastFailure, setLastFailure] = useState<"none" | "read" | "ceremony">("none");

    const refresh = useCallback(async (settlement: PrivilegedMfaSettlement = "check") => {
        const outcome = await settlePrivilegedMfaStanding(settlement);
        setLastFailure((previous) => {
            if (outcome !== "failed") return "none";
            return settlement === "ceremony" || previous === "ceremony" ? "ceremony" : "read";
        });
        return outcome;
    }, []);

    useEffect(() => {
        const recheck = () => {
            if (document.visibilityState !== "visible") return;
            void refresh("check");
        };
        recheck();
        document.addEventListener("visibilitychange", recheck);
        const unsubscribe = subscribeClientRequestIdentityInvalidation(recheck);
        return () => {
            document.removeEventListener("visibilitychange", recheck);
            unsubscribe();
        };
    }, [refresh]);

    const standing = requirements?.standing;
    return {
        requirements,
        status: requirements === null
            ? lastFailure === "none" ? "loading" : "failed"
            : lastFailure === "ceremony" ? "failed" : "ready",
        confined: reason !== null || standing === "ENROLLMENT_REQUIRED" || standing === "ATTESTATION_REQUIRED",
        reason,
        refresh,
    };
}
