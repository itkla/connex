/** @vitest-environment jsdom */
import { act, cleanup, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import type { PropsWithChildren } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import NotificationsInbox from "@/app/components/notifications/NotificationsInbox";
import { excludeExpiredInvitationCounts, notificationInvitationDeadline } from "@/app/components/notifications/notificationExpiry";
import { NotificationProvider, useNotifications, useUnexpiredNotifications } from "@/app/hooks/useNotifications";
import { NowProvider } from "@/app/hooks/useNow";
import type { Notification, NotificationCounts } from "@/app/lib/types";
import notifications from "@/messages/en/notifications.json";
import common from "@/messages/en/common.json";

const fixture = vi.hoisted(() => ({
    counts: vi.fn<() => Promise<NotificationCounts>>(),
    list: vi.fn(),
    facets: vi.fn(),
    activeWorkspaceId: 7,
}));

vi.mock("next/navigation", () => ({
    usePathname: () => "/notifications",
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/app/lib/api", async (original) => ({
    ...await original<typeof import("@/app/lib/api")>(),
    getNotificationCounts: fixture.counts,
    getNotifications: fixture.list,
    getNotificationFacets: fixture.facets,
    currentPrivilegedMfaReadiness: () => "ready",
    privilegedMfaConfinementFor: () => false,
    subscribePrivilegedMfaConfinement: () => () => {},
}));
vi.mock("@/app/lib/realtime", () => ({
    createNotificationSocket: () => ({ activate: () => {}, deactivate: () => {} }),
}));
vi.mock("@/app/hooks/useWorkspace", () => ({
    useWorkspace: () => ({ activeWorkspaceId: fixture.activeWorkspaceId }),
}));
vi.mock("@/app/components/notifications/useNotificationWorkspaceActions", () => ({
    useNotificationWorkspaceActions: () => ({}),
}));
vi.mock("@/app/components/motion/Rise", () => ({
    default: ({ children }: PropsWithChildren) => children,
}));

const START = Date.parse("2026-11-15T12:00:00Z");
const MAX_TIMEOUT = 2_147_483_647;

function notice(id: number, deadline?: number, extra: Partial<Notification> = {}): Notification {
    return {
        id, workspaceId: 9, type: "workspace.join", category: "workspace", severity: "info",
        templateVersion: 0, title: `Invitation ${id}`, triggeredAt: new Date(START).toISOString(),
        stateVersion: 3,
        createdAt: new Date(START).toISOString(), updatedAt: new Date(START).toISOString(),
        invitationExpiresAt: deadline == null ? undefined : new Date(deadline).toISOString(),
        ...extra,
    };
}

function counts(extra: Partial<NotificationCounts> = {}): NotificationCounts {
    return { unread: 1, snoozed: 0, stateVersion: 3, asOf: new Date(START).toISOString(), quietHoursActive: false, ...extra };
}

function Wrapper({ children }: PropsWithChildren) {
    return <NextIntlClientProvider locale="en" timeZone="UTC" messages={{ ...common, ...notifications }}>
        <NowProvider value={START}>
            <NotificationProvider recipientId={41}>{children}</NotificationProvider>
        </NowProvider>
    </NextIntlClientProvider>;
}

function Probe({ items, scope = "all" }: { items: Notification[]; scope?: string }) {
    const visible = useUnexpiredNotifications(items, scope);
    const { unread, snoozed, refreshUnread } = useNotifications();
    return <>
        <output aria-label="Visible notices">{visible.map((item) => item.title).join(", ")}</output>
        <output aria-label="Unread">{unread}</output>
        <output aria-label="Snoozed">{snoozed}</output>
        <button onClick={() => void refreshUnread()}>Refresh counts</button>
    </>;
}

async function start() {
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
}

beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(START);
    fixture.activeWorkspaceId = 7;
    fixture.counts.mockReset().mockResolvedValue(counts());
    fixture.list.mockReset().mockResolvedValue({ items: [], total: 0, stateVersion: 3, asOf: new Date(START).toISOString() });
    fixture.facets.mockReset().mockResolvedValue({ categories: [], severities: [], workspaces: [], stateVersion: 3 });
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.useRealTimers();
});

