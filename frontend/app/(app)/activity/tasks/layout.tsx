import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("ActivityTasksLayout");
    return {
        title: t("title"),
        description: t("description"),
    };
}

export default function ActivityTasksLayout({
    children,
}: Readonly<{
    children: React.ReactNode;
}>) {
    return children;
}
