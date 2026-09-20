import { afterEach, describe, expect, it, vi } from "vitest";

import { syncStrippedUrlWithRouter, takeOneTimeLinkToken } from "@/app/lib/oneTimeLink";

afterEach(() => {
    vi.unstubAllGlobals();
});

/** Stubs the window surface both the strip and the router sync touch. */
function stubWindow(location: { hash: string; search: string; pathname: string }, state: unknown) {
    const replaceState = vi.fn();
    vi.stubGlobal("window", { location, history: { state, replaceState } });
    return replaceState;
}

describe("takeOneTimeLinkToken", () => {
    it("removes the fragment and query before returning the bearer", () => {
        const replaceState = stubWindow({
            hash: "#token=secret_bearer_value_123456",
            search: "?tracking=mail",
            pathname: "/auth/reset-password",
        }, { navigation: 1 });

        expect(takeOneTimeLinkToken()).toBe("secret_bearer_value_123456");
        expect(replaceState).toHaveBeenCalledWith(
            { navigation: 1 },
            "",
            "/auth/reset-password",
        );

        syncStrippedUrlWithRouter();
    });

    it("returns null without rewriting an already canonical URL", () => {
        const replaceState = stubWindow({ hash: "", search: "", pathname: "/invite" }, null);

        expect(takeOneTimeLinkToken()).toBeNull();
        expect(replaceState).not.toHaveBeenCalled();
    });

});

describe("syncStrippedUrlWithRouter", () => {
    it("replays the stripped URL with a state the router patch has to adopt", () => {
        const replaceState = stubWindow({
            hash: "#token=secret_bearer_value_123456",
            search: "",
            pathname: "/invite",
        }, { __NA: true, __PRIVATE_NEXTJS_INTERNALS_TREE: { tree: "seeded" } });
        takeOneTimeLinkToken();
        replaceState.mockClear();

        syncStrippedUrlWithRouter();

        expect(replaceState).toHaveBeenCalledWith(null, "", "/invite");
    });

    it("replays the stripped URL once", () => {
        const replaceState = stubWindow({
            hash: "#token=secret_bearer_value_123456",
            search: "",
            pathname: "/invite",
        }, { __NA: true });
        takeOneTimeLinkToken();
        replaceState.mockClear();

        syncStrippedUrlWithRouter();
        syncStrippedUrlWithRouter();

        expect(replaceState).toHaveBeenCalledTimes(1);
    });

    it("leaves the history entry alone when nothing was stripped", () => {
        const replaceState = stubWindow({ hash: "", search: "", pathname: "/invite" }, { __NA: true });
        takeOneTimeLinkToken();

        syncStrippedUrlWithRouter();

        expect(replaceState).not.toHaveBeenCalled();
    });
});
