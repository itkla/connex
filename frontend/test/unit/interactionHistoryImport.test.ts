import { describe, expect, it } from "vitest";

import {
    buildHistoryImportMapping,
    historyImportFields,
    historyImportMappingIsComplete,
    suggestHistoryImportField,
} from "@/app/lib/interaction-history-import";

describe("interaction history import mapping", () => {
    it("offers common and kind-specific fields", () => {
        expect(historyImportFields("activities").map((field) => field.key)).toEqual([
            "occurredAt",
            "participantEmail",
            "participantPhone",
            "sourceId",
            "subject",
            "type",
            "notes",
        ]);
        expect(historyImportFields("notes").map((field) => field.key)).toContain("content");
        expect(historyImportFields("tasks").map((field) => field.key)).toContain("completed");
    });

    it("recognizes common CRM export headers", () => {
        expect(suggestHistoryImportField("Activity date", "activities")).toBe("occurredAt");
        expect(suggestHistoryImportField("Contact Email", "notes")).toBe("participantEmail");
        expect(suggestHistoryImportField("Task Description", "tasks")).toBe("description");
        expect(suggestHistoryImportField("Unknown field", "activities")).toBeNull();
    });

    it("requires one participant identity and every required kind field", () => {
        expect(historyImportMappingIsComplete("activities", {
            date: "occurredAt",
            email: "participantEmail",
            subject: "subject",
        })).toBe(true);
        expect(historyImportMappingIsComplete("activities", {
            date: "occurredAt",
            subject: "subject",
        })).toBe(false);
        expect(historyImportMappingIsComplete("notes", {
            date: "occurredAt",
            phone: "participantPhone",
            content: "content",
        })).toBe(true);
    });

    it("rejects duplicate field assignments and omits ignored columns", () => {
        const targets = {
            first: "participantEmail",
            second: "participantEmail",
            third: "occurredAt",
            fourth: "description",
            fifth: "ignore",
        };
        expect(historyImportMappingIsComplete("tasks", targets)).toBe(false);
        expect(buildHistoryImportMapping(Object.keys(targets), targets)).toEqual([
            { column: "first", field: "participantEmail" },
            { column: "second", field: "participantEmail" },
            { column: "third", field: "occurredAt" },
            { column: "fourth", field: "description" },
        ]);
    });
});
