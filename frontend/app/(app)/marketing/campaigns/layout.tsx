import { type ReactNode } from "react";
import { type Metadata } from "next";
import { getTranslations } from "next-intl/server";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("CampaignsLayout");
    return { title: t("title") };
}

export default function CampaignsLayout({ children }: { children: ReactNode }) {
    return children;
}
