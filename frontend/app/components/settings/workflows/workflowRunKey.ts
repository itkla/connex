const RUN_KEY = /^(?:canonical|legacy)-([1-9]\d*)$/;

/**
 * The run number a run key carries, for copy that names a run to a person. The key itself is
 * a support identifier and never product copy, so an unrecognised shape falls back to the key
 * rather than inventing a number.
 * @param runKey the server-issued run key
 * @returns the run number, or the key when it carries none
 */
export function workflowRunNumber(runKey: string): string {
    return RUN_KEY.exec(runKey)?.[1] ?? runKey;
}
