import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("ActivityAllLayout");
    return {
        title: t("title"),
        description: t("description"),
    };
}

export default function ActivityAllLayout({
    children,
}: Readonly<{
    children: React.ReactNode;
}>) {
    return children;
}
