import Link from "next/link";
import { headers } from "next/headers";
import { getTranslations } from "next-intl/server";
import { ArrowRightIcon } from "@heroicons/react/24/outline";
import { getPublicPageUserFromCookie } from "@/app/lib/api";
import LandingNav from "@/app/components/landing/LandingNav";
import LandingFooter from "@/app/components/landing/LandingFooter";
import FujiSpine from "@/app/components/landing/FujiSpine";
import LandingFaq from "@/app/components/landing/LandingFaq";
import {
    DealRiskSurface,
    IntroPathSurface,
    WarmthReadingSurface,
} from "@/app/components/landing/ProductSurfaces";
import Reveal from "@/app/components/landing/Reveal";
import type { Metadata } from "next";

const btnPrimary =
    "inline-flex items-center justify-center gap-2 rounded-full bg-brand px-6 py-3 text-base font-semibold text-brand-foreground transition-[transform,background-color] duration-(--motion-micro) ease-out hover:bg-brand-hover active:scale-[0.98] motion-reduce:active:scale-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand focus-visible:ring-offset-2 focus-visible:ring-offset-background";

const btnGhost =
    "inline-flex items-center justify-center rounded-full border border-border bg-background/70 px-6 py-3 text-base font-medium text-foreground backdrop-blur-sm transition-[transform,border-color,background-color] duration-(--motion-micro) ease-out hover:bg-muted active:scale-[0.98] motion-reduce:active:scale-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand focus-visible:ring-offset-2 focus-visible:ring-offset-background";

/** Background-coloured halo so copy always wins where the spine passes behind it. */
const HALO = "[text-shadow:0_0_10px_var(--background),0_0_22px_var(--background)]";

const sectionHeading =
    `font-display text-[clamp(2rem,4vw,3.25rem)] leading-[1.15] tracking-[-0.01em] text-balance text-foreground [line-break:strict] [word-break:auto-phrase] ${HALO}`;

const sectionBody =
    `mt-5 max-w-[62ch] text-lg leading-relaxed text-muted-foreground text-pretty [word-break:auto-phrase] ${HALO}`;

/**
 * Localized metadata for the public landing page. Without this the page inherits
 * the app shell's generic title and description, which is what every share card
 * and search result would otherwise show.
 */
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

/**
 * Public landing page. The session only selects the header's call to action, so an
 * unreachable backend falls back to the signed-out one rather than failing the page.
 */
