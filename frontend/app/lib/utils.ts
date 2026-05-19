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

export function copyToClipboard(value: string, label: string) {
    try {
        navigator.clipboard.writeText(value);
        return true;
    } catch {
        console.error(`Failed to copy ${label.toLowerCase()}`);
        return false;
    }
}   