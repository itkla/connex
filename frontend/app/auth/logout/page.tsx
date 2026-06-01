"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef } from "react";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { LoaderCircle } from "lucide-react";
import { useTranslations } from "next-intl";

import { logout } from "@/app/lib/api";

export default function LogoutPage() {
    const router = useRouter();
    const t = useTranslations("AuthLogout");
    const hasLoggedOut = useRef(false);

    useEffect(() => {
        if (hasLoggedOut.current) {
            return;
        }

        hasLoggedOut.current = true;

        async function signOut() {
            try {
                await logout();
                toastSuccess(t("successMessage"));
            } catch (err) {
                const message = err instanceof Error ? err.message : t("errorFallback");
                toastError(message);
            } finally {
                router.replace("/");
                router.refresh();
            }
        }

        void signOut();
    }, [router, t]);

    return (
        // <div className="flex min-h-screen items-center justify-center bg-white px-6">
        //     <p className="text-base text-black">Signing out...</p>
        // </div>
        <div className="flex min-h-screen items-center justify-center bg-white px-6">
            <span className="flex justify-center items-center w-full">
                <LoaderCircle className="size-4 animate-spin text-white" />
            </span>
            <p className="text-base text-black">{t("signingOut")}</p>
        </div>
    );
}
