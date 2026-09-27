import type { useTranslations } from "next-intl";

export type LandingTranslation = ReturnType<typeof useTranslations<"CommonHome">>;

/** Fixed fictional evidence; elapsed-time claims are measured against this snapshot. */
export const SAMPLE_WORKSPACE = {
    asOf: "2026-09-11",
    renewalAmount: 8_400_000,
    wonAmount: 12_000_000,
    lastContact: "2026-08-07",
    expectedClose: "2026-08-30",
    followUpDue: "2026-09-11",
    wonDate: "2026-08-28",
    ownerChanged: "2026-09-01",
    sources: {
        review: { date: "2026-08-07" },
        pricing: { date: "2026-08-05" },
        introduction: { date: "2026-08-21" },
        close: { date: "2026-08-30" },
    },
} as const;

export type SampleSource = keyof typeof SAMPLE_WORKSPACE.sources;
export const ATTENTION_EXAMPLES = ["cooling", "risk", "introduction"] as const;
export type AttentionExample = (typeof ATTENTION_EXAMPLES)[number];
export const ATTENTION_SOURCES = {
    cooling: "review",
    risk: "close",
    introduction: "introduction",
} as const satisfies Record<AttentionExample, SampleSource>;
