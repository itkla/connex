"use client";

import { useCallback } from "react";
import { useTranslations } from "next-intl";

import {
    ApiError,
    PASSKEY_ENROLLMENT_REQUIRED_CODE,
    PASSKEY_STEP_UP_CANCELED_CODE,
    PASSKEY_STEP_UP_FAILED_CODE,
} from "@/app/lib/api";
import { toastError, toastInfo } from "@/app/lib/toast";

export function usePasskeyStepUpErrorHandler() {
    const t = useTranslations("PasskeyStepUp");

    return useCallback(
        (error: unknown): boolean => {
            if (!(error instanceof ApiError)) return false;
            if (error.code === PASSKEY_STEP_UP_CANCELED_CODE) {
                toastInfo(t("canceled"));
                return true;
            }
            if (error.code === PASSKEY_ENROLLMENT_REQUIRED_CODE) {
                toastError(t("enrollmentRequired"));
                return true;
            }
            if (error.code === PASSKEY_STEP_UP_FAILED_CODE) {
                toastError(t("failed"));
                return true;
            }
            return false;
        },
        [t],
    );
}
