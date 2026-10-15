// @vitest-environment jsdom
import { act, useEffect } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { PrivilegedMfaStandingState } from "@/app/hooks/usePrivilegedMfaStanding";
import type { PasskeyRegistrationRequirements } from "@/app/lib/types";

const store = vi.hoisted(() => ({
    requirements: null as PasskeyRegistrationRequirements | null,
    reason: null as "enrollment" | "attestation" | null,
    listeners: new Set<() => void>(),
    identityListeners: new Set<() => void>(),
    outcomes: [] as Array<{ outcome: "applied" | "superseded" | "failed"; requirements?: PasskeyRegistrationRequirements }>,
    settle: vi.fn(),
}));

vi.mock("@/app/lib/api", () => ({
    currentPrivilegedMfaRequirements: () => store.requirements,
    privilegedMfaConfinementReason: () => store.reason,
    subscribePrivilegedMfaConfinement: (listener: () => void) => {
        store.listeners.add(listener);
        return () => store.listeners.delete(listener);
    },
    subscribeClientRequestIdentityInvalidation: (listener: () => void) => {
        store.identityListeners.add(listener);
        return () => store.identityListeners.delete(listener);
    },
    settlePrivilegedMfaStanding: store.settle,
}));

function requirements(standing: PasskeyRegistrationRequirements["standing"]): PasskeyRegistrationRequirements {
    return {
        currentPasswordRequired: false,
        emailConfirmationRequired: false,
        emailConfirmationSatisfied: false,
        standing,
        approvalGaps: [],
    };
}

let container: HTMLDivElement;
let root: Root;
let latest: PrivilegedMfaStandingState | null = null;

async function mountHook() {
    const { usePrivilegedMfaStanding } = await import("@/app/hooks/usePrivilegedMfaStanding");
    function Probe() {
        const state = usePrivilegedMfaStanding();
        useEffect(() => {
            latest = state;
        });
        return null;
    }
    await act(async () => root.render(<Probe />));
}

function current(): PrivilegedMfaStandingState {
    if (latest === null) throw new Error("usePrivilegedMfaStanding did not render");
    return latest;
}

beforeEach(() => {
    container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
    latest = null;
    store.requirements = null;
    store.reason = null;
    store.outcomes = [];
    store.settle.mockImplementation(async () => {
        const next = store.outcomes.shift() ?? { outcome: "failed" as const };
        if (next.requirements) {
            store.requirements = next.requirements;
            store.reason = next.requirements.standing === "ATTESTATION_REQUIRED" ? "attestation" : null;
            for (const listener of store.listeners) listener();
        }
        return next.outcome;
    });
});

afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    vi.clearAllMocks();
});

describe("the privileged-MFA standing a surface sees", () => {
    it("reads on mount and reports the decisive standing", async () => {
        store.outcomes = [{ outcome: "applied", requirements: requirements("ATTESTATION_REQUIRED") }];
        await mountHook();

        expect(store.settle).toHaveBeenCalledWith("check");
        expect(current().status).toBe("ready");
        expect(current().confined).toBe(true);
        expect(current().requirements?.standing).toBe("ATTESTATION_REQUIRED");
    });

    it("reports a failed read until a later one decides", async () => {
        store.outcomes = [{ outcome: "failed" }, { outcome: "applied", requirements: requirements("SATISFIED") }];
        await mountHook();
        expect(current().status).toBe("failed");
        expect(current().confined).toBe(false);

        await act(async () => {
            await current().refresh();
        });

        expect(current().status).toBe("ready");
        expect(current().confined).toBe(false);
    });

    it("stays loading, and so not cleared, before any read has decided", async () => {
        store.settle.mockImplementation(() => new Promise(() => undefined));
        await mountHook();

        expect(current().status).toBe("loading");
        expect(current().requirements).toBeNull();
    });

    it("re-reads when the tab returns and when the identity changes, and stops once unmounted", async () => {
        store.outcomes = [
            { outcome: "applied", requirements: requirements("SATISFIED") },
            { outcome: "applied", requirements: requirements("SATISFIED") },
            { outcome: "applied", requirements: requirements("SATISFIED") },
        ];
        await mountHook();
        expect(store.settle).toHaveBeenCalledTimes(1);

        await act(async () => {
            document.dispatchEvent(new Event("visibilitychange"));
        });
        await act(async () => {
            for (const listener of store.identityListeners) listener();
        });
        expect(store.settle).toHaveBeenCalledTimes(3);

        await act(async () => root.unmount());
        root = createRoot(container);
        document.dispatchEvent(new Event("visibilitychange"));
        expect(store.identityListeners.size).toBe(0);
        expect(store.settle).toHaveBeenCalledTimes(3);
    });
});
