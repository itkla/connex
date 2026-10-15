"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { ApiError, PRIVILEGED_MFA_CONFINEMENT_ACKNOWLEDGEMENT_REQUIRED_CODE } from "@/app/lib/api";

/** A role change waiting for the actor to accept that it confines members (#1534). */
export type PendingConfinementAcknowledgement = {
    /**
     * How many members the change leaves without a passkey approved in the organization, or null
     * when the refusal did not say.
     */
    affectedCount: number | null;
};

/** Runs role changes that may need the confinement acknowledgement, and the question in between. */
export type ConfinementAcknowledgement = {
    /** The question awaiting an answer, or null when none is open. */
    pending: PendingConfinementAcknowledgement | null;
    /**
     * Runs the change unacknowledged. When the server answers that it would confine members, asks
     * the actor and, once they agree, runs it again acknowledged. Resolves null when they decline.
     * Every other failure is thrown unchanged.
     */
    run: <T>(change: (acknowledgeConfinement: boolean) => Promise<T>) => Promise<T | null>;
    /** Answers the open question: true proceeds with the change, false abandons it. */
    answer: (proceed: boolean) => void;
};

/**
 * Asks before a role change confines members (#1534). The server refuses a change that would leave
 * members privileged in an organization without a passkey approved there, with how many, and the
 * change goes through only when it is resent acknowledged. Only that refusal opens the question; a
 * departure or any other `409` is the caller's to handle. Leaving the page abandons an open question.
 * @returns the pending question and the runner that raises it
 */
export function useConfinementAcknowledgement(): ConfinementAcknowledgement {
    const [pending, setPending] = useState<PendingConfinementAcknowledgement | null>(null);
    const resolver = useRef<((proceed: boolean) => void) | null>(null);

    const settle = useCallback((proceed: boolean) => {
        const resolve = resolver.current;
        resolver.current = null;
        setPending(null);
        resolve?.(proceed);
    }, []);

    useEffect(() => () => settle(false), [settle]);

    const run = useCallback(async <T>(change: (acknowledgeConfinement: boolean) => Promise<T>): Promise<T | null> => {
        try {
            return await change(false);
        } catch (err) {
            if (!isConfinementAcknowledgementRequired(err)) throw err;
            const affectedCount = err.affectedCount ?? null;
            resolver.current?.(false);
            const proceed = await new Promise<boolean>((resolve) => {
                resolver.current = resolve;
                setPending({ affectedCount });
            });
            if (!proceed) return null;
            return change(true);
        }
    }, []);

    return { pending, run, answer: settle };
}

/**
 * Thrown by a form whose dialog must stay open when the actor declines the acknowledgement, so the
 * form can be adjusted instead of closing as though the change were saved.
 */
export class ConfinementAcknowledgementDeclined extends Error {
    constructor() {
        super("The role change was abandoned before it confined anyone");
        this.name = "ConfinementAcknowledgementDeclined";
    }
}

/** Whether a failed change is the refusal that only the confinement acknowledgement lifts. */
function isConfinementAcknowledgementRequired(err: unknown): err is ApiError {
    return err instanceof ApiError && err.code === PRIVILEGED_MFA_CONFINEMENT_ACKNOWLEDGEMENT_REQUIRED_CODE;
}
