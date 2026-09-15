import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("AuthLogout");
    return { title: t("title"), robots: { index: false, follow: false } };
}

export default function LogoutLayout({
    children,
}: Readonly<{
    children: React.ReactNode;
}>) {
    return children;
}
