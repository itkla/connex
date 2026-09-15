import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import { ConfirmPasskeyForm } from "@/app/auth/confirm-passkey/ConfirmPasskeyForm";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("AuthConfirmPasskey");
    return { title: t("title"), robots: { index: false, follow: false } };
}

export default function ConfirmPasskeyPage() {
    return <ConfirmPasskeyForm />;
}
