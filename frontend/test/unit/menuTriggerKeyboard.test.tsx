/** @vitest-environment jsdom */
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import WorkspaceSwitcher from "@/app/components/WorkspaceSwitcher";
import NotificationBell from "@/app/components/notifications/NotificationBell";
import TagsBrowser from "@/app/components/library/tags/TagsBrowser";
import { TooltipProvider } from "@/components/ui/tooltip";
import workspace from "@/messages/en/workspace.json";
import notifications from "@/messages/en/notifications.json";
import activity from "@/messages/en/activity.json";
import common from "@/messages/en/common.json";
import records from "@/messages/en/records.json";

vi.mock("@/app/hooks/useWorkspace", () => ({ useWorkspace: () => ({
    workspaces: [{ id: 1, name: "Sales" }], activeWorkspaceId: 1,
    activeWorkspace: { id: 1, name: "Sales" }, switching: false, switchTo: vi.fn(),
}) }));
vi.mock("@/app/hooks/useNotifications", () => ({ useNotifications: () => ({ recipientId: 1, unread: 0, refreshUnread: vi.fn() }) }));
vi.mock("@/app/hooks/useNow", () => ({ useLiveNow: () => 0 }));
vi.mock("@/app/hooks/useApiErrorToast", () => ({ useApiErrorToast: () => vi.fn() }));
vi.mock("@/app/components/notifications/useNotificationWorkspaceActions", () => ({ useNotificationWorkspaceActions: () => ({}) }));
vi.mock("@/app/components/NewWorkspaceDialog", () => ({ default: () => null }));
vi.mock("@/app/components/library/tags/TagDialog", () => ({ default: () => null }));
vi.mock("@/app/components/library/tags/TagMembersSheet", () => ({ default: () => null }));
vi.mock("@/app/components/records/DeleteRecordDialog", () => ({ default: () => null }));
vi.mock("@/app/lib/api", async (original) => ({
    ...await original<typeof import("@/app/lib/api")>(),
    getNotifications: async () => ({ items: [], stateVersion: 0 }),
}));

beforeEach(() => {
    vi.stubGlobal("ResizeObserver", class { observe() {} unobserve() {} disconnect() {} });
    Object.defineProperty(window, "matchMedia", { configurable: true, value: (media: string) => ({
        media, matches: false, addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {},
    }) });
    HTMLElement.prototype.scrollIntoView = vi.fn();
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

for (const component of ["WorkspaceSwitcher", "NotificationBell", "TagsBrowser"] as const) {
    describe(`${component} keyboard menu trigger`, () => {
        it.each(["Enter", " "])("opens once with %s when motion is enabled", async (key) => {
            const view = component === "WorkspaceSwitcher" ? <WorkspaceSwitcher />
                : component === "NotificationBell" ? <NotificationBell />
                : <TagsBrowser tags={[{ id: 1, name: "Priority", color: "#abcdef", createdAt: "", updatedAt: "" }]} />;
            render(<NextIntlClientProvider locale="en" timeZone="UTC" messages={{ ...workspace, ...notifications, ...activity, ...common, ...records }}>
                <TooltipProvider>{view}</TooltipProvider>
            </NextIntlClientProvider>);
            const name = component === "WorkspaceSwitcher" ? workspace.WorkspaceSwitcher.switchAria
                : component === "NotificationBell" ? notifications.Notifications.bellLabel
                : activity.ActivityLibraryTags.actionsAria.replace("{name}", "Priority");
            const trigger = screen.getByRole("button", { name });
            trigger.focus();
            fireEvent.keyDown(trigger, { key });
            fireEvent.keyUp(trigger, { key });
            await waitFor(() => expect(trigger.getAttribute("aria-expanded")).toBe("true"));
            expect(screen.getByRole("menu")).toBeTruthy();
        });
    });
}
