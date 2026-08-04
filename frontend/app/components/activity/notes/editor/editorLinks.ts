const ALLOWED_LINK_PROTOCOLS = new Set(['http:', 'https:', 'mailto:', 'tel:']);

/** Normalizes editor-entered links while rejecting executable or unsupported protocols. */
export function normalizeEditorLinkHref(value: string): string | null {
    const href = value.trim();
    if (!href || /\s/.test(href)) return null;
    if (href.startsWith('//')) return null;
    if (href.startsWith('/')) return href;
    if (href.startsWith('#')) return href;

    const candidate = /^[a-z][a-z\d+.-]*:/i.test(href) ? href : `https://${href}`;
    try {
        const url = new URL(candidate);
        return ALLOWED_LINK_PROTOCOLS.has(url.protocol) ? candidate : null;
    } catch {
        return null;
    }
}
