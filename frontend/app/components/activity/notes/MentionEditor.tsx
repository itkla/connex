'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { UserIcon } from '@heroicons/react/24/outline';

import { getActiveWorkspaceMembers } from '@/app/lib/api';
import { type WorkspaceMember } from '@/app/lib/types';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { cn } from '@/lib/utils';

const TOKEN = /\[([^\]]+)\]\((user):(\d+)\)/g;
const HANDLE = /[A-Za-z0-9_.\-]/;
const MAX_SUGGESTIONS = 6;

let membersCache: Promise<WorkspaceMember[]> | null = null;
function loadMembers(): Promise<WorkspaceMember[]> {
    if (!membersCache) {
        membersCache = getActiveWorkspaceMembers().catch(() => {
            membersCache = null;
            return [];
        });
    }
    return membersCache;
}

type EditorSegment = { kind: 'text'; value: string } | { kind: 'mention'; id: number; label: string };

function splitTokens(value: string): EditorSegment[] {
    const segments: EditorSegment[] = [];
    let lastIndex = 0;
    for (const match of value.matchAll(TOKEN)) {
        const start = match.index ?? 0;
        if (start > lastIndex) segments.push({ kind: 'text', value: value.slice(lastIndex, start) });
        segments.push({ kind: 'mention', id: Number(match[3]), label: match[1] });
        lastIndex = start + match[0].length;
    }
    if (lastIndex < value.length) segments.push({ kind: 'text', value: value.slice(lastIndex) });
    return segments;
}

function makeChip(id: number, label: string): HTMLSpanElement {
    const chip = document.createElement('span');
    chip.dataset.userId = String(id);
    chip.dataset.label = label;
    chip.contentEditable = 'false';
    chip.className = 'rounded-sm bg-brand-light/50 px-0.5 font-medium text-brand-dark';
    chip.textContent = `@${label}`;
    return chip;
}

function renderInto(root: HTMLElement, value: string) {
    root.replaceChildren();
    for (const segment of splitTokens(value)) {
        if (segment.kind === 'mention') root.appendChild(makeChip(segment.id, segment.label));
        else root.appendChild(document.createTextNode(segment.value));
    }
}

function serialize(root: HTMLElement): string {
    let out = '';
    root.childNodes.forEach((node) => {
        if (node.nodeType === Node.TEXT_NODE) {
            out += node.textContent ?? '';
        } else if (node instanceof HTMLElement) {
            if (node.dataset.userId) out += `[${node.dataset.label ?? ''}](user:${node.dataset.userId})`;
            else if (node.tagName === 'BR') out += '\n';
            else out += node.textContent ?? '';
        }
    });
    return out;
}

type ActiveQuery = { text: string; top: number; left: number };

type Props = {
    id?: string;
    value: string;
    onChange: (value: string) => void;
    placeholder?: string;
    className?: string;
    excludeUserId?: number;
    ariaInvalid?: boolean;
    ariaDescribedby?: string;
    autoFocus?: boolean;
};

/**
 * A contentEditable note composer with inline @-mention chips. The editable DOM
 * is driven imperatively (never re-rendered by React while focused) to keep the
 * caret stable; {@link serialize} converts it back to the `[Label](user:id)`
 * token string exposed via {@code onChange}. Typing `@` opens a member picker
 * whose selection inserts a non-editable chip.
 */
