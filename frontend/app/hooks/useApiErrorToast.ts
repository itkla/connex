"use client";

import { useCallback } from "react";
import { useTranslations } from "next-intl";

import { toastApiError } from "@/app/lib/errorMessages";

/**
 * Binds {@link toastApiError} to a root translator so a caller cannot hand it a namespaced one, and
 * qualifies fallback title keys with the caller's own namespace so they stay as short as the
 * `useTranslations` calls beside them.
 * @param namespace the caller's message namespace, when its fallback keys are written relative to it
 * @returns a reporter taking a rejection and optional caller-owned title and description keys
 */
export function useApiErrorToast(namespace?: string): (
    error: unknown,
    fallbackKey?: string,
    fallbackDescriptionKey?: string,
) => void {
    const t = useTranslations();

    return useCallback(
        (error: unknown, fallbackKey?: string, fallbackDescriptionKey?: string) => {
            const qualified = fallbackKey === undefined || namespace === undefined
                ? fallbackKey
                : `${namespace}.${fallbackKey}`;
            const qualifiedDescription = fallbackDescriptionKey === undefined || namespace === undefined
                ? fallbackDescriptionKey
                : `${namespace}.${fallbackDescriptionKey}`;
            toastApiError(error, t, qualified, qualifiedDescription);
        },
        [namespace, t],
    );
}
