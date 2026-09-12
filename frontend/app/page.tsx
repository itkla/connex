import Link from "next/link";
import { headers } from "next/headers";
import { Schibsted_Grotesk, Source_Sans_3 } from "next/font/google";
import { getTranslations } from "next-intl/server";
import { ArrowRightIcon } from "@heroicons/react/24/outline";
import type { Metadata } from "next";
import { Button } from "@/components/ui/button";
import { getPublicPageUserFromCookie } from "@/app/lib/api";
import LandingNav from "@/app/components/landing/LandingNav";
import LandingFooter from "@/app/components/landing/LandingFooter";
import LandingFaq from "@/app/components/landing/LandingFaq";
import LandingPricing from "@/app/components/landing/LandingPricing";
import GuidedExample from "@/app/components/landing/GuidedExample";
import { AskConnexPreview, ConnectedRecordPreview, MapPreview, WorkflowPreview } from "@/app/components/landing/ProductPreviews";
import { TeamworkPreview } from "@/app/components/landing/TeamworkPreview";
import { WarmthPreview } from "@/app/components/landing/WarmthPreview";
import styles from "@/app/components/landing/landing.module.css";
import fujiStyles from "@/app/components/landing/fuji.module.css";
import { FujiBackdrop } from "@/app/components/landing/FujiBackdrop";

const display = Schibsted_Grotesk({ variable: "--font-landing-display", subsets: ["latin"], display: "swap" });
const body = Source_Sans_3({ variable: "--font-landing-body", subsets: ["latin"], display: "swap" });
const container = "mx-auto max-w-7xl px-5 sm:px-8";
const chapter = "py-12 sm:py-16";
const description = "mt-5 max-w-[62ch] text-lg leading-relaxed text-muted-foreground sm:text-xl";
const featureHeading = "mt-4 text-3xl leading-tight tracking-tight sm:text-4xl";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("CommonHome");
    return {
        title: t("metaTitle"),
        description: t("metaDescription"),
        alternates: { canonical: "/" },
        openGraph: {
            title: t("metaTitle"),
            description: t("metaDescription"),
            type: "website",
        },
        twitter: { card: "summary_large_image", title: t("metaTitle"), description: t("metaDescription") },
    };
}

