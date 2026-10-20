"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import dynamic from "next/dynamic";
import { useTranslations } from "next-intl";
import {
    ArrowLeftIcon,
    BriefcaseIcon,
    CheckCircleIcon,
    ExclamationCircleIcon,
    LockClosedIcon,
    UserIcon,
    UsersIcon,
} from "@heroicons/react/24/outline";
import type { Contact, Deal, Note, NoteVisibility, User } from "@/app/lib/types";
import { SegmentedControl } from "@/components/ui/segmented-control";
import { createNote, isWorkspaceUnavailableError, updateNote } from "@/app/lib/api";
import { useApiErrorToast } from "@/app/hooks/useApiErrorToast";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { useFormDraft } from "@/app/hooks/useFormDraft";
import {
    clearDraftIfGeneration,
    DRAFT_VERSIONS,
    getDraftKeyGeneration,
    readDraft,
    type DraftKeyParts,
} from "@/app/lib/formDrafts";
import { noteContentToVisibleText } from "@/app/lib/references";
import { renderedWorkspaceNavigationVersion, workspacePinnedRequest } from "@/app/lib/renderedWorkspace";
import { toastDismiss, toastError, toastInfo } from "@/app/lib/toast";
import { deriveNoteTitle } from "@/app/lib/noteText";
import { CrumbLabel } from "@/app/hooks/useNavTrail";
import RecordReturnLink from "@/app/components/records/RecordReturnLink";
import { PageShell } from "@/app/components/PageShell";
import BacklinksPanel from "./BacklinksPanel";

const RichNoteEditor = dynamic(() => import("./RichNoteEditor"), { ssr: false });

type SaveStatus = "idle" | "saving" | "saved" | "error";

type NoteEditorDraft = {
    title: string;
    content: string;
    visibility: NoteVisibility;
};

const KEPT_DRAFT_LABEL_LENGTH = 48;

function isNoteEditorDraft(value: unknown): value is NoteEditorDraft {
    return typeof value === "object"
        && value !== null
        && "title" in value
        && typeof value.title === "string"
        && "content" in value
        && typeof value.content === "string"
        && "visibility" in value
        && (value.visibility === "private" || value.visibility === "workspace");
}

function keptDraftLabel(content: string): string {
    const text = noteContentToVisibleText(content).trim().replace(/\s+/g, " ");
    return text.length > KEPT_DRAFT_LABEL_LENGTH ? `${text.slice(0, KEPT_DRAFT_LABEL_LENGTH - 1)}…` : text;
}

const MAX_NOTE_TITLE_LENGTH = 255;

function titleIsTooLong(value: string): boolean {
    return value.length > MAX_NOTE_TITLE_LENGTH;
}

type Props = {
    note: Note | null;
    currentUserId: number;
    persons: Contact[];
    deals: Deal[];
    users: User[];
    returnPath: string;
};

/**
 * Full-page rich note editor. Handles both an existing note and a new draft,
 * persisting via debounced autosave (creating on the first non-empty change).
 *
 * Every save, including the flush when the editor unmounts, is pinned to the workspace the editor
 * was opened in (#1732). A workspace switch publishes the new selection before navigation unmounts
 * this page, so an unpinned flush would write this note's text into the newly selected workspace.
 * Once that workspace refuses a save because the viewer is no longer a member, autosave stops for
 * good: retrying could only fail again. The text stays in the editor and is also kept as this
 * editor's own draft (form type `noteEditor`, one per new note or existing note), written through
 * the draft store's ownership and generation guards so it never replaces another composer's draft.
 * Opening the same editor in that workspace again offers to resume it; it is never offered in
 * another workspace.
 */
