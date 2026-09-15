import type { Metadata } from "next";
import { headers } from "next/headers";
import { getTranslations } from "next-intl/server";
import { resolvePreLaunch } from "@/app/lib/landingMode";
import LandingNav from "@/app/components/landing/LandingNav";
import LandingFooter from "@/app/components/landing/LandingFooter";
import LegalArticle, { type LegalSection } from "@/app/components/legal/LegalArticle";

const SECTION_IDS = [
    "acceptance",
    "service",
    "accounts",
    "customerData",
    "acceptableUse",
    "fees",
    "ip",
    "confidentiality",
    "privacy",
    "warranty",
    "liability",
    "term",
    "governingLaw",
    "changes",
    "contact",
] as const;

const UPDATED = "2026-07-01";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("Legal");
    return { title: t("terms.title"), description: t("terms.metaDescription") };
}

export default async function TermsPage() {
    const preLaunch = resolvePreLaunch({ host: (await headers()).get("host") });
    const t = await getTranslations("Legal");
    const nav = await getTranslations("CommonHome");

    const sections: LegalSection[] = SECTION_IDS.map((id) => ({
        id,
        heading: t(`terms.sec.${id}.h`),
        body: t(`terms.sec.${id}.b`),
    }));

    return (
        <div className="font-body min-h-screen bg-background text-foreground">
            <LandingNav ctaHref="/auth/register" ctaLabel={nav("ctaGetStarted")} preLaunch={preLaunch} />
            <main>
                <LegalArticle
                    title={t("terms.title")}
                    updated={t("updated", { date: UPDATED })}
                    lede={t("terms.lede")}
                    notice={t("draftNotice")}
                    tocLabel={t("tocLabel")}
                    sections={sections}
                />
            </main>
            <LandingFooter showLogin={!preLaunch} showDocs={!preLaunch} />
        </div>
    );
}
