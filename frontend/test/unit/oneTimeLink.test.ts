import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

type OneTimeLinkModule = typeof import("@/app/lib/oneTimeLink");

let takeOneTimeLinkToken: OneTimeLinkModule["takeOneTimeLinkToken"];
let syncStrippedUrlWithRouter: OneTimeLinkModule["syncStrippedUrlWithRouter"];

beforeEach(async () => {
    vi.resetModules();
    ({ takeOneTimeLinkToken, syncStrippedUrlWithRouter } = await import("@/app/lib/oneTimeLink"));
});

afterEach(() => {
    vi.unstubAllGlobals();
});

/** The mutable location stub both the strip and the router sync read. */
type StubLocation = { hash: string; search: string; pathname: string };

/** Stubs the window surface both the strip and the router sync touch. */
function stubWindow(location: StubLocation, state: unknown) {
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
    });

    it("returns null without rewriting an already canonical URL", () => {
        const replaceState = stubWindow({ hash: "", search: "", pathname: "/invite" }, null);

        expect(takeOneTimeLinkToken()).toBeNull();
        expect(replaceState).not.toHaveBeenCalled();
    });

});

describe("syncStrippedUrlWithRouter", () => {
    it("replays the stripped URL with a state the router patch has to adopt", () => {
        const location = { hash: "#token=secret_bearer_value_123456", search: "", pathname: "/invite" };
        const replaceState = stubWindow(
            location,
            { __NA: true, __PRIVATE_NEXTJS_INTERNALS_TREE: { tree: "seeded" } },
        );
        takeOneTimeLinkToken();
        location.hash = "";
        replaceState.mockClear();

        syncStrippedUrlWithRouter();

        expect(replaceState).toHaveBeenCalledWith(null, "", "/invite");
    });

    it("replays the stripped URL once", () => {
        const location = { hash: "#token=secret_bearer_value_123456", search: "", pathname: "/invite" };
        const replaceState = stubWindow(location, { __NA: true });
        takeOneTimeLinkToken();
        location.hash = "";
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

    it("replays the URL the document holds when something moved it past the stripped one", () => {
        const location = { hash: "#token=secret_bearer_value_123456", search: "", pathname: "/invite" };
        const replaceState = stubWindow(location, { __NA: true });
        takeOneTimeLinkToken();
        location.hash = "";
        location.pathname = "/dashboard";
        location.search = "?workspace=42";
        replaceState.mockClear();

        syncStrippedUrlWithRouter();

        expect(replaceState).toHaveBeenCalledWith(null, "", "/dashboard?workspace=42");
    });

    it("stands down while a second link's bearer is still in the fragment, and stays owed", () => {
        const location = { hash: "#token=secret_bearer_value_123456", search: "", pathname: "/invite" };
        const replaceState = stubWindow(location, { __NA: true });
        takeOneTimeLinkToken();
        location.hash = "#token=second_bearer_value_654321";
        replaceState.mockClear();

        syncStrippedUrlWithRouter();

        expect(replaceState).not.toHaveBeenCalled();

        location.hash = "";
        syncStrippedUrlWithRouter();

        expect(replaceState).toHaveBeenCalledWith(null, "", "/invite");
    });
});
