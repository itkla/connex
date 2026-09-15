/** @vitest-environment jsdom */
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/** The session the mocked `getCurrentUserResultFromCookie` resolves for the page under test. */
const session = vi.hoisted(() => ({
    result: { ok: true, data: null } as { ok: true; data: { id: number } | null } | { ok: false },
}));

vi.mock("@/app/components/AuthForm", () => ({ AuthForm: () => null }));
vi.mock("next/headers", () => ({
    headers: async () => new Headers({ cookie: "JSESSIONID=session; connex_workspace=11" }),
}));
vi.mock("@/app/lib/api", async (importOriginal) => {
    const actual = await importOriginal<typeof import("@/app/lib/api")>();
    return {
        ...actual,
        getCapabilities: async () => null,
        toResult: async () => ({ ok: false }),
        getCurrentUserResultFromCookie: async () => session.result,
    };
});

import LoginPage from "@/app/auth/login/page";
import SignedOutStorageCleanup from "@/app/components/SignedOutStorageCleanup";

const ACCOUNT_KEY = "connex:browser-account";
const DATA_KEYS = [
    "connex:recents:7:11",
    "connex:view:7:11:ask-connex:pinned",
    "connex:columns:persons:7:11",
    "connex:density:7:11",
    "connex:sidebar-mode:7:11",
    "connex:business-card-import-recovery:v2:7:request",
];
const RETURN_KEY = "connex:record-return-context";
const DRAFT_KEY = "connex:draft:7:11:note:new";
const storage = window.localStorage;
const drafts = window.sessionStorage;
let container: HTMLDivElement;
let root: Root;

function seedAbandonedSession() {
    for (const key of DATA_KEYS) storage.setItem(key, "Unique private contact");
    storage.setItem(ACCOUNT_KEY, "7");
    storage.setItem("theme", "dark");
    drafts.setItem(RETURN_KEY, "/records/contacts/42");
    drafts.setItem(DRAFT_KEY, "Recoverable private draft");
}

function entries(area: Storage): Record<string, string> {
    const found: Record<string, string> = {};
    for (let index = 0; index < area.length; index += 1) {
        const key = area.key(index);
        if (key !== null) found[key] = area.getItem(key) ?? "";
    }
    return found;
}

function expectRecordDataGone() {
    for (const key of DATA_KEYS) expect(storage.getItem(key), key).toBeNull();
    expect(drafts.getItem(RETURN_KEY)).toBeNull();
}

function expectRecordDataRetained() {
    for (const key of DATA_KEYS) expect(storage.getItem(key), key).toBe("Unique private contact");
    expect(drafts.getItem(RETURN_KEY)).toBe("/records/contacts/42");
    expect(drafts.getItem(DRAFT_KEY)).toBe("Recoverable private draft");
}

beforeEach(() => {
    vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true);
    session.result = { ok: true, data: null };
    storage.clear();
    drafts.clear();
    container = document.createElement("div");
    document.body.append(container);
    root = createRoot(container);
});

afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    storage.clear();
    drafts.clear();
    vi.unstubAllGlobals();
});

describe("unauthenticated shell storage cleanup", () => {
    it("removes record data a session left behind without an explicit sign-out", async () => {
        seedAbandonedSession();

        await act(async () => root.render(<SignedOutStorageCleanup />));

        expectRecordDataGone();
        expect(storage.getItem("theme")).toBe("dark");
        expect(storage.getItem(ACCOUNT_KEY)).toBe("7");
    });

    it("leaves a browser that holds no record data untouched", async () => {
        storage.setItem("theme", "dark");
        storage.setItem("calendar:view", "month");
        storage.setItem(ACCOUNT_KEY, "7");
        drafts.setItem(DRAFT_KEY, "Recoverable private draft");
        const before = { local: entries(storage), session: entries(drafts) };

        await act(async () => root.render(<SignedOutStorageCleanup />));

        expect(Object.keys(before.local)).toHaveLength(3);
        expect(entries(storage)).toEqual(before.local);
        expect(entries(drafts)).toEqual(before.session);
    });

    it("runs when the sign-in page loads for the next browser user", async () => {
        seedAbandonedSession();

        const page = await LoginPage({ searchParams: Promise.resolve({}) });
        await act(async () => root.render(page));

        expectRecordDataGone();
    });

    it("keeps a signed-in visitor's record data when the sign-in page renders behind a redirect", async () => {
        seedAbandonedSession();
        session.result = { ok: true, data: { id: 7 } };

        const page = await LoginPage({ searchParams: Promise.resolve({ redirect: "/dashboard" }) });
        await act(async () => root.render(page));

        expectRecordDataRetained();
        expect(storage.getItem(ACCOUNT_KEY)).toBe("7");
    });

    it("removes nothing when the authentication check cannot be completed", async () => {
        seedAbandonedSession();
        session.result = { ok: false };

        const page = await LoginPage({ searchParams: Promise.resolve({ redirect: "/dashboard" }) });
        await act(async () => root.render(page));

        expectRecordDataRetained();
    });
});
