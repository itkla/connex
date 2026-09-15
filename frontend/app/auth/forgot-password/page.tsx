import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import { ForgotPasswordForm } from "@/app/auth/forgot-password/ForgotPasswordForm";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("AuthForgotPassword");
    return { title: t("title"), robots: { index: false, follow: false } };
}

export default function ForgotPasswordPage() {
    return <ForgotPasswordForm />;
}
