import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";
import LandingNav from "@/app/components/landing/LandingNav";
import LandingFooter from "@/app/components/landing/LandingFooter";
import LegalDisclosureList, { type LegalDisclosureRow } from "@/app/components/legal/LegalDisclosureList";

const ROW_IDS = [
    "seller",
    "manager",
    "address",
    "phone",
    "email",
    "url",
    "price",
    "additionalFees",
    "paymentMethods",
    "paymentTiming",
    "deliveryTiming",
    "returns",
    "environment",
] as const;

const UPDATED = "2026-07-01";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("Legal");
    return { title: `${t("tokushoho.title")} — ${t("brand")}` };
}

export default async function TokushohoPage() {
    const t = await getTranslations("Legal");
    const nav = await getTranslations("CommonHome");

    const rows: LegalDisclosureRow[] = ROW_IDS.map((id) => ({
        id,
        term: t(`tokushoho.row.${id}.t`),
        description: t(`tokushoho.row.${id}.d`),
    }));

    return (
        <div className="font-body min-h-screen bg-background text-foreground">
            <LandingNav ctaHref="/auth/register" ctaLabel={nav("ctaGetStarted")} />
            <main>
                <LegalDisclosureList
                    title={t("tokushoho.title")}
                    updated={t("updated", { date: UPDATED })}
                    lede={t("tokushoho.lede")}
                    notice={t("draftNotice")}
                    rows={rows}
                />
            </main>
            <LandingFooter />
        </div>
    );
}