/** Public session resolution selects CTAs; transport failures leave the whole page readable. */
export default async function Home() {
    const cookie = (await headers()).get("cookie");
    const user = await getPublicPageUserFromCookie(cookie);
    const t = await getTranslations("CommonHome");
    const ctaHref = user ? "/dashboard" : "/auth/register";
    const ctaLabel = user ? t("ctaDashboard") : t("heroCtaPrimary");

    return (
        <div className={`${styles.landing} ${display.variable} ${body.variable} min-h-screen bg-background text-foreground`}>
            <a href="#main" className="sr-only rounded-full bg-brand px-4 py-2 text-sm font-semibold text-brand-foreground focus:not-sr-only focus:absolute focus:left-6 focus:top-4 focus:z-50">{t("skipToContent")}</a>
            <LandingNav ctaHref={ctaHref} ctaLabel={ctaLabel} />
            <main id="main">
                <section className={`${fujiStyles.hero} flex min-h-[calc(100svh-4rem-1px)] flex-col justify-center py-16 sm:py-20`} aria-labelledby="hero-title">
                    <FujiBackdrop pauseLabel={t("heroPauseScenery")} resumeLabel={t("heroResumeScenery")} />
                    <div className={`${container} ${fujiStyles.copy}`}><div className="max-w-5xl">
                        <h1 id="hero-title" className={styles.heroHeading}><span className="block">{t("heroHeadlineLead")}</span><span className="block">{t("heroHeadlineRest")}</span></h1>
                        <p className={`${description} max-w-[56ch]`}>{t("heroSubtext")}</p>
                        <div className="mt-7 flex flex-wrap items-center gap-3">
                            <Button asChild variant="brand" size="page" className="min-h-11 h-auto whitespace-normal px-6 py-3 text-base"><Link href={ctaHref}>{ctaLabel}<ArrowRightIcon aria-hidden="true" className="size-4" /></Link></Button>
                            <Button asChild variant="ghost" size="page" className="min-h-11 h-auto whitespace-normal px-5 py-3 text-base"><a href="#features">{t("heroSecondaryCta")}<ArrowRightIcon aria-hidden="true" className="size-4" /></a></Button>
                        </div>
                    </div></div>
                </section>

                <section id="product" className={`${container} ${chapter} scroll-mt-20`} aria-labelledby="product-title">
                    <h2 id="product-title" className={`${styles.heading} max-w-3xl`}>{t("contextHeading")}</h2>
                    <p className={description}>{t("contextBody")}</p>
                    <ConnectedRecordPreview t={t} />
                </section>

                <section id="features" className={`${container} ${chapter} scroll-mt-20`} aria-labelledby="features-title">
                    <h2 id="features-title" className={`${styles.heading} max-w-4xl`}>{t("featuresHeading")}</h2>
                    <div className="mt-12 grid items-start gap-10 lg:mt-16 lg:grid-cols-[0.8fr_1.2fr] lg:gap-16">
                        <div><h3 className="text-xl font-bold text-brand-dark dark:text-brand">{t("askName")}</h3><p className={featureHeading}>{t("askHeading")}</p><p className={description}>{t("askBody")}</p><p className="mt-5 text-sm leading-relaxed text-muted-foreground">{t("askAvailability")}</p></div>
                        <AskConnexPreview t={t} />
                    </div>
                    <div className="mt-16 sm:mt-20">
                        <h3 className="text-xl font-bold text-brand-dark dark:text-brand">{t("mapName")}</h3>
                        <p className={featureHeading}>{t("mapHeading")}</p><p className={description}>{t("mapBody")}</p>
                        <MapPreview t={t} />
                    </div>
                    <div className="mt-16 sm:mt-20">
                        <h3 className="text-xl font-bold text-brand-dark dark:text-brand">{t("workflowName")}</h3><p className={featureHeading}>{t("workflowHeading")}</p><p className={description}>{t("workflowBody")}</p>
                        <WorkflowPreview t={t} />
                    </div>
                    <div id="warmth" className="mt-16 grid items-center gap-10 scroll-mt-20 sm:mt-20 lg:grid-cols-[0.8fr_1.2fr] lg:gap-16" aria-labelledby="warmth-title">
                        <div>
                            <h3 id="warmth-title" className="text-xl font-bold text-brand-dark dark:text-brand">{t("warmth.name")}</h3>
                            <p className={featureHeading}>{t("warmth.heading")}</p>
                            <p className={description}>{t("warmth.body")}</p>
                            <p className="mt-5 text-base leading-relaxed text-muted-foreground">{t("warmth.evidence")}</p>
                            <Link href="/docs/relationship-intelligence/warmth" className="mt-5 inline-flex min-h-11 items-center gap-2 text-base font-medium underline underline-offset-4">{t("warmth.docs")}<ArrowRightIcon aria-hidden="true" className="size-4 shrink-0" /></Link>
                        </div>
                        <WarmthPreview t={t} />
                    </div>
                </section>

                <section id="deploy" className={`${container} ${chapter} scroll-mt-20`} aria-labelledby="attention-title">
                    <h2 id="attention-title" className={`${styles.heading} max-w-4xl`}>{t.rich("attentionHeading", { em: (chunks) => <em className="font-semibold not-italic text-brand-dark dark:text-brand">{chunks}</em> })}</h2>
                    <p className={description}>{t("attentionBody")}</p>
                    <GuidedExample />
                </section>

                <section className={`${container} ${chapter}`} aria-labelledby="team-title">
                    <TeamworkPreview t={t} header={<>
                        <h2 id="team-title" className={`${styles.heading} max-w-4xl`}>{t("teamHeading")}</h2>
                        <p className={description}>{t("teamBody")}</p>
                    </>} />
                </section>

                <section id="workflow" className={`${container} ${chapter} scroll-mt-20`} aria-labelledby="start-title">
                    <h2 id="start-title" className={`${styles.heading} max-w-3xl`}>{t("startHeading")}</h2>
                    <ol className="mt-10 grid gap-8 sm:grid-cols-3 sm:gap-10">
                        {(["Capture", "Understand", "Act"] as const).map((step, index) => <li key={step}><span className="text-3xl font-semibold text-brand-dark tabular-nums dark:text-brand">{index + 1}</span><h3 className="mt-5 text-xl font-semibold">{t(`step${step}Title`)}</h3><p className="mt-3 text-base leading-relaxed text-muted-foreground">{t(`step${step}Body`)}</p></li>)}
                    </ol>
                    <p className="mt-8 max-w-[70ch] text-base leading-relaxed text-muted-foreground">{t("startNote")}</p>
                </section>

                <section id="pricing" className={`${container} ${chapter} scroll-mt-20`} aria-labelledby="pricing-title">
                    <h2 id="pricing-title" className={styles.heading}>{t("pricing.heading")}</h2>
                    <p className={description}>{t("pricing.body")}</p>
                    <LandingPricing t={t} ctaHref={ctaHref} ctaLabel={ctaLabel} />
                </section>

                <section className={`${container} ${chapter} grid gap-8 lg:grid-cols-[0.7fr_1.3fr] lg:gap-16`} aria-labelledby="faq-title">
                    <h2 id="faq-title" className={styles.heading}>{t("faqHeading")}</h2>
                    <LandingFaq t={t} />
                </section>

                <section className="bg-brand/10" aria-labelledby="closing-title">
                    <div className={`${container} ${chapter}`}><h2 id="closing-title" className={`${styles.heading} max-w-3xl`}>{t("ctaHeading")}</h2><div className="mt-7"><Button asChild variant="brand" size="page" className="min-h-11 h-auto whitespace-normal px-6 py-3 text-base"><Link href={ctaHref}>{ctaLabel}<ArrowRightIcon aria-hidden="true" className="size-4" /></Link></Button></div></div>
                </section>
            </main>
            <LandingFooter withDividers={false} />
        </div>
    );
}
