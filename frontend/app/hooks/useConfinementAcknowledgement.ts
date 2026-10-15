"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { ApiError, PRIVILEGED_MFA_CONFINEMENT_ACKNOWLEDGEMENT_REQUIRED_CODE } from "@/app/lib/api";

/** A role change waiting for the actor to accept that it confines members (#1534). */
export type PendingConfinementAcknowledgement = {
    /** Identifies this question, so an answer can never reach a different change. */
    id: number;
    /** What the change does, in the actor's words, so they know which change they are accepting. */
    subject: string;
    /**
     * How many members the change leaves without a passkey approved in the organization, or null
     * when the refusal did not say.
     */
    affectedCount: number | null;
};

/** Runs role changes that may need the confinement acknowledgement, and the questions in between. */
export type ConfinementAcknowledgement = {
    /** The question awaiting an answer, or null when none is open. Later questions wait their turn. */
    pending: PendingConfinementAcknowledgement | null;
    /**
     * Runs the change unacknowledged. When the server answers that it would confine members, asks
     * the actor about this change and, once they agree, runs it again acknowledged. Resolves null
     * when they decline or the surface goes away. Every other failure is thrown unchanged.
     */
    run: <T>(subject: string, change: (acknowledgeConfinement: boolean) => Promise<T>) => Promise<T | null>;
    /** Answers the question with this id; an answer for any other question is ignored. */
    answer: (id: number, proceed: boolean) => void;
};

type Question = PendingConfinementAcknowledgement & { resolve: (proceed: boolean) => void };

/**
 * Asks before a role change confines members (#1534). The server refuses a change that would leave
 * members privileged in an organization without a passkey approved there, with how many, and the
 * change goes through only when it is resent acknowledged. Only that refusal opens a question; a
 * departure or any other `409` is the caller's to handle. Questions are asked one at a time, each
 * naming its change, and an answer carries the id of the question it answers, so confirming one
 * change can never acknowledge another. Leaving the page abandons every question.
 * @returns the open question and the runner that raises questions
 */
export function useConfinementAcknowledgement(): ConfinementAcknowledgement {
    const [pending, setPending] = useState<PendingConfinementAcknowledgement | null>(null);
    const queue = useRef<Question[]>([]);
    const nextId = useRef(0);
    const disposed = useRef(false);

    const showNext = useCallback(() => {
        const next = queue.current[0];
        setPending(next ? { id: next.id, subject: next.subject, affectedCount: next.affectedCount } : null);
    }, []);

    const answer = useCallback((id: number, proceed: boolean) => {
        const current = queue.current[0];
        if (!current || current.id !== id) return;
        queue.current.shift();
        current.resolve(proceed);
        showNext();
    }, [showNext]);

    const abandonAll = useCallback(() => {
        disposed.current = true;
        for (const question of queue.current.splice(0)) question.resolve(false);
    }, []);

    useEffect(() => {
        disposed.current = false;
        return abandonAll;
    }, [abandonAll]);

    const run = useCallback(async <T>(
        subject: string,
        change: (acknowledgeConfinement: boolean) => Promise<T>,
    ): Promise<T | null> => {
        try {
            return await change(false);
        } catch (err) {
            if (!isConfinementAcknowledgementRequired(err)) throw err;
            if (disposed.current) return null;
            nextId.current += 1;
            const id = nextId.current;
            const proceed = await new Promise<boolean>((resolve) => {
                queue.current.push({ id, subject, affectedCount: err.affectedCount ?? null, resolve });
                if (queue.current.length === 1) showNext();
            });
            if (!proceed) return null;
            return change(true);
        }
    }, [showNext]);

    return { pending, run, answer };
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
