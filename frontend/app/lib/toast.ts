import { type ReactNode } from 'react';
import { type ExternalToast, toast } from 'sonner';

export type ToastOptions = ExternalToast;
export type ToastId = string | number;

const successStyle = { backgroundColor: 'var(--color-brand)', color: 'white' };
const errorStyle = { backgroundColor: 'var(--color-destructive)', color: 'white' };
const warnStyle = { backgroundColor: 'var(--color-warning)', color: 'white' };
const infoStyle = { backgroundColor: 'var(--color-info)', color: 'white' };

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
