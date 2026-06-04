import Link from "next/link";
import { headers } from "next/headers";
import { getTranslations } from "next-intl/server";
import {
    ArrowRightIcon,
    BellAlertIcon,
    ChartBarIcon,
    FunnelIcon,
    ShareIcon,
    UsersIcon,
} from "@heroicons/react/24/outline";
import { getCurrentUserFromCookie } from "@/app/lib/api";
import LandingNav from "@/app/components/landing/LandingNav";
import LanguageSwitcher from "@/app/components/landing/LanguageSwitcher";
import HeroVisual from "@/app/components/landing/HeroVisual";
import Reveal from "@/app/components/landing/Reveal";
import { Link2 } from "lucide-react";

const btnPrimary =
    "inline-flex items-center justify-center gap-2 rounded-full bg-brand px-6 py-3 text-base font-semibold text-neutral-950 transition-[transform,background-color] duration-150 ease-out hover:bg-brand-hover active:scale-[0.98]";

export default async function Home() {
    const cookie = (await headers()).get("cookie");
    const user = await getCurrentUserFromCookie(cookie);
    const t = await getTranslations("CommonHome");

    const ctaHref = user ? "/dashboard" : "/auth/register";
    const ctaLabel = user ? t("ctaDashboard") : t("ctaGetStarted");

    const features = [
        {
            key: "pipelines",
            icon: FunnelIcon,
            title: t("featurePipelinesTitle"),
            body: t("featurePipelinesBody"),
            span: "sm:col-span-2 lg:col-span-7",
        },
        {
            key: "records",
            icon: UsersIcon,
            title: t("featureRecordsTitle"),
            body: t("featureRecordsBody"),
            span: "sm:col-span-1 lg:col-span-5",
        },
        {
            key: "analytics",
            icon: ChartBarIcon,
            title: t("featureAnalyticsTitle"),
            body: t("featureAnalyticsBody"),
            span: "sm:col-span-1 lg:col-span-4",
        },
        {
            key: "followup",
            icon: BellAlertIcon,
            title: t("featureFollowupTitle"),
            body: t("featureFollowupBody"),
            span: "sm:col-span-2 lg:col-span-4",
        },
        {
            key: "map",
            icon: ShareIcon,
            title: t("featureMapTitle"),
            body: t("featureMapBody"),
            span: "sm:col-span-2 lg:col-span-4",
        },
    ];

    const steps = [
        { n: "01", title: t("workflowStep1Title"), body: t("workflowStep1Body") },
        { n: "02", title: t("workflowStep2Title"), body: t("workflowStep2Body") },
        { n: "03", title: t("workflowStep3Title"), body: t("workflowStep3Body") },
    ];

    return (
        <div className="font-body min-h-screen bg-white text-neutral-900">
            <LandingNav ctaHref={ctaHref} ctaLabel={ctaLabel} />

            <main>
                <section className="mx-auto grid max-w-7xl grid-cols-1 items-center gap-12 px-6 pt-14 pb-20 lg:min-h-[calc(100dvh-4rem)] lg:grid-cols-2 lg:gap-16 lg:px-8 lg:pb-28 lg:pt-20">
                    <div className="max-w-xl">
                        <h1 className="connex-rise font-display text-[clamp(2.5rem,6.5vw,4.25rem)] leading-[1.08] tracking-[-0.01em] text-balance text-neutral-900">
                            {t("heroHeadlineLead")} <em className="italic">{t("heroHeadlineEmphasis")}</em>{" "}
                            {t("heroHeadlineRest")}
                        </h1>
                        <p
                            className="connex-rise mt-6 max-w-md text-lg leading-relaxed text-neutral-600 text-pretty"
                            style={{ animationDelay: "90ms" }}
                        >
                            {t("heroSubtext")}
                        </p>
                        <div
                            className="connex-rise mt-9 flex flex-wrap items-center gap-3"
                            style={{ animationDelay: "180ms" }}
                        >
                            <Link href={ctaHref} className={btnPrimary}>
                                {ctaLabel}
                                <ArrowRightIcon className="size-4" />
                            </Link>
                            <Link
                                href="#workflow"
                                className="inline-flex items-center justify-center rounded-full border border-black/10 px-6 py-3 text-base font-medium text-neutral-700 transition-[transform,border-color,background-color] duration-150 ease-out hover:border-black/20 hover:bg-neutral-50 active:scale-[0.98]"
                            >
                                {t("heroSecondaryCta")}
                            </Link>
                        </div>
                    </div>

                    <div className="flex justify-center lg:justify-end">
                        <HeroVisual
                            labels={{
                                connections: t("heroVisualConnections"),
                                company: t("heroVisualCompany"),
                                dealsOpen: t("heroVisualDealsOpen"),
                                dealValue: t("heroVisualDealValue"),
                                won: t("heroVisualWon"),
                                contactName1: t("heroVisualContactName1"),
                                contactRole1: t("heroVisualContactRole1"),
                                contactName2: t("heroVisualContactName2"),
                                contactRole2: t("heroVisualContactRole2"),
                                note: t("heroVisualNote"),
                            }}
                        />
                    </div>
                </section>

                <section id="features" className="scroll-mt-20 border-t border-black/[0.06] bg-neutral-50/60">
                    <div className="mx-auto max-w-7xl px-6 py-20 lg:px-8 lg:py-28">
                        <Reveal className="max-w-2xl">
                            <h2 className="font-display text-[clamp(2rem,4vw,3rem)] leading-[1.1] tracking-[-0.01em] text-balance text-neutral-900">
                                {t("featuresHeading")}
                            </h2>
                            <p className="mt-4 max-w-xl text-lg leading-relaxed text-neutral-600 text-pretty">
                                {t("featuresSubtext")}
                            </p>
                        </Reveal>

                        <div className="mt-12 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-12">
                            {features.map((feature, i) => {
                                const Icon = feature.icon;
                                return (
                                    <Reveal key={feature.key} delay={i * 0.06} className={feature.span}>
                                        <div className="flex h-full flex-col rounded-2xl border border-black/[0.07] bg-white p-6">
                                            <div className="flex size-10 items-center justify-center rounded-xl bg-brand-light">
                                                <Icon className="size-5 text-brand-dark" />
                                            </div>
                                            <h3 className="mt-5 text-lg font-semibold text-neutral-900">
                                                {feature.title}
                                            </h3>
                                            <p className="mt-2 text-[15px] leading-relaxed text-neutral-600">
                                                {feature.body}
                                            </p>
                                            <FeatureMotif
                                                featureKey={feature.key}
                                                stageLabels={[
                                                    t("featurePipelineStageNew"),
                                                    t("featurePipelineStageTalking"),
                                                    t("featurePipelineStageWon"),
                                                ]}
                                            />
                                        </div>
                                    </Reveal>
                                );
                            })}
                        </div>
                    </div>
                </section>

                <section id="workflow" className="scroll-mt-20 border-t border-black/[0.06]">
                    <div className="mx-auto max-w-7xl px-6 py-20 lg:px-8 lg:py-28">
                        <Reveal className="max-w-2xl">
                            <h2 className="font-display text-[clamp(2rem,4vw,3rem)] leading-[1.1] tracking-[-0.01em] text-balance text-neutral-900">
                                {t("workflowHeading")}
                            </h2>
                            <p className="mt-4 max-w-xl text-lg leading-relaxed text-neutral-600 text-pretty">
                                {t("workflowSubtext")}
                            </p>
                        </Reveal>

                        <div className="mt-14 grid grid-cols-1 gap-x-10 gap-y-12 md:grid-cols-3">
                            {steps.map((step, i) => (
                                <Reveal key={step.n} delay={i * 0.08}>
                                    <div className="relative">
                                        <div className="flex items-center gap-4">
                                            <span className="font-display text-3xl text-brand-dark">{step.n}</span>
                                            <span className="h-px flex-1 bg-gradient-to-r from-brand/40 to-transparent" />
                                        </div>
                                        <h3 className="mt-5 text-xl font-semibold text-neutral-900">{step.title}</h3>
                                        <p className="mt-2 text-[15px] leading-relaxed text-neutral-600 text-pretty">
                                            {step.body}
                                        </p>
                                    </div>
                                </Reveal>
                            ))}
                        </div>
                    </div>
                </section>

                <section className="px-6 pb-20 lg:px-8 lg:pb-28">
                    <Reveal className="mx-auto max-w-7xl">
                        <div className="relative overflow-hidden rounded-[32px] bg-brand px-8 py-16 sm:px-14 sm:py-20">
                            <div className="relative max-w-2xl">
                                <h2 className="font-display text-[clamp(2rem,4.5vw,3.25rem)] leading-[1.08] tracking-[-0.01em] text-balance text-neutral-950">
                                    {t("ctaHeading")}
                                </h2>
                                <p className="mt-4 max-w-lg text-lg leading-relaxed text-neutral-900/80 text-pretty">
                                    {t("ctaSubtext")}
                                </p>
                                <div className="mt-8">
                                    <Link
                                        href={ctaHref}
                                        className="inline-flex items-center justify-center gap-2 rounded-full bg-neutral-950 px-7 py-3.5 text-base font-semibold text-white transition-[transform,background-color] duration-150 ease-out hover:bg-neutral-800 active:scale-[0.98]"
                                    >
                                        {ctaLabel}
                                        <ArrowRightIcon className="size-4" />
                                    </Link>
                                </div>
                            </div>
                            <div
                                className="pointer-events-none absolute -right-16 -top-24 size-80 rounded-full bg-white/20 blur-3xl"
                                aria-hidden="true"
                            />
                        </div>
                    </Reveal>
                </section>
            </main>

            <footer className="border-t border-black/[0.06] bg-white">
                <div className="mx-auto max-w-7xl px-6 py-12 lg:px-8">
                    <div className="flex flex-col gap-8 sm:flex-row sm:items-start sm:justify-between">
                        <div className="max-w-xs">
                            <Link href="/" className="flex items-center gap-2.5">
                                <span className="size-3 rounded-[5px] bg-brand" aria-hidden="true" />
                                <span className="text-lg font-bold tracking-tight text-neutral-900">
                                    {t("brand")}
                                </span>
                            </Link>
                            <p className="mt-3 text-sm leading-relaxed text-neutral-500">{t("footerTagline")}</p>
                        </div>

                        <div className="flex flex-col gap-6 sm:flex-row sm:items-start sm:gap-12">
                            <nav className="flex flex-col gap-3 text-sm">
                                <a href="#features" className="text-neutral-600 transition-colors hover:text-neutral-900">
                                    {t("navFeatures")}
                                </a>
                                <a href="#workflow" className="text-neutral-600 transition-colors hover:text-neutral-900">
                                    {t("navWorkflow")}
                                </a>
                                <Link
                                    href="/auth/login"
                                    className="text-neutral-600 transition-colors hover:text-neutral-900"
                                >
                                    {t("navLogin")}
                                </Link>
                            </nav>
                            <LanguageSwitcher align="start" />
                        </div>
                    </div>

                    <div className="mt-10 border-t border-black/[0.06] pt-6 text-sm text-neutral-400">
                        {t("footerRights")}
                    </div>
                </div>
            </footer>
        </div>
    );
}

