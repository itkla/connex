/** @vitest-environment jsdom */
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import NotificationsPage from "@/app/(app)/notifications/page";
import NotificationEmailLink from "@/app/components/notifications/NotificationEmailLink";
import type { Notification } from "@/app/lib/types";
import en from "@/messages/en/notifications.json";
import ja from "@/messages/ja/notifications.json";

const state = vi.hoisted(() => ({
    read: vi.fn(),
    run: vi.fn<(workspaceId: number, action: (switched: boolean) => Promise<void>) => Promise<boolean>>(),
    router: { push: vi.fn(), replace: vi.fn(), refresh: vi.fn() },
}));
vi.mock("next/navigation", () => ({ useRouter: () => state.router }));
vi.mock("@/app/lib/api", () => ({ markNotificationRead: state.read }));
vi.mock("@/app/hooks/useWorkspace", () => ({ useWorkspace: () => ({ runInWorkspace: state.run }) }));
vi.mock("@/app/lib/renderedWorkspace", () => ({ navigateAfterWorkspaceSelection: (navigate: () => void) => navigate() }));
vi.mock("@/app/components/notifications/NotificationsInbox", () => ({ default: () => <div data-testid="inbox" /> }));

const notification: Notification = {
    id: 41, workspaceId: 7, type: "rule", category: "person", severity: "info", templateVersion: 0,
    title: "Follow up", actionUrl: "/records/contacts/19?task=31", triggeredAt: "", createdAt: "", updatedAt: "",
};
afterEach(cleanup);
beforeEach(() => {
    vi.resetAllMocks();
    state.read.mockResolvedValue(notification);
    state.run.mockImplementation(async (_workspace, action) => { await action(true); return true; });
});

for (const locale of ["en", "ja"] as const) {
    const messages = locale === "en" ? en : ja;
    const copy = messages.Notifications;
    const show = (notificationId: number | null) => render(
        <NextIntlClientProvider locale={locale} messages={messages}>
            <NotificationEmailLink notificationId={notificationId} />
        </NextIntlClientProvider>,
    );

    describe(`emailed notification in ${locale}`, () => {
        it("authorizes the notification and waits for workspace A selection before leaving workspace B", async () => {
            let select: () => void = () => {};
            const selected = new Promise<void>((resolve) => { select = resolve; });
            state.run.mockImplementation(async (_workspace, action) => {
                await selected;
                await action(true);
                return true;
            });
            show(41);
            await waitFor(() => expect(state.run).toHaveBeenCalledWith(7, expect.any(Function)));
            expect(state.read).toHaveBeenCalledWith(41);
            expect(state.router.replace).not.toHaveBeenCalled();
            await act(async () => { select(); await selected; });
            await waitFor(() => expect(state.router.replace).toHaveBeenCalledWith("/records/contacts/19?task=31"));
            expect(state.router.refresh).toHaveBeenCalledOnce();
        });

        it("refuses invalid link ids without requesting or selecting anything", () => {
            show(null);
            expect(state.read).not.toHaveBeenCalled();
            expect(state.run).not.toHaveBeenCalled();
            expect(screen.getByRole("heading", { name: copy.emailLinkError })).toBeTruthy();
            expect(screen.getByRole("link", { name: copy.viewAll }).getAttribute("href")).toBe("/notifications");
        });

        it("does not select another workspace when recipient authorization is refused", async () => {
            state.read.mockRejectedValue(new Error("Unavailable"));
            show(41);
            expect(await screen.findByRole("heading", { name: copy.emailLinkError })).toBeTruthy();
            expect(state.run).not.toHaveBeenCalled();
        });

        it.each([null, "//external.example/path"])("refuses an unavailable or unsafe record destination %s", async (actionUrl) => {
            state.read.mockResolvedValue({ ...notification, actionUrl });
            show(41);
            expect(await screen.findByRole("heading", { name: copy.emailLinkError })).toBeTruthy();
            expect(state.run).not.toHaveBeenCalled();
        });

        it("shows a recoverable failure when workspace selection is refused", async () => {
            state.run.mockResolvedValue(false);
            show(41);
            expect(await screen.findByRole("heading", { name: copy.emailLinkError })).toBeTruthy();
            expect(state.router.replace).not.toHaveBeenCalled();
        });
    });
}

it("the existing notifications page owns and validates the open query", async () => {
    render(await NotificationsPage({ searchParams: Promise.resolve({}) }));
    expect(screen.getByTestId("inbox")).toBeTruthy();
    cleanup();
    render(<NextIntlClientProvider locale="en" messages={en}>
        {await NotificationsPage({ searchParams: Promise.resolve({ open: ["41", "42"] }) })}
    </NextIntlClientProvider>);
    expect(state.read).not.toHaveBeenCalled();
    expect(screen.getByRole("heading", { name: en.Notifications.emailLinkError })).toBeTruthy();
    cleanup();
    render(<NextIntlClientProvider locale="en" messages={en}>
        {await NotificationsPage({ searchParams: Promise.resolve({ open: "41" }) })}
    </NextIntlClientProvider>);
    await waitFor(() => expect(state.read).toHaveBeenCalledWith(41));
    await waitFor(() => expect(state.router.replace).toHaveBeenCalledWith(notification.actionUrl));
});
