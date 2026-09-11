import Link from "next/link";
import { headers } from "next/headers";
import { getTranslations } from "next-intl/server";
import { ArrowRightIcon } from "@heroicons/react/24/outline";
import { getPublicPageUserFromCookie } from "@/app/lib/api";
import LandingNav from "@/app/components/landing/LandingNav";
import LandingFooter from "@/app/components/landing/LandingFooter";
import FujiSpine from "@/app/components/landing/FujiSpine";
import LandingFaq from "@/app/components/landing/LandingFaq";
import LandingTheme from "@/app/components/landing/LandingTheme";
import {
    ActivityTimelineMock,
    CompanyRecordMock,
    DealRiskMock,
    HandoverMock,
    RadarBoardMock,
} from "@/app/components/landing/ProductMocks";
import Reveal from "@/app/components/landing/Reveal";
import type { Metadata } from "next";

const btnPrimary =
    "inline-flex items-center justify-center gap-2 rounded-full bg-brand px-6 py-3 text-base font-semibold text-brand-foreground transition-[transform,background-color] duration-(--motion-micro) ease-out hover:bg-brand-hover active:scale-[0.98] motion-reduce:active:scale-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand focus-visible:ring-offset-2 focus-visible:ring-offset-background";

const btnGhost =
    "inline-flex items-center justify-center rounded-full border border-border bg-background/70 px-6 py-3 text-base font-medium text-foreground backdrop-blur-sm transition-[transform,border-color,background-color] duration-(--motion-micro) ease-out hover:bg-muted active:scale-[0.98] motion-reduce:active:scale-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand focus-visible:ring-offset-2 focus-visible:ring-offset-background";

/** Background-coloured halo so copy always wins where the spine passes behind it. */
const HALO = "[text-shadow:0_0_10px_var(--background),0_0_22px_var(--background)]";

const sectionHeading =
    "font-display text-[clamp(2rem,4vw,3.25rem)] leading-[1.15] tracking-[-0.01em] text-balance text-foreground [line-break:strict] [word-break:auto-phrase]";

const sectionBody =
    "mt-5 max-w-[62ch] text-lg leading-relaxed text-muted-foreground text-pretty [word-break:auto-phrase]";

/** The product loop, as three moves. Numbered because it genuinely is a sequence. */
const STEPS = ["Capture", "Understand", "Act"] as const;

/** The two arrangements that are genuinely for sale. */
const DEPLOYMENTS = ["Cloud", "Self"] as const;

