import { type ReactNode } from 'react';
import { type ExternalToast, toast } from 'sonner';

export type ToastOptions = ExternalToast;
export type ToastId = string | number;

const successStyle = { backgroundColor: 'var(--color-brand)', color: 'var(--color-brand-foreground)' };
const errorStyle = { backgroundColor: 'var(--color-destructive)', color: 'var(--color-destructive-foreground)' };
const warnStyle = { backgroundColor: 'var(--color-warning)', color: 'var(--color-warning-foreground)' };
const infoStyle = { backgroundColor: 'var(--color-info)', color: 'var(--color-info-foreground)' };

export function toastSuccess(message: ReactNode, options?: ToastOptions) {
    return toast.success(message, { ...options, style: { ...successStyle, ...options?.style } });
}

export function toastError(message: ReactNode, options?: ToastOptions) {
    return toast.error(message, { ...options, style: { ...errorStyle, ...options?.style } });
}

export function toastWarn(message: ReactNode, options?: ToastOptions) {
    return toast.warning(message, { ...options, style: { ...warnStyle, ...options?.style } });
}

export function toastInfo(message: ReactNode, options?: ToastOptions) {
    return toast.info(message, { ...options, style: { ...infoStyle, ...options?.style } });
}

export function toastLoading(message: ReactNode, options?: ToastOptions) {
    return toast.loading(message, { ...options, style: { ...successStyle, ...options?.style } });
}

export function toastDismiss(id?: ToastId): void {
    toast.dismiss(id);
}
