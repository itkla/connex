import { afterEach, describe, expect, it, vi } from "vitest";

import { takeOneTimeLinkToken } from "@/app/lib/oneTimeLink";

afterEach(() => {
    vi.unstubAllGlobals();
});

describe("takeOneTimeLinkToken", () => {
    it("removes the fragment and query before returning the bearer", () => {
        const replaceState = vi.fn();
        vi.stubGlobal("window", {
            location: {
                hash: "#token=secret_bearer_value_123456",
                search: "?tracking=mail",
                pathname: "/auth/reset-password",
            },
            history: {
                state: { navigation: 1 },
                replaceState,
            },
        });

        expect(takeOneTimeLinkToken()).toBe("secret_bearer_value_123456");
        expect(replaceState).toHaveBeenCalledWith(
            { navigation: 1 },
            "",
            "/auth/reset-password",
        );
    });

    it("returns null without rewriting an already canonical URL", () => {
        const replaceState = vi.fn();
        vi.stubGlobal("window", {
            location: { hash: "", search: "", pathname: "/invite" },
            history: { state: null, replaceState },
        });

        expect(takeOneTimeLinkToken()).toBeNull();
        expect(replaceState).not.toHaveBeenCalled();
    });

});