export default function MentionEditor({
    id,
    value,
    onChange,
    placeholder,
    className,
    excludeUserId,
    ariaInvalid,
    ariaDescribedby,
    autoFocus,
}: Props) {
    const editorRef = useRef<HTMLDivElement>(null);
    const lastValue = useRef<string | null>(null);
    const [members, setMembers] = useState<WorkspaceMember[]>([]);
    const [query, setQuery] = useState<ActiveQuery | null>(null);
    const [activeIndex, setActiveIndex] = useState(0);

    useEffect(() => {
        let cancelled = false;
        loadMembers().then((list) => {
            if (!cancelled) setMembers(list);
        });
        return () => {
            cancelled = true;
        };
    }, []);

    useEffect(() => {
        const el = editorRef.current;
        if (!el) return;
        if (value !== lastValue.current) {
            renderInto(el, value);
            lastValue.current = value;
        }
    }, [value]);

    useEffect(() => {
        if (autoFocus) editorRef.current?.focus();
    }, [autoFocus]);

    const suggestions = useMemo(() => {
        if (!query) return [];
        const needle = query.text.toLowerCase();
        return members
            .filter((member) => member.id !== excludeUserId)
            .filter(
                (member) =>
                    member.displayName.toLowerCase().includes(needle) ||
                    member.username.toLowerCase().includes(needle),
            )
            .slice(0, MAX_SUGGESTIONS);
    }, [query, members, excludeUserId]);

    const emit = useCallback(() => {
        const el = editorRef.current;
        if (!el) return;
        const serialized = serialize(el);
        lastValue.current = serialized;
        onChange(serialized);
    }, [onChange]);

    const closeMenu = useCallback(() => {
        setQuery(null);
        setActiveIndex(0);
    }, []);

    const detectQuery = useCallback(() => {
        const el = editorRef.current;
        const selection = window.getSelection();
        if (!el || !selection || !selection.isCollapsed || selection.rangeCount === 0) {
            closeMenu();
            return;
        }
        const range = selection.getRangeAt(0);
        const node = range.startContainer;
        if (node.nodeType !== Node.TEXT_NODE || !el.contains(node)) {
            closeMenu();
            return;
        }
        const textBefore = (node.textContent ?? '').slice(0, range.startOffset);
        let index = textBefore.length - 1;
        let handle = '';
        while (index >= 0) {
            const char = textBefore[index];
            if (char === '@') {
                const preceding = index === 0 ? ' ' : textBefore[index - 1];
                if (/\s/.test(preceding) || (index === 0 && (node.previousSibling === null || node.previousSibling instanceof HTMLElement))) {
                    const rect = range.getBoundingClientRect();
                    setQuery({ text: handle, top: rect.bottom + 4, left: rect.left });
                    setActiveIndex(0);
                    return;
                }
                closeMenu();
                return;
            }
            if (!HANDLE.test(char)) break;
            handle = char + handle;
            index -= 1;
        }
        closeMenu();
    }, [closeMenu]);

    const handleInput = useCallback(() => {
        emit();
        detectQuery();
    }, [emit, detectQuery]);

    const insertMention = useCallback(
        (member: WorkspaceMember) => {
            const el = editorRef.current;
            const selection = window.getSelection();
            if (!el || !selection || selection.rangeCount === 0) return;
            const range = selection.getRangeAt(0);
            const node = range.startContainer;
            if (node.nodeType !== Node.TEXT_NODE) return;

            const text = node.textContent ?? '';
            const caret = range.startOffset;
            const at = text.lastIndexOf('@', caret - 1);
            if (at === -1) return;

            const before = text.slice(0, at);
            const after = text.slice(caret);
            const parent = node.parentNode;
            if (!parent) return;

            const chip = makeChip(member.id, member.displayName);
            const spacer = document.createTextNode(' ');
            const afterNode = document.createTextNode(after);
            const beforeNode = document.createTextNode(before);

            parent.replaceChild(afterNode, node);
            parent.insertBefore(spacer, afterNode);
            parent.insertBefore(chip, spacer);
            if (before.length > 0) parent.insertBefore(beforeNode, chip);

            const next = document.createRange();
            next.setStart(afterNode, 0);
            next.collapse(true);
            selection.removeAllRanges();
            selection.addRange(next);

            closeMenu();
            emit();
        },
        [closeMenu, emit],
    );

    const handleKeyDown = useCallback(
        (event: React.KeyboardEvent<HTMLDivElement>) => {
            if (query && suggestions.length > 0) {
                if (event.key === 'ArrowDown') {
                    event.preventDefault();
                    setActiveIndex((index) => (index + 1) % suggestions.length);
                    return;
                }
                if (event.key === 'ArrowUp') {
                    event.preventDefault();
                    setActiveIndex((index) => (index - 1 + suggestions.length) % suggestions.length);
                    return;
                }
                if (event.key === 'Enter' || event.key === 'Tab') {
                    event.preventDefault();
                    insertMention(suggestions[activeIndex]);
                    return;
                }
                if (event.key === 'Escape') {
                    event.preventDefault();
                    closeMenu();
                    return;
                }
            }
            if (event.key === 'Enter') {
                event.preventDefault();
                document.execCommand('insertText', false, '\n');
            }
        },
        [query, suggestions, activeIndex, insertMention, closeMenu],
    );

    return (
        <>
            <div
                id={id}
                ref={editorRef}
                role="textbox"
                aria-multiline="true"
                aria-invalid={ariaInvalid}
                aria-describedby={ariaDescribedby}
                contentEditable
                suppressContentEditableWarning
                spellCheck
                onInput={handleInput}
                onKeyDown={handleKeyDown}
                onBlur={() => window.setTimeout(closeMenu, 120)}
                data-placeholder={placeholder}
                className={cn(
                    'min-h-[8.5rem] whitespace-pre-wrap break-words outline-none',
                    'empty:before:pointer-events-none empty:before:text-muted-foreground empty:before:content-[attr(data-placeholder)]',
                    className,
                )}
            />
            {query && suggestions.length > 0 && (
                <ul
                    role="listbox"
                    className="fixed z-50 max-h-64 w-64 overflow-y-auto rounded-md bg-popover p-1 text-popover-foreground shadow-md ring-1 ring-foreground/10"
                    style={{ top: query.top, left: query.left }}
                >
                    {suggestions.map((member, index) => (
                        <li key={member.id} role="option" aria-selected={index === activeIndex}>
                            <button
                                type="button"
                                onMouseDown={(event) => {
                                    event.preventDefault();
                                    insertMention(member);
                                }}
                                onMouseEnter={() => setActiveIndex(index)}
                                className={cn(
                                    'flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left text-sm transition-colors',
                                    index === activeIndex ? 'bg-brand-light/50 text-brand-dark' : 'text-foreground',
                                )}
                            >
                                <Avatar size="sm" className="ring-1 ring-border">
                                    {member.profilePictureUrl ? (
                                        <AvatarImage src={member.profilePictureUrl} alt={member.displayName} />
                                    ) : (
                                        <AvatarFallback>
                                            <UserIcon className="size-3 text-muted-foreground" />
                                        </AvatarFallback>
                                    )}
                                </Avatar>
                                <span className="min-w-0 flex-1 truncate font-medium">{member.displayName}</span>
                                <span className="shrink-0 truncate text-xs text-muted-foreground">@{member.username}</span>
                            </button>
                        </li>
                    ))}
                </ul>
            )}
        </>
    );
}
