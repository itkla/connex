import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

import {
    ASK_CONNEX_WATCH_SUBJECTS,
    ASK_CONNEX_WATCH_TYPES,
    askConnexWatchHref,
    askConnexWatchLimitsText,
    askConnexWatchSupports,
    askConnexWatchTrigger,
    askConnexWatchTriggerText,
} from "@/app/components/ask-connex/askConnexWatch";
import type { AiWatch, AiWatchSubjectKind } from "@/app/lib/types";

const MESSAGES_ROOT = join(process.cwd(), "messages");
const LOCALES = ["en", "ja"] as const;

function catalog(locale: string): Record<string, unknown> {
    return JSON.parse(
        readFileSync(join(MESSAGES_ROOT, locale, "common.json"), "utf8"),
    ) as Record<string, unknown>;
}

function notificationCatalog(locale: string): Record<string, unknown> {
    return JSON.parse(
        readFileSync(join(MESSAGES_ROOT, locale, "notifications.json"), "utf8"),
    ) as Record<string, unknown>;
}

function lookup(tree: unknown, path: string): unknown {
    return path.split(".").reduce<unknown>((node, segment) => {
        if (node === null || typeof node !== "object") return undefined;
        return (node as Record<string, unknown>)[segment];
    }, tree);
}

/**
 * Resolves the one ICU construct this copy uses, so a plural message is proved to render rather
 * than passed over as an opaque string. `next-intl` does the real formatting at runtime; this only
 * has to agree with it about which branch a count selects.
 */
function selectPlurals(raw: string, values: Record<string, string | number>, locale: string) {
    return raw.replace(
        /\{(\w+), plural,(?:\s*one \{([^{}]*)\})?\s*other \{([^{}]*)\}\}/g,
        (whole, name: string, one: string | undefined, other: string) => {
            const value = values[name];
            if (typeof value !== "number") return whole;
            const branch = new Intl.PluralRules(locale).select(value) === "one" && one !== undefined
                ? one
                : other;
            return branch.replaceAll("#", String(value));
        },
    );
}

/** A translator over one real message catalog, so the tests read the shipped copy. */
function translator(locale: string) {
    const messages = catalog(locale);
    return (key: string, values?: Record<string, string | number>) => {
        const raw = lookup(messages, `AskConnex.commandCenter.${key}`);
        if (typeof raw !== "string") return `MISSING:${key}`;
        if (values === undefined) return raw;
        return Object.entries(values).reduce(
            (text, [name, value]) => text.replaceAll(`{${name}}`, String(value)),
            selectPlurals(raw, values, locale),
        );
    };
}

function watch(overrides: Partial<AiWatch> = {}): AiWatch {
    return {
        id: 5,
        watchType: "relationship_cooling",
        subjectKind: "person",
        subjectId: 42,
        subjectLabel: "Aiko Tanaka",
        thresholdBand: "cold",
        thresholdDays: null,
        thresholdLevel: null,
        status: "active",
        cooldownDays: 7,
        expiresOn: null,
        lastEvaluatedAt: null,
        lastFiredAt: null,
        lastFiredState: null,
        ...overrides,
    };
}

