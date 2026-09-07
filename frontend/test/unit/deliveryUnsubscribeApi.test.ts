/** @vitest-environment jsdom */
import { afterEach, describe, expect, it, vi } from "vitest";

import { confirmUnsubscribe, getUnsubscribeInfo } from "@/app/lib/api";
import type { DeliveryUnsubscribeInfo } from "@/app/lib/types";

const FLOW_ID = "c".repeat(64);

function validInfo(): DeliveryUnsubscribeInfo {
    return {
        flowId: FLOW_ID,
        channel: "email",
        address: "r***@dest.test",
        unsubscribed: false,
    };
}

function jsonResponse(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: { "Content-Type": "application/json" },
    });
}

function stubResponses(body: unknown) {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
        if (String(input).endsWith("/api/auth/csrf")) {
            return Promise.resolve(jsonResponse({ headerName: "X-CSRF-TOKEN", token: "csrf-token" }));
        }
        return Promise.resolve(jsonResponse(body));
    });
    vi.stubGlobal("fetch", fetchMock);
    return fetchMock;
}

afterEach(() => {
    vi.unstubAllGlobals();
});

describe("delivery unsubscribe public response boundary", () => {
    it("accepts a well-formed preview and keeps its flow identity", async () => {
        stubResponses(validInfo());

        await expect(getUnsubscribeInfo()).resolves.toEqual(validInfo());
    });

    it.each([
        ["missing", (info: DeliveryUnsubscribeInfo) => {
            delete (info as Partial<DeliveryUnsubscribeInfo>).flowId;
        }],
        ["uppercase", (info: DeliveryUnsubscribeInfo) => {
            info.flowId = "C".repeat(64);
        }],
        ["short", (info: DeliveryUnsubscribeInfo) => {
            info.flowId = "c".repeat(63);
        }],
    ])("rejects a preview whose flow identity is %s", async (_case, mutate) => {
        const info = validInfo();
        mutate(info);
        stubResponses(info);

        await expect(getUnsubscribeInfo()).rejects.toMatchObject({
            status: 502,
            code: "INVALID_PUBLIC_RESPONSE",
        });
    });

    it("echoes the previewed flow identity in the confirmation body", async () => {
        const fetchMock = stubResponses({ ...validInfo(), unsubscribed: true });

        const result = await confirmUnsubscribe({ flowId: FLOW_ID });

        expect(result.unsubscribed).toBe(true);
        const confirmation = fetchMock.mock.calls
            .find((call) => !String(call[0]).endsWith("/api/auth/csrf"));
        if (!confirmation) throw new Error("The confirmation request was not sent");
        const init: RequestInit | undefined = confirmation[1];
        expect(String(confirmation[0])).not.toContain(FLOW_ID);
        expect(init?.method).toBe("POST");
        expect(init?.credentials).toBe("include");
        expect(init?.body).toBe(JSON.stringify({ flowId: FLOW_ID }));
        expect(new Headers(init?.headers).get("X-CSRF-TOKEN")).toBe("csrf-token");
    });
});
