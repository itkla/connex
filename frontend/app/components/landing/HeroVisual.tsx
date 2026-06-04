"use client";

import { motion, useReducedMotion } from "motion/react";
import { BuildingOffice2Icon, CalendarDaysIcon } from "@heroicons/react/24/outline";
import { TrophyIcon } from "@heroicons/react/16/solid";

type Labels = {
    connections: string;
    company: string;
    dealsOpen: string;
    dealValue: string;
    won: string;
    contactName1: string;
    contactRole1: string;
    contactName2: string;
    contactRole2: string;
    note: string;
};

const EASE = [0.23, 1, 0.32, 1] as const;

function initials(name: string) {
    return name
        .split(" ")
        .map((part) => part[0])
        .slice(0, 2)
        .join("")
        .toUpperCase();
}

export default function HeroVisual({ labels }: { labels: Labels }) {
    const reduce = useReducedMotion();

    const fade = (delay: number) =>
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

    return (
        <div className="w-full max-w-[440px] rounded-[28px] border border-black/[0.06] bg-white p-6 shadow-[0_30px_70px_-30px_rgba(15,23,42,0.35)] ring-1 ring-black/[0.02]">
            <div className="flex items-center justify-between">
                <span className="text-sm font-medium text-neutral-500">{labels.connections}</span>
                <span className="flex items-center gap-1.5 text-xs font-medium text-brand-dark">
                    <TrophyIcon className="size-3.5" />
                    {labels.won}
                </span>
            </div>

            <div className="relative mt-4 h-[300px]">
                <svg
                    className="absolute inset-0 h-full w-full"
                    viewBox="0 0 100 100"
                    fill="none"
                    preserveAspectRatio="none"
                    aria-hidden="true"
                >
                    <motion.path
                        d="M50 50 C 66 40, 76 30, 82 17"
                        stroke="#73d200"
                        strokeWidth="1.5"
                        strokeLinecap="round"
                        vectorEffect="non-scaling-stroke"
                        {...drawPath(0.45)}
                    />
                    <motion.path
                        d="M50 50 C 36 62, 26 72, 18 83"
                        stroke="#73d200"
                        strokeWidth="1.5"
                        strokeLinecap="round"
                        vectorEffect="non-scaling-stroke"
                        {...drawPath(0.6)}
                    />
                </svg>

                <motion.div
                    {...fade(0.1)}
                    className="absolute left-1/2 top-1/2 w-[212px] -translate-x-1/2 -translate-y-1/2 rounded-2xl border border-black/[0.06] bg-white p-3.5 shadow-[0_18px_40px_-24px_rgba(15,23,42,0.5)]"
                >
                    <div className="flex items-center gap-3">
                        <div className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-brand-light">
                            <BuildingOffice2Icon className="size-5 text-brand-dark" />
                        </div>
                        <div className="min-w-0 flex-1">
                            <div className="truncate text-sm font-semibold text-neutral-900">{labels.company}</div>
                            <div className="truncate text-xs text-neutral-500">{labels.dealsOpen}</div>
                        </div>
                        <div className="text-base font-semibold leading-none text-brand-dark">{labels.dealValue}</div>
                    </div>
                </motion.div>

                <motion.div {...fade(0.55)} className="absolute" style={{ left: "82%", top: "17%" }}>
                    <div className="relative -translate-x-1/2 -translate-y-1/2">
                        <span className="flex size-10 items-center justify-center rounded-full bg-neutral-900 text-xs font-semibold text-white ring-4 ring-white">
                            {initials(labels.contactName1)}
                        </span>
                        <div className="absolute right-full top-1/2 mr-2 -translate-y-1/2 whitespace-nowrap text-right leading-tight">
                            <div className="text-xs font-medium text-neutral-900">{labels.contactName1}</div>
                            <div className="text-[11px] text-neutral-500">{labels.contactRole1}</div>
                        </div>
                    </div>
                </motion.div>

                <motion.div {...fade(0.7)} className="absolute" style={{ left: "18%", top: "83%" }}>
                    <div className="relative -translate-x-1/2 -translate-y-1/2">
                        <span className="flex size-10 items-center justify-center rounded-full bg-brand text-xs font-semibold text-neutral-900 ring-4 ring-white">
                            {initials(labels.contactName2)}
                        </span>
                        <div className="absolute left-full top-1/2 ml-2 -translate-y-1/2 whitespace-nowrap text-left leading-tight">
                            <div className="text-xs font-medium text-neutral-900">{labels.contactName2}</div>
                            <div className="text-[11px] text-neutral-500">{labels.contactRole2}</div>
                        </div>
                    </div>
                </motion.div>
            </div>

            <motion.div
                {...fade(0.85)}
                className="mt-4 flex items-center gap-2.5 rounded-2xl bg-brand-light px-3.5 py-3"
            >
                <CalendarDaysIcon className="size-4 shrink-0 text-brand-dark" />
                <span className="text-sm text-brand-dark">{labels.note}</span>
            </motion.div>
        </div>
    );
}