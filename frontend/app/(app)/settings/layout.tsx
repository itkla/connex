import { getTranslations } from "next-intl/server";

import SettingsTabs from "@/app/components/settings/SettingsTabs";

export default async function SettingsLayout({ children }: { children: React.ReactNode }) {
    const t = await getTranslations("WorkspaceSettings");
    return (
        <div className="mx-auto w-full max-w-7xl space-y-8">
            <header>
                <h1 className="text-4xl font-extrabold tracking-tight">{t("title")}</h1>
                <p className="mt-1 max-w-prose text-sm text-muted-foreground">{t("subtitle")}</p>
            </header>
            <SettingsTabs />
            <div>{children}</div>
        </div>
    );
}
