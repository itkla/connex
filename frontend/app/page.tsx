import Link from "next/link";
import { headers } from "next/headers";
import { getTranslations } from "next-intl/server";
import { ArrowRightIcon } from "@heroicons/react/24/outline";
import { getPublicPageUserFromCookie } from "@/app/lib/api";
import LandingNav from "@/app/components/landing/LandingNav";
import LandingFooter from "@/app/components/landing/LandingFooter";
import FujiSpine from "@/app/components/landing/FujiSpine";
import FeatureBento from "@/app/components/landing/FeatureBento";
import LandingFaq from "@/app/components/landing/LandingFaq";
import Reveal from "@/app/components/landing/Reveal";
import type { Metadata } from "next";

const btnPrimary =
    "inline-flex items-center justify-center gap-2 rounded-full bg-brand px-6 py-3 text-base font-semibold text-brand-foreground transition-[transform,background-color] duration-(--motion-micro) ease-out hover:bg-brand-hover active:scale-[0.98] motion-reduce:active:scale-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand focus-visible:ring-offset-2 focus-visible:ring-offset-background";

const btnGhost =
    "inline-flex items-center justify-center rounded-full border border-border bg-background/70 px-6 py-3 text-base font-medium text-foreground backdrop-blur-sm transition-[transform,border-color,background-color] duration-(--motion-micro) ease-out hover:bg-muted active:scale-[0.98] motion-reduce:active:scale-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand focus-visible:ring-offset-2 focus-visible:ring-offset-background";

/** Background-coloured halo so copy always wins where the spine passes behind it. */
const HALO = "[text-shadow:0_0_10px_var(--background),0_0_22px_var(--background)]";

const sectionHeading =
    `font-display text-[clamp(2rem,4vw,3.25rem)] leading-[1.15] tracking-[-0.01em] text-balance text-foreground [line-break:strict] ${HALO}`;

const sectionBody =
    `mt-5 max-w-[62ch] text-lg leading-relaxed text-muted-foreground text-pretty [word-break:auto-phrase] ${HALO}`;

/** The four warmth bands, coldest to hottest, drawn from the shared domain tokens. */
const WARMTH_BANDS = [
    { key: "cold", token: "bg-warmth-cold" },
    { key: "cool", token: "bg-warmth-cool" },
    { key: "warm", token: "bg-warmth-warm" },
    { key: "hot", token: "bg-warmth-hot" },
] as const;

/** The product loop, as three moves. Numbered because it genuinely is a sequence. */
const STEPS = ["Capture", "Understand", "Act"] as const;

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
        alternates: { canonical: "/", languages: { en: "/", ja: "/" } },
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
                                className={`connex-rise font-display text-[clamp(2.25rem,5vw,4rem)] leading-[1.08] tracking-[-0.015em] text-balance text-foreground [line-break:strict] ${HALO}`}
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
                                <Link href={ctaHref} className={btnPrimary}>
                                    {ctaLabel}
                                    <ArrowRightIcon className="size-4" />
                                </Link>
                                <a href="#warmth" className={btnGhost}>
                                    {t("heroSecondaryCta")}
                                </a>
                            </div>
                        </div>
                    </section>

                    <section id="warmth" className="scroll-mt-20">
                        <div className="mx-auto max-w-7xl px-6 py-24 lg:px-8 lg:py-32">
                            <Reveal className="max-w-3xl">
                                <h2 className={sectionHeading}>{t("warmthHeading")}</h2>
                                <p className={sectionBody}>{t("warmthBody")}</p>
                            </Reveal>

                            <Reveal delay={0.08} className="mt-12 max-w-md">
                                <div className="flex gap-1.5" aria-hidden="true">
                                    {WARMTH_BANDS.map((band) => (
                                        <span key={band.key} className={`h-2 flex-1 rounded-full ${band.token}`} />
                                    ))}
                                </div>
                                <div className={`mt-3 flex gap-1.5 text-sm text-muted-foreground ${HALO}`}>
                                    {WARMTH_BANDS.map((band) => (
                                        <span key={band.key} className="flex-1 text-center">
                                            {t(`warmthBand_${band.key}`)}
                                        </span>
                                    ))}
                                </div>
                            </Reveal>
                        </div>
                    </section>

                    <section>
                        <div className="mx-auto flex max-w-7xl justify-end px-6 py-24 lg:px-8 lg:py-32">
                            <Reveal className="max-w-2xl lg:text-right">
                                <h2 className={sectionHeading}>{t("radarHeading")}</h2>
                                <p className={`${sectionBody} lg:ml-auto`}>{t("radarBody")}</p>
                            </Reveal>
                        </div>
                    </section>

                    <section>
                        <div className="mx-auto max-w-3xl px-6 py-24 text-center lg:px-8 lg:py-32">
                            <Reveal>
                                <h2 className={sectionHeading}>{t("introHeading")}</h2>
                                <p className={`${sectionBody} mx-auto`}>{t("introBody")}</p>
                            </Reveal>
                        </div>
                    </section>

                    <section id="features" className="scroll-mt-20">
                        <div className="mx-auto max-w-7xl px-6 py-24 lg:px-8 lg:py-32">
                            <Reveal className="max-w-2xl">
                                <h2 className={sectionHeading}>{t("bentoHeading")}</h2>
                            </Reveal>
                            <FeatureBento />
                        </div>
                    </section>

                    <section id="workflow" className="scroll-mt-20">
                        <div className="mx-auto max-w-7xl px-6 py-24 lg:px-8 lg:py-32">
                            <Reveal className="max-w-2xl">
                                <h2 className={sectionHeading}>{t("stepsHeading")}</h2>
                            </Reveal>
                            <div className="mt-14 grid grid-cols-1 gap-x-10 gap-y-12 md:grid-cols-3">
                                {STEPS.map((step, i) => (
                                    <Reveal key={step} delay={i * 0.08}>
                                        <div className="flex items-center gap-4">
                                            <span className="font-display text-3xl text-brand-dark">
                                                {String(i + 1).padStart(2, "0")}
                                            </span>
                                            <span className="h-px flex-1 bg-gradient-to-r from-brand/50 to-transparent" />
                                        </div>
                                        <h3 className={`mt-5 text-xl font-semibold text-foreground ${HALO}`}>
                                            {t(`step${step}Title`)}
                                        </h3>
                                        <p
                                            className={`mt-2 text-[15px] leading-relaxed text-muted-foreground text-pretty [word-break:auto-phrase] ${HALO}`}
                                        >
                                            {t(`step${step}Body`)}
                                        </p>
                                    </Reveal>
                                ))}
                            </div>
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
                                <div className="mt-10">
                                    <Link href={ctaHref} className={btnPrimary}>
                                        {ctaLabel}
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
    );
}