export default function NoteEditorView({ note, currentUserId, persons, deals, users, returnPath }: Props) {
    const t = useTranslations("ActivityNotesEditor");
    const showApiError = useApiErrorToast("ActivityNotesEditor");
    const { activeWorkspaceId } = useWorkspace();
    const tResume = useTranslations("DraftResume");
    const [originWorkspaceId] = useState(activeWorkspaceId);
    const [originNavigationVersion] = useState(renderedWorkspaceNavigationVersion);
    const [noteId, setNoteId] = useState<number | null>(note?.id ?? null);
    const editorDraftScope = noteId === null ? "new" : `note:${noteId}`;
    const editorDraftParts = useMemo<DraftKeyParts>(() => ({
        userId: currentUserId,
        workspaceId: originWorkspaceId,
        formType: "noteEditor",
        scope: editorDraftScope,
    }), [currentUserId, editorDraftScope, originWorkspaceId]);
    const editorDraft = useFormDraft<NoteEditorDraft>({
        keyParts: editorDraftParts,
        version: DRAFT_VERSIONS.noteEditor,
    });
    const persistEditorDraft = editorDraft.persist;
    const clearEditorDraft = editorDraft.clear;
    const rekeyEditorDraft = editorDraft.rekeyScope;

    const [title, setTitle] = useState(note?.title ?? "");
    const [content, setContent] = useState(note?.content ?? "");
    const [visibility, setVisibility] = useState<NoteVisibility>(
        note?.visibility ?? (note?.person || note?.deal ? "workspace" : "private"),
    );
    const [status, setStatus] = useState<SaveStatus>("idle");
    const [titleError, setTitleError] = useState<string | null>(null);
    const dirtyRef = useRef(false);
    const workspaceRefusedRef = useRef(false);
    const savingRef = useRef(false);
    const mountedRef = useRef(true);
    const saveRef = useRef<() => void>(() => {});
    const stateRef = useRef({ noteId, title, content, visibility });

    useEffect(() => {
        stateRef.current = { noteId, title, content, visibility };
    }, [noteId, title, content, visibility]);

    useEffect(() => {
        mountedRef.current = true;
        return () => {
            mountedRef.current = false;
        };
    }, []);

    const keepRefusedText = useCallback(() => {
        const snapshot = stateRef.current;
        if (!snapshot.content.trim()) return;
        persistEditorDraft({ title: snapshot.title, content: snapshot.content, visibility: snapshot.visibility });
    }, [persistEditorDraft]);

    useEffect(() => {
        const kept = readDraft(editorDraftParts, { version: DRAFT_VERSIONS.noteEditor });
        if (!kept || !isNoteEditorDraft(kept.data)) return;
        const keptData = kept.data;
        const keptGeneration = getDraftKeyGeneration(kept.key);
        toastInfo(tResume("noteMessageNamed", { label: keptDraftLabel(keptData.content) }), {
            id: kept.key,
            duration: Infinity,
            action: {
                label: tResume("resumeNote"),
                onClick: () => {
                    if (getDraftKeyGeneration(kept.key) !== keptGeneration) return;
                    persistEditorDraft(keptData);
                    setTitle(keptData.title);
                    setContent(keptData.content);
                    setVisibility(keptData.visibility);
                    dirtyRef.current = true;
                },
            },
            cancel: {
                label: tResume("discardNote"),
                onClick: () => {
                    clearDraftIfGeneration(kept.key, keptGeneration);
                },
            },
        });
        return () => toastDismiss(kept.key);
    }, [editorDraftParts, persistEditorDraft, tResume]);

    const noteWorkspaceRefused = useCallback((error: unknown, report = true): boolean => {
        if (!isWorkspaceUnavailableError(error)) return false;
        if (!workspaceRefusedRef.current) {
            workspaceRefusedRef.current = true;
            if (report) showApiError(error, "saveError");
        }
        keepRefusedText();
        return true;
    }, [keepRefusedText, showApiError]);

    const person = note?.person ? persons.find((item) => item.id === note.person) ?? null : null;
    const deal = note?.deal ? deals.find((item) => item.id === note.deal) ?? null : null;
    const author = users.find((item) => item.id === (note?.author ?? currentUserId)) ?? null;

    const save = useCallback(() => {
        if (savingRef.current) return;
        if (workspaceRefusedRef.current) {
            dirtyRef.current = false;
            keepRefusedText();
            return;
        }
        const snapshot = stateRef.current;
        const body = snapshot.content;
        const nextTitle = snapshot.title.trim() ? snapshot.title.trim() : null;
        if (!body.trim()) {
            dirtyRef.current = false;
            setStatus("idle");
            return;
        }
        if (nextTitle != null && titleIsTooLong(nextTitle)) {
            setTitleError(t("titleTooLong", { max: MAX_NOTE_TITLE_LENGTH }));
            setStatus("error");
            return;
        }
        setTitleError(null);
        savingRef.current = true;
        dirtyRef.current = false;
        setStatus("saving");
        const pinned = workspacePinnedRequest(originWorkspaceId);
        const request =
            snapshot.noteId == null
                ? createNote(
                    { content: body, title: nextTitle, visibility: snapshot.visibility, author: currentUserId },
                    pinned,
                )
                : updateNote(snapshot.noteId, { content: body, title: nextTitle, visibility: snapshot.visibility }, pinned);
        request
            .then((saved) => {
                if (snapshot.noteId == null && saved?.id) {
                    rekeyEditorDraft(`note:${saved.id}`);
                    stateRef.current = { ...stateRef.current, noteId: saved.id };
                    setNoteId(saved.id);
                    if (mountedRef.current && renderedWorkspaceNavigationVersion() === originNavigationVersion) {
                        window.history.replaceState(null, "", `/activity/notes/${saved.id}`);
                    }
                }
                clearEditorDraft();
                setStatus("saved");
            })
            .catch((error: unknown) => {
                setStatus("error");
                if (noteWorkspaceRefused(error)) return;
                dirtyRef.current = true;
                toastError(t("saveError"));
            })
            .finally(() => {
                savingRef.current = false;
                if (dirtyRef.current) saveRef.current();
            });
    }, [clearEditorDraft, currentUserId, keepRefusedText, noteWorkspaceRefused, originNavigationVersion, originWorkspaceId, rekeyEditorDraft, t]);

    const ensureNoteId = useCallback(async (): Promise<number | null> => {
        const existing = stateRef.current.noteId;
        if (existing != null) return existing;

        while (savingRef.current) {
            await new Promise<void>((resolve) => {
                window.setTimeout(resolve, 40);
            });
            const createdWhileWaiting = stateRef.current.noteId;
            if (createdWhileWaiting != null) return createdWhileWaiting;
        }
        if (workspaceRefusedRef.current) return null;

        const snapshot = stateRef.current;
        const nextTitle = snapshot.title.trim() ? snapshot.title.trim() : null;
        if (nextTitle != null && titleIsTooLong(nextTitle)) {
            setTitleError(t("titleTooLong", { max: MAX_NOTE_TITLE_LENGTH }));
            setStatus("error");
            throw new Error("title-too-long");
        }

        savingRef.current = true;
        setTitleError(null);
        setStatus("saving");
        try {
            const body = snapshot.content.trim() ? snapshot.content : "\u200B";
            const saved = await createNote({
                content: body,
                title: nextTitle,
                visibility: snapshot.visibility,
                author: currentUserId,
            }, workspacePinnedRequest(originWorkspaceId));
            if (!saved?.id) throw new Error("missing-note-id");
            rekeyEditorDraft(`note:${saved.id}`);
            stateRef.current = { ...stateRef.current, noteId: saved.id };
            setNoteId(saved.id);
            if (mountedRef.current && renderedWorkspaceNavigationVersion() === originNavigationVersion) {
                window.history.replaceState(null, "", `/activity/notes/${saved.id}`);
            }
            dirtyRef.current = false;
            setStatus("saved");
            return saved.id;
        } catch (error) {
            setStatus("error");
            if (!noteWorkspaceRefused(error, false)) dirtyRef.current = true;
            throw error;
        } finally {
            savingRef.current = false;
            if (dirtyRef.current) saveRef.current();
        }
    }, [currentUserId, noteWorkspaceRefused, originNavigationVersion, originWorkspaceId, rekeyEditorDraft, t]);

    useEffect(() => {
        saveRef.current = save;
    }, [save]);

    useEffect(() => {
        if (!dirtyRef.current) return;
        const handle = setTimeout(() => save(), 900);
        return () => clearTimeout(handle);
    }, [title, content, visibility, save]);

    useEffect(() => {
        const flush = () => {
            if (dirtyRef.current) saveRef.current();
        };
        window.addEventListener("beforeunload", flush);
        return () => {
            window.removeEventListener("beforeunload", flush);
            flush();
        };
    }, []);

    const markDirty = () => {
        dirtyRef.current = true;
    };

    const recordHref = person
        ? `/records/contacts/${person.id}`
        : deal
          ? `/records/deals/${deal.id}`
          : null;
    const recordLabel = person?.name ?? deal?.name ?? null;
    const RecordIcon = person ? UserIcon : BriefcaseIcon;
    const displayTitle = deriveNoteTitle({ title, content }, t("untitled"));

    return (
        <PageShell>
                {note ? <CrumbLabel value={displayTitle} /> : null}
                <div className={note ? "flex items-center justify-end gap-4" : "flex items-center justify-between gap-4"}>
                    {note ? null : (
                        <RecordReturnLink
                            href={returnPath}
                            className="inline-flex w-fit items-center gap-2 text-sm text-brand transition-colors hover:text-brand-hover"
                        >
                            <ArrowLeftIcon className="h-4 w-4" />
                            {t("back")}
                        </RecordReturnLink>
                    )}
                    <SaveIndicator status={status} labels={t} />
                </div>

                <div className="mx-auto w-full max-w-3xl">
                    <input
                        id="note-title"
                        value={title}
                        onChange={(event) => {
                            const nextTitle = event.target.value;
                            const nextTitleError = titleIsTooLong(nextTitle.trim())
                                ? t("titleTooLong", { max: MAX_NOTE_TITLE_LENGTH })
                                : null;
                            setTitle(nextTitle);
                            setTitleError(nextTitleError);
                            if (nextTitleError) setStatus("error");
                            else if (titleError) setStatus("idle");
                            markDirty();
                        }}
                        placeholder={t("titlePlaceholder")}
                        aria-label={t("titlePlaceholder")}
                        aria-invalid={titleError ? true : undefined}
                        aria-describedby={titleError ? "note-title-error" : undefined}
                        className="w-full border-0 bg-transparent p-0 text-4xl font-extrabold tracking-tight text-foreground outline-none placeholder:text-muted-foreground/50"
                    />
                    {titleError ? (
                        <p id="note-title-error" className="mt-2 text-sm text-destructive">
                            {titleError}
                        </p>
                    ) : null}

                    <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted-foreground">
                        {author ? <span>{author.displayName || author.username}</span> : null}
                        {recordHref && recordLabel ? (
                            <>
                                <span aria-hidden="true">·</span>
                                <Link
                                    href={recordHref}
                                    className="inline-flex items-center gap-1 rounded-md bg-muted px-1.5 py-0.5 text-foreground transition-colors hover:bg-accent"
                                >
                                    <RecordIcon className="h-3.5 w-3.5" />
                                    <span className="max-w-[16rem] truncate">{recordLabel}</span>
                                </Link>
                            </>
                        ) : null}
                        <SegmentedControl<NoteVisibility>
                            ariaLabel={t("visibilityAria")}
                            className="ml-auto"
                            value={visibility}
                            onChange={(value) => {
                                setVisibility(value);
                                markDirty();
                            }}
                            options={[
                                {
                                    value: "private",
                                    label: t("visibilityPrivate"),
                                    icon: <LockClosedIcon className="h-3.5 w-3.5" />,
                                },
                                {
                                    value: "workspace",
                                    label: t("visibilityWorkspace"),
                                    icon: <UsersIcon className="h-3.5 w-3.5" />,
                                },
                            ]}
                        />
                    </div>

                    <div className="mt-6">
                        <RichNoteEditor
                            value={content}
                            onChange={(markdown) => {
                                setContent(markdown);
                                markDirty();
                            }}
                            excludeUserId={currentUserId}
                            autofocus={noteId == null}
                            ensureNoteId={ensureNoteId}
                        />
                    </div>

                    {noteId != null ? (
                        <BacklinksPanel refType="note" refId={noteId} excludeNoteId={noteId} />
                    ) : null}
                </div>
        </PageShell>
    );
}

function SaveIndicator({
    status,
    labels,
}: {
    status: SaveStatus;
    labels: (key: string) => string;
}) {
    if (status === "saving") {
        return <span className="text-sm text-muted-foreground">{labels("saving")}</span>;
    }
    if (status === "saved") {
        return (
            <span className="inline-flex items-center gap-1.5 text-sm text-muted-foreground">
                <CheckCircleIcon className="h-4 w-4 text-brand" />
                {labels("saved")}
            </span>
        );
    }
    if (status === "error") {
        return (
            <span className="inline-flex items-center gap-1.5 text-sm text-destructive">
                <ExclamationCircleIcon className="h-4 w-4" />
                {labels("saveError")}
            </span>
        );
    }
    return null;
}
