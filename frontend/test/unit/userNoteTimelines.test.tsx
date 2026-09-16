import { isValidElement, type ReactElement, type ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import UserPage from "@/app/(app)/users/[id]/page";
import MePage from "@/app/(app)/me/page";
import Timeline from "@/app/components/me/Timeline";
import PulseStrip from "@/app/components/me/PulseStrip";
import SectionUnavailable from "@/app/components/SectionUnavailable";
import StatCard from "@/app/components/me/StatCard";
import { getUserNotesFromCookie, getUserNoteActivityResultFromCookie } from "@/app/lib/api";

const fixture = vi.hoisted(() => ({
    user: { id: 51, displayName: "Member", username: "member", timezone: "UTC", createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-01T00:00:00Z" },
    notes: Array.from({ length: 25 }, (_, index) => ({ id: index + 1, author: 51, content: `Note ${index + 1}`, createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-01T00:00:00Z" })),
}));

vi.mock("next/headers", () => ({ headers: async () => new Headers({ cookie: "connex_workspace=7" }) }));
vi.mock("next-intl/server", () => ({ getTranslations: async () => (key: string) => key, getLocale: async () => "en" }));
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
    getDealsFromCookie: async () => [],
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

    it("counts all 105 recent notes even when the preview contains only older notes edited recently", async () => {
        vi.useFakeTimers();
        vi.setSystemTime(new Date("2026-09-15T12:00:00Z"));
        vi.mocked(getUserNotesFromCookie).mockResolvedValueOnce(Array.from({ length: 100 }, (_, index) => ({
            ...fixture.notes[0], id: index + 1, updatedAt: "2026-09-15T12:00:00Z",
        })));
        const page = await MePage();
        expect(getUserNoteActivityResultFromCookie).toHaveBeenCalledWith(51, "connex_workspace=7");
        expect(findByType(page, PulseStrip)[0]?.props).toMatchObject({
            totalTouches: 105, streak: 2,
            days: expect.arrayContaining([{ date: "2026-09-14", count: 101 }, { date: "2026-09-15", count: 4 }]),
        });
        expect(findByType(page, Timeline)[0]?.props).toMatchObject({ notes: expect.any(Array) });
    });

    it("shows an unavailable section instead of zero touches when the aggregate fails", async () => {
        vi.mocked(getUserNoteActivityResultFromCookie).mockResolvedValueOnce({ ok: false });
        const page = await MePage();
        expect(findByType(page, PulseStrip)).toHaveLength(0);
        expect(findByType(page, SectionUnavailable)).toHaveLength(1);
    });

    it("gives the current user's timeline the same authored-note continuation target", async () => {
        const page = await MePage();
        expect(getUserNotesFromCookie).toHaveBeenCalledWith(51, "connex_workspace=7", { size: 25 });
        expect(findByType(page, Timeline)[0]?.props).toMatchObject({
            notes: fixture.notes, noteTarget: { type: "user", id: 51 },
        });
    });
});
