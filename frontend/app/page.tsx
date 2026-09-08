import Link from "next/link";
import { headers } from "next/headers";
import { getTranslations } from "next-intl/server";
import { ArrowRightIcon } from "@heroicons/react/24/outline";
import { getPublicPageUserFromCookie } from "@/app/lib/api";
import LandingNav from "@/app/components/landing/LandingNav";
import LandingFooter from "@/app/components/landing/LandingFooter";
import FujiSpine from "@/app/components/landing/FujiSpine";
import Reveal from "@/app/components/landing/Reveal";

const btnPrimary =
    "inline-flex items-center justify-center gap-2 rounded-full bg-brand px-6 py-3 text-base font-semibold text-brand-foreground transition-[transform,background-color] duration-(--motion-micro) ease-out hover:bg-brand-hover active:scale-[0.98] motion-reduce:active:scale-100";

const btnGhost =
    "inline-flex items-center justify-center rounded-full border border-border bg-background/70 px-6 py-3 text-base font-medium text-foreground backdrop-blur-sm transition-[transform,border-color,background-color] duration-(--motion-micro) ease-out hover:bg-muted active:scale-[0.98] motion-reduce:active:scale-100";

const sectionHeading =
    "font-display text-[clamp(2rem,4vw,3.25rem)] leading-[1.15] tracking-[-0.01em] text-balance text-foreground [line-break:strict] [text-shadow:0_0_10px_var(--background),0_0_22px_var(--background)]";

const sectionBody =
    "mt-5 max-w-[62ch] text-lg leading-relaxed text-muted-foreground text-pretty [word-break:auto-phrase] [text-shadow:0_0_10px_var(--background),0_0_22px_var(--background)]";

/** The four warmth bands, coldest to hottest, drawn from the shared domain tokens. */
const WARMTH_BANDS = [
    { key: "cold", token: "bg-warmth-cold" },
    { key: "cool", token: "bg-warmth-cool" },
    { key: "warm", token: "bg-warmth-warm" },
    { key: "hot", token: "bg-warmth-hot" },
] as const;

/** The three plain-language guarantees behind every reading Connex shows. */
const GUARANTEES = ["guaranteeScope", "guaranteeTrail", "guaranteeProposes"] as const;

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
                <LandingNav ctaHref={ctaHref} ctaLabel={ctaLabel} />

                <main>
                    <section className="mx-auto flex min-h-[calc(100dvh-4rem)] max-w-7xl flex-col justify-center px-6 pt-12 pb-24 lg:px-8 lg:pt-20">
                        <div className="max-w-3xl">
                            <h1 className="connex-rise font-display text-[clamp(2.25rem,5vw,4rem)] leading-[1.08] tracking-[-0.015em] text-balance text-foreground [line-break:strict] [text-shadow:0_0_10px_var(--background),0_0_22px_var(--background)]">
                                {t("heroHeadlineLead")}
                                <br />
                                {t("heroHeadlineRest")}
                            </h1>
                            <p
                                className="connex-rise mt-7 max-w-[46ch] text-lg leading-relaxed text-muted-foreground text-pretty [word-break:auto-phrase] [text-shadow:0_0_10px_var(--background),0_0_22px_var(--background)]"
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
                                <Link href="#warmth" className={btnGhost}>
                                    {t("heroSecondaryCta")}
                                </Link>
                            </div>
                        </div>
                    </section>

                    <section id="warmth" className="scroll-mt-20">
                        <div className="mx-auto max-w-7xl px-6 py-24 lg:px-8 lg:py-36">
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
                                <div className="mt-3 flex justify-between text-sm text-muted-foreground">
                                    {WARMTH_BANDS.map((band) => (
                                        <span key={band.key}>{t(`warmthBand_${band.key}`)}</span>
                                    ))}
                                </div>
                            </Reveal>
                        </div>
                    </section>

                    <section id="features" className="scroll-mt-20">
                        <div className="mx-auto flex max-w-7xl justify-end px-6 py-24 lg:px-8 lg:py-36">
                            <Reveal className="max-w-2xl lg:text-right">
                                <h2 className={sectionHeading}>{t("radarHeading")}</h2>
                                <p className={`${sectionBody} lg:ml-auto`}>{t("radarBody")}</p>
                            </Reveal>
                        </div>
                    </section>

                    <section id="workflow" className="scroll-mt-20">
                        <div className="mx-auto max-w-3xl px-6 py-24 text-center lg:py-36">
                            <Reveal>
                                <h2 className={sectionHeading}>{t("introHeading")}</h2>
                                <p className={`${sectionBody} mx-auto`}>{t("introBody")}</p>
                            </Reveal>
                        </div>
                    </section>

                    <section>
                        <div className="mx-auto max-w-7xl px-6 py-24 lg:px-8 lg:py-36">
                            <Reveal className="max-w-2xl">
                                <h2 className={sectionHeading}>{t("evidenceHeading")}</h2>
                            </Reveal>
                            <div className="mt-14 grid max-w-5xl gap-x-12 gap-y-10 sm:grid-cols-2 lg:grid-cols-3">
                                {GUARANTEES.map((key, i) => (
                                    <Reveal key={key} delay={i * 0.06}>
                                        <h3 className="text-lg font-semibold text-foreground [text-shadow:0_0_10px_var(--background),0_0_22px_var(--background)]">{t(`${key}Title`)}</h3>
                                        <p className="mt-2 text-[15px] leading-relaxed text-muted-foreground text-pretty [word-break:auto-phrase] [text-shadow:0_0_10px_var(--background),0_0_22px_var(--background)]">
                                            {t(`${key}Body`)}
                                        </p>
                                    </Reveal>
                                ))}
                            </div>
                        </div>
                    </section>

                    <section>
                        <div className="mx-auto max-w-7xl px-6 pt-16 pb-32 lg:px-8 lg:pb-48">
                            <Reveal className="max-w-2xl">
                                <h2 className="font-display text-[clamp(2rem,4.5vw,3.5rem)] leading-[1.12] tracking-[-0.015em] text-balance text-foreground [line-break:strict] [text-shadow:0_0_10px_var(--background),0_0_22px_var(--background)]">
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
