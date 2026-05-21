// transplanted from /me 

export function formatShortDate(value?: string) {
    if (!value) return '';

    const date = new Date(value);

    if (Number.isNaN(date.getTime())) return value;

    return new Intl.DateTimeFormat('en', {
        month: 'short',
        day: 'numeric',
    }).format(date);
}

export function timeOf(value?: string) {
    if (!value) return 0;
    const t = new Date(value).getTime();
    return Number.isNaN(t) ? 0 : t;
}

export function formatDate(value?: string) {
    if (!value) {
        return '—';
    }

    const date = new Date(value);

    if (Number.isNaN(date.getTime())) {
        return value;
    }

    return new Intl.DateTimeFormat('en', { dateStyle: 'long' }).format(date);
}

export function formatDateTime(value?: string) {
    if (!value) {
        return 'Never';
    }

    const date = new Date(value);

    if (Number.isNaN(date.getTime())) {
        return value;
    }

    return new Intl.DateTimeFormat('en', {
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
 * formats a value to a compact currency string (e.g. 1k, 100k, 1m, 1b)
 * 
 * NOTE: this will also format things like JPY, CNY, EUR, etc. but it'll be in english formatting (￥10,000,000 will become ￥10M and NOT 1千万円)
 * TODO: add support for Japanese-style number formatting, but not a priority
 * @param value - the value to format
 * @param currency - the currency to format the value in
 * @returns the formatted value
 */
export function formatCompactCurrency(value: number, currency = 'USD') {
    try {
        return new Intl.NumberFormat('en', {
            notation: 'compact',
            maximumFractionDigits: 1,
            style: 'currency',
            currency,
        }).format(value);
    } catch {
        return new Intl.NumberFormat('en', {
            notation: 'compact',
            maximumFractionDigits: 1,
        }).format(value);
    }
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