import { getTranslations } from "next-intl/server";

import SettingsTabs from "@/app/components/settings/SettingsTabs";

export default async function SettingsLayout({ children }: { children: React.ReactNode }) {
    const t = await getTranslations("WorkspaceSettings");
    return (
        <div className="mx-auto w-full max-w-3xl px-6 py-10">
            <h1 className="mb-6 text-2xl font-semibold tracking-tight text-foreground">{t("title")}</h1>
            <SettingsTabs />
            <div className="mt-8">{children}</div>
        </div>
    );
}
