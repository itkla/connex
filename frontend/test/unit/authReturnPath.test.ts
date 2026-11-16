import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it, vi } from "vitest";
import { socialLoginStartPath, ssoStartPath } from "@/app/lib/api";
import { authReturnPath, authSwitchHref, returnsToInvitation } from "@/app/lib/authReturnPath";

describe("safe authentication destinations", () => {
    it.each(["/invite", "/invite-link", "/settings/workspace/people#members"])("retains %s in either direction", (path) => {
        expect(authSwitchHref("/auth/register", path)).toBe(`/auth/register?redirect=${encodeURIComponent(path)}`);
        expect(authSwitchHref("/auth/login", path)).toBe(`/auth/login?redirect=${encodeURIComponent(path)}`);
    });
    it.each(["https://elsewhere.test/invite", "//elsewhere.test/invite", "/\\elsewhere.test/invite", "/\t/elsewhere.test/invite", "javascript:alert(1)"])("refuses %s", (path) => {
        expect(authReturnPath(path)).toBeNull();
        expect(authSwitchHref("/auth/register", path)).toBe("/auth/register");
        expect(returnsToInvitation(path)).toBe(false);
    });
    it("refuses duplicate query values instead of throwing during render", () => {
        expect(authReturnPath(["/invite", "/dashboard"])).toBeNull();
        expect(authReturnPath(undefined)).toBeNull();
    });
    it("only defers workspace creation for invitation acceptance routes", () => {
        expect(returnsToInvitation("/invite-link?from=email")).toBe(true);
        expect(returnsToInvitation("/invite")).toBe(true);
        expect(returnsToInvitation("/invite-other")).toBe(false);
        expect(returnsToInvitation("/dashboard")).toBe(false);
    });
});


vi.mock("next-intl/server", () => ({ getTranslations: vi.fn() }));

describe("registration entry destination", () => {
    it("passes the invitation return path into the registration form", async () => {
        const { default: RegisterPage } = await import("@/app/auth/register/page");
        const form = await RegisterPage({ searchParams: Promise.resolve({ redirect: "/invite-link" }) });
        expect(form.props.redirectUrl).toBe("/invite-link");
    });
    it("rejects repeated destination query values", async () => {
        const { default: RegisterPage } = await import("@/app/auth/register/page");
        const form = await RegisterPage({ searchParams: Promise.resolve({ redirect: ["/invite", "/dashboard"] }) });
        expect(form.props.redirectUrl).toBeNull();
    });
});


describe("shared browser and server return-path contract", () => {
    const raw: unknown = JSON.parse(readFileSync(resolve(process.cwd(), "../backend/src/test/resources/auth-return-path-cases.json"), "utf8"));
    if (!Array.isArray(raw)) throw new Error("Return-path fixture must be an array");
    const cases = raw.map((entry: unknown) => {
        if (typeof entry !== "object" || entry === null || !("input" in entry) || typeof entry.input !== "string"
            || !("expected" in entry) || (entry.expected !== null && typeof entry.expected !== "string")) {
            throw new Error("Invalid return-path fixture");
        }
        return { input: entry.input, expected: entry.expected };
    });
    it.each(cases)("validates $input consistently", ({ input, expected }) => {
        expect(authReturnPath(input)).toBe(expected);
        expect(authSwitchHref("/auth/register", input)).toBe(expected === null ? "/auth/register"
            : `/auth/register?${new URLSearchParams({ redirect: expected })}`);
        if (expected === null) expect(returnsToInvitation(input)).toBe(false);
    });
});


describe("SSO and social login start destinations", () => {
    it.each(["oidc", "saml"] as const)("carries the validated invitation for %s", (protocol) => {
        const base = protocol === "oidc" ? "/api/oauth2/authorization/org-7" : "/saml2/authenticate/org-7";
        expect(ssoStartPath("org-7", protocol, "/invite-link")).toBe(`${base}?redirect=%2Finvite-link`);
        expect(ssoStartPath("org-7", protocol, "/%2f%2fhost")).toBe(base);
    });
    it.each(["google", "microsoft"] as const)("carries the validated invitation for %s", (provider) => {
        expect(socialLoginStartPath(provider, "/invite")).toBe(`/api/oauth2/authorization/${provider}?redirect=%2Finvite`);
        expect(socialLoginStartPath(provider, "https://connex.invalid/invite")).toBe(`/api/oauth2/authorization/${provider}`);
    });
});


it("passes the invitation destination through the SSO linking page", async () => {
    const { default: SsoLinkPage } = await import("@/app/sso/link/page");
    const form = await SsoLinkPage({ searchParams: Promise.resolve({ redirect: "/invite" }) });
    expect(form.props.redirectUrl).toBe("/invite");
});
