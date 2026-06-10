'use client';

import { useCallback, useState } from 'react';
import { isFieldError } from '@/app/lib/api';

/**
 * Holds backend field-level validation messages and surfaces them inline, mirroring how
 * @returns 
 */
export function useFieldErrors() {
    const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

    const reset = useCallback(() => setFieldErrors({}), []);

    const clearError = useCallback((key: string) => {
        setFieldErrors((prev) => {
            if (!prev[key]) return prev;
            const next = { ...prev };
            delete next[key];
            return next;
        });
    }, []);

    const captureFieldErrors = useCallback((err: unknown) => {
        if (isFieldError(err)) {
            setFieldErrors(err.fieldErrors);
            return true;
        }
        return false;
    }, []);

    return { fieldErrors, setFieldErrors, reset, clearError, captureFieldErrors };
}