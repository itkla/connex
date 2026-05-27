import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("RecordsPipelinesLayout");
    return {
        title: t("title"),
        description: t("description"),
    };
}

export default function RecordsPipelinesLayout({
    children,
}: Readonly<{
    children: React.ReactNode;
}>) {
    return children;
}
