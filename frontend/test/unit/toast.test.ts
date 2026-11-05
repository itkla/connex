import { beforeEach, describe, expect, it, vi } from "vitest";

const sonner = vi.hoisted(() => ({
    dismiss: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
    loading: vi.fn(() => "loading-toast"),
    success: vi.fn(),
    warning: vi.fn(),
}));

vi.mock("sonner", () => ({ toast: sonner }));

import { toastDismiss, toastLoading, toastError, toastSuccess, toastInfo, toastWarn } from "@/app/lib/toast";

describe("branded toast lifecycle helpers", () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it("shows loading feedback with the brand treatment and preserves caller options", () => {
        expect(toastLoading("Exporting", {
            id: "export",
            style: { color: "black" },
        })).toBe("loading-toast");

        expect(sonner.loading).toHaveBeenCalledWith("Exporting", {
            id: "export",
            style: {
                backgroundColor: "var(--color-brand)",
                color: "black",
            },
        });
    });

    it("dismisses the requested toast through the shared boundary", () => {
        toastDismiss("export");

        expect(sonner.dismiss).toHaveBeenCalledWith("export");
    });
});


describe("semantic toast foregrounds", () => {
    it.each([
        [toastSuccess, sonner.success, "brand"],
        [toastLoading, sonner.loading, "brand"],
        [toastError, sonner.error, "destructive"],
        [toastInfo, sonner.info, "info"],
        [toastWarn, sonner.warning, "warning"],
    ] as const)("pairs the variant surface and foreground", (show, spy, token) => {
        show("Outcome", { description: "Recovery details", id: "outcome" });
        expect(spy).toHaveBeenLastCalledWith("Outcome", {
            description: "Recovery details",
            id: "outcome",
            style: {
                backgroundColor: `var(--color-${token})`,
                color: `var(--color-${token}-foreground)`,
            },
        });
    });
});
