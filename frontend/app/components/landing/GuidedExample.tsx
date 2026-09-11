"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { SegmentedControl } from "@/components/ui/segmented-control";
import { SampleEvidence } from "./SampleEvidence";
import { ATTENTION_EXAMPLES, ATTENTION_SOURCES, type AttentionExample, type LandingTranslation } from "./sampleWorkspace";

function Example({ example, t }: { example: AttentionExample; t: LandingTranslation }) {
    return (
        <div className="grid gap-8 lg:grid-cols-[1fr_1.1fr] lg:gap-16" data-attention-example={example}>
            <div>
                <p className="mb-5 flex items-center gap-2 text-sm font-medium">
                    <span aria-hidden="true" className={`size-2 rounded-full ${example === "cooling" ? "bg-warmth-cool" : example === "risk" ? "bg-risk-high" : "bg-brand"}`} />
                    {t(`attention_${example}_label`)}
                </p>
                <dl className="space-y-6">
                    {(["who", "why", "next"] as const).map((step) => (
                        <div key={step}>
                            <dt className="text-sm text-muted-foreground">{t(`attention_${step}`)}</dt>
                            <dd className={step === "who" ? "mt-2 text-2xl font-semibold" : "mt-2 text-lg leading-relaxed"}>
                                {t(`attention_${example}_${step}`)}
                            </dd>
                        </div>
                    ))}
                </dl>
            </div>
            <div className="self-start rounded-xl bg-background p-6 sm:p-8">
                <h3 className="text-lg font-semibold">{t("attentionEvidence")}</h3>
                <p className="mb-4 mt-2 text-sm leading-relaxed text-muted-foreground">{t("attentionEvidenceBody")}</p>
                <SampleEvidence source={ATTENTION_SOURCES[example]} t={t} />
                <p className="mt-5 text-sm leading-relaxed text-muted-foreground">{t(`attention_${example}_limit`)}</p>
            </div>
        </div>
    );
}

/** Selection only changes local fictional evidence; no CRM or AI client is mounted. */
export default function GuidedExample() {
    const t = useTranslations("CommonHome");
    const [example, setExample] = useState<AttentionExample>("cooling");

    return (
        <div className="mt-10 rounded-2xl bg-muted/60 p-5 sm:p-8 lg:p-10">
            <div className="mb-8 flex flex-wrap items-center justify-between gap-4">
                <SegmentedControl
                    value={example}
                    onChange={setExample}
                    ariaLabel={t("attentionChoose")}
                    options={ATTENTION_EXAMPLES.map((value) => ({ value, label: t(`attention_${value}_tab`) }))}
                    className="max-w-full flex-wrap [&_button]:min-h-11 [&_button]:whitespace-normal [&_button]:text-foreground"
                />
                <p className="text-sm text-muted-foreground">{t("sampleCaption")}</p>
            </div>
            <div aria-live="polite" aria-atomic="true">
                <Example key={example} example={example} t={t} />
            </div>
            <noscript>
                {ATTENTION_EXAMPLES.filter((value) => value !== "cooling").map((value) => (
                    <div key={value} className="mt-10 border-t border-border pt-10"><Example example={value} t={t} /></div>
                ))}
            </noscript>
        </div>
    );
}
