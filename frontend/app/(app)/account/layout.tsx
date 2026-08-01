import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import Rise from "@/app/components/motion/Rise";
import { PageShell } from "@/app/components/PageShell";
import { PageHeader } from "@/app/components/PageHeader";
import AccountTabs from "@/app/components/account/AccountTabs";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("AccountLayout");
    return {
        title: t("title"),
        description: t("description"),
    };
}

export default async function AccountLayout({ children }: { children: React.ReactNode }) {
    const t = await getTranslations("Account");
    return (
        <PageShell tier="reading">
            <Rise>
                <PageHeader title={t("title")} description={t("subtitle")} />
            </Rise>
            <AccountTabs />
            <div>{children}</div>
        </PageShell>
    );
}