const TRUST_POINTS = ["Permissions", "Audit", "Data"] as const;

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
    const heroCtaLabel = user ? t("ctaDashboard") : t("heroCtaPrimary");

    return (
        <LandingTheme>
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
                    <section className="mx-auto max-w-7xl px-6 pt-10 pb-16 lg:px-8 lg:pt-16 lg:pb-24">
                        <div className="grid items-center gap-12 lg:grid-cols-12 lg:gap-12">
                            <div className="lg:col-span-5">
                                <p className={`connex-rise text-sm font-medium text-muted-foreground ${HALO}`}>
                                    {t("heroEyebrow")}
                                </p>
                                <h1
                                    className={`connex-rise mt-4 font-display text-[clamp(2.25rem,4.6vw,3.75rem)] leading-[1.06] tracking-[-0.02em] text-balance text-foreground [line-break:strict] [word-break:auto-phrase] ${HALO}`}
                                    style={{ animationDelay: "60ms" }}
                                >
                                    <span className="block">{t("heroHeadlineLead")}</span>
                                    <span className="block">{t("heroHeadlineRest")}</span>
                                </h1>
                                <p
                                    className={`connex-rise mt-6 max-w-[48ch] text-lg leading-relaxed text-muted-foreground text-pretty [word-break:auto-phrase] ${HALO}`}
                                    style={{ animationDelay: "120ms" }}
                                >
                                    {t("heroSubtext")}
                                </p>
                                <div
                                    className="connex-rise mt-8 flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-center"
                                    style={{ animationDelay: "180ms" }}
                                >
                                    <Link href={ctaHref} className={btnPrimary}>
                                        {heroCtaLabel}
                                        <ArrowRightIcon className="size-4" />
                                    </Link>
                                    <a href="#product" className={btnGhost}>
                                        {t("heroSecondaryCta")}
                                    </a>
                                </div>
                            </div>
                            <div className="connex-rise lg:col-span-7" style={{ animationDelay: "220ms" }}>
                                <CompanyRecordMock />
                            </div>
                        </div>
                    </section>

                    <section id="product" className="scroll-mt-20">
                        <div className="mx-auto grid max-w-7xl items-center gap-12 px-6 py-16 sm:py-20 lg:grid-cols-2 lg:gap-16 lg:px-8 lg:py-24">
                            <Reveal>
                                <h2 className={sectionHeading}>{t("contextHeading")}</h2>
                                <p className={sectionBody}>{t("contextBody")}</p>
                            </Reveal>
                            <Reveal delay={0.08}>
                                <ActivityTimelineMock />
                            </Reveal>
                        </div>
                    </section>

                    <section id="features" className="scroll-mt-20">
                        <div className="mx-auto max-w-7xl px-6 py-16 sm:py-20 lg:px-8 lg:py-24">
                            <Reveal className="max-w-3xl">
                                <h2 className={sectionHeading}>{t("followHeading")}</h2>
                                <p className={sectionBody}>{t("followBody")}</p>
                            </Reveal>
                            <div className="mt-12 grid gap-6 lg:grid-cols-[1.35fr_1fr]">
                                <Reveal delay={0.06}>
                                    <RadarBoardMock />
                                </Reveal>
                                <Reveal delay={0.12}>
                                    <DealRiskMock />
                                </Reveal>
                            </div>
                            <Reveal delay={0.16}>
                                <p className="mt-8 max-w-[60ch] text-sm leading-relaxed text-muted-foreground">
                                    {t("followNote")}
                                </p>
                            </Reveal>
                        </div>
                    </section>

                    <section className="scroll-mt-20">
                        <div className="mx-auto grid max-w-7xl items-center gap-12 px-6 py-16 sm:py-20 lg:grid-cols-2 lg:gap-16 lg:px-8 lg:py-24">
                            <Reveal className="lg:order-2">
                                <h2 className={sectionHeading}>{t("continuityHeading")}</h2>
                                <p className={sectionBody}>{t("continuityBody")}</p>
                            </Reveal>
                            <Reveal delay={0.08} className="lg:order-1">
                                <HandoverMock />
                            </Reveal>
                        </div>
                    </section>

                    <section id="deploy" className="scroll-mt-20">
                        <div className="mx-auto max-w-7xl px-6 py-16 sm:py-20 lg:px-8 lg:py-24">
                            <Reveal className="max-w-3xl">
                                <h2 className={sectionHeading}>{t("deployHeading")}</h2>
                                <p className={sectionBody}>{t("deployBody")}</p>
                            </Reveal>
                            <div className="mt-12 grid gap-6 md:grid-cols-2">
                                {DEPLOYMENTS.map((kind, i) => (
                                    <Reveal key={kind} delay={0.06 * (i + 1)}>
                                        <div className="h-full rounded-2xl border border-border bg-card p-6 lg:p-8">
                                            <h3 className="text-lg font-semibold text-foreground">
                                                {t(`deploy${kind}Title`)}
                                            </h3>
                                            <p className="mt-2 text-[15px] leading-relaxed text-muted-foreground text-pretty [word-break:auto-phrase]">
                                                {t(`deploy${kind}Body`)}
                                            </p>
                                        </div>
                                    </Reveal>
                                ))}
                            </div>
                            <Reveal delay={0.2} className="mt-10">
                                <h3 className="text-sm font-semibold text-foreground">{t("trustHeading")}</h3>
                                <ul className="mt-4 grid gap-3 sm:grid-cols-3">
                                    {TRUST_POINTS.map((kind) => (
                                        <li
                                            key={kind}
                                            className="rounded-xl border border-border px-4 py-3 text-[13px] leading-relaxed text-muted-foreground text-pretty [word-break:auto-phrase]"
                                        >
                                            {t(`trust${kind}`)}
                                        </li>
                                    ))}
                                </ul>
                            </Reveal>
                        </div>
                    </section>

                    <section id="workflow" className="scroll-mt-20">
                        <div className="mx-auto max-w-7xl px-6 py-16 sm:py-20 lg:px-8 lg:py-24">
                            <Reveal className="max-w-3xl">
                                <h2 className={sectionHeading}>{t("startHeading")}</h2>
                            </Reveal>
                            <div className="mt-12 grid gap-10 sm:grid-cols-3 sm:gap-8">
                                {STEPS.map((step, i) => (
                                    <Reveal key={step} delay={0.06 * i}>
                                        <div className="flex items-center gap-4">
                                            <span className="font-display text-2xl leading-none text-brand-dark tabular-nums dark:text-brand">
                                                {`0${i + 1}`}
                                            </span>
                                            <span className="h-px flex-1 bg-border" />
                                        </div>
                                        <h3 className="mt-5 text-xl font-semibold text-foreground">
                                            {t(`step${step}Title`)}
                                        </h3>
                                        <p className="mt-2 text-[15px] leading-relaxed text-muted-foreground text-pretty [word-break:auto-phrase]">
                                            {t(`step${step}Body`)}
                                        </p>
                                    </Reveal>
                                ))}
                            </div>
                        </div>
                    </section>

                    <section>
                        <div className="mx-auto grid max-w-7xl gap-x-16 gap-y-8 px-6 py-16 sm:py-20 lg:grid-cols-[0.85fr_1.15fr] lg:px-8 lg:py-24">
                            <Reveal>
                                <h2 className={`${sectionHeading} lg:sticky lg:top-28`}>{t("faqHeading")}</h2>
                            </Reveal>
                            <Reveal delay={0.06}>
                                <LandingFaq />
                            </Reveal>
                        </div>
                    </section>

                    <section>
                        <div className="mx-auto max-w-7xl px-6 pt-12 pb-32 lg:px-8 lg:pb-48">
                            <Reveal className="max-w-2xl">
                                <h2
                                    className={`font-display text-[clamp(2rem,4.5vw,3.5rem)] leading-[1.12] tracking-[-0.015em] text-balance text-foreground [line-break:strict] [word-break:auto-phrase] ${HALO}`}
                                >
                                    {t("ctaHeading")}
                                </h2>
                                <p className={sectionBody}>{t("ctaSubtext")}</p>
                                <div className="mt-10">
                                    <Link href={ctaHref} className={btnPrimary}>
                                        {heroCtaLabel}
                                        <ArrowRightIcon className="size-4" />
                                    </Link>
                                </div>
                            </Reveal>
                        </div>
                    </section>
                </main>

                <LandingFooter />
            </div>
        </div>
        </LandingTheme>
    );
}
