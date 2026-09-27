import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import { ConfirmEmailForm } from "@/app/auth/confirm-email/ConfirmEmailForm";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("AuthConfirmEmail");
    return { title: t("title"), robots: { index: false, follow: false } };
}

export default function ConfirmEmailPage() {
    return <ConfirmEmailForm />;
}
