import { createTranslator } from "next-intl";
import { describe, expect, it } from "vitest";

import en from "@/messages/en/deals.json";
import ja from "@/messages/ja/deals.json";

const CATALOGUES = { en, ja } as const;

type Locale = keyof typeof CATALOGUES;

const LOCALES = Object.keys(CATALOGUES) as Locale[];

const STAKEHOLDER_KEYS = ["factorStakeholderCold", "factorStakeholderCooling"] as const;

function riskTranslator(locale: Locale) {
    return createTranslator({
        locale,
        messages: CATALOGUES[locale],
        namespace: "DealRisk",
    });
}

describe("the cooling-stakeholder sentence names one contact both surfaces can render", () => {
    it.each(LOCALES)("renders %s as plain text for the surfaces that need a string", (locale) => {
        const t = riskTranslator(locale);

        for (const key of STAKEHOLDER_KEYS) {
            const sentence = t.markup(key, {
                contact: "Champion Aya Tanaka",
                person: (chunks) => chunks,
            });

            expect(sentence).toContain("Champion Aya Tanaka");
            expect(sentence).not.toContain("<person>");
            expect(sentence).not.toContain("</person>");
        }
    });

    it.each(LOCALES)("wraps only the contact in %s so the panel can link them", (locale) => {
        const t = riskTranslator(locale);

        for (const key of STAKEHOLDER_KEYS) {
            const marked = t.markup(key, {
                contact: "Champion Aya Tanaka",
                person: (chunks) => `[${chunks}]`,
            });

            expect(marked).toContain("[Champion Aya Tanaka]");
        }
    });
});
