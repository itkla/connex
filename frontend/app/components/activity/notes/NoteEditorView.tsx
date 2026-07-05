"use client";

import { useCallback, useEffect, useRef, useState } from "react";
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
import { SegmentedToggle } from "@/app/components/filters";
import { createNote, updateNote } from "@/app/lib/api";
import { toastError } from "@/app/lib/toast";
import { deriveNoteTitle } from "@/app/lib/noteText";
import { CrumbLabel } from "@/app/hooks/useNavTrail";

const RichNoteEditor = dynamic(() => import("./RichNoteEditor"), { ssr: false });

type SaveStatus = "idle" | "saving" | "saved" | "error";

type Props = {
    note: Note | null;
    currentUserId: number;
    persons: Contact[];
    deals: Deal[];
    users: User[];
};

/**
 * Full-page rich note editor. Handles both an existing note and a new draft,
 * persisting via debounced autosave (creating on the first non-empty change).
 */
export default function NoteEditorView({ note, currentUserId, persons, deals, users }: Props) {
    const t = useTranslations("ActivityNotesEditor");

    const [noteId, setNoteId] = useState<number | null>(note?.id ?? null);
    const [title, setTitle] = useState(note?.title ?? "");
    const [content, setContent] = useState(note?.content ?? "");
    const [visibility, setVisibility] = useState<NoteVisibility>(
        note?.visibility ?? (note?.person || note?.deal ? "workspace" : "private"),
    );
    const [status, setStatus] = useState<SaveStatus>("idle");
    const dirtyRef = useRef(false);
    const savingRef = useRef(false);
    const saveRef = useRef<() => void>(() => {});
    const stateRef = useRef({ noteId, title, content, visibility });

    useEffect(() => {
        stateRef.current = { noteId, title, content, visibility };
    }, [noteId, title, content, visibility]);

    const person = note?.person ? persons.find((item) => item.id === note.person) ?? null : null;
    const deal = note?.deal ? deals.find((item) => item.id === note.deal) ?? null : null;
    const author = users.find((item) => item.id === (note?.author ?? currentUserId)) ?? null;

    const save = useCallback(() => {
        if (savingRef.current) return;
        const snapshot = stateRef.current;
        const body = snapshot.content;
        const nextTitle = snapshot.title.trim() ? snapshot.title.trim() : null;
        if (!body.trim()) {
            dirtyRef.current = false;
            setStatus("idle");
            return;
        }
        savingRef.current = true;
        dirtyRef.current = false;
        setStatus("saving");
        const request =
            snapshot.noteId == null
                ? createNote({ content: body, title: nextTitle, visibility: snapshot.visibility, author: currentUserId })
                : updateNote(snapshot.noteId, { content: body, title: nextTitle, visibility: snapshot.visibility });
        request
            .then((saved) => {
                if (snapshot.noteId == null && saved?.id) {
                    stateRef.current = { ...stateRef.current, noteId: saved.id };
                    setNoteId(saved.id);
                    window.history.replaceState(null, "", `/activity/notes/${saved.id}`);
                }
                setStatus("saved");
            })
            .catch(() => {
                dirtyRef.current = true;
                setStatus("error");
                toastError(t("saveError"));
            })
            .finally(() => {
                savingRef.current = false;
                if (dirtyRef.current) saveRef.current();
            });
    }, [currentUserId, t]);

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
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-5xl flex-col gap-6">
                <CrumbLabel value={displayTitle} />
                <div className="flex items-center justify-between gap-4">
                    <Link
                        href="/activity/notes"
                        className="inline-flex w-fit items-center gap-2 text-sm text-brand transition-colors hover:text-brand-hover"
                    >
                        <ArrowLeftIcon className="h-4 w-4" />
                        {t("back")}
                    </Link>
                    <SaveIndicator status={status} labels={t} />
                </div>

                <div className="mx-auto w-full max-w-3xl">
                    <input
                        value={title}
                        onChange={(event) => {
                            setTitle(event.target.value);
                            markDirty();
                        }}
                        placeholder={t("titlePlaceholder")}
                        aria-label={t("titlePlaceholder")}
                        className="w-full border-0 bg-transparent p-0 text-4xl font-extrabold tracking-tight text-foreground outline-none placeholder:text-muted-foreground/50"
                    />

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
                        <SegmentedToggle<NoteVisibility>
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
                        />
                    </div>
                </div>
            </div>
        </div>
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
