import { getTranslations } from "next-intl/server";

import Rise from "@/app/components/motion/Rise";
import SettingsTabs from "@/app/components/settings/SettingsTabs";

export default async function SettingsLayout({ children }: { children: React.ReactNode }) {
    const t = await getTranslations("WorkspaceSettings");
    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-[100rem] flex-col gap-10">
                <Rise>
                    <header>
                        <h1 className="text-4xl font-extrabold tracking-tight">{t("title")}</h1>
                        <p className="mt-1 max-w-prose text-sm text-muted-foreground">{t("subtitle")}</p>
                    </header>
                </Rise>
                <SettingsTabs />
                <div>{children}</div>
            </div>
        </div>
    );
}
