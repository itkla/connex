import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import { ACTIONS, EVENTS, RECORD_TYPES } from "@/app/components/settings/workflows/vocabulary";
import { locales } from "@/i18n/config";

type MessageValue = string | { [key: string]: MessageValue };

function isMessageTree(value: unknown): value is { [key: string]: MessageValue } {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function workspaceRules(locale: string): { [key: string]: MessageValue } {
    const parsed: unknown = JSON.parse(
        readFileSync(path.resolve(process.cwd(), "messages", locale, "workspace.json"), "utf8"),
    );
    if (!isMessageTree(parsed) || !isMessageTree(parsed.WorkspaceRules)) {
        throw new Error(`messages/${locale}/workspace.json has no WorkspaceRules namespace`);
    }
    return parsed.WorkspaceRules;
}

function resolve(tree: { [key: string]: MessageValue }, key: string): string | null {
    let current: MessageValue = tree;
    for (const segment of key.split(".")) {
        if (!isMessageTree(current)) return null;
        const next: MessageValue | undefined = current[segment];
        if (next === undefined) return null;
        current = next;
    }
    return typeof current === "string" ? current : null;
}

function missingKeys(locale: string): string[] {
    const rules = workspaceRules(locale);
    const keys = [
        ...RECORD_TYPES.map((recordType) => `record.${recordType}`),
        ...Object.values(EVENTS).flat().map((event) => `event.${event}`),
        ...Object.values(ACTIONS).flat().map((action) => `action.${action}`),
    ];
    return [...new Set(keys)].filter((key) => resolve(rules, key) === null);
}

describe("every authoring vocabulary token has a label in every locale", () => {
    it.each(locales)("%s renders each record type, event, and action", (locale) => {
        expect(missingKeys(locale)).toEqual([]);
    });

    it("declares events and actions for every selectable record type", () => {
        expect(RECORD_TYPES.filter((recordType) => (EVENTS[recordType] ?? []).length === 0)).toEqual([]);
        expect(RECORD_TYPES.filter((recordType) => (ACTIONS[recordType] ?? []).length === 0)).toEqual([]);
    });
});
