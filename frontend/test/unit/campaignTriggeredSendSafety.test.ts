import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const DELIVERY_PANEL = "app/components/marketing/campaigns/CampaignDelivery.tsx";
const TYPES = "app/lib/types.ts";

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), "utf8");
}

type MessageValue = string | { [key: string]: MessageValue };

function isMessageTree(value: unknown): value is { [key: string]: MessageValue } {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

describe("workflow-managed campaign sends remain read-only on the campaign surface", () => {
    it("models their rollback-readable snapshot and closed origin vocabulary", () => {
        const types = source(TYPES);
        const send = types.slice(
            types.indexOf("export type CampaignSend ="),
            types.indexOf("};", types.indexOf("export type CampaignSend =")),
        );

        expect(send).toContain("snapshotId: number;");
        expect(send).toContain('origin: "audience" | "triggered"');
    });

    it("limits queue, pause, and cancel eligibility to audience sends", () => {
        const panel = source(DELIVERY_PANEL);

        expect(panel).toContain('const isAudienceSend = send.origin === "audience"');
        expect(panel).toContain("const canQueue = isAudienceSend");
        expect(panel).toContain("const canPause = isAudienceSend");
        expect(panel).toContain("isAudienceSend && (");
    });

    it("labels the managed origin in both locales", () => {
        for (const locale of ["en", "ja"] as const) {
            const parsed: unknown = JSON.parse(source(`messages/${locale}/campaigns.json`));
            if (!isMessageTree(parsed) || !isMessageTree(parsed.CampaignSends)) {
                throw new Error(`messages/${locale}/campaigns.json has no CampaignSends namespace`);
            }
            expect(parsed.CampaignSends.workflowOrigin).toBeTruthy();
        }
    });
});
