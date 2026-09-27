import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import { VerifyEmailForm } from "@/app/auth/verify-email/VerifyEmailForm";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("AuthVerifyEmail");
    return { title: t("title"), robots: { index: false, follow: false } };
}

export default function VerifyEmailPage() {
    return <VerifyEmailForm />;
}
