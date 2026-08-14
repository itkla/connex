"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import {
    ArrowRightIcon,
    ArrowLeftIcon,
    CheckCircleIcon,
    ExclamationTriangleIcon,
} from "@heroicons/react/24/outline";
import { LoaderCircle } from "lucide-react";

import {
    ApiError,
    confirmEmailVerification,
    exchangeEmailVerificationToken,
    validateEmailVerificationToken,
} from "@/app/lib/api";
import { takeOneTimeLinkToken } from "@/app/lib/oneTimeLink";
import { toastError } from "@/app/lib/toast";
import AuthBrandPanel from "@/app/components/auth/AuthBrandPanel";

type Status = "validating" | "invalid" | "ready" | "success";

/**
 * Redeems a registration email-verification link: it validates the token, lets the
 * recipient confirm with an explicit action (so link prefetchers can't auto-verify),
 * and reports success or an invalid/expired link.
 */
export function ConfirmEmailForm() {
    const tForm = useTranslations("AuthForm");
    const t = useTranslations("AuthConfirmEmail");

    const [status, setStatus] = useState<Status>("validating");
    const [submitting, setSubmitting] = useState(false);

    useEffect(() => {
        let active = true;
        const token = takeOneTimeLinkToken();
        const establishFlow = token
            ? exchangeEmailVerificationToken(token).then(() => {
                window.location.replace("/auth/confirm-email");
                return { valid: true };
            })
            : validateEmailVerificationToken();
        establishFlow
            .then((result) => {
                if (active) {
                    setStatus(result.valid ? "ready" : "invalid");
                }
            })
            .catch(() => {
                if (active) {
                    setStatus("invalid");
                }
            });
        return () => {
            active = false;
        };
    }, []);

    async function onConfirm() {
        setSubmitting(true);
        try {
            await confirmEmailVerification();
            setStatus("success");
        } catch (err) {
            if (err instanceof ApiError && err.status === 400) {
                setStatus("invalid");
            } else {
                toastError(t("genericError"));
            }
        } finally {
            setSubmitting(false);
        }
    }

    return (
        <div className="min-h-[100dvh] w-full bg-background lg:grid lg:h-[100dvh] lg:grid-cols-[1fr_1.05fr] lg:grid-rows-[100dvh] lg:overflow-hidden">
            <div className="relative flex min-h-[100dvh] flex-col px-6 py-10 sm:px-10 lg:h-full lg:min-h-0 lg:overflow-y-auto lg:px-14 lg:py-12">
                <Link
                    href="/"
                    className="flex w-fit items-center gap-2.5 rounded-lg transition-opacity duration-150 ease-out hover:opacity-70 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand lg:hidden"
                >
                    <span className="size-3 rounded-[5px] bg-brand" aria-hidden="true" />
                    <span className="text-lg font-bold tracking-tight text-foreground">{tForm("brand")}</span>
                </Link>

                <div className="flex flex-1 items-center justify-center">
                    <div className="w-full max-w-[400px] py-10 lg:py-0">
                        {status === "validating" && (
                            <div className="flex items-center gap-3 text-muted-foreground">
                                <LoaderCircle className="size-5 animate-spin" />
                                <span className="text-base">{t("validatingLabel")}</span>
                            </div>
                        )}

                        {status === "invalid" && (
                            <div className="connex-rise">
                                <ExclamationTriangleIcon className="size-11 text-destructive" aria-hidden="true" />
                                <h1 className="mt-5 font-display font-black text-[clamp(2rem,4vw,2.75rem)] leading-[1.1] tracking-[-0.01em] text-balance text-foreground">
                                    {t("invalidTitle")}
                                </h1>
                                <p className="mt-3 text-base leading-relaxed text-muted-foreground text-pretty">
                                    {t("invalidBody")}
                                </p>
                                <Link
                                    href="/auth/login"
                                    className="mt-8 inline-flex items-center justify-center gap-2 rounded-full bg-brand px-6 py-3.5 text-base font-semibold text-brand-foreground transition-[transform,background-color] duration-150 ease-out hover:bg-brand-hover active:scale-[0.98]"
                                >
                                    {t("backToLogin")}
                                    <ArrowRightIcon className="size-4" />
                                </Link>
                            </div>
                        )}

                        {status === "ready" && (
                            <>
                                <h1 className="connex-rise font-display font-black text-[clamp(2rem,4vw,2.75rem)] leading-[1.1] tracking-[-0.01em] text-balance text-foreground">
                                    {t("title")}
                                </h1>
                                <p
                                    className="connex-rise mt-3 text-base leading-relaxed text-muted-foreground text-pretty"
                                    style={{ animationDelay: "60ms" }}
                                >
                                    {t("subtitle")}
                                </p>
                                <button
                                    type="button"
                                    onClick={onConfirm}
                                    disabled={submitting}
                                    style={{ animationDelay: "120ms" }}
                                    className="connex-rise mt-9 flex w-full items-center justify-center gap-2 rounded-full bg-brand px-6 py-3.5 text-base font-semibold text-brand-foreground transition-[transform,background-color,opacity] duration-150 ease-out hover:bg-brand-hover active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-70"
                                >
                                    {submitting ? (
                                        <>
                                            <LoaderCircle className="size-4 animate-spin" />
                                            {t("submittingLabel")}
                                        </>
                                    ) : (
                                        <>
                                            {t("submitLabel")}
                                            <ArrowRightIcon className="size-4" />
                                        </>
                                    )}
                                </button>
                            </>
                        )}

                        {status === "success" && (
                            <div className="connex-rise">
                                <CheckCircleIcon className="size-11 text-brand" aria-hidden="true" />
                                <h1 className="mt-5 font-display font-black text-[clamp(2rem,4vw,2.75rem)] leading-[1.1] tracking-[-0.01em] text-balance text-foreground">
                                    {t("successTitle")}
                                </h1>
                                <p className="mt-3 text-base leading-relaxed text-muted-foreground text-pretty">
                                    {t("successBody")}
                                </p>
                                <Link
                                    href="/dashboard"
                                    className="mt-8 inline-flex items-center justify-center gap-2 rounded-full bg-brand px-6 py-3.5 text-base font-semibold text-brand-foreground transition-[transform,background-color] duration-150 ease-out hover:bg-brand-hover active:scale-[0.98]"
                                >
                                    {t("continue")}
                                    <ArrowRightIcon className="size-4" />
                                </Link>
                            </div>
                        )}

                        {(status === "ready" || status === "validating") && (
                            <p
                                className="connex-rise mt-7 text-center text-sm text-muted-foreground"
                                style={{ animationDelay: "180ms" }}
                            >
                                <Link
                                    href="/auth/login"
                                    className="inline-flex items-center gap-1.5 font-semibold text-foreground underline decoration-brand decoration-2 underline-offset-4 transition-colors duration-150 ease-out hover:decoration-brand-hover"
                                >
                                    <ArrowLeftIcon className="size-4" />
                                    {t("backToLogin")}
                                </Link>
                            </p>
                        )}
                    </div>
                </div>
            </div>

            <AuthBrandPanel className="hidden lg:block lg:h-full" />
        </div>
    );
}
