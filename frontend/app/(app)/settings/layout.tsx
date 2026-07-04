import { getTranslations } from "next-intl/server";

import Rise from "@/app/components/motion/Rise";
import SettingsTabs from "@/app/components/settings/SettingsTabs";
import { getSsoInstanceEnabled } from "@/app/lib/api";

export default async function SettingsLayout({ children }: { children: React.ReactNode }) {
    const t = await getTranslations("WorkspaceSettings");
    const ssoEnabled = await getSsoInstanceEnabled()
        .then((r) => r.enabled)
        .catch(() => false);
    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-7xl flex-col gap-10">
                <Rise>
                    <header>
                        <h1 className="text-4xl font-extrabold tracking-tight">{t("title")}</h1>
                        <p className="mt-1 max-w-prose text-sm text-muted-foreground">{t("subtitle")}</p>
                    </header>
                </Rise>
                <SettingsTabs ssoEnabled={ssoEnabled} />
                <div>{children}</div>
            </div>
        </div>
    );
}
