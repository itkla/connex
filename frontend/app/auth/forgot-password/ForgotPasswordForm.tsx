"use client";

import Link from "next/link";
import { useState } from "react";
import { useTranslations } from "next-intl";
import { ArrowRightIcon, ArrowLeftIcon, CheckCircleIcon } from "@heroicons/react/24/outline";
import { LoaderCircle } from "lucide-react";

import { requestPasswordReset } from "@/app/lib/api";
import { useApiErrorToast } from "@/app/hooks/useApiErrorToast";
import AuthBrandPanel from "@/app/components/auth/AuthBrandPanel";

export function ForgotPasswordForm() {
    const tForm = useTranslations("AuthForm");
    const t = useTranslations("AuthForgotPassword");
    const showApiError = useApiErrorToast("AuthForgotPassword");

    const [email, setEmail] = useState("");
    const [submitting, setSubmitting] = useState(false);
    const [sent, setSent] = useState(false);

    async function onSubmit(e: React.SubmitEvent<HTMLFormElement>) {
        e.preventDefault();
        setSubmitting(true);

        try {
            await requestPasswordReset({ email });
            setSent(true);
        } catch (err) {
            showApiError(err, "genericError");
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
                        {sent ? (
                            <div className="connex-rise">
                                <CheckCircleIcon className="size-11 text-brand" aria-hidden="true" />
                                <h1 className="mt-5 font-display font-black text-[clamp(2rem,4vw,2.75rem)] leading-[1.1] tracking-[-0.01em] text-balance text-foreground">
                                    {t("sentTitle")}
                                </h1>
                                <p className="mt-3 text-base leading-relaxed text-muted-foreground text-pretty">
                                    {t("sentBody")}
                                </p>
                                <Link
                                    href="/auth/login"
                                    className="mt-8 inline-flex items-center gap-2 text-sm font-semibold text-foreground underline decoration-brand decoration-2 underline-offset-4 transition-colors duration-150 ease-out hover:decoration-brand-hover"
                                >
                                    <ArrowLeftIcon className="size-4" />
                                    {t("backToLogin")}
                                </Link>
                            </div>
                        ) : (
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
                                                id="forgot-email"
                                                type="email"
                                                value={email}
                                                onChange={(e) => setEmail(e.target.value)}
                                                placeholder=" "
                                                autoComplete="email"
                                                required
                                                className="peer h-14 w-full rounded-xl border border-input bg-input px-4 pt-5 pb-1.5 text-base text-foreground outline-none transition-[border-color,box-shadow,background-color] duration-150 ease-out placeholder:text-transparent focus:border-brand focus:bg-background focus:ring-4 focus:ring-brand/20"
                                            />
                                            <label
                                                htmlFor="forgot-email"
                                                className="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 text-base text-muted-foreground transition-all duration-150 ease-out peer-focus:top-2.5 peer-focus:translate-y-0 peer-focus:text-xs peer-focus:text-foreground peer-[:not(:placeholder-shown)]:top-2.5 peer-[:not(:placeholder-shown)]:translate-y-0 peer-[:not(:placeholder-shown)]:text-xs"
                                            >
                                                {tForm("labelEmail")}
                                            </label>
                                        </div>
                                    </div>

                                    <button
                                        type="submit"
                                        disabled={submitting}
                                        style={{ animationDelay: "180ms" }}
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
                                    style={{ animationDelay: "240ms" }}
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
