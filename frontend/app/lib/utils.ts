// transplanted from /me 

import { type UploadedFile } from '@/app/lib/types';

export function formatShortDate(value: string | undefined, locale: string) {
    if (!value) {
        return '—';
    }

    const date = new Date(value);

    if (Number.isNaN(date.getTime())) return value;

    return new Intl.DateTimeFormat(locale, {
        month: 'short',
        day: 'numeric',
    }).format(date);
}

/**
 * Persists the user's locale preference.
 * @param locale - the locale code to persist (e.g. "en", "ja")
 */
export function setLocaleCookie(locale: string) {
    document.cookie = `NEXT_LOCALE=${locale};path=/;max-age=31536000;samesite=lax`;
}

export function timeOf(value?: string) {
    if (!value) return 0;
    const t = new Date(value).getTime();
    return Number.isNaN(t) ? 0 : t;
}

export function formatDate(value: string | undefined, locale: string) {
    if (!value) {
        return '—';
    }

    const date = new Date(value);

    if (Number.isNaN(date.getTime())) {
        return value;
    }

    return new Intl.DateTimeFormat(locale, { dateStyle: 'long' }).format(date);
}

export function formatDateTime(value: string | undefined, locale: string) {
    if (!value) {
        return 'Never';
    }

    const date = new Date(value);

    if (Number.isNaN(date.getTime())) {
        return value;
    }

    return new Intl.DateTimeFormat(locale, {
        dateStyle: 'medium',
        timeStyle: 'short',
    }).format(date);
}

/**
 * converts a date to a mysql datetime string
 * @param value - the date to convert to a mysql datetime string
 * @returns 
 */
export function toMysqlDateTime(value?: string | Date | number): string {
    const date =
        value == null ? new Date() : value instanceof Date ? value : new Date(value);

    if (Number.isNaN(date.getTime())) {
        throw new Error('Invalid date');
    }

    const pad = (n: number) => String(n).padStart(2, '0');
    return `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())} ${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())}:${pad(date.getUTCSeconds())}`;
}

/**
 * parses a mysql datetime string to a number
 * @param value - the mysql datetime string to parse
 * @returns 
 */
export function parseMysqlDateTime(value?: string | null): number {
    if (!value) return NaN;
    const s = value.trim().replace(' ', 'T');
    const hasTz = /[Zz]$|[+-]\d{2}:?\d{2}$/.test(s);
    return Date.parse(hasTz ? s : s + 'Z');
}

export function pickDominantCurrency(items: { currency?: string | null }[]): string {
    const counts = new Map<string, number>();
    for (const item of items) {
        const c = item.currency || 'USD';
        counts.set(c, (counts.get(c) ?? 0) + 1);
    }
    let best = 'USD';
    let bestCount = 0;
    for (const [c, n] of counts) {
        if (n > bestCount) { best = c; bestCount = n; }
    }
    return best;
}

/**
 * formats a value to a compact currency string (e.g. 1k, 100k, 1m, 1b)
 * 
 * NOTE: this will also format things like JPY, CNY, EUR, etc. but it'll be in english formatting (￥10,000,000 will become ￥10M and NOT 1千万円)
 * TODO: add support for Japanese-style number formatting, but not a priority
 * @param value - the value to format
 * @param currency - the currency to format the value in
 * @returns the formatted value
 */
export function formatCompactCurrency(value: number, currency = 'USD', locale = 'en-US') {
    try {
        return new Intl.NumberFormat(locale, {
            notation: 'compact',
            maximumFractionDigits: 1,
            style: 'currency',
            currency,
        }).format(value);
    } catch {
        return new Intl.NumberFormat(locale, {
            notation: 'compact',
            maximumFractionDigits: 1,
        }).format(value);
    }
}

/**
 * formats a value to a currency string (e.g. $1,000.00)
 * @param value - the value to format
 * @param currency - the currency to format the value in
 * @param locale - the locale to format the value in
 * @returns the formatted value
 */
export function formatCurrency(value: number, currency = 'USD', locale = 'en-US') {
    // for every 3 digits, add a comma
    return value.toLocaleString(locale, {
        style: 'currency',
        currency,
    });
}

/**
 * copies a value to the clipboard
 * @param value - the value to copy to the clipboard
 * @param label - the label of the value to copy to the clipboard
 * @returns boolean
 */
export function copyToClipboard(value: string, label: string) {
    try {
        navigator.clipboard.writeText(value);
        return true;
    } catch {
        console.error(`Failed to copy ${label.toLowerCase()}`);
        return false;
    }
}

/**
 * uploads a contact picture to the public directory and returns the public url
 * @param contactId - the id of the contact to upload the picture for
 * @param file 
 * @returns the public url of the uploaded picture
 */
export async function uploadContactPicture(contactId: number, file: File): Promise<string> {
    const formData = new FormData();
    formData.append('contactPicture', file);
    const res = await fetch(`/api/contacts/profile-picture?contactId=${contactId}`, {
        method: 'PUT',
        body: formData,
    });
    if (!res.ok) {
        throw new Error('Failed to upload contact picture');
    }
    const data = (await res.json()) as { imageUrl: string };
    return data.imageUrl;
}

/**
 * uploads a company logo to the public directory and returns the public url
 * @param companyId - the id of the company to upload the logo for
 * @param file 
 * @returns the public url of the uploaded logo
 */
export async function uploadCompanyLogo(companyId: number, file: File): Promise<string> {
    const formData = new FormData();
    formData.append('companyLogo', file);
    const res = await fetch(`/api/companies/logo?companyId=${companyId}`, {
        method: 'PUT',
        body: formData,
    });
    if (!res.ok) {
        throw new Error('Failed to upload company logo');
    }
    const data = (await res.json()) as { logoUrl: string };
    return data.logoUrl;
}