describe("Ask Connex watch triggers", () => {
    it("derives the trigger sentence from the typed threshold the server evaluates", () => {
        expect(askConnexWatchTrigger(watch())).toEqual({
            key: "cooling",
            values: { band: "cold" },
        });
        expect(
            askConnexWatchTrigger(watch({
                watchType: "no_interaction",
                thresholdBand: null,
                thresholdDays: 30,
            })),
        ).toEqual({ key: "noInteraction", values: { days: 30 } });
        expect(
            askConnexWatchTrigger(watch({
                watchType: "commitment_overdue",
                thresholdBand: null,
            })),
        ).toEqual({ key: "commitmentOverdue", values: {} });
        expect(
            askConnexWatchTrigger(watch({
                watchType: "deal_risk_threshold",
                subjectKind: "deal",
                thresholdBand: null,
                thresholdLevel: "high",
            })),
        ).toEqual({ key: "dealRisk", values: { level: "high" } });
    });

    it("refuses to state a trigger whose declared threshold is missing", () => {
        expect(askConnexWatchTrigger(watch({ thresholdBand: null }))).toEqual({
            key: "unknown",
            values: {},
        });
        expect(
            askConnexWatchTrigger(watch({
                watchType: "no_interaction",
                thresholdBand: null,
                thresholdDays: null,
            })),
        ).toEqual({ key: "unknown", values: {} });
    });

    it("renders a complete sentence in both languages, with no placeholder left behind", () => {
        const cases: AiWatch[] = [
            watch(),
            watch({ watchType: "no_interaction", thresholdBand: null, thresholdDays: 30 }),
            watch({ watchType: "commitment_overdue", thresholdBand: null }),
            watch({
                watchType: "deal_risk_threshold",
                subjectKind: "deal",
                thresholdBand: null,
                thresholdLevel: "high",
            }),
            watch({ thresholdBand: null }),
        ];
        for (const locale of LOCALES) {
            const t = translator(locale);
            for (const row of cases) {
                const text = askConnexWatchTriggerText(row, t);
                expect(text).not.toContain("MISSING:");
                expect(text).not.toMatch(/\{[a-zA-Z]+\}/);
                expect(text.trim().length).toBeGreaterThan(0);
            }
        }
    });

    it("deep links a fired watch to the record whose evidence explains it", () => {
        expect(askConnexWatchHref(watch())).toBe("/records/contacts/42");
        expect(askConnexWatchHref(watch({ subjectKind: "company", subjectId: 8 })))
            .toBe("/records/companies/8");
        expect(askConnexWatchHref(watch({ subjectKind: "deal", subjectId: 3 })))
            .toBe("/records/deals/3");
    });

    /**
     * The evaluated commitment condition is not scoped to the reader's own assigned tasks — the
     * watch is about the record — so the sentence has to say so. A trigger narrower than the rule
     * that fires it is exactly the divergence a typed watch exists to prevent.
     */
    it("says whose commitments the overdue watch counts, in both languages", () => {
        const overdue = watch({ watchType: "commitment_overdue", thresholdBand: null });
        expect(askConnexWatchTriggerText(overdue, translator("en")).toLowerCase())
            .toContain("whoever");
        expect(askConnexWatchTriggerText(overdue, translator("ja")))
            .toContain("担当者を問わず");
    });

    /**
     * The cooldown and the expiry bind whether or not the member chose them, so they are stated
     * wherever the trigger is.
     */
    it("states the cooldown and the expiry alongside the trigger, in both languages", () => {
        for (const locale of LOCALES) {
            const t = translator(locale);
            const never = askConnexWatchLimitsText(
                { cooldownDays: 7, expiresOn: null }, t, (date) => date,
            );
            const dated = askConnexWatchLimitsText(
                { cooldownDays: 1, expiresOn: "2026-09-30" }, t, (date) => `[${date}]`,
            );
            for (const text of [never.cooldown, never.expiry, dated.cooldown, dated.expiry]) {
                expect(text).not.toContain("MISSING:");
                expect(text).not.toMatch(/\{[a-zA-Z]+/);
                expect(text.trim().length).toBeGreaterThan(0);
            }
            expect(never.cooldown).toContain("7");
            expect(dated.expiry).toContain("[2026-09-30]");
        }
    });

    it("mirrors the server's declaration of which records each type can watch", () => {
        expect(askConnexWatchSupports("deal_risk_threshold", "deal")).toBe(true);
        expect(askConnexWatchSupports("deal_risk_threshold", "person")).toBe(false);
        expect(askConnexWatchSupports("relationship_cooling", "deal")).toBe(false);
        expect(askConnexWatchSupports("commitment_overdue", "company")).toBe(true);
    });
});

describe("Ask Connex command centre copy", () => {
    const REQUIRED_KEYS = [
        "briefsTitle",
        "briefsBody",
        "briefsUnavailable",
        "openLatestBrief",
        "dailyLabel",
        "dailyHourLabel",
        "weeklyLabel",
        "weeklyDayLabel",
        "hour",
        "scheduleZone",
        "scheduleFailed",
        "watchesTitle",
        "watchesEmpty",
        "watchesHint",
        "watchActions",
        "watchFailed",
        "pause",
        "resume",
        "delete",
        "statusActive",
        "statusPaused",
        "neverFired",
        "lastFired",
        "subjectUnavailable",
        "createMenuItem",
        "createTitle",
        "createDescription",
        "createTypeLabel",
        "createBandLabel",
        "createDaysLabel",
        "createDaysOption",
        "createLevelLabel",
        "createPreviewLabel",
        "createPreviewNote",
        "createCancel",
        "createApply",
        "watchesUsage",
        "cooldownEvery",
        "expiresNever",
        "expiresOnDate",
        "deleteTitle",
        "deleteBody",
        "deleteBodyNamed",
    ];

    it("carries every command-centre string in both languages", () => {
        for (const locale of LOCALES) {
            const messages = catalog(locale);
            for (const key of REQUIRED_KEYS) {
                expect(
                    lookup(messages, `AskConnex.commandCenter.${key}`),
                    `${locale} is missing AskConnex.commandCenter.${key}`,
                ).toBeTypeOf("string");
            }
            for (const weekday of [1, 2, 3, 4, 5, 6, 7]) {
                expect(
                    lookup(messages, `AskConnex.commandCenter.weekday.${weekday}`),
                ).toBeTypeOf("string");
            }
            for (const type of ASK_CONNEX_WATCH_TYPES) {
                expect(
                    lookup(messages, `AskConnex.commandCenter.watchTypeName.${type}`),
                    `${locale} is missing a name for ${type}`,
                ).toBeTypeOf("string");
            }
            for (const band of ["warm", "cool", "cold"]) {
                expect(lookup(messages, `AskConnex.commandCenter.band.${band}`)).toBeTypeOf("string");
            }
            for (const level of ["medium", "high"]) {
                expect(lookup(messages, `AskConnex.commandCenter.level.${level}`)).toBeTypeOf("string");
            }
        }
    });

    it("carries the brief and watch notification copy in both languages", () => {
        const keys = [
            "aiDailyBriefTitle",
            "aiDailyBriefBody",
            "aiWeeklyReviewTitle",
            "aiWeeklyReviewBody",
            "aiWatchTitle",
            "aiWatchBody",
            "aiWatchCoolingTitle",
            "aiWatchCoolingBody",
            "aiWatchQuietTitle",
            "aiWatchQuietBody",
            "aiWatchOverdueTitle",
            "aiWatchOverdueBody",
            "aiWatchRiskTitle",
            "aiWatchRiskBody",
            "aiWatchBand_warm",
            "aiWatchBand_cool",
            "aiWatchBand_cold",
            "aiWatchLevel_medium",
            "aiWatchLevel_high",
        ];
        for (const locale of LOCALES) {
            const messages = notificationCatalog(locale);
            for (const key of keys) {
                expect(
                    lookup(messages, `Notifications.${key}`),
                    `${locale} is missing Notifications.${key}`,
                ).toBeTypeOf("string");
            }
        }
    });

    it("keeps the assistant out of the watch decision in its own copy", () => {
        for (const locale of LOCALES) {
            const t = translator(locale);
            const hint = t("watchesHint");
            expect(hint).not.toContain("MISSING:");
            // No vague "Ask Connex noticed something" language: the product contract is that a
            // notification names the rule and the change, so the copy promising that must too.
            expect(hint.toLowerCase()).not.toContain("noticed something");
        }
    });
});

describe("Ask Connex watch subject vocabulary", () => {
    it("offers exactly the evaluated types in the picker, with nothing derived from key order", () => {
        expect([...ASK_CONNEX_WATCH_TYPES].sort())
            .toEqual(Object.keys(ASK_CONNEX_WATCH_SUBJECTS).sort());
    });

    it("declares at least one creatable record kind for every evaluated type", () => {
        const kinds: AiWatchSubjectKind[] = ["person", "company", "deal"];
        for (const [type, subjects] of Object.entries(ASK_CONNEX_WATCH_SUBJECTS)) {
            expect(subjects.length, `${type} must be creatable somewhere`).toBeGreaterThan(0);
            for (const subject of subjects) {
                expect(kinds).toContain(subject);
            }
        }
    });
});
