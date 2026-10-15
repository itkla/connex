import { act, useEffect, type ReactNode } from "react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, it, vi } from "vitest";

import ConfinementAcknowledgementDialog from "@/app/components/settings/ConfinementAcknowledgementDialog";
import {
    useConfinementAcknowledgement,
    type ConfinementAcknowledgement,
} from "@/app/hooks/useConfinementAcknowledgement";
import { ApiError, PRIVILEGED_MFA_CONFINEMENT_ACKNOWLEDGEMENT_REQUIRED_CODE } from "@/app/lib/api";
import enSettings from "@/messages/en/settings.json";
import jaSettings from "@/messages/ja/settings.json";
import {
    installInteractiveDocument,
    type InteractiveElement,
} from "@/test/unit/helpers/interactiveDocument";

vi.mock("@/components/ui/dialog", async () => {
    const React = await import("react");
    const Passthrough = ({ children }: { children?: ReactNode }) =>
        React.createElement(React.Fragment, null, children);
    return {
        Dialog: ({ open, children }: { open: boolean; children?: ReactNode }) =>
            open ? React.createElement(React.Fragment, null, children) : null,
        DialogContent: Passthrough,
        DialogDescription: Passthrough,
        DialogFooter: Passthrough,
        DialogHeader: Passthrough,
        DialogTitle: Passthrough,
    };
});

const mountedRoots: Array<{ unmount: () => void }> = [];

function refusal(affectedCount?: number): ApiError {
    const error = new ApiError("confinement", 409, PRIVILEGED_MFA_CONFINEMENT_ACKNOWLEDGEMENT_REQUIRED_CODE);
    error.affectedCount = affectedCount;
    return error;
}

function refusedOnce<T>(result: T, affectedCount = 1) {
    return vi.fn(async (acknowledge: boolean) => {
        if (!acknowledge) throw refusal(affectedCount);
        return result;
    });
}

function deferred<T>() {
    let resolve: (value: T) => void = () => undefined;
    let reject: (reason: unknown) => void = () => undefined;
    const promise = new Promise<T>((settle, fail) => {
        resolve = settle;
        reject = fail;
    });
    return { promise, resolve, reject };
}

async function mount(locale = "en", messages: Record<string, unknown> = enSettings) {
    const { createRoot } = await import("react-dom/client");
    const installed = installInteractiveDocument();
    const root = createRoot(installed.container);
    mountedRoots.push(root);
    let latest: ConfinementAcknowledgement | null = null;
    function Probe() {
        const acknowledgement = useConfinementAcknowledgement();
        useEffect(() => {
            latest = acknowledgement;
        });
        return <ConfinementAcknowledgementDialog acknowledgement={acknowledgement} />;
    }
    await act(async () => root.render(
        <NextIntlClientProvider locale={locale} messages={messages}>
            <Probe />
        </NextIntlClientProvider>,
    ));
    return {
        container: installed.container,
        buttons: () => installed.elements.filter((element) => element.tagName === "BUTTON"),
        click: (button: InteractiveElement) => act(async () => installed.dispatch("click", button)),
        current(): ConfinementAcknowledgement {
            if (latest === null) throw new Error("useConfinementAcknowledgement did not render");
            return latest;
        },
        async unmount() {
            await act(async () => root.unmount());
            mountedRoots.splice(mountedRoots.indexOf(root), 1);
        },
    };
}

afterEach(async () => {
    for (const root of mountedRoots.splice(0)) await act(async () => root.unmount());
    vi.unstubAllGlobals();
});