/**
 * uploads a file for any entity to the public directory and returns its metadata.
 * mirrors the profile-picture flow: the binary is written to disk here, then the
 * caller records it against the backend via createAttachment().
 * @param entityType - the owning entity type (e.g. "company", "person", "deal", "user")
 * @param entityId - the owning entity id
 * @param file - the file to upload
 * @returns the stored file's public url and metadata
 */
export async function uploadFile(entityType: string, entityId: number, file: File): Promise<UploadedFile> {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('entityType', entityType);
    formData.append('entityId', String(entityId));
    const res = await fetch('/api/uploads', {
        method: 'POST',
        body: formData,
    });
    if (!res.ok) {
        throw new Error('Failed to upload file');
    }
    return (await res.json()) as UploadedFile;
}

/**
 * removes a previously uploaded file from disk. best-effort; failures are swallowed
 * so a missing file never blocks deleting the attachment record.
 * @param url - the public url returned by uploadFile()
 */
export async function deleteUploadedFile(url: string): Promise<void> {
    try {
        await fetch(`/api/uploads?url=${encodeURIComponent(url)}`, { method: 'DELETE' });
    } catch {
        // best-effort cleanup
    }
}

/**
 * formats a byte count into a human-readable size (e.g. 1.2 MB)
 * @param bytes - the number of bytes
 * @returns the formatted size string
 */
export function formatFileSize(bytes?: number): string {
    if (bytes == null || Number.isNaN(bytes) || bytes < 0) return '—';
    if (bytes < 1024) return `${bytes} B`;
    const units = ['KB', 'MB', 'GB', 'TB'];
    let value = bytes / 1024;
    let unitIndex = 0;
    while (value >= 1024 && unitIndex < units.length - 1) {
        value /= 1024;
        unitIndex++;
    }
    return `${value.toFixed(value >= 10 || Number.isInteger(value) ? 0 : 1)} ${units[unitIndex]}`;
}

/**
 * normalizes a hex color string to a lowercase hex color string
 * @param input - the hex color string to normalize
 * @returns the normalized hex color string
 */
export function normalizeHex(input: string): string | null {
    const value = input.trim().replace(/^#/, '');
    if (/^[0-9a-f]{3}$/i.test(value)) {
        const [r, g, b] = value.split('');
        return `#${r}${r}${g}${g}${b}${b}`.toLowerCase();
    }
    if (/^[0-9a-f]{6}$/i.test(value)) {
        return `#${value}`.toLowerCase();
    }
    return null;
}

/**
 * converts a hex color string to an rgb object
 * @param hex 
 * @returns 
 */
function toRgb(hex: string): { r: number; g: number; b: number } {
    const normalized = normalizeHex(hex) ?? '#000000';
    const int = parseInt(normalized.slice(1), 16);
    return { r: (int >> 16) & 255, g: (int >> 8) & 255, b: int & 255 };
}

/**
 * calculates the relative luminance of a hex color string
 * @param hex - the hex color string to calculate the relative luminance of
 * @returns the relative luminance of the hex color string
 */
function relativeLuminance(hex: string): number {
    const { r, g, b } = toRgb(hex);
    const channel = (c: number) => {
        const s = c / 255;
        return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
    };
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

/**
 * calculates the readable text color for a hex color string
 * @param hex - the hex color string to calculate the readable text color for
 * @returns the readable text color for the hex color string
 */
export function readableTextColor(hex: string): string {
    const l = relativeLuminance(hex);
    const contrastWithWhite = 1.05 / (l + 0.05);
    const contrastWithBlack = (l + 0.05) / 0.05;
    return contrastWithWhite >= contrastWithBlack ? '#ffffff' : '#171717';
}

/**
 * converts a hex color string to an hsl object
 * @param hex - the hex color string to convert to an hsl object
 * @returns the hsl object
 */
function toHsl(hex: string): { h: number; s: number; l: number } {
    const { r, g, b } = toRgb(hex);
    const rn = r / 255;
    const gn = g / 255;
    const bn = b / 255;
    const max = Math.max(rn, gn, bn);
    const min = Math.min(rn, gn, bn);
    const l = (max + min) / 2;
    const d = max - min;
    if (d === 0) return { h: 0, s: 0, l };
    const s = d / (1 - Math.abs(2 * l - 1));
    let h: number;
    if (max === rn) h = ((gn - bn) / d) % 6;
    else if (max === gn) h = (bn - rn) / d + 2;
    else h = (rn - gn) / d + 4;
    h *= 60;
    if (h < 0) h += 360;
    return { h, s, l };
}

/**
 * compares two hex color strings by their hue
 * @param a - the first hex color string to compare
 * @param b - the second hex color string to compare
 * @returns the comparison result
 */
export function compareByColor(a: string, b: string): number {
    const ha = toHsl(a);
    const hb = toHsl(b);
    const aGray = ha.s < 0.12;
    const bGray = hb.s < 0.12;
    if (aGray !== bGray) return aGray ? 1 : -1;
    if (aGray && bGray) return hb.l - ha.l;
    if (Math.abs(ha.h - hb.h) > 0.5) return ha.h - hb.h;
    return hb.l - ha.l;
}

/**
 * converts a name to initials
 * @param name - the name to convert to initials
 * @returns the initials of the name
 */
export function initials(name: string) {
    return name
        .split(" ")
        .map((part) => part[0])
        .slice(0, 2)
        .join("")
        .toUpperCase();
}