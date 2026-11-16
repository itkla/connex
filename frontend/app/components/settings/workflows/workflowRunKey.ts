const RUN_KEY = /^(canonical|legacy)-([1-9]\d*)$/;

/** How a run key is named to a person, without exposing the key itself. */
export type WorkflowRunReferenceParts = {
    /** Whether the run uses the alternate run-number sequence. */
    earlier: boolean;
    /** The run number a person can quote, or the raw key when it carries none. */
    number: string;
};

/**
 * Splits a run key into the parts its copy needs. The two execution histories are numbered
 * by separate sequences, so the two can collide on the same number and must never share a label.
 * The key itself is a support identifier and never product copy, so an unrecognised shape falls
 * back to the key rather than inventing a number.
 * @param runKey the server-issued run key
 * @returns which numbering sequence to use, and the number to show
 */
export function workflowRunReferenceParts(runKey: string): WorkflowRunReferenceParts {
    const match = RUN_KEY.exec(runKey);
    return match === null
        ? { earlier: false, number: runKey }
        : { earlier: match[1] === "legacy", number: match[2] };
}
