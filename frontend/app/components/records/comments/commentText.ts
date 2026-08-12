import { defaultAltFromFileName } from '@/app/lib/managed-image';

/**
 * Flattens a comment body to plain text for canSubmit checks and collapsed
 * thread summaries: reference tokens and Markdown links reduce to their
 * labels, and emphasis, code, heading, and quote markers are stripped so the
 * summary never shows raw syntax.
 */
export function commentPlainText(value: string): string {
    return value
        .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
        .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
        .replace(/(\*\*|__)([^*_]+)\1/g, '$2')
        .replace(/(^|\s)[*_]([^*_]+)[*_](?=\s|$|[.,!?])/g, '$1$2')
        .replace(/`([^`]*)`/g, '$1')
        .replace(/^#{1,6}\s+/gm, '')
        .replace(/^>\s?/gm, '')
        .trim();
}

/**
 * Builds the Markdown image embed for an uploaded comment attachment. The
 * alt text derives from the file name and is stripped of characters that
 * would terminate the alt segment or inject extra syntax, keeping the embed
 * a single well-formed token.
 */
export function commentImageMarkdown(fileName: string, url: string): string {
    const alt = defaultAltFromFileName(fileName)
        .replace(/[[\]()\\`!\r\n]/gu, ' ')
        .replace(/\s+/gu, ' ')
        .trim();
    return `![${alt || 'image'}](${url})`;
}

/** Appends an image embed to a comment body on its own paragraph. */
export function appendCommentImage(value: string, imageMarkdown: string): string {
    const trimmed = value.replace(/\s+$/u, '');
    return trimmed.length > 0 ? `${trimmed}\n\n${imageMarkdown}` : imageMarkdown;
}

/** Wire shape of a persisted comment composer draft. */
export type CommentDraft = {
    content: string;
};

/** Narrow an unknown draft payload restored from session storage. */
export function isCommentDraft(value: unknown): value is CommentDraft {
    return (
        typeof value === 'object' &&
        value !== null &&
        'content' in value &&
        typeof (value as { content: unknown }).content === 'string'
    );
}
