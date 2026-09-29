import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("InviteLinkAccept");
    return { title: t("metaTitle"), robots: { index: false, follow: false } };
}

export default function InviteLinkLayout({
    children,
}: Readonly<{
    children: React.ReactNode;
}>) {
    return children;
}
