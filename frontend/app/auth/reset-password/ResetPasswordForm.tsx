"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import {
    ArrowRightIcon,
    ArrowLeftIcon,
    EyeIcon,
    EyeSlashIcon,
    ExclamationTriangleIcon,
} from "@heroicons/react/24/outline";
import { LoaderCircle } from "lucide-react";

import {
    ApiError,
    exchangePasswordResetToken,
    logout,
    resetPassword,
    validateResetToken,
} from "@/app/lib/api";
import { takeOneTimeLinkToken } from "@/app/lib/oneTimeLink";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { useFieldErrors } from "@/app/hooks/useFieldErrors";
import AuthBrandPanel from "@/app/components/auth/AuthBrandPanel";

type Status = "validating" | "invalid" | "ready";
const BREACHED_PASSWORD_CODE = "BREACHED_PASSWORD";
const BREACHED_PASSWORD_CHECK_UNAVAILABLE_CODE = "BREACHED_PASSWORD_CHECK_UNAVAILABLE";

/** Runs the fragment exchange and renders the existing token-free password-reset form. */
export function ResetPasswordForm() {
    const router = useRouter();
    const tForm = useTranslations("AuthForm");
    const t = useTranslations("AuthResetPassword");

    const [status, setStatus] = useState<Status>("validating");
    const [password, setPassword] = useState("");
    const [confirm, setConfirm] = useState("");
    const [showPassword, setShowPassword] = useState(false);
    const [submitting, setSubmitting] = useState(false);
    const [mismatch, setMismatch] = useState(false);
    const { fieldErrors, setFieldErrors, reset, clearError, captureFieldErrors } = useFieldErrors();

    useEffect(() => {
        let active = true;
        const token = takeOneTimeLinkToken();
        const establishFlow = token
            ? exchangePasswordResetToken(token).then(() => {
                window.location.replace("/auth/reset-password");
                return { valid: true };
            })
            : validateResetToken();
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

    async function onSubmit(e: React.SubmitEvent<HTMLFormElement>) {
        e.preventDefault();
        if (password !== confirm) {
            setMismatch(true);
            return;
        }

        setMismatch(false);
        reset();
        setSubmitting(true);

        try {
            await resetPassword({ newPassword: password });
            toastSuccess(t("successMessage"));
            await logout().catch(() => undefined);
            router.push("/auth/login");
        } catch (err) {
            if (err instanceof ApiError) {
                const passwordMessage = err.code === BREACHED_PASSWORD_CODE
                    ? t("breachedPassword")
                    : err.code === BREACHED_PASSWORD_CHECK_UNAVAILABLE_CODE
                        ? t("passwordScreeningUnavailable")
                        : null;
                if (passwordMessage) {
                    setFieldErrors({ newPassword: passwordMessage });
                }
                const captured = passwordMessage != null || captureFieldErrors(err);
                if (err.status === 400 && !captured) {
                    setStatus("invalid");
                    return;
                }
                if (!captured) {
                    toastError(err.message);
                }
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
                                    href="/auth/forgot-password"
                                    className="mt-8 inline-flex items-center justify-center gap-2 rounded-full bg-brand px-6 py-3.5 text-base font-semibold text-brand-foreground transition-[transform,background-color] duration-150 ease-out hover:bg-brand-hover active:scale-[0.98]"
                                >
                                    {t("requestNew")}
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

                                <form className="mt-9 space-y-3" onSubmit={onSubmit} noValidate>
                                    <div className="connex-rise" style={{ animationDelay: "120ms" }}>
                                        <div className="relative">
                                            <input
                                                id="reset-password"
                                                type={showPassword ? "text" : "password"}
                                                value={password}
                                                onChange={(e) => {
                                                    setPassword(e.target.value);
                                                    setMismatch(false);
                                                    clearError("newPassword");
                                                }}
                                                placeholder=" "
                                                autoComplete="new-password"
                                                required
                                                aria-invalid={Boolean(fieldErrors.newPassword)}
                                                aria-describedby={fieldErrors.newPassword ? "reset-password-error" : undefined}
                                                className={`peer h-14 w-full rounded-xl border bg-input px-4 pr-12 pt-5 pb-1.5 text-base text-foreground outline-none transition-[border-color,box-shadow,background-color] duration-150 ease-out placeholder:text-transparent focus:bg-background focus:ring-4 ${
                                                    fieldErrors.newPassword
                                                        ? "border-destructive focus:border-destructive focus:ring-destructive/15"
                                                        : "border-input focus:border-brand focus:ring-brand/20"
                                                }`}
                                            />
                                            <label
                                                htmlFor="reset-password"
                                                className={`pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 text-base transition-all duration-150 ease-out peer-focus:top-2.5 peer-focus:translate-y-0 peer-focus:text-xs peer-[:not(:placeholder-shown)]:top-2.5 peer-[:not(:placeholder-shown)]:translate-y-0 peer-[:not(:placeholder-shown)]:text-xs ${
                                                    fieldErrors.newPassword
                                                        ? "text-destructive"
                                                        : "text-muted-foreground peer-focus:text-foreground"
                                                }`}
                                            >
                                                {t("labelPassword")}
                                            </label>
                                            <button
                                                type="button"
                                                onClick={() => setShowPassword((s) => !s)}
                                                aria-label={showPassword ? tForm("hidePassword") : tForm("showPassword")}
                                                aria-pressed={showPassword}
                                                className="absolute right-2 top-1/2 inline-flex size-9 -translate-y-1/2 items-center justify-center rounded-lg text-muted-foreground transition-[color,transform] duration-150 ease-out hover:text-foreground focus-visible:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand active:scale-[0.94]"
                                            >
                                                {showPassword ? <EyeSlashIcon className="size-5" /> : <EyeIcon className="size-5" />}
                                            </button>
                                        </div>
                                        {fieldErrors.newPassword && (
                                            <p id="reset-password-error" className="mt-1.5 px-1 text-sm text-destructive">
                                                {fieldErrors.newPassword}
                                            </p>
                                        )}
                                    </div>

                                    <div className="connex-rise" style={{ animationDelay: "180ms" }}>
                                        <div className="relative">
                                            <input
                                                id="reset-confirm"
                                                type={showPassword ? "text" : "password"}
                                                value={confirm}
                                                onChange={(e) => {
                                                    setConfirm(e.target.value);
                                                    setMismatch(false);
                                                }}
                                                placeholder=" "
                                                autoComplete="new-password"
                                                required
                                                aria-invalid={mismatch}
                                                aria-describedby={mismatch ? "reset-confirm-error" : undefined}
                                                className={`peer h-14 w-full rounded-xl border bg-input px-4 pt-5 pb-1.5 text-base text-foreground outline-none transition-[border-color,box-shadow,background-color] duration-150 ease-out placeholder:text-transparent focus:bg-background focus:ring-4 ${
                                                    mismatch
                                                        ? "border-destructive focus:border-destructive focus:ring-destructive/15"
                                                        : "border-input focus:border-brand focus:ring-brand/20"
                                                }`}
                                            />
                                            <label
                                                htmlFor="reset-confirm"
                                                className={`pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 text-base transition-all duration-150 ease-out peer-focus:top-2.5 peer-focus:translate-y-0 peer-focus:text-xs peer-[:not(:placeholder-shown)]:top-2.5 peer-[:not(:placeholder-shown)]:translate-y-0 peer-[:not(:placeholder-shown)]:text-xs ${
                                                    mismatch ? "text-destructive" : "text-muted-foreground peer-focus:text-foreground"
                                                }`}
                                            >
                                                {t("labelConfirm")}
                                            </label>
                                        </div>
                                        {mismatch && (
                                            <p id="reset-confirm-error" className="mt-1.5 px-1 text-sm text-destructive">
                                                {t("mismatch")}
                                            </p>
                                        )}
                                    </div>

                                    <button
                                        type="submit"
                                        disabled={submitting}
                                        style={{ animationDelay: "240ms" }}
                                        className="connex-rise mt-2 flex w-full items-center justify-center gap-2 rounded-full bg-brand px-6 py-3.5 text-base font-semibold text-brand-foreground transition-[transform,background-color,opacity] duration-150 ease-out hover:bg-brand-hover active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-70"
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
                                </form>

                                <p
                                    className="connex-rise mt-7 text-center text-sm text-muted-foreground"
                                    style={{ animationDelay: "300ms" }}
                                >
                                    <Link
                                        href="/auth/login"
                                        className="inline-flex items-center gap-1.5 font-semibold text-foreground underline decoration-brand decoration-2 underline-offset-4 transition-colors duration-150 ease-out hover:decoration-brand-hover"
                                    >
                                        <ArrowLeftIcon className="size-4" />
                                        {t("backToLogin")}
                                    </Link>
                                </p>
                            </>
                        )}
                    </div>
                </div>
            </div>

            <AuthBrandPanel className="hidden lg:block lg:h-full" />
        </div>
    );
}
