"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useRef, useState } from "react";
import { toastSuccess } from "@/app/lib/toast";
import { useTranslations } from "next-intl";

import { logout } from "@/app/lib/api";
import { useApiErrorToast } from "@/app/hooks/useApiErrorToast";
import AuthBrandPanel from "@/app/components/auth/AuthBrandPanel";
import { Button } from "@/components/ui/button";

/** Requires an intentional confirmation before ending the session and clearing browser drafts. */
export default function LogoutPage() {
    const router = useRouter();
    const tForm = useTranslations("AuthForm");
    const t = useTranslations("AuthLogout");
    const showApiError = useApiErrorToast("AuthLogout");
    const pending = useRef(false);
    const [submitting, setSubmitting] = useState(false);

    async function signOut(event: React.SubmitEvent<HTMLFormElement>) {
        event.preventDefault();
        if (pending.current) return;
        pending.current = true;
        setSubmitting(true);
        try {
            await logout();
            toastSuccess(t("successMessage"));
            router.replace("/");
            router.refresh();
        } catch (error) {
            showApiError(error, "errorFallback");
            pending.current = false;
            setSubmitting(false);
        }
    }

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
                        <h1
                            id="logout-title"
                            className="connex-rise font-display font-black text-[clamp(2rem,4vw,2.75rem)] leading-[1.1] tracking-[-0.01em] text-balance text-foreground"
                        >
                            {t("title")}
                        </h1>
                        <p
                            className="connex-rise mt-3 text-base leading-relaxed text-muted-foreground text-pretty"
                            style={{ animationDelay: "60ms" }}
                        >
                            {t("description")}
                        </p>

                        <form
                            onSubmit={signOut}
                            aria-busy={submitting}
                            aria-labelledby="logout-title"
                            className="connex-rise mt-9 flex flex-wrap gap-3"
                            style={{ animationDelay: "120ms" }}
                        >
                            <Button
                                type="button"
                                variant="outline"
                                size="page"
                                autoFocus
                                disabled={submitting}
                                onClick={() => router.replace("/dashboard")}
                            >
                                {t("cancel")}
                            </Button>
                            <Button type="submit" size="page" disabled={submitting}>
                                {submitting ? t("signingOut") : t("confirm")}
                            </Button>
                        </form>
                    </div>
                </div>
            </div>

            <AuthBrandPanel className="hidden lg:block lg:h-full" />
        </div>
    );
}
