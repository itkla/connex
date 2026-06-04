"use client";

import Link from "next/link";
import { motion, useReducedMotion } from "motion/react";
import { useTranslations } from "next-intl";
import { BuildingOffice2Icon, CalendarDaysIcon } from "@heroicons/react/24/outline";
import { TrophyIcon } from "@heroicons/react/16/solid";
import { initials } from "@/app/lib/utils";

const EASE = [0.23, 1, 0.32, 1] as const;

// function initials(name: string) {
//     return name
//         .split(" ")
//         .map((part) => part[0])
//         .slice(0, 2)
//         .join("")
//         .toUpperCase();
// }

export default function AuthBrandPanel({ className = "" }: { className?: string }) {
    const t = useTranslations("AuthBrand");
    const tForm = useTranslations("AuthForm");
    const reduce = useReducedMotion();

    const enter = (delay: number) =>
        reduce
            ? { initial: false as const }
            : {
                initial: { opacity: 0, scale: 0.94 },
                animate: { opacity: 1, scale: 1 },
                transition: { duration: 0.6, ease: EASE, delay },
            };

    const drawPath = (delay: number) =>
        reduce
            ? { initial: false as const }
            : {
                initial: { pathLength: 0, opacity: 0 },
                animate: { pathLength: 1, opacity: 1 },
                transition: { duration: 0.9, ease: EASE, delay },
            };

    const float = (delay: number) =>
        reduce
            ? {}
            : {
                animate: { y: [0, -6, 0] },
                transition: {
                    duration: 6,
                    repeat: Infinity,
                    ease: "easeInOut" as const,
                    delay,
                },
            };

    return (
        <aside className={`relative isolate overflow-hidden bg-brand ${className}`}>
            {/* // top left accent blur */}
            <div
                aria-hidden="true"
                className="pointer-events-none absolute -left-28 -top-28 size-96 rounded-full bg-white/25 blur-3xl"
            />

            {/* // bottom right accent blur */}
            <div
                aria-hidden="true"
                className="pointer-events-none absolute -bottom-32 -right-20 size-[30rem] rounded-full bg-white/15 blur-3xl"
            />

            <div className="relative flex h-full flex-col p-10 xl:p-14">
                <Link
                    href="/"
                    className="flex w-fit items-center gap-2.5 rounded-lg transition-opacity duration-150 ease-out hover:opacity-80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-neutral-950/40 focus-visible:ring-offset-4 focus-visible:ring-offset-brand"
                >
                    <span className="size-3 rounded-[5px] bg-neutral-950" aria-hidden="true" />
                    <span className="text-lg font-bold tracking-tight text-neutral-950">{tForm("brand")}</span>
                </Link>

                {/* // copy-pasted from the landing page hero visual ehehe */}
                <div className="flex min-h-0 flex-1 items-center justify-center py-6">
                    <div className="w-full max-w-[400px]">
                        <div className="flex items-center justify-between">
                            <span className="text-sm font-medium text-neutral-950/70">
                                {t("connectionsLabel")}
                            </span>
                            <span className="flex items-center gap-1.5 text-xs font-semibold text-neutral-950">
                                <TrophyIcon className="size-3.5" />
                                {t("wonLabel")}
                            </span>
                        </div>

                        <div className="relative mt-4 h-[260px]">
                            <svg
                                className="absolute inset-0 h-full w-full"
                                viewBox="0 0 100 100"
                                fill="none"
                                preserveAspectRatio="none"
                                aria-hidden="true"
                            >
                                <motion.path
                                    d="M50 50 C 66 40, 76 30, 82 17"
                                    stroke="#0a0a0a"
                                    strokeOpacity={0.22}
                                    strokeWidth="1.5"
                                    strokeLinecap="round"
                                    vectorEffect="non-scaling-stroke"
                                    {...drawPath(0.45)}
                                />
                                <motion.path
                                    d="M50 50 C 36 62, 26 72, 18 83"
                                    stroke="#0a0a0a"
                                    strokeOpacity={0.22}
                                    strokeWidth="1.5"
                                    strokeLinecap="round"
                                    vectorEffect="non-scaling-stroke"
                                    {...drawPath(0.6)}
                                />
                            </svg>

                            <motion.div
                                {...enter(0.1)}
                                className="absolute left-1/2 top-1/2 w-[220px] -translate-x-1/2 -translate-y-1/2 rounded-2xl bg-white p-3.5 shadow-[0_24px_50px_-24px_rgba(15,23,42,0.55)]"
                            >
                                <div className="flex items-center gap-3">
                                    <div className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-brand-light">
                                        <BuildingOffice2Icon className="size-5 text-brand-dark" />
                                    </div>
                                    <div className="min-w-0 flex-1">
                                        <div className="truncate text-sm font-semibold text-neutral-900">
                                            {t("companyName")}
                                        </div>
                                        <div className="truncate text-xs text-neutral-500">{t("dealsOpen")}</div>
                                    </div>
                                    <div className="text-base font-semibold leading-none text-brand-dark">
                                        {t("dealValue")}
                                    </div>
                                </div>
                            </motion.div>

                            <motion.div {...enter(0.55)} className="absolute" style={{ left: "82%", top: "17%" }}>
                                <motion.div className="relative -translate-x-1/2 -translate-y-1/2" {...float(0)}>
                                    {/* <div className="bg-white rounded-lg p-2 shadow-[0_18px_40px_-28px_rgba(15,23,42,0.6)]"> */}
                                        <span className="flex size-10 items-center justify-center rounded-full bg-neutral-950 text-xs font-semibold text-white ring-4 ring-white/60 shadow-[0_18px_40px_-28px_rgba(15,23,42,0.6)]">
                                            {initials(t("contactName1"))}
                                        </span>
                                        <div className="absolute right-full top-1/2 mr-2 -translate-y-1/2 whitespace-nowrap text-right leading-tight">
                                            <div className="text-xs font-semibold text-neutral-950">
                                                {t("contactName1")}
                                            </div>
                                            <div className="text-[11px] text-neutral-950/65">{t("contactRole1")}</div>
                                        </div>
                                    {/* </div> */}
                                </motion.div>
                            </motion.div>

                            <motion.div {...enter(0.7)} className="absolute" style={{ left: "18%", top: "83%" }}>
                                <motion.div className="relative -translate-x-1/2 -translate-y-1/2" {...float(1.2)}>
                                    <span className="flex size-10 items-center justify-center rounded-full bg-white text-xs font-semibold text-neutral-900 shadow-[0_10px_24px_-8px_rgba(15,23,42,0.5)] ring-4 ring-white/60">
                                        {initials(t("contactName2"))}
                                    </span>
                                    <div className="absolute left-full top-1/2 ml-2 -translate-y-1/2 whitespace-nowrap text-left leading-tight">
                                        <div className="text-xs font-semibold text-neutral-950">
                                            {t("contactName2")}
                                        </div>
                                        <div className="text-[11px] text-neutral-950/65">{t("contactRole2")}</div>
                                    </div>
                                </motion.div>
                            </motion.div>
                        </div>

                        {/* <motion.div
                            {...enter(0.85)}
                            className="mt-4 flex items-center gap-2.5 rounded-2xl bg-white/90 px-3.5 py-3 shadow-[0_18px_40px_-28px_rgba(15,23,42,0.6)]"
                        >
                            <CalendarDaysIcon className="size-4 shrink-0 text-brand-dark" />
                            <span className="text-sm text-neutral-800">{t("note")}</span>
                        </motion.div> */}
                    </div>
                </div>

                {/* <div className="max-w-md">
                    <h2 className="font-display text-[clamp(1.75rem,2.6vw,2.5rem)] leading-[1.12] tracking-[-0.01em] text-balance text-neutral-950">
                        {t("headlineLead")} <em className="italic">{t("headlineEmphasis")}</em> {t("headlineRest")}
                    </h2>
                    <p className="mt-3 text-base leading-relaxed text-neutral-900/80 text-pretty">{t("caption")}</p>
                </div> */}
            </div>
        </aside>
    );
}