/** @vitest-environment jsdom */
import { act, type ComponentProps } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import AskConnexProvider from "@/app/components/ask-connex/AskConnexProvider";
import type AskConnexDrawer from "@/app/components/ask-connex/AskConnexDrawer";
import type AskConnexMissionCreate from "@/app/components/ask-connex/AskConnexMissionCreate";
import CommentsSection from "@/app/components/records/comments/CommentsSection";
import type { ActiveRecordRef } from "@/app/lib/actions/types";
import { isInlineManagedImageFile } from "@/app/lib/managed-image";
import { bindRenderedWorkspace, releaseRenderedWorkspace } from "@/app/lib/renderedWorkspace";
import { observeProposalReviewClock } from "@/app/lib/proposalReviewClock";
import type { AiAgentRun, AiChatSession, AiChatSessionDetail } from "@/app/lib/types";
import { askConnexToolCall } from "./helpers/askConnexToolCards";
import { deferred, image, json, ORIGIN, shell, stubBackend, switchWorkspace } from "./helpers/workspaceChainBackend";

const boundary = vi.hoisted<{ drawer: ComponentProps<typeof AskConnexDrawer> | null; missionCreate: ComponentProps<typeof AskConnexMissionCreate> | null; params: { sessionId?: string }; noop: () => void; canPersist: () => boolean; search: URLSearchParams; context: { user: { id: number; displayName: string }; record: ActiveRecordRef | null; selection: null } }>(() => ({ drawer: null, missionCreate: null, params: { sessionId: "4" }, noop: () => undefined, canPersist: () => true, search: new URLSearchParams(), context: { user: { id: 7, displayName: "Origin member" }, record: null, selection: null } }));
const translate = vi.hoisted(() => Object.assign((key: string) => key, { has: () => false }));
vi.mock("next/navigation", () => ({ useParams: () => boundary.params, usePathname: () => boundary.params.sessionId ? "/ask-connex/4" : "/ask-connex", useSearchParams: () => boundary.search, useRouter: () => ({ push: boundary.noop, replace: boundary.noop, refresh: boundary.noop }) }));
vi.mock("next-intl", () => ({ useLocale: () => "en", useTranslations: () => translate }));
vi.mock("motion/react", () => ({ useReducedMotion: () => true }));
vi.mock("@/app/components/ask-connex/AskConnexDrawer", () => ({ default: (props: ComponentProps<typeof AskConnexDrawer>) => { boundary.drawer = props; return <>{props.missionComposerAction}</>; } }));
vi.mock("@/app/components/ask-connex/AskConnexMissionCreate", () => ({ default: (props: ComponentProps<typeof AskConnexMissionCreate>) => { boundary.missionCreate = props; return null; } }));
vi.mock("@/app/hooks/useActions", () => ({ useActions: () => ({ context: boundary.context }), useRegisterActions: boundary.noop }));
vi.mock("@/app/hooks/useWorkspace", () => ({ useWorkspace: () => ({ activeWorkspaceId: 9, switching: false }) }));
vi.mock("@/app/hooks/usePermissions", () => ({ usePermissionCheck: (permission: string) => ["AI_USE", "ATTACHMENT_CREATE"].includes(permission) ? "granted" : "denied" }));
vi.mock("@/app/hooks/useAskConnexSkills", () => ({ useAskConnexSkills: () => [] }));
vi.mock("@/app/hooks/useIsMobile", () => ({ useIsMobile: () => false }));
vi.mock("@/app/hooks/useNow", () => ({ useLiveNow: () => Date.parse("2026-10-19T12:00:00Z"), useNow: () => Date.parse("2026-10-19T12:00:00Z") }));
vi.mock("@/app/hooks/useAccountStorageGeneration", () => ({ useAccountStorageGeneration: () => boundary.canPersist }));
vi.mock("@/app/lib/realtime", () => ({ createAiChatSocket: () => ({ activate: boundary.noop, deactivate: boundary.noop }) }));
vi.mock("@/app/lib/toast", () => ({ toastError: vi.fn(), toastInfo: vi.fn(), toastSuccess: vi.fn(), toastDismiss: vi.fn() }));
vi.mock("@/app/lib/managed-image", async (importOriginal) => ({ ...await importOriginal<typeof import("@/app/lib/managed-image")>(), isInlineManagedImageFile: vi.fn(async () => true) }));
vi.mock("@/app/components/activity/notes/MentionEditor", () => ({ default: ({ value, onChange }: { value: string; onChange: (value: string) => void }) => <textarea value={value} onChange={(event) => onChange(event.target.value)} /> }));

