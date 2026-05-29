import { type ReactNode } from 'react';
import { type ExternalToast, toast } from 'sonner';

const successStyle = { backgroundColor: 'var(--color-brand)', color: 'white' };
const errorStyle = { backgroundColor: 'var(--color-destructive)', color: 'white' };
const warnStyle = { backgroundColor: 'var(--color-warning)', color: 'white' };
const infoStyle = { backgroundColor: 'var(--color-info)', color: 'white' };

export function toastSuccess(message: ReactNode, options?: ExternalToast) {
    return toast.success(message, { ...options, style: { ...successStyle, ...options?.style } });
}

export function toastError(message: ReactNode, options?: ExternalToast) {
    return toast.error(message, { ...options, style: { ...errorStyle, ...options?.style } });
}

export function toastWarn(message: ReactNode, options?: ExternalToast) {
    return toast.warning(message, { ...options, style: { ...warnStyle, ...options?.style } });
}

export function toastInfo(message: ReactNode, options?: ExternalToast) {
    return toast.info(message, { ...options, style: { ...infoStyle, ...options?.style } });
}