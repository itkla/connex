import { afterEach, expect, it, vi } from "vitest";

import { getGeneratedDocumentTargets } from "@/app/lib/api";

afterEach(() => vi.unstubAllGlobals());

it("reads only the requested document ids with the supplied workspace and cancellation signal", async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(new Response('[{"id":61,"dealId":41,"title":"Quote"}]', {
        headers: { "content-type": "application/json" },
    }));
    vi.stubGlobal("fetch", fetch);
    const controller = new AbortController();
    expect(await getGeneratedDocumentTargets([61, 62], {
        headers: { "X-Workspace-Id": "7" }, signal: controller.signal,
    })).toEqual([{ id: 61, dealId: 41, title: "Quote" }]);
    const [url, init] = fetch.mock.calls[0] ?? [];
    const parsed = new URL(String(url), "http://localhost");
    expect(parsed.pathname).toBe("/api/documents/targets");
    expect(parsed.searchParams.getAll("ids")).toEqual(["61", "62"]);
    expect(init).toEqual(expect.objectContaining({ method: "GET", signal: controller.signal, cache: "no-store" }));
    expect(new Headers(init?.headers).get("X-Workspace-Id")).toBe("7");
});