describe("clock-driven invitation expiry", () => {
    it("removes an already-read invitation from the actual inbox at its deadline without fetching", async () => {
        const invitation = notice(1, START + 1_000, { readAt: new Date(START).toISOString(), stateVersion: 0 });
        fixture.list.mockResolvedValue({ items: [invitation], total: 1, stateVersion: 3, asOf: new Date(START).toISOString() });
        fixture.counts.mockResolvedValue(counts({ unread: 0 }));
        render(<NotificationsInbox />, { wrapper: Wrapper });
        await start();
        expect(document.querySelector('[data-notification-row="1"]')).toBeTruthy();
        await act(async () => { await vi.advanceTimersByTimeAsync(999); });
        expect(document.querySelector('[data-notification-row="1"]')).toBeTruthy();
        await act(async () => { await vi.advanceTimersByTimeAsync(1); });
        expect(document.querySelector('[data-notification-row="1"]')).toBeNull();
        expect(screen.getByText(notifications.Notifications.empty)).toBeTruthy();
        expect(screen.getByText("No notifications")).toBeTruthy();
        expect(fixture.list).toHaveBeenCalledOnce();
        expect(fixture.facets).toHaveBeenCalledOnce();
        expect(fixture.counts).toHaveBeenCalledOnce();
    });

    it("keeps unread and snoozed counts aligned, deduplicates surfaces and does not double-subtract a fresh snapshot", async () => {
        const items = [notice(1, START + 1_000), notice(2, START + 1_000, { readAt: new Date(START).toISOString() }),
            notice(3, START + 1_000, { snoozedUntil: new Date(START + 20_000).toISOString() }), notice(4)];
        fixture.counts.mockResolvedValue(counts({ unread: 2, snoozed: 1 }));
        render(<><Probe items={items} /><Probe items={items} /></>, { wrapper: Wrapper });
        await start();
        await act(async () => { await vi.advanceTimersByTimeAsync(1_000); });
        expect(screen.getAllByLabelText("Visible notices").map((el) => el.textContent)).toEqual(["Invitation 4", "Invitation 4"]);
        expect(screen.getAllByLabelText("Unread").map((el) => el.textContent)).toEqual(["1", "1"]);
        expect(screen.getAllByLabelText("Snoozed").map((el) => el.textContent)).toEqual(["0", "0"]);
        expect(fixture.counts).toHaveBeenCalledOnce();
        fixture.counts.mockResolvedValue(counts({ unread: 1, asOf: new Date(START + 1_000).toISOString() }));
        await act(async () => screen.getAllByRole("button", { name: "Refresh counts" })[0]?.click());
        expect(screen.getAllByLabelText("Unread").map((el) => el.textContent)).toEqual(["1", "1"]);
    });

    it("adjusts a count response that was already in flight at expiry", async () => {
        let resolveCounts: (value: NotificationCounts) => void = () => {};
        fixture.counts.mockReturnValueOnce(new Promise((resolve) => { resolveCounts = resolve; }))
            .mockResolvedValue(counts({ unread: 0, asOf: new Date(START + 1_000).toISOString() }));
        render(<Probe items={[notice(1, START + 1_000)]} />, { wrapper: Wrapper });
        await start();
        await act(async () => { await vi.advanceTimersByTimeAsync(1_000); });
        await act(async () => resolveCounts(counts()));
        expect(screen.getByLabelText("Unread").textContent).toBe("0");
        expect(screen.getByLabelText("Visible notices").textContent).toBe("");
        expect(fixture.counts).toHaveBeenCalledTimes(2);
    });

    it("refreshes unread counts once when another surface retains an older invitation snapshot", async () => {
        const other = notice(2, undefined, { type: "rule", title: "Other unread notice" });
        const older = [notice(1, START + 1_000, { stateVersion: 3 }), other];
        const read = [notice(1, START + 1_000, {
            readAt: new Date(START + 500).toISOString(),
            updatedAt: new Date(START + 500).toISOString(),
            stateVersion: 4,
        }), other];
        fixture.counts.mockResolvedValue(counts({ unread: 2 }));
        const view = render(<><Probe items={older} /><Probe items={older} /></>, { wrapper: Wrapper });
        await start();
        await act(async () => { await vi.advanceTimersByTimeAsync(500); });
        view.rerender(<><Probe items={older} /><Probe items={read} /></>);
        fixture.counts.mockResolvedValue(counts({
            unread: 1, stateVersion: 4, asOf: new Date(START + 500).toISOString(),
        }));
        await act(async () => screen.getAllByRole("button", { name: "Refresh counts" })[0]?.click());
        view.rerender(<><Probe items={older} /></>);
        let resolveExpiryCounts: (value: NotificationCounts) => void = () => {};
        fixture.counts.mockReturnValueOnce(new Promise((resolve) => { resolveExpiryCounts = resolve; }));
        await act(async () => { await vi.advanceTimersByTimeAsync(500); });
        expect(screen.getByLabelText("Visible notices").textContent).toBe("Other unread notice");
        expect(screen.getByLabelText("Unread").textContent).toBe("1");
        expect(fixture.counts).toHaveBeenCalledTimes(3);
        await act(async () => resolveExpiryCounts(counts({
            unread: 1, stateVersion: 4, asOf: new Date(START + 1_000).toISOString(),
        })));
        expect(screen.getByLabelText("Unread").textContent).toBe("1");
    });

    it("retries an in-flight count snapshot once when its invitation state is out of date", async () => {
        let resolveCounts: (value: NotificationCounts) => void = () => {};
        fixture.counts.mockReturnValueOnce(new Promise((resolve) => { resolveCounts = resolve; }))
            .mockResolvedValue(counts({ unread: 1, stateVersion: 4, asOf: new Date(START + 1_000).toISOString() }));
        render(<Probe items={[notice(1, START + 1_000),
            notice(2, undefined, { type: "rule", title: "Other unread notice" })]} />, { wrapper: Wrapper });
        await start();
        await act(async () => { await vi.advanceTimersByTimeAsync(1_000); });
        await act(async () => resolveCounts(counts({
            unread: 2, stateVersion: 4, asOf: new Date(START + 500).toISOString(),
        })));
        expect(screen.getByLabelText("Visible notices").textContent).toBe("Other unread notice");
        expect(screen.getByLabelText("Unread").textContent).toBe("1");
        expect(fixture.counts).toHaveBeenCalledTimes(2);
    });

    it("retains a proven count deduction when a newer surface expires the same invitation", async () => {
        const other = notice(2, undefined, { type: "rule", title: "Other unread notice" });
        const older = [notice(1, START + 1_000), other];
        const newer = [notice(1, START + 1_000, {
            readAt: new Date(START + 500).toISOString(), stateVersion: 4,
        }), other];
        fixture.counts.mockResolvedValue(counts({ unread: 2 }));
        const view = render(<><Probe items={older} /><Probe items={older} /><Probe items={older} /></>, { wrapper: Wrapper });
        await start();
        await act(async () => { await vi.advanceTimersByTimeAsync(500); });
        view.rerender(<><Probe items={older} /><Probe items={newer} /><Probe items={newer} /></>);
        let resolveCounts: (value: NotificationCounts) => void = () => {};
        const freshCounts = counts({ unread: 1, stateVersion: 4, asOf: new Date(START + 1_000).toISOString() });
        fixture.counts.mockResolvedValue(freshCounts);
        fixture.counts.mockReturnValueOnce(new Promise((resolve) => { resolveCounts = resolve; }));
        await act(async () => { await vi.advanceTimersByTimeAsync(500); });
        expect(screen.getAllByLabelText("Visible notices").map((el) => el.textContent)).toEqual([
            "Other unread notice", "Other unread notice", "Other unread notice",
        ]);
        expect(screen.getAllByLabelText("Unread").map((el) => el.textContent)).toEqual(["1", "1", "1"]);
        expect(fixture.counts).toHaveBeenCalledTimes(2);
        await act(async () => resolveCounts(freshCounts));
        expect(screen.getAllByLabelText("Unread").map((el) => el.textContent)).toEqual(["1", "1", "1"]);
        expect(fixture.counts).toHaveBeenCalledTimes(2);
    });

    it.each([true, false])("keeps counts stable when a boundary response finishes before expiry: %s", async (responseFirst) => {
        const items = [notice(1, START + 1_000),
            notice(2, undefined, { type: "rule", title: "Other unread notice" })];
        const crossingCounts = counts({ unread: 1 });
        const freshCounts = counts({ unread: 1, asOf: new Date(START + 1_000).toISOString() });
        let resolveCrossing: (value: NotificationCounts) => void = () => {};
        let resolveFresh: (value: NotificationCounts) => void = () => {};
        if (responseFirst) window.setTimeout(() => resolveCrossing(crossingCounts), 1_000);
        fixture.counts.mockResolvedValue(counts({ unread: 2 }));
        render(<Probe items={items} />, { wrapper: Wrapper });
        await start();
        await act(async () => { await vi.advanceTimersByTimeAsync(500); });
        fixture.counts.mockResolvedValue(freshCounts)
            .mockReturnValueOnce(new Promise((resolve) => { resolveCrossing = resolve; }))
            .mockReturnValueOnce(new Promise((resolve) => { resolveFresh = resolve; }));
        await act(async () => screen.getByRole("button", { name: "Refresh counts" }).click());
        await act(async () => { await vi.advanceTimersByTimeAsync(500); });
        if (!responseFirst) await act(async () => resolveCrossing(crossingCounts));
        expect(screen.getByLabelText("Visible notices").textContent).toBe("Other unread notice");
        expect(screen.getByLabelText("Unread").textContent).toBe("1");
        expect(fixture.counts).toHaveBeenCalledTimes(3);
        await act(async () => resolveFresh(freshCounts));
        expect(screen.getByLabelText("Unread").textContent).toBe("1");
        expect(fixture.counts).toHaveBeenCalledTimes(3);
    });

    it("schedules only the earliest deadline, then the next one", async () => {
        const timer = vi.spyOn(window, "setTimeout");
        render(<Probe items={[notice(1, START + 1_000), notice(2, START + 3_000)]} />, { wrapper: Wrapper });
        await start();
        expect(timer.mock.calls.filter(([, delay]) => delay !== 0).map(([, delay]) => delay)).toEqual([1_000]);
        await act(async () => { await vi.advanceTimersByTimeAsync(1_000); });
        expect(screen.getByLabelText("Visible notices").textContent).toBe("Invitation 2");
        expect(timer).toHaveBeenLastCalledWith(expect.any(Function), 2_000);
        await act(async () => { await vi.advanceTimersByTimeAsync(2_000); });
        expect(screen.getByLabelText("Visible notices").textContent).toBe("");
    });

    it("clears the deadline timer on workspace/filter changes and all timers on unmount", async () => {
        const timer = vi.spyOn(window, "setTimeout");
        const clear = vi.spyOn(window, "clearTimeout");
        const items = [notice(1, START + 1_000)];
        const view = render(<Probe items={items} />, { wrapper: Wrapper });
        await start();
        const first = timer.mock.results[0]?.value;
        fixture.activeWorkspaceId = 8;
        view.rerender(<Probe items={items} />);
        expect(clear).toHaveBeenCalledWith(first);
        const second = timer.mock.results.at(-1)?.value;
        view.rerender(<Probe items={items} scope="9" />);
        expect(clear).toHaveBeenCalledWith(second);
        view.rerender(<Probe items={[]} scope="all" />);
        await act(async () => { await vi.advanceTimersByTimeAsync(1_000); });
        expect(screen.getByLabelText("Unread").textContent).toBe("1");
        view.rerender(<Probe items={[notice(2, START + 2_000)]} />);
        view.unmount();
        expect(vi.getTimerCount()).toBe(0);
    });

    it("bounds long delays and rearms instead of overflowing or expiring early", async () => {
        const timer = vi.spyOn(window, "setTimeout");
        const items = [notice(1, START + MAX_TIMEOUT + 1_000)];
        render(<Probe items={items} />, { wrapper: Wrapper });
        await start();
        expect(timer).toHaveBeenCalledWith(expect.any(Function), MAX_TIMEOUT);
        const callback = timer.mock.calls.find(([, delay]) => delay === MAX_TIMEOUT)?.[0];
        if (typeof callback !== "function") throw new Error("Missing bounded deadline timer");
        await act(async () => {
            vi.setSystemTime(START + MAX_TIMEOUT);
            callback();
        });
        expect(timer).toHaveBeenLastCalledWith(expect.any(Function), 1_000);
        expect(screen.getByLabelText("Visible notices").textContent).toBe("Invitation 1");
        await act(async () => { await vi.advanceTimersByTimeAsync(1_000); });
        expect(screen.getByLabelText("Visible notices").textContent).toBe("");
    });

    it("does not schedule expiry timers for absent, invalid or unrelated deadlines", async () => {
        const timer = vi.spyOn(window, "setTimeout");
        render(<Probe items={[notice(1), notice(2, undefined, { invitationExpiresAt: null }),
            notice(3, undefined, { invitationExpiresAt: "invalid" }), notice(4, START + 1_000, { type: "task.due" })]} />, { wrapper: Wrapper });
        await start();
        expect(timer.mock.calls.filter(([, delay]) => delay !== 0)).toEqual([]);
        await act(async () => { await vi.advanceTimersByTimeAsync(1_000); });
        expect(screen.getByLabelText("Visible notices").textContent).toBe("Invitation 1, Invitation 2, Invitation 3, Invitation 4");
    });
});

it("parses offset-less deadlines as UTC and excludes inactive/read notices from count adjustments", () => {
    expect(notificationInvitationDeadline(notice(1, undefined, { invitationExpiresAt: "2026-11-15 12:00:01" }))).toBe(START + 1_000);
    expect(excludeExpiredInvitationCounts(counts({ unread: 2 }), [
        notice(1, START, {}), notice(2, START + 1_000, { dismissedAt: "2026-11-15T12:00:00Z" }),
        notice(3, START + 1_000, { resolvedAt: "2026-11-15T12:00:00Z" }), notice(4, START + 1_000, { readAt: "2026-11-15T12:00:00Z" }),
    ]).unread).toBe(2);
});
