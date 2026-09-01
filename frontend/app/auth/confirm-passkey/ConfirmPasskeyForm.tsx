"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import {
    ArrowRightIcon,
    ArrowLeftIcon,
    CheckCircleIcon,
    ExclamationTriangleIcon,
    LockClosedIcon,
} from "@heroicons/react/24/outline";
import { LoaderCircle } from "lucide-react";

import { ApiError, exchangePasskeyBootstrapConfirmation } from "@/app/lib/api";
import { settingsDestination } from "@/app/lib/settingsEntryPoints";
import { takeOneTimeLinkToken } from "@/app/lib/oneTimeLink";
import AuthBrandPanel from "@/app/components/auth/AuthBrandPanel";
import { Button } from "@/components/ui/button";

type Status = "validating" | "invalid" | "signIn" | "success";

/**
 * Redeems an emailed first-passkey enrollment confirmation. The bearer arrives in the URL
 * fragment, is exchanged once for a session-bound stamp, and is stripped from the address bar
 * before anything else runs. Redemption requires the signed-in session that requested it, so an
 * attacker holding only the password cannot have someone else's click authorize their enrollment.
 */
export function ConfirmPasskeyForm() {
    const tForm = useTranslations("AuthForm");
    const t = useTranslations("AuthConfirmPasskey");

    const [status, setStatus] = useState<Status>("validating");

    useEffect(() => {
        let active = true;
        const token = takeOneTimeLinkToken();
        const redeem = token
            ? exchangePasskeyBootstrapConfirmation(token)
            : Promise.reject(new ApiError("This confirmation link is invalid or has expired", 400));
        redeem
            .then(() => {
                if (active) {
                    setStatus("success");
                }
            })
            .catch((err: unknown) => {
                if (!active) {
                    return;
                }
                setStatus(err instanceof ApiError && err.status === 401 ? "signIn" : "invalid");
            });
        return () => {
            active = false;
        };
    }, []);

    const securityHref = settingsDestination("personal.security").href;

    return (
        <div className="min-h-[100dvh] w-full bg-background lg:grid lg:h-[100dvh] lg:grid-cols-[1fr_1.05fr] lg:grid-rows-[100dvh] lg:overflow-hidden">
            <div className="relative flex min-h-[100dvh] flex-col px-6 py-10 sm:px-10 lg:h-full lg:min-h-0 lg:overflow-y-auto lg:px-14 lg:py-12">
                <Link
                    href="/"
                    className="flex w-fit items-center gap-2.5 rounded-lg transition-opacity duration-(--motion-micro) ease-out hover:opacity-70 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand lg:hidden"
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
                                <Button asChild variant="brand" size="page" className="mt-8">
                                    <Link href={securityHref}>
                                        {t("continue")}
                                        <ArrowRightIcon className="size-4" />
                                    </Link>
                                </Button>
                            </div>
                        )}

                        {status === "signIn" && (
                            <div className="connex-rise">
                                <LockClosedIcon className="size-11 text-muted-foreground" aria-hidden="true" />
                                <h1 className="mt-5 font-display font-black text-[clamp(2rem,4vw,2.75rem)] leading-[1.1] tracking-[-0.01em] text-balance text-foreground">
                                    {t("signInTitle")}
                                </h1>
                                <p className="mt-3 text-base leading-relaxed text-muted-foreground text-pretty">
                                    {t("signInBody")}
                                </p>
                                <Button asChild variant="brand" size="page" className="mt-8">
                                    <Link href="/auth/login">
                                        {t("backToLogin")}
                                        <ArrowRightIcon className="size-4" />
                                    </Link>
                                </Button>
                            </div>
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
                                <Button asChild variant="brand" size="page" className="mt-8">
                                    <Link href={securityHref}>
                                        {t("continue")}
                                        <ArrowRightIcon className="size-4" />
                                    </Link>
                                </Button>
                            </div>
                        )}

                        {status === "validating" && (
                            <p
                                className="connex-rise mt-7 text-center text-sm text-muted-foreground"
                                style={{ animationDelay: "180ms" }}
                            >
                                <Link
                                    href="/auth/login"
                                    className="inline-flex items-center gap-1.5 font-semibold text-foreground underline decoration-brand decoration-2 underline-offset-4 transition-colors duration-(--motion-micro) ease-out hover:decoration-brand-hover"
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