export default async function Home() {
    const cookie = (await headers()).get("cookie");
    const user = await getPublicPageUserFromCookie(cookie);
    const t = await getTranslations("CommonHome");

    const ctaHref = user ? "/dashboard" : "/auth/register";
    const ctaLabel = user ? t("ctaDashboard") : t("ctaGetStarted");

    return (
        <div className="font-body relative min-h-screen bg-background text-foreground">
            <FujiSpine />

            <div className="relative z-10">
                <a
                    href="#main"
                    className="sr-only rounded-full bg-brand px-4 py-2 text-sm font-semibold text-brand-foreground focus:not-sr-only focus:absolute focus:left-6 focus:top-4 focus:z-50"
                >
                    {t("skipToContent")}
                </a>
                <LandingNav ctaHref={ctaHref} ctaLabel={ctaLabel} />

                <main id="main">
                    <section className="mx-auto flex min-h-[calc(100dvh-4rem)] max-w-7xl flex-col justify-center px-6 pt-12 pb-24 lg:px-8 lg:pt-20">
                        <div className="max-w-3xl">
                            <h1
                                className={`connex-rise font-display text-[clamp(2.25rem,5vw,4rem)] leading-[1.08] tracking-[-0.015em] text-balance text-foreground [line-break:strict] [word-break:auto-phrase] ${HALO}`}
                            >
                                <span className="block">{t("heroHeadlineLead")}</span>
                                <span className="block">{t("heroHeadlineRest")}</span>
                            </h1>
                            <p
                                className={`connex-rise mt-7 max-w-[46ch] text-lg leading-relaxed text-muted-foreground text-pretty [word-break:auto-phrase] ${HALO}`}
                                style={{ animationDelay: "90ms" }}
                            >
                                {t("heroSubtext")}
                            </p>
                            <div
                                className="connex-rise mt-10 flex flex-wrap items-center gap-3"
                                style={{ animationDelay: "180ms" }}
                            >
                                <a href="#features" className={btnPrimary}>
                                    {t("heroCtaPrimary")}
                                    <ArrowRightIcon className="size-4" />
                                </a>
                                <Link href="/docs" className={btnGhost}>
                                    {t("heroCtaSecondary")}
                                </Link>
                            </div>
                        </div>
                    </section>

                    <section>
                        <div className="mx-auto max-w-7xl px-6 py-24 lg:px-8 lg:py-32">
                            <Reveal className="max-w-3xl">
                                <h2 className={sectionHeading}>{t("caseHeading")}</h2>
                                <p className={sectionBody}>{t("caseBody")}</p>
                            </Reveal>
                        </div>
                    </section>

                    <section id="features" className="scroll-mt-20">
                        <div className="mx-auto grid max-w-7xl items-center gap-12 px-6 py-24 lg:grid-cols-2 lg:gap-16 lg:px-8 lg:py-32">
                            <Reveal>
                                <h2 className={sectionHeading}>{t("riskHeading")}</h2>
                                <p className={sectionBody}>{t("riskBody")}</p>
                            </Reveal>
                            <Reveal delay={0.08}>
                                <DealRiskSurface />
                            </Reveal>
                        </div>
                    </section>

                    <section id="workflow" className="scroll-mt-20">
                        <div className="mx-auto grid max-w-7xl items-center gap-12 px-6 py-24 lg:grid-cols-2 lg:gap-16 lg:px-8 lg:py-32">
                            <Reveal className="lg:order-2">
                                <h2 className={sectionHeading}>{t("introHeading")}</h2>
                                <p className={sectionBody}>{t("introBody")}</p>
                            </Reveal>
                            <Reveal delay={0.08} className="lg:order-1">
                                <IntroPathSurface />
                            </Reveal>
                        </div>
                    </section>

                    <section>
                        <div className="mx-auto grid max-w-7xl items-center gap-12 px-6 py-24 lg:grid-cols-2 lg:gap-16 lg:px-8 lg:py-32">
                            <Reveal>
                                <h2 className={sectionHeading}>{t("evidenceHeading")}</h2>
                                <p className={sectionBody}>{t("evidenceBody")}</p>
                            </Reveal>
                            <Reveal delay={0.08}>
                                <WarmthReadingSurface />
                            </Reveal>
                        </div>
                    </section>

                    <section>
                        <div className="mx-auto grid max-w-7xl gap-x-16 gap-y-16 px-6 py-24 md:grid-cols-2 lg:px-8 lg:py-32">
                            <Reveal>
                                <h2 className={sectionHeading}>{t("teamHeading")}</h2>
                                <p className={sectionBody}>{t("teamBody")}</p>
                            </Reveal>
                            <Reveal delay={0.06}>
                                <h2 className={sectionHeading}>{t("hostingHeading")}</h2>
                                <p className={sectionBody}>{t("hostingBody")}</p>
                            </Reveal>
                        </div>
                    </section>

                    <section>
                        <div className="mx-auto max-w-7xl px-6 py-24 lg:px-8 lg:py-32">
                            <div className="max-w-4xl">
                                <Reveal>
                                    <h2 className={sectionHeading}>{t("faqHeading")}</h2>
                                </Reveal>
                                <Reveal delay={0.06}>
                                    <div className="rounded-2xl bg-background px-4 sm:px-6">
                                        <LandingFaq />
                                    </div>
                                </Reveal>
                            </div>
                        </div>
                    </section>

                    <section>
                        <div className="mx-auto max-w-7xl px-6 pt-12 pb-32 lg:px-8 lg:pb-48">
                            <Reveal className="max-w-2xl">
                                <h2
                                    className={`font-display text-[clamp(2rem,4.5vw,3.5rem)] leading-[1.12] tracking-[-0.015em] text-balance text-foreground [line-break:strict] ${HALO}`}
                                >
                                    {t("ctaHeading")}
                                </h2>
                                <p className={sectionBody}>{t("ctaSubtext")}</p>
                                <div className="mt-10 flex flex-wrap items-center gap-3">
                                    <a href="#features" className={btnPrimary}>
                                        {t("heroCtaPrimary")}
                                        <ArrowRightIcon className="size-4" />
                                    </a>
                                    <Link href="/docs" className={btnGhost}>
                                        {t("heroCtaSecondary")}
                                    </Link>
                                </div>
                            </Reveal>
                        </div>
                    </section>
                </main>

                <LandingFooter />
            </div>
        </div>
    );
}
