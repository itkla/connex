import { isValidElement, type ReactElement, type ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import UserPage from "@/app/(app)/users/[id]/page";
import Timeline from "@/app/components/me/Timeline";
import StatCard from "@/app/components/me/StatCard";

const fixture = vi.hoisted(() => ({
    user: { id: 51, displayName: "Member", username: "member", timezone: "UTC", createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-01T00:00:00Z" },
    notes: Array.from({ length: 25 }, (_, index) => ({ id: index + 1, author: 51, content: `Note ${index + 1}`, createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-01T00:00:00Z" })),
}));

vi.mock("next/headers", () => ({ headers: async () => new Headers({ cookie: "connex_workspace=7" }) }));
vi.mock("next-intl/server", () => ({ getTranslations: async () => (key: string) => key, getTimeZone: async () => "UTC", getLocale: async () => "en" }));
vi.mock("@/app/lib/api", () => ({
    getCurrentUserResultFromCookie: async () => ({ ok: true, data: fixture.user }),
    getUserById: async () => fixture.user,
    getUserNotesFromCookie: vi.fn(async () => [...fixture.notes, ...fixture.notes.slice(0, 5).map((note) => ({ ...note, id: note.id + 25 }))]),
    getUserNoteActivityResultFromCookie: vi.fn(async () => ({ ok: true, data: [
        { date: "2026-09-14", count: 101 }, { date: "2026-09-15", count: 4 },
    ] })),
    getUserNotesPageResultFromCookie: async () => ({ ok: true, data: { items: fixture.notes, total: 105 } }),
    getUserTasksFromCookie: async () => [],
    getUserActivitiesFromCookie: async () => [],
    getContacts: async () => [],
    getContactsFromCookie: async () => [],
    getDeals: async () => [],
    getDealsFromCookie: vi.fn(async () => []),
    getDealMetricsFromCookie: vi.fn(async () => ({ byCurrency: [], totalCount: 0 })),
    getMyWorkspacesFromCookie: async () => ({ activeWorkspaceId: null, workspaces: [] }),
    getUsers: async () => [],
    getAttachmentsFromCookie: async () => [],
    getContactTemperaturesFromCookie: async () => [],
    getDealRisksFromCookie: async () => [],
    getMyWorkResultFromCookie: async () => ({ ok: false, error: { kind: "unavailable" } }),
}));

function hasChildren(value: unknown): value is { children?: ReactNode } {
    return typeof value === "object" && value !== null && "children" in value;
}

function findByType(node: ReactNode, type: unknown): ReactElement[] {
    if (Array.isArray(node)) return node.flatMap((child: ReactNode) => findByType(child, type));
    if (!isValidElement(node)) return [];
    if (node.type === type) return [node];
    return hasChildren(node.props) ? findByType(node.props.children, type) : [];
}

afterEach(() => vi.useRealTimers());

describe("authored note timeline pages", () => {
    it("shows the complete visible profile total while rendering only the first bounded page", async () => {
        const page = await UserPage({ params: { id: 51 } });
        expect(findByType(page, StatCard).map((element) => element.props)).toContainEqual({ label: "notes", value: 105 });
        expect(findByType(page, Timeline)[0]?.props).toMatchObject({
            notes: fixture.notes, noteTarget: { type: "user", id: 51 },
        });
    });

});
