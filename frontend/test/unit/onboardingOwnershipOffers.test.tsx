// @vitest-environment jsdom
import { act, createElement, type ReactNode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { SelfOwnershipOffers } from "@/app/hooks/useSelfOwnershipOffers";
import type { OwnershipOffer } from "@/app/lib/types";

const offers = vi.hoisted(() => ({
    state: null as SelfOwnershipOffers | null,
}));

vi.mock("next/navigation", () => ({
    useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
}));
vi.mock("next-intl", () => ({
    useTranslations: (namespace: string) => (key: string) => `${namespace}.${key}`,
    useFormatter: () => ({ dateTime: () => "Oct 21, 2026" }),
}));
vi.mock("motion/react", async () => {
    const react = await import("react");
    const motionOnly = new Set(["initial", "animate", "exit", "transition"]);
    const passthrough = new Proxy(
        {},
        {
            get: (_target, tag: string) => (props: Record<string, unknown>) =>
                react.createElement(tag, Object.fromEntries(
                    Object.entries(props).filter(([key]) => !motionOnly.has(key)))),
        },
    );
    return { motion: passthrough, useReducedMotion: () => true };
});
vi.mock("@/app/hooks/useSelfOwnershipOffers", () => ({
    useSelfOwnershipOffers: () => {
        if (offers.state === null) throw new Error("self offers state not configured");
        return offers.state;
    },
}));
vi.mock("@/app/hooks/useApiErrorToast", () => ({ useApiErrorToast: () => vi.fn() }));
vi.mock("@/app/components/auth/AuthBrandPanel", () => ({ default: () => null }));
vi.mock("@/app/components/account/OwnershipConfinementDialog", () => ({
    default: ({ offers: state }: { offers: SelfOwnershipOffers }): ReactNode =>
        state.confinementOffer ? createElement("div", { role: "dialog" }, "confinement") : null,
}));

const offer: OwnershipOffer = {
    id: "41",
    scopeKind: "ORGANIZATION",
    orgId: 3,
    workspaceId: null,
    offerorUserId: 7,
    offereeUserId: 11,
    state: "OPEN",
    createdAt: "2026-10-14T00:00:00Z",
    expiresAt: "2026-10-21T00:00:00Z",
    closedAt: null,
    closedByUserId: null,
    closureReason: null,
    scopeName: "Acme",
    offerorDisplayName: "Riley",
    offereeDisplayName: "Dana",
    offereeEmail: "dana@example.com",
};

let container: HTMLDivElement;
let root: Root;

function selfOffers(list: OwnershipOffer[]): SelfOwnershipOffers {
    return {
        offers: list,
        busyOfferId: null,
        confinementOffer: null,
        scopeLabel: (candidate) => candidate.scopeName ?? "",
        accept: vi.fn(async () => undefined),
        decline: vi.fn(async () => undefined),
        dismissConfinement: vi.fn(),
    };
}

async function renderOnboarding() {
    const { default: OnboardingForm } = await import("@/app/onboarding/OnboardingForm");
    await act(async () => root.render(createElement(OnboardingForm)));
}

function button(label: string): HTMLButtonElement {
    const match = [...container.querySelectorAll("button")].find((candidate) => candidate.textContent === label);
    if (!match) throw new Error(`no button labelled ${label}`);
    return match;
}

beforeEach(() => {
    container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
});

afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    offers.state = null;
});

describe("onboarding ownership offers", () => {
    it("lets an account without a workspace accept or decline its organization offer", async () => {
        const state = selfOffers([offer]);
        offers.state = state;
        await renderOnboarding();

        expect(container.textContent).toContain("AccountInvites.ownershipTitle");
        expect(container.querySelector("#onboarding-ownership-offers")).not.toBeNull();

        await act(async () => button("AccountInvites.accept").click());
        expect(state.accept).toHaveBeenCalledWith(offer);

        await act(async () => button("AccountInvites.decline").click());
        expect(state.decline).toHaveBeenCalledWith(offer);
    });

    it("renders no offers section when nothing is pending", async () => {
        offers.state = selfOffers([]);
        await renderOnboarding();

        expect(container.querySelector("#onboarding-ownership-offers")).toBeNull();
        expect(container.textContent).not.toContain("AccountInvites.ownershipTitle");
    });
});
