import { describe, expect, it, vi } from "vitest";
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