function FeatureMotif({ featureKey, stageLabels }: { featureKey: string; stageLabels: string[] }) {
    if (featureKey === "pipelines") {
        const stages = [
            { label: stageLabels[0], count: 12, width: "100%", accent: false },
            { label: stageLabels[1], count: 5, width: "76%", accent: false },
            { label: stageLabels[2], count: 3, width: "54%", accent: true },
        ];
        return (
            <div className="mt-6 flex grow flex-col justify-end gap-1.5">
                {stages.map((stage) => (
                    <div
                        key={stage.label}
                        style={{ width: stage.width }}
                        className={`flex items-center justify-between rounded-lg px-3 py-1.5 ${stage.accent
                                ? "bg-brand text-neutral-950"
                                : "border border-black/[0.06] bg-neutral-50 text-neutral-700"
                            }`}
                    >
                        <span className="text-xs font-medium">{stage.label}</span>
                        <span
                            className={`text-[11px] tabular-nums ${stage.accent ? "text-neutral-950/70" : "text-neutral-400"
                                }`}
                        >
                            {stage.count}
                        </span>
                    </div>
                ))}
            </div>
        );
    }

    // TODO: turn this into a switch statement
    if (featureKey === "records") {
        const people = ["MD", "TA", "JR", "SO"];
        return (
            <div className="mt-6 flex grow items-end">
                <div className="flex -space-x-2.5">
                    {people.map((p, idx) => (
                        <span
                            key={p}
                            className={`flex size-9 items-center justify-center rounded-full text-xs font-semibold ring-2 ring-white ${idx % 2 === 0 ? "bg-neutral-900 text-white" : "bg-brand-light text-brand-dark"
                                }`}
                        >
                            {p}
                        </span>
                    ))}
                    <span className="flex size-9 items-center justify-center rounded-full bg-neutral-100 text-xs font-medium text-neutral-500 ring-2 ring-white">
                        +9
                    </span>
                </div>
            </div>
        );
    }

    if (featureKey === "analytics") {
        const bars = [38, 56, 46, 72, 90];
        return (
            <div className="mt-6 flex h-20 grow items-end gap-1.5">
                {bars.map((h, idx) => (
                    <div
                        key={idx}
                        style={{ height: `${h}%` }}
                        className={`flex-1 rounded-t-md ${idx >= 3 ? "bg-brand" : "bg-brand-light"}`}
                    />
                ))}
            </div>
        );
    }

    if (featureKey === "map") {
        return (
            <div className="relative mt-6 h-24 grow">
                <svg
                    className="absolute inset-0 h-full w-full"
                    viewBox="0 0 100 100"
                    fill="none"
                    preserveAspectRatio="none"
                    aria-hidden="true"
                >
                    <line x1="26" y1="50" x2="68" y2="22" stroke="#73d200" strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
                    <line x1="26" y1="50" x2="70" y2="78" stroke="#d4d4d4" strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
                    <line x1="68" y1="22" x2="70" y2="78" stroke="#d4d4d4" strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
                </svg>
                <span
                    className="absolute size-6 -translate-x-1/2 -translate-y-1/2 rounded-md bg-brand shadow-sm"
                    style={{ left: "26%", top: "50%" }}
                />
                <span
                    className="absolute size-5 -translate-x-1/2 -translate-y-1/2 rounded-md bg-neutral-300 ring-2 ring-white"
                    style={{ left: "68%", top: "22%" }}
                />
                <span
                    className="absolute size-4 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-emerald-500 bg-white ring-2 ring-white"
                    style={{ left: "70%", top: "78%" }}
                />
            </div>
        );
    }

    return null;
}
