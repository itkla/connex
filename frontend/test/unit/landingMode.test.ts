import { describe, expect, it } from "vitest";
import { resolvePreLaunch } from "@/app/lib/landingMode";

describe("resolvePreLaunch", () => {
    it("falls back to prelaunch when nothing is configured", () => {
        expect(resolvePreLaunch({ host: "connexcrm.jp", mode: undefined, preLaunchHosts: undefined })).toBe(true);
    });

    it("honours the deployment default when the host is not listed", () => {
        expect(
            resolvePreLaunch({ host: "preview.connexcrm.jp", mode: "product", preLaunchHosts: "connexcrm.jp" }),
        ).toBe(false);
    });

    it("forces prelaunch for a listed host even when the deployment serves the product", () => {
        expect(
            resolvePreLaunch({ host: "connexcrm.jp", mode: "product", preLaunchHosts: "connexcrm.jp" }),
        ).toBe(true);
    });

    it("ignores the port, surrounding space, and case", () => {
        expect(
            resolvePreLaunch({ host: "ConnexCRM.jp:443", mode: "product", preLaunchHosts: " connexcrm.jp , www.connexcrm.jp " }),
        ).toBe(true);
    });

    it("treats a leading dot as a suffix match covering the apex and its subdomains", () => {
        const preLaunchHosts = ".connexcrm.jp";
        expect(resolvePreLaunch({ host: "connexcrm.jp", mode: "product", preLaunchHosts })).toBe(true);
        expect(resolvePreLaunch({ host: "preview.connexcrm.jp", mode: "product", preLaunchHosts })).toBe(true);
        expect(resolvePreLaunch({ host: "notconnexcrm.jp", mode: "product", preLaunchHosts })).toBe(false);
    });

    it("does not match a host that merely contains the pattern", () => {
        expect(
            resolvePreLaunch({ host: "connexcrm.jp.evil.test", mode: "product", preLaunchHosts: "connexcrm.jp" }),
        ).toBe(false);
    });

    it("keeps the product page when the host header is missing and the mode says product", () => {
        expect(resolvePreLaunch({ host: null, mode: "product", preLaunchHosts: "connexcrm.jp" })).toBe(false);
    });
});
