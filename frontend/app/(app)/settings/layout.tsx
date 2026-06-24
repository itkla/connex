import { getTranslations } from "next-intl/server";

import SettingsTabs from "@/app/components/settings/SettingsTabs";

export default async function SettingsLayout({ children }: { children: React.ReactNode }) {
    const t = await getTranslations("WorkspaceSettings");
    return (
        <div className="mx-auto w-full max-w-5xl space-y-8 px-6 py-10 pb-16">
            <header>
                <h1 className="text-3xl font-bold tracking-tight text-balance text-foreground sm:text-4xl">
                    {t("title")}
                </h1>
                <p className="mt-1.5 text-sm text-muted-foreground">{t("subtitle")}</p>
            </header>
            <SettingsTabs />
            <div>{children}</div>
        </div>
    );
}
