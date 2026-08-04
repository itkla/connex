const MAX_IMAGE_SOURCE_LENGTH = 2_048;
const IMAGE_SOURCE_CONTROL_CHARACTERS = /[\u0000-\u001f\u007f\\]/;
const RELATIVE_IMAGE_ORIGIN = "https://connex.invalid";

/** Returns a safe HTTPS or application-relative image source for persisted note Markdown. */
export function normalizeNoteImageSource(value: string): string | null {
    const source = value.trim();
    if (!source || source.length > MAX_IMAGE_SOURCE_LENGTH || IMAGE_SOURCE_CONTROL_CHARACTERS.test(source)) {
        return null;
    }
    if (source.startsWith("/")) {
        if (source.startsWith("//")) return null;
        try {
            const parsed = new URL(source, RELATIVE_IMAGE_ORIGIN);
            if (parsed.origin !== RELATIVE_IMAGE_ORIGIN) return null;
            return `${parsed.pathname}${parsed.search}${parsed.hash}`;
        } catch {
            return null;
        }
    }
    try {
        const parsed = new URL(source);
        if (parsed.protocol !== "https:" || parsed.username || parsed.password) return null;
        return parsed.href;
    } catch {
        return null;
    }
}