let root: Root;
beforeEach(() => {
    vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true);
    vi.stubGlobal("matchMedia", (query: string) => ({ media: query, matches: false, addEventListener: boundary.noop, removeEventListener: boundary.noop }));
    const container = document.createElement("div"); document.body.appendChild(container); root = createRoot(container);
    boundary.drawer = null;
    boundary.missionCreate = null;
    boundary.params = { sessionId: "4" };
    boundary.context.record = null;
    bindRenderedWorkspace(shell, ORIGIN);
    document.cookie = "connex_workspace=" + ORIGIN + "; path=/";
});
afterEach(async () => {
    await act(async () => root.unmount());
    releaseRenderedWorkspace(shell);
    document.body.innerHTML = "";
    document.cookie = "connex_workspace=; path=/; max-age=0";
    window.localStorage.clear(); window.sessionStorage.clear();
    vi.unstubAllGlobals(); vi.clearAllMocks();
});
function drawer(): ComponentProps<typeof AskConnexDrawer> {
    if (boundary.drawer === null) throw new Error("The assistant drawer did not render");
    return boundary.drawer;
}

describe("sequential batch origins", () => {
    it("forwards the file action origin through session creation and XHR upload", async () => {
        boundary.params = {};
        const session: AiChatSession = { id: 4, workspaceId: ORIGIN, createdByUserId: 7, title: "Origin attachment", visibility: "private", status: "active", archived: false, ownedByCurrentUser: true, historySummarized: false, participationStatus: null, lastMessageAt: null, archivedAt: null, createdAt: "", updatedAt: "" };
        const path = "/api/ai/assistant/sessions";
        const backend = stubBackend(path, (requestPath, method) => {
            if (requestPath === path) return json(method === "GET" ? { items: [], total: 0 } : session);
            if (requestPath === path + "/4") return json({ session, messages: { items: [], total: 0 } });
            if (requestPath.endsWith("/invitations")) return json({ items: [], total: 0 });
            return undefined;
        });
        class UploadRequest extends EventTarget {
            readonly upload = new EventTarget();
            readonly headers = new Headers();
            withCredentials = false;
            status = 200;
            statusText = "OK";
            responseText = JSON.stringify({ id: 41, fileName: image.name, contentType: image.type, size: image.size, kind: "image", createdAt: "" });
            path = "";
            open(_method: string, url: string): void { this.path = new URL(url, "http://localhost").pathname; }
            setRequestHeader(name: string, value: string): void { this.headers.set(name, value); }
            send(): void {
                backend.requests.push({ path: this.path, workspace: this.headers.get("X-Workspace-Id"), init: { headers: this.headers } });
                this.dispatchEvent(new Event("load"));
            }
            abort(): void { this.dispatchEvent(new Event("abort")); }
        }
        vi.stubGlobal("XMLHttpRequest", UploadRequest);
        await act(async () => root.render(<AskConnexProvider>{null}</AskConnexProvider>));
        expect(drawer().canAttachFiles).toBe(true);
        await act(async () => { drawer().onAttachFiles([image]); await backend.started.promise; });
        switchWorkspace();
        await act(async () => backend.held.resolve(backend.response(path)));
        await vi.waitFor(() => expect(drawer().fileAttachments).toMatchObject([{ id: 41, status: "ready" }]));
        expect(backend.requests.map(({ path, workspace }) => ({ path, workspace }))).toEqual([
            { path, workspace: String(ORIGIN) },
            { path: path + "/4/attachments", workspace: String(ORIGIN) },
        ]);
    });

    it("forwards the record-review mission origin through session creation", async () => {
        boundary.params = {};
        boundary.context.record = { type: "person", id: 41, label: "Origin contact" };
        const goal = "Review the origin contact";
        const session: AiChatSession = { id: 4, workspaceId: ORIGIN, createdByUserId: 7, title: "Origin mission", visibility: "private", status: "active", archived: false, ownedByCurrentUser: true, historySummarized: false, participationStatus: null, lastMessageAt: null, archivedAt: null, createdAt: "", updatedAt: "" };
        const run: AiAgentRun = {
            schemaVersion: 1, id: 12, sessionId: 4, missionType: "record_review_v1", definitionVersion: 1,
            state: "done", reasonCode: null, stateVersion: "1", goal,
            scope: { kind: "record", recordKind: "person", recordId: 41 },
            createdAt: "2026-10-19T12:00:00Z", executionDeadlineAt: "2026-10-19T12:15:00Z",
            hardDeadlineAt: "2026-10-19T12:15:00Z", startedAt: "2026-10-19T12:00:00Z", finishedAt: "2026-10-19T12:01:00Z",
            outputAvailable: false, outputRevision: null, proposalMembershipRevision: null, proposalFrozenAt: null,
            proposalFirstPreparedAt: null, reviewExpiresAt: null, proposalCounts: null, contentWithheld: false, availableActions: [], progress: null,
        };
        const path = "/api/ai/assistant/sessions";
        const backend = stubBackend(path, (requestPath, method) => {
            if (requestPath === path) return json(method === "GET" ? { items: [], total: 0 } : session);
            if (requestPath === path + "/4") return json({ session, messages: { items: [], total: 0 } });
            if (requestPath === path + "/4/missions") return json(run);
            if (requestPath === "/api/ai/assistant/missions") return json({ items: [], nextCursor: null });
            if (requestPath.endsWith("/invitations")) return json({ items: [], total: 0 });
            return undefined;
        });
        await act(async () => root.render(<AskConnexProvider>{null}</AskConnexProvider>));
        await act(async () => drawer().onOpenWorkspace());
        const mission = boundary.missionCreate;
        if (mission === null) throw new Error("The record-review mission composer did not render");
        expect(mission.disabled).toBe(false);
        expect(mission.recordLabel).toBe("Origin contact");
        let pending: Promise<boolean> | undefined;
        await act(async () => { pending = mission.onCreate(goal, "record_review_v1"); await backend.started.promise; });
        switchWorkspace();
        let created: boolean | undefined;
        await act(async () => { backend.held.resolve(backend.response(path)); created = await pending; });

        expect(created).toBe(true);
        expect(backend.requests.map(({ path, workspace }) => ({ path, workspace }))).toEqual([
            { path, workspace: String(ORIGIN) },
            { path: path + "/4/missions", workspace: String(ORIGIN) },
        ]);
        const missionRequest = backend.requests[1];
        expect(missionRequest?.init.signal).toBeInstanceOf(AbortSignal);
        expect(backend.requests[0]?.init.signal).toBe(missionRequest?.init.signal);
        const body = missionRequest?.init.body;
        expect(typeof body === "string" ? JSON.parse(body) : null).toMatchObject({
            missionType: "record_review_v1", goal, scope: { kind: "record", recordKind: "person", recordId: 41 },
        });
    });

    it.each(["first upload", "file validation"])("comment image uploads while %s is pending", async (hold) => {
        const path = "/api/attachments/upload-image";
        const backend = stubBackend(hold === "first upload" ? path : undefined);
        const validation = deferred<boolean>();
        if (hold === "file validation") vi.mocked(isInlineManagedImageFile).mockReturnValueOnce(validation.promise);
        await act(async () => root.render(<CommentsSection targetType="person" targetId={41} currentUser={{ id: 7, displayName: "Origin member", profilePictureUrl: null }} canComment canModerate />));
        const input = document.querySelector<HTMLInputElement>('input[type="file"]');
        if (input === null) throw new Error("The comment image input did not render");
        await act(async () => {
            Object.defineProperty(input, "files", { value: [image, image] });
            input.dispatchEvent(new Event("change", { bubbles: true }));
            if (hold === "first upload") await backend.started.promise;
        });
        switchWorkspace();
        await act(async () => { backend.held.resolve(backend.response(path)); validation.resolve(true); });
        expect(backend.requests.map(({ path, workspace }) => ({ path, workspace }))).toEqual([
            { path, workspace: String(ORIGIN) }, { path, workspace: String(ORIGIN) },
        ]);
        expect(document.querySelector("textarea")?.value).toContain("/api/attachments/41/content");
    });

    it.each(["approve", "reject", "undo"] as const)("reviewed tool %s decisions keep the batch origin", async (action) => {
        observeProposalReviewClock(new Headers({ Date: new Date().toUTCString() }));
        const proposals = [31, 32].map((id) => askConnexToolCall({ id, tier: action === "undo" ? "auto" : "confirm", reviewState: "open", reviewExpiresAt: new Date(Date.now() + 86_400_000).toISOString(), status: action === "undo" ? "executed" : "proposed", undoAvailable: action === "undo", undoExpiresAt: new Date(Date.now() + 86_400_000).toISOString(), change: { field: "owner", currentValue: null, currentValueUnresolved: false, proposedValue: "Teammate", state: "ready" } }));
        const detail: AiChatSessionDetail = {
            session: { id: 4, workspaceId: ORIGIN, createdByUserId: 7, title: "Origin decisions", visibility: "private", status: "active", archived: false, ownedByCurrentUser: true, historySummarized: false, participationStatus: null, lastMessageAt: null, archivedAt: null, createdAt: "", updatedAt: "" },
            messages: { items: [
                { id: 21, sessionId: 4, seq: 1, authorKind: "user", authorUserId: 7, authorDisplayName: "Origin member", content: "Assign both owners", createdAt: "2026-10-18T00:00:00Z" },
                { id: 22, sessionId: 4, seq: 2, authorKind: "assistant", authorUserId: null, authorDisplayName: null, content: "Review these proposals", createdAt: "2026-10-18T00:00:01Z" },
            ], total: 2 },
        };
        const prefix = "/api/ai/assistant/sessions/4/tool-calls/";
        const heldPath = prefix + "31/" + action;
        const backend = stubBackend(heldPath, (path, method) => {
            if (path === "/api/ai/assistant/sessions/4") return json(detail);
            if (path === "/api/ai/assistant/sessions/4/tool-calls") return json(proposals);
            if (path === prefix + "31" || path === prefix + "32") return json(proposals.find((proposal) => path.endsWith(String(proposal.id))));
            if (method !== "GET" && path.startsWith(prefix)) return json({ id: Number(path.split("/").at(-2)), tool: "assign_owner", tier: "confirm", status: action === "approve" ? "executed" : action === "undo" ? "undone" : "rejected", result: null, undoAvailable: false, undoExpiresAt: null, reviewState: "open", reviewExpiresAt: null });
            if (path === "/api/ai/assistant/sessions" || path.endsWith("/invitations")) return json({ items: [], total: 0 });
            return undefined;
        });
        await act(async () => root.render(<AskConnexProvider>{null}</AskConnexProvider>));
        expect(drawer().actionableToolCallIds.size).toBe(2);
        let pending: Promise<void> | undefined;
        await act(async () => { pending = drawer().onToolActions([31, 32], action); await backend.started.promise; });
        switchWorkspace();
        await act(async () => { backend.held.resolve(backend.response(heldPath)); await pending; });
        expect(backend.requests.map(({ path, workspace }) => ({ path, workspace }))).toEqual([
            { path: heldPath, workspace: String(ORIGIN) }, { path: prefix + "32/" + action, workspace: String(ORIGIN) },
        ]);
    });
});
