import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import { ResetPasswordForm } from "@/app/auth/reset-password/ResetPasswordForm";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("AuthResetPassword");
    return { title: t("title"), robots: { index: false, follow: false } };
}

export default function ResetPasswordPage() {
    return <ResetPasswordForm />;
}
