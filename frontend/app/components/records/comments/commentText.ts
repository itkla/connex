/**
 * Flattens the `[Label](type:id)` reference tokens of a comment body to their
 * plain labels, for canSubmit checks and collapsed-thread summaries.
 */
export function commentPlainText(value: string): string {
    return value
        .replace(/\[([^\]]*)\]\((?:user|person|deal|company|note|file|task|activity):\d+\)/g, '$1')
        .trim();
}
