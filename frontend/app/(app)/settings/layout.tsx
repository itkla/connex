import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import Rise from "@/app/components/motion/Rise";
import SettingsTabs from "@/app/components/settings/SettingsTabs";
import { DEFAULT_CAPABILITIES, getCapabilities } from "@/app/lib/api";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("WorkspaceSettings");
    return {
        title: t("title"),
        description: t("subtitle"),
    };
}

export default async function SettingsLayout({ children }: { children: React.ReactNode }) {
    const t = await getTranslations("WorkspaceSettings");
    const capabilities = await getCapabilities().catch(() => DEFAULT_CAPABILITIES);
    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-[100rem] flex-col gap-10">
                <Rise>
                    <header>
                        <h1 className="text-4xl font-extrabold tracking-tight">{t("title")}</h1>
                        <p className="mt-1 max-w-prose text-sm text-muted-foreground">{t("subtitle")}</p>
                    </header>
                </Rise>
                <SettingsTabs mailManaged={capabilities.mailManaged} />
                <div>{children}</div>
            </div>
        </div>
    );
}
