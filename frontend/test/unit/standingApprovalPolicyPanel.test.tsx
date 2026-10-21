/** @vitest-environment jsdom */
import { act, type ChangeEvent } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import OrgStandingApprovalPolicy from "@/app/components/organization/OrgStandingApprovalPolicy";
import type { AiStandingApprovalPolicy, AiStandingApprovalPolicyRequest } from "@/app/lib/types";

const mocks = vi.hoisted(() => ({
    get: vi.fn<(workspaceId: number, init: RequestInit) => Promise<AiStandingApprovalPolicy>>(),
    save: vi.fn<(workspaceId: number, request: AiStandingApprovalPolicyRequest, init: RequestInit) => Promise<AiStandingApprovalPolicy>>(),
    error: vi.fn(), success: vi.fn(),
    invalidated: { current: () => {} },
}));
vi.mock("next-intl", () => ({ useTranslations: () => (key: string) => key }));
vi.mock("@/app/hooks/useApiErrorToast", () => ({ useApiErrorToast: () => mocks.error }));
vi.mock("@/app/lib/toast", () => ({ toastSuccess: mocks.success }));
vi.mock("@/app/lib/api", () => ({
    getAiStandingApprovalPolicy: mocks.get, saveAiStandingApprovalPolicy: mocks.save,
    ApiError: class extends Error { status = 409; },
}));
vi.mock("@/app/lib/standingApprovalPolicy", async () => {
    const actual = await vi.importActual<typeof import("@/app/lib/standingApprovalPolicy")>("@/app/lib/standingApprovalPolicy");
    return { ...actual, subscribeStandingApprovalPolicy: (callback: () => void) => {
        mocks.invalidated.current = callback;
        return () => {};
    } };
});
vi.mock("@/components/ui/switch", () => ({ Switch: ({ checked, onCheckedChange, id }: {
    checked: boolean; onCheckedChange: (checked: boolean) => void; id: string;
}) => <input id={id} type="checkbox" checked={checked}
    onChange={(event: ChangeEvent<HTMLInputElement>) => onCheckedChange(event.target.checked)} /> }));

const initial: AiStandingApprovalPolicy = {
    provisioned: true, authoringEnabled: false, enabled: false, allowedTools: [],
    maximumDailyCap: 100, maximumPerRunCap: 10, stateVersion: "1", killEpoch: "1",
};
let host: HTMLDivElement;
let root: Root;

function button(text: string): HTMLButtonElement {
    const found = [...host.querySelectorAll("button")].find((value) => value.textContent === text);
    if (!found) throw new Error(`Missing button ${text}`);
    return found;
}

async function mount() {
    await act(async () => { root.render(<OrgStandingApprovalPolicy workspaceId={9} />); });
}

beforeEach(() => {
    Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
    mocks.get.mockReset().mockResolvedValue(initial);
    mocks.save.mockReset().mockResolvedValue({ ...initial, stateVersion: "2", killEpoch: "2" });
    mocks.error.mockReset();
    host = document.createElement("div");
    document.body.append(host);
    root = createRoot(host);
});
afterEach(async () => {
    await act(async () => root.unmount());
    host.remove();
});

describe("organization standing approval controls", () => {
    it("refreshes clean settings on connect or invalidation and keeps safety controls available", async () => {
        await mount();
        expect(button("stop").disabled).toBe(false);
        expect(host.textContent).toContain("unavailable");
        await act(async () => mocks.invalidated.current());
        expect(mocks.get).toHaveBeenCalledTimes(2);
        expect(button("save").disabled).toBe(false);
        expect(host.querySelectorAll("a")).toHaveLength(0);
    });
    it("preserves edits when another administrator changes the policy until explicit refresh", async () => {
        await mount();
        const toggle = host.querySelector("#standing-approval-enabled");
        if (!(toggle instanceof HTMLInputElement)) throw new Error("Missing switch");
        await act(async () => toggle.click());
        await act(async () => mocks.invalidated.current());
        expect(mocks.get).toHaveBeenCalledTimes(1);
        expect(toggle.checked).toBe(true);
        expect(button("save").disabled).toBe(true);
        await act(async () => button("refresh").click());
        expect(mocks.get).toHaveBeenCalledTimes(2);
        expect(toggle.checked).toBe(false);
        expect(button("stop").disabled).toBe(false);
    });
    it("refuses an older GET that completes after an acknowledged kill", async () => {
        await mount();
        let finishRead: (value: AiStandingApprovalPolicy) => void = () => {};
        mocks.get.mockImplementationOnce(() => new Promise<AiStandingApprovalPolicy>((resolve) => {
            finishRead = resolve;
        }));
        await act(async () => mocks.invalidated.current());
        await act(async () => button("stop").click());
        await act(async () => finishRead({ ...initial, enabled: true }));
        const toggle = host.querySelector("#standing-approval-enabled");
        if (!(toggle instanceof HTMLInputElement)) throw new Error("Missing switch");
        expect(toggle.checked).toBe(false);
        expect(mocks.success).toHaveBeenCalledWith("stopped");
    });
    it("pins an organization kill to the rendered workspace and persisted settings", async () => {
        await mount();
        const toggle = host.querySelector("#standing-approval-enabled");
        if (!(toggle instanceof HTMLInputElement)) throw new Error("Missing switch");
        await act(async () => toggle.click());
        await act(async () => button("stop").click());
        expect(mocks.save).toHaveBeenCalledWith(9, {
            enabled: false, allowedTools: [], maximumDailyCap: 100,
            maximumPerRunCap: 10, expectedStateVersion: "1",
        }, expect.objectContaining({ headers: expect.any(Headers) }));
        const options = mocks.save.mock.calls[0]?.[2];
        expect(new Headers(options?.headers).get("X-Workspace-Id")).toBe("9");
        expect(mocks.success).toHaveBeenCalledWith("stopped");
    });
});
