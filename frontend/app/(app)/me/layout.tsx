import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("MeLayout");
    return {
        title: t("title"),
        description: t("description"),
    };
}

export default function MeLayout({
    children,
}: Readonly<{
    children: React.ReactNode;
}>) {
    return children;
}
