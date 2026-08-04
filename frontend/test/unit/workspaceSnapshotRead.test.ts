import { afterEach, describe, expect, it, vi } from "vitest";

import { getMyWorkspacesFromCookie } from "@/app/lib/api";

afterEach(() => {
    vi.unstubAllGlobals();
});

describe("workspace snapshot reads", () => {
    it("surfaces a backend failure instead of fabricating an empty snapshot", async () => {
        vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(
            JSON.stringify({ message: "Temporarily unavailable" }),
            {
                status: 503,
                headers: { "Content-Type": "application/json" },
            },
        )));

        await expect(getMyWorkspacesFromCookie(
            "JSESSIONID=session; connex_workspace=7",
        )).rejects.toMatchObject({ status: 503 });
    });

    it("returns an empty snapshot only when there is no authenticated cookie to forward", async () => {
        const fetch = vi.fn();
        vi.stubGlobal("fetch", fetch);

        await expect(getMyWorkspacesFromCookie(null)).resolves.toEqual({
            workspaces: [],
            activeWorkspaceId: null,
        });
        expect(fetch).not.toHaveBeenCalled();
    });
});
