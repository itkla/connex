/**
 * Shared Markdown extension options for the note WYSIWYG editor. Kept as a
 * single export so paste/sanitize acceptance tests can assert the contract the
 * live editor ships without remounting TipTap in jsdom.
 */
export const NOTE_EDITOR_MARKDOWN_OPTIONS = {
    html: false,
    breaks: true,
    linkify: false,
    transformPastedText: true,
    transformCopiedText: true,
} as const;
