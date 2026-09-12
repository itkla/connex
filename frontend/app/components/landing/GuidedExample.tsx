"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { ArrowRightIcon, CalendarDaysIcon, ChevronDownIcon, ClockIcon, ExclamationCircleIcon, UserGroupIcon, UserIcon } from "@heroicons/react/24/outline";
import { SegmentedControl } from "@/components/ui/segmented-control";
import { SampleEvidence } from "./SampleEvidence";
import { ATTENTION_EXAMPLES, ATTENTION_SOURCES, type AttentionExample, type LandingTranslation } from "./sampleWorkspace";

function SignalVisual({ example }: { example: AttentionExample }) {
    if (example === "introduction") return <div className="flex items-center justify-center gap-5 text-brand-dark dark:text-brand" aria-hidden="true"><UserGroupIcon className="size-16" /><ArrowRightIcon className="size-8" /><UserIcon className="size-16" /></div>;
    const Icon = example === "cooling" ? UserIcon : CalendarDaysIcon;
    const Badge = example === "cooling" ? ClockIcon : ExclamationCircleIcon;
    return <div className={`relative mx-auto w-fit ${example === "cooling" ? "text-warmth-cool" : "text-risk-high"}`} aria-hidden="true"><Icon className="size-20" /><Badge className="absolute -right-4 -bottom-2 size-9 rounded-full bg-muted" /></div>;
}

function Example({ example, t }: { example: AttentionExample; t: LandingTranslation }) {
    return (
        <div className="grid items-center gap-8 md:grid-cols-2 md:gap-12" data-attention-example={example}>
            <div className="py-5 text-center">
                <SignalVisual example={example} />
                <p className="mt-6 text-sm text-muted-foreground">{t(`attention_${example}_label`)}</p>
                <p className="mt-2 text-2xl font-semibold sm:text-3xl">{t(`attention_${example}_visual`)}</p>
            </div>
            <div>
                <p className="text-sm text-muted-foreground">{t("attention_next")}</p>
                <p className="mt-2 text-xl font-medium leading-relaxed">{t(`attention_${example}_next`)}</p>
                <details className="group mt-5 border-t border-border" data-attention-evidence>
                    <summary className="flex min-h-11 cursor-pointer list-none items-center justify-between gap-4 py-3 text-sm font-medium [&::-webkit-details-marker]:hidden">{t("attentionEvidence")}<ChevronDownIcon aria-hidden="true" className="size-4 shrink-0 group-open:rotate-180" /></summary>
                    <p className="mt-2 font-medium">{t(`attention_${example}_who`)}</p>
                    <p className="my-3 text-sm leading-relaxed text-muted-foreground">{t(`attention_${example}_why`)}</p>
                    <SampleEvidence source={ATTENTION_SOURCES[example]} t={t} />
                    <p className="mt-3 text-sm leading-relaxed text-muted-foreground">{t(`attention_${example}_limit`)}</p>
                    <p className="mt-4 text-sm text-muted-foreground">{t("sampleCaption")}</p>
                </details>
            </div>
        </div>
    );
}

/** The signal reads visually first; the recorded example remains available on request. */
export default function GuidedExample() {
    const t = useTranslations("CommonHome");
    const [example, setExample] = useState<AttentionExample>("cooling");

    return (
        <div className="mt-10 rounded-2xl bg-muted/60 p-5 sm:p-8 lg:p-10">
            <SegmentedControl
                value={example}
                onChange={setExample}
                ariaLabel={t("attentionChoose")}
                options={ATTENTION_EXAMPLES.map((value) => ({ value, label: t(`attention_${value}_tab`) }))}
                className="max-w-full flex-wrap [&_button]:min-h-11 [&_button]:whitespace-normal [&_button]:text-foreground"
            />
            <div className="mt-8" aria-live="polite" aria-atomic="true"><Example key={example} example={example} t={t} /></div>
            <noscript>
                {ATTENTION_EXAMPLES.filter((value) => value !== "cooling").map((value) => <div key={value} className="mt-12"><Example example={value} t={t} /></div>)}
            </noscript>
        </div>
    );
}
