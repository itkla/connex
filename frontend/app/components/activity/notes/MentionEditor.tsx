'use client';

import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { UserIcon } from '@heroicons/react/24/outline';

import { getActiveWorkspaceMembers } from '@/app/lib/api';
import { type WorkspaceMember } from '@/app/lib/types';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { cn } from '@/lib/utils';

const TOKEN = /\[([^\]]+)\]\((user):(\d+)\)/g;
const HANDLE = /[A-Za-z0-9_.\-]/;
const MAX_SUGGESTIONS = 6;
const MENU_WIDTH = 256;
const MENU_MAX_HEIGHT = 280;

function currentWorkspaceKey(): string {
    if (typeof document === 'undefined') return '';
    const match = document.cookie.match(/(?:^|;\s*)connex_workspace=([^;]+)/);
    return match ? match[1] : '';
}

let membersCache: { key: string; promise: Promise<WorkspaceMember[]> } | null = null;
function loadMembers(): Promise<WorkspaceMember[]> {
    const key = currentWorkspaceKey();
    if (!membersCache || membersCache.key !== key) {
        membersCache = {
            key,
            promise: getActiveWorkspaceMembers().catch(() => {
                membersCache = null;
                return [];
            }),
        };
    }
    return membersCache.promise;
}

function sanitizeLabel(label: string): string {
    return label.replace(/[[\]\r\n]/g, '').trim();
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

function serializeNode(node: Node): string {
    if (node.nodeType === Node.TEXT_NODE) return node.textContent ?? '';
    if (!(node instanceof HTMLElement)) return '';
    if (node.dataset.userId) return `[${node.dataset.label ?? ''}](user:${node.dataset.userId})`;
    if (node.tagName === 'BR') return '\n';
    let inner = '';
    node.childNodes.forEach((child) => {
        inner += serializeNode(child);
    });
    return node.tagName === 'DIV' || node.tagName === 'P' ? `\n${inner}` : inner;
}

function serialize(root: HTMLElement): string {
    let out = '';
    root.childNodes.forEach((node) => {
        out += serializeNode(node);
    });
    return out.replace(/^\n/, '');
}

type ActiveQuery = { text: string; left: number; top?: number; bottom?: number; above: boolean };

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
 * token string exposed via {@code onChange}. Typing `@` opens a member picker —
 * an ARIA combobox popup, positioned from the caret and flipping above when
 * there isn't room below — whose selection inserts a non-editable chip.
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
    const listboxId = useId();
    const reduceMotion = useReducedMotion();
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

    const menuOpen = query !== null && suggestions.length > 0;
    const activeOptionId = menuOpen ? `${listboxId}-opt-${activeIndex}` : undefined;

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
                if (/\s/.test(preceding) || (index === 0 && !(node.previousSibling instanceof Text))) {
                    const rect = range.getBoundingClientRect();
                    const spaceBelow = window.innerHeight - rect.bottom;
                    const above = spaceBelow < MENU_MAX_HEIGHT && rect.top > spaceBelow;
                    const left = Math.max(8, Math.min(rect.left, window.innerWidth - MENU_WIDTH - 8));
                    setQuery({
                        text: handle,
                        left,
                        top: above ? undefined : rect.bottom + 6,
                        bottom: above ? window.innerHeight - rect.top + 6 : undefined,
                        above,
                    });
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
            if (!el || !selection || selection.rangeCount === 0) {
                closeMenu();
                return;
            }
            const range = selection.getRangeAt(0);
            const node = range.startContainer;
            if (node.nodeType !== Node.TEXT_NODE) {
                closeMenu();
                return;
            }

            const text = node.textContent ?? '';
            const caret = range.startOffset;
            const at = text.lastIndexOf('@', caret - 1);
            const parent = node.parentNode;
            if (at === -1 || !parent) {
                closeMenu();
                return;
            }

            const before = text.slice(0, at);
            const after = text.slice(caret);

            const chip = makeChip(member.id, sanitizeLabel(member.displayName) || member.username);
            const spacer = document.createTextNode(' ');
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
            if (menuOpen) {
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
                emit();
            }
        },
        [menuOpen, suggestions, activeIndex, insertMention, closeMenu, emit],
    );

    return (
        <>
            <div
                id={id}
                ref={editorRef}
                role="textbox"
                aria-multiline="true"
                aria-autocomplete="list"
                aria-controls={menuOpen ? listboxId : undefined}
                aria-activedescendant={activeOptionId}
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
            <AnimatePresence>
                {menuOpen && query && (
                    <motion.ul
                        key="mention-menu"
                        id={listboxId}
                        role="listbox"
                        initial={reduceMotion ? { opacity: 0 } : { opacity: 0, scale: 0.96 }}
                        animate={reduceMotion ? { opacity: 1 } : { opacity: 1, scale: 1 }}
                        exit={reduceMotion ? { opacity: 0 } : { opacity: 0, scale: 0.98, transition: { duration: 0.1 } }}
                        transition={{ duration: 0.15, ease: [0.23, 1, 0.32, 1] }}
                        style={{
                            left: query.left,
                            top: query.top,
                            bottom: query.bottom,
                            transformOrigin: query.above ? 'bottom left' : 'top left',
                        }}
                        className="fixed z-50 max-h-64 w-64 overflow-y-auto rounded-md bg-popover p-1 text-popover-foreground shadow-md ring-1 ring-foreground/10"
                    >
                        {suggestions.map((member, index) => {
                            const optionId = `${listboxId}-opt-${index}`;
                            return (
                                <li key={member.id} id={optionId} role="option" aria-selected={index === activeIndex}>
                                    <button
                                        type="button"
                                        tabIndex={-1}
                                        onMouseDown={(event) => {
                                            event.preventDefault();
                                            insertMention(member);
                                        }}
                                        onMouseEnter={() => setActiveIndex(index)}
                                        className={cn(
                                            'flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left text-sm transition-colors duration-100',
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
                            );
                        })}
                    </motion.ul>
                )}
            </AnimatePresence>
        </>
    );
}