describe("asking before a role change confines members", () => {
    it("runs a change that confines nobody without asking", async () => {
        const mounted = await mount();
        const change = vi.fn(async (acknowledge: boolean) => (acknowledge ? "acknowledged" : "saved"));

        let result: string | null = null;
        await act(async () => {
            result = await mounted.current().run("Change Dana to Admin", change);
        });

        expect(result).toBe("saved");
        expect(change.mock.calls).toEqual([[false]]);
        expect(mounted.current().pending).toBeNull();
    });

    it("names the change, asks with the count and reruns it acknowledged once the actor agrees", async () => {
        const mounted = await mount();
        const change = refusedOnce("saved", 2);

        let settled: Promise<string | null> = Promise.resolve(null);
        await act(async () => {
            settled = mounted.current().run("Change Dana to Admin", change);
        });
        const question = mounted.current().pending;
        expect(question).toMatchObject({ subject: "Change Dana to Admin", affectedCount: 2 });
        expect(mounted.container.textContent).toContain("Change Dana to Admin");
        expect(mounted.container.textContent).toContain(
            "2 members will have administrator access in this organization without a passkey approved here.",
        );

        await act(async () => mounted.current().answer(question?.id ?? -1, true));

        await expect(settled).resolves.toBe("saved");
        expect(change.mock.calls).toEqual([[false], [true]]);
        expect(mounted.current().pending).toBeNull();
    });

    it("abandons the change when the actor declines in the dialog", async () => {
        const mounted = await mount();
        const change = refusedOnce("saved");

        let settled: Promise<string | null> = Promise.resolve("unsettled");
        await act(async () => {
            settled = mounted.current().run("Change Dana to Admin", change);
        });
        const [cancel] = mounted.buttons();
        await mounted.click(cancel);

        await expect(settled).resolves.toBeNull();
        expect(change.mock.calls).toEqual([[false]]);
        expect(mounted.current().pending).toBeNull();
    });

    it("proceeds from the dialog's own button", async () => {
        const mounted = await mount();
        const change = refusedOnce("saved", 3);

        let settled: Promise<string | null> = Promise.resolve(null);
        await act(async () => {
            settled = mounted.current().run("Change Dana to Admin", change);
        });
        const [, proceed] = mounted.buttons();
        await mounted.click(proceed);

        await expect(settled).resolves.toBe("saved");
        expect(change.mock.calls).toEqual([[false], [true]]);
    });

    it("asks one change at a time, so confirming the first never acknowledges the second", async () => {
        const mounted = await mount();
        const first = refusedOnce("first", 1);
        const second = refusedOnce("second", 1);

        let firstSettled: Promise<string | null> = Promise.resolve("unsettled");
        let secondSettled: Promise<string | null> = Promise.resolve("unsettled");
        await act(async () => {
            firstSettled = mounted.current().run("Change Dana to Admin", first);
        });
        const firstQuestion = mounted.current().pending;
        await act(async () => {
            secondSettled = mounted.current().run("Change Kai to Admin", second);
        });
        expect(mounted.current().pending).toEqual(firstQuestion);

        await act(async () => mounted.current().answer(firstQuestion?.id ?? -1, true));
        await expect(firstSettled).resolves.toBe("first");
        expect(second.mock.calls).toEqual([[false]]);

        const secondQuestion = mounted.current().pending;
        expect(secondQuestion?.subject).toBe("Change Kai to Admin");
        expect(secondQuestion?.id).not.toBe(firstQuestion?.id);

        await act(async () => mounted.current().answer(firstQuestion?.id ?? -1, true));
        expect(mounted.current().pending).toEqual(secondQuestion);
        expect(second.mock.calls).toEqual([[false]]);

        await act(async () => mounted.current().answer(secondQuestion?.id ?? -1, false));
        await expect(secondSettled).resolves.toBeNull();
        expect(second.mock.calls).toEqual([[false]]);
    });

    it.each([
        { label: "a departure refusal", error: new ApiError("Last owner", 409, "WORKSPACE_LAST_OWNER") },
        { label: "a refusal without a code", error: new ApiError("Conflict", 409) },
        { label: "a network failure", error: new TypeError("Failed to fetch") },
    ])("passes $label through without asking", async ({ error }) => {
        const mounted = await mount();
        const change = vi.fn(async () => {
            throw error;
        });

        let outcome: unknown = null;
        await act(async () => {
            outcome = await mounted.current().run("Change Dana to Admin", change).catch((err: unknown) => err);
        });

        expect(outcome).toBe(error);
        expect(change).toHaveBeenCalledOnce();
        expect(mounted.current().pending).toBeNull();
    });

    it("lets a failure of the acknowledged retry reach the caller", async () => {
        const mounted = await mount();
        const failure = new ApiError("Server error", 500);
        const change = vi.fn(async (acknowledge: boolean) => {
            throw acknowledge ? failure : refusal(1);
        });

        let settled: Promise<unknown> = Promise.resolve(null);
        await act(async () => {
            settled = mounted.current().run("Change Dana to Admin", change).catch((err: unknown) => err);
        });
        await act(async () => mounted.current().answer(mounted.current().pending?.id ?? -1, true));

        await expect(settled).resolves.toBe(failure);
    });

    it("says members generally when the refusal gives no count", async () => {
        const mounted = await mount();
        await act(async () => {
            void mounted.current().run("Change Dana to Admin", async (acknowledge: boolean) => {
                if (!acknowledge) throw refusal(undefined);
                return "saved";
            });
        });

        expect(mounted.current().pending).toMatchObject({ affectedCount: null });
        expect(mounted.container.textContent).toContain(
            "Some members will have administrator access in this organization",
        );
    });

    it("abandons an open question when the page goes away", async () => {
        const mounted = await mount();
        let settled: Promise<string | null> = Promise.resolve("unsettled");
        await act(async () => {
            settled = mounted.current().run("Change Dana to Admin", refusedOnce("saved"));
        });

        await mounted.unmount();

        await expect(settled).resolves.toBeNull();
    });

    it("never opens a question for a refusal that arrives after the page went away", async () => {
        const mounted = await mount();
        const response = deferred<string>();
        const change = vi.fn((acknowledge: boolean) => (acknowledge ? Promise.resolve("saved") : response.promise));

        let settled: Promise<string | null> = Promise.resolve("unsettled");
        await act(async () => {
            settled = mounted.current().run("Change Dana to Admin", change);
        });
        await mounted.unmount();
        response.reject(refusal(1));

        await expect(settled).resolves.toBeNull();
        expect(change.mock.calls).toEqual([[false]]);
    });

    it("explains the change in Japanese", async () => {
        const mounted = await mount("ja", jaSettings);
        await act(async () => {
            void mounted.current().run("Dana を 管理者 に変更", refusedOnce("saved", 2));
        });

        expect(mounted.container.textContent).toContain("この変更を続けますか？");
        expect(mounted.container.textContent).toContain("Dana を 管理者 に変更");
        expect(mounted.container.textContent).toContain("2人のメンバー");
    });
});
