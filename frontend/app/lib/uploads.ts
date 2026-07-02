import path from "path";

const DEFAULT_MAX_BYTES = 25 * 1024 * 1024;

/** Maximum accepted upload size in bytes, overridable via `CONNEX_MAX_UPLOAD_BYTES`. */
export const MAX_BYTES = Number(process.env.CONNEX_MAX_UPLOAD_BYTES) || DEFAULT_MAX_BYTES;

const IMAGE_CONTENT_TYPES = new Set(["image/png", "image/jpeg", "image/webp", "image/gif", "image/avif"]);
const IMAGE_EXTENSIONS = new Set([".png", ".jpg", ".jpeg", ".webp", ".gif", ".avif"]);

/** An HTTP error describing why an upload was rejected. */
export type UploadRejection = { status: number; error: string };

/**
 * Reduces an arbitrary, possibly attacker-controlled value to a single safe path
 * segment: drops any directory components, replaces unsafe characters, strips
 * leading dots, and caps length. The result can never contain a path separator,
 * so it cannot traverse out of its target directory.
 */
export function safeFileName(value: string): string {
    const base = value.split(/[\\/]/).pop() ?? "file";
    const cleaned = base.replace(/[^a-zA-Z0-9._-]/g, "_").replace(/^\.+/, "");
    return cleaned.slice(0, 200) || "file";
}

/**
 * Resolves `fileName` within `targetDir` and confirms the result stays inside
 * that directory. Returns the absolute path, or `null` if it would escape.
 */
export function resolveContained(targetDir: string, fileName: string): string | null {
    const resolved = path.resolve(targetDir, fileName);
    if (resolved !== targetDir && !resolved.startsWith(targetDir + path.sep)) {
        return null;
    }
    return resolved;
}

const RENDERABLE_EXTENSIONS = new Set([
    ".html", ".htm", ".xhtml", ".shtml", ".svg", ".svgz", ".xml", ".xsl", ".xslt",
    ".js", ".mjs", ".cjs", ".htaccess",
]);
const RENDERABLE_CONTENT_TYPES = new Set([
    "text/html", "application/xhtml+xml", "image/svg+xml",
    "text/xml", "application/xml",
    "text/javascript", "application/javascript", "application/ecmascript", "text/ecmascript",
]);

/**
 * Rejects uploads whose type a browser can render or execute inline (HTML, SVG,
 * XML, JavaScript). Such files are safe as opaque downloads but become stored
 * XSS if ever served inline from the app origin, so the generic attachment route
 * refuses them (documents, images, PDFs, archives, etc. remain allowed). Returns
 * a rejection describing the HTTP error, or `null` when acceptable.
 */
export function rejectRenderableUpload(file: File): UploadRejection | null {
    if (file.size > MAX_BYTES) {
        return {
            status: 413,
            error: `File exceeds the maximum size of ${Math.floor(MAX_BYTES / (1024 * 1024))}MB`,
        };
    }
    const extension = path.extname(file.name).toLowerCase();
    const contentType = file.type.toLowerCase().split(";")[0].trim();
    if (RENDERABLE_EXTENSIONS.has(extension) || RENDERABLE_CONTENT_TYPES.has(contentType)) {
        return { status: 415, error: "This file type can't be uploaded as an attachment" };
    }
    return null;
}

/**
 * Validates an uploaded image against the size limit and the content-type /
 * extension allowlist (PNG, JPEG, WebP, GIF, AVIF). Returns a rejection
 * describing the HTTP error, or `null` when the upload is acceptable.
 */
export function rejectInvalidImage(file: File): UploadRejection | null {
    if (file.size > MAX_BYTES) {
        return {
            status: 413,
            error: `File exceeds the maximum size of ${Math.floor(MAX_BYTES / (1024 * 1024))}MB`,
        };
    }
    if (!IMAGE_CONTENT_TYPES.has(file.type.toLowerCase()) || !IMAGE_EXTENSIONS.has(path.extname(file.name).toLowerCase())) {
        return { status: 415, error: "Only PNG, JPEG, WebP, GIF, or AVIF images are allowed" };
    }
    return null;
}
