import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import { AuthForm } from "@/app/components/AuthForm";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("AuthRegister");
    return { title: t("title"), robots: { index: false, follow: false } };
}

export default async function RegisterPage({
    searchParams,
}: {
    searchParams: Promise<{ redirect?: string | string[] }>;
}) {
    const { redirect } = await searchParams;
    return <AuthForm mode="register" redirectUrl={typeof redirect === "string" ? redirect : null} />;
}
