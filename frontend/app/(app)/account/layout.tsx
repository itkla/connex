import { getTranslations } from "next-intl/server";

import Rise from "@/app/components/motion/Rise";
import AccountTabs from "@/app/components/account/AccountTabs";

export default async function AccountLayout({ children }: { children: React.ReactNode }) {
    const t = await getTranslations("Account");
    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-5xl flex-col gap-10">
                <Rise>
                    <header>
                        <h1 className="text-4xl font-extrabold tracking-tight">{t("title")}</h1>
                        <p className="mt-1 max-w-prose text-sm text-muted-foreground">{t("subtitle")}</p>
                    </header>
                </Rise>
                <AccountTabs />
                <div>{children}</div>
            </div>
        </div>
    );
}
