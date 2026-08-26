'use client';

import { useCallback, useEffect, useId, useImperativeHandle, useMemo, useRef, useState, type Ref } from 'react';
import { createPortal } from 'react-dom';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { useTranslations } from 'next-intl';
import { Loader2Icon } from 'lucide-react';
import { BoltIcon, BriefcaseIcon, BuildingOffice2Icon, CheckCircleIcon, DocumentTextIcon, PaperClipIcon, UserIcon } from '@heroicons/react/24/outline';

import { getCompanies, getDeals, getWorkspaceMembers, search } from '@/app/lib/api';
import { type Company, type Deal, type NoteReferenceType, type SearchResults, type WorkspaceMember } from '@/app/lib/types';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { cn } from '@/lib/utils';
import { filterSlashCommands, type SlashCommandDef } from './commands/slashCommandRegistry';

const TOKEN = /\[([^\]]+)\]\((user|person|deal|company|note|file|task|activity):(\d+)\)/g;
const HANDLE = /[A-Za-z0-9_.\-぀-ヿ㐀-䶿一-鿿豈-﫿ｦ-ﾟＡ-Ｚａ-ｚ０-９]/;
const MAX_SUGGESTIONS = 8;
const MENU_WIDTH = 288;
const MENU_MAX_HEIGHT = 320;
const SEARCH_DEBOUNCE_MS = 220;

const RECORD_ICON = { person: UserIcon, deal: BriefcaseIcon, company: BuildingOffice2Icon, note: DocumentTextIcon, file: PaperClipIcon, task: CheckCircleIcon, activity: BoltIcon };

type Trigger = '@' | '#' | '/';

const MENTION_TRIGGER_TYPES: Record<'@' | '#', readonly NoteReferenceType[]> = {
    '@': ['user', 'person'],
    '#': ['deal', 'company'],
};

function currentWorkspaceKey(): string {
    if (typeof document === 'undefined') return '';
    const match = document.cookie.match(/(?:^|;\s*)connex_workspace=([^;]+)/);
    return match ? decodeURIComponent(match[1]) : '';
}

function requestWorkspaceKey(requestInit?: RequestInit): string {
    const headerWorkspace = new Headers(requestInit?.headers).get('X-Workspace-Id');
    return headerWorkspace ?? currentWorkspaceKey();
}

function requestWorkspaceId(requestInit?: RequestInit): number | null {
    const workspaceId = Number(requestWorkspaceKey(requestInit));
    return Number.isSafeInteger(workspaceId) && workspaceId > 0 ? workspaceId : null;
}

let membersCache: { key: string; promise: Promise<WorkspaceMember[]> } | null = null;
function loadMembers(requestInit?: RequestInit): Promise<WorkspaceMember[]> {
    const workspaceId = requestWorkspaceId(requestInit);
    if (workspaceId === null) return Promise.resolve([]);
    if (requestInit?.signal) {
        return getWorkspaceMembers(workspaceId, requestInit).catch(() => []);
    }
    const key = requestWorkspaceKey(requestInit);
    if (!membersCache || membersCache.key !== key) {
        const promise = getWorkspaceMembers(workspaceId, requestInit).catch(() => {
            if (membersCache?.promise === promise) membersCache = null;
            return [];
        });
        membersCache = {
            key,
            promise,
        };
    }
    return membersCache.promise;
}

function sanitizeLabel(label: string): string {
    return label.replace(/[[\]\r\n]/g, '').trim();
}

type Suggestion = {
    type: NoteReferenceType;
    id: number;
    label: string;
    sublabel: string;
    avatarUrl?: string | null;
};

const EMPTY_MEMBERS: WorkspaceMember[] = [];
const EMPTY_SUGGESTIONS: Suggestion[] = [];

function memberSuggestions(members: WorkspaceMember[], excludeUserId?: number): Suggestion[] {
    return members.flatMap((member): Suggestion[] =>
        member.id === excludeUserId ? [] : [{
            type: 'user',
            id: member.id,
            label: member.displayName,
            sublabel: `@${member.username}`,
            avatarUrl: member.profilePictureUrl,
        }],
    );
}

function searchSuggestions(results: SearchResults, excludeUserId?: number): Suggestion[] {
    const users = (results.users ?? []).flatMap((user): Suggestion[] =>
        user.id === excludeUserId ? [] : [{
            type: 'user',
            id: user.id,
            label: user.displayName,
            sublabel: `@${user.username}`,
            avatarUrl: user.profilePictureUrl,
        }],
    );
    const people = (results.people ?? []).map((person): Suggestion => ({
        type: 'person',
        id: person.id,
        label: person.name,
        sublabel: person.title || person.company?.name || 'Contact',
        avatarUrl: person.imageUrl,
    }));
    const deals = (results.deals ?? []).map((deal): Suggestion => ({
        type: 'deal',
        id: deal.id,
        label: deal.name,
        sublabel: 'Deal',
    }));
    const companies = (results.companies ?? []).map((company): Suggestion => ({
        type: 'company',
        id: company.id,
        label: company.name,
        sublabel: company.industry || 'Company',
    }));
    const files = (results.attachments ?? []).map((file): Suggestion => ({
        type: 'file',
        id: file.id,
        label: file.fileName,
        sublabel: file.entityLabel || file.contentType || '',
    }));
    return [...users, ...people, ...deals, ...companies, ...files];
}

let recordsCache: { key: string; promise: Promise<Suggestion[]> } | null = null;
function fetchRecords(requestInit?: RequestInit): Promise<Suggestion[]> {
    return Promise.all([
        getCompanies(requestInit).catch((error): Company[] => {
            if (requestInit?.signal?.aborted) throw error;
            return [];
        }),
        getDeals(requestInit).catch((error): Deal[] => {
            if (requestInit?.signal?.aborted) throw error;
            return [];
        }),
    ]).then(([companies, deals]): Suggestion[] => [
        ...companies.map((company): Suggestion => ({
            type: 'company',
            id: company.id,
            label: company.name,
            sublabel: company.industry || 'Company',
        })),
        ...deals.map((deal): Suggestion => ({
            type: 'deal',
            id: deal.id,
            label: deal.name,
            sublabel: 'Deal',
        })),
    ]);
}

function loadRecords(requestInit?: RequestInit): Promise<Suggestion[]> {
    if (requestInit?.signal) {
        return fetchRecords(requestInit).catch(() => []);
    }
    const key = requestWorkspaceKey(requestInit);
    if (!recordsCache || recordsCache.key !== key) {
        const promise = fetchRecords(requestInit).catch(() => {
            if (recordsCache?.promise === promise) recordsCache = null;
            return [];
        });
        recordsCache = {
            key,
            promise,
        };
    }
    return recordsCache.promise;
}

type EditorSegment =
    | { kind: 'text'; value: string }
    | { kind: 'reference'; type: NoteReferenceType; id: number; label: string };

function splitTokens(value: string): EditorSegment[] {
    const segments: EditorSegment[] = [];
    let lastIndex = 0;
    for (const match of value.matchAll(TOKEN)) {
        const start = match.index ?? 0;
        if (start > lastIndex) segments.push({ kind: 'text', value: value.slice(lastIndex, start) });
        segments.push({ kind: 'reference', type: match[2] as NoteReferenceType, id: Number(match[3]), label: match[1] });
        lastIndex = start + match[0].length;
    }
    if (lastIndex < value.length) segments.push({ kind: 'text', value: value.slice(lastIndex) });
    return segments;
}

function makeChip(type: NoteReferenceType, id: number, label: string): HTMLSpanElement {
    const chip = document.createElement('span');
    chip.dataset.refType = type;
    chip.dataset.refId = String(id);
    chip.dataset.label = label;
    chip.contentEditable = 'false';
    if (type === 'user') {
        chip.className = 'rounded-sm bg-brand-light/50 px-0.5 font-medium text-brand-dark';
        chip.textContent = `@${label}`;
    } else {
        chip.className = 'rounded-sm bg-muted px-1 font-medium text-foreground';
        chip.textContent = label;
    }
    return chip;
}

function renderInto(root: HTMLElement, value: string) {
    root.replaceChildren();
    for (const segment of splitTokens(value)) {
        if (segment.kind === 'reference') root.appendChild(makeChip(segment.type, segment.id, segment.label));
        else root.appendChild(document.createTextNode(segment.value));
    }
}

function serializeNode(node: Node): string {
    if (node.nodeType === Node.TEXT_NODE) return node.textContent ?? '';
    if (!(node instanceof HTMLElement)) return '';
    if (node.dataset.refType && node.dataset.refId) {
        return `[${node.dataset.label ?? ''}](${node.dataset.refType}:${node.dataset.refId})`;
    }
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

type MenuPosition = {
    left: number;
    top?: number;
    bottom?: number;
    maxHeight: number;
    above: boolean;
};

type ActiveQuery = MenuPosition & {
    text: string;
    trigger: Trigger;
    workspaceKey: string;
};

type RemoteSearchState =
    | { key: string; status: 'success'; results: SearchResults }
    | { key: string; status: 'error' }
    | null;

type WorkspaceItems<T> = { workspaceKey: string; items: T[] };

function menuPosition(range: Range): MenuPosition {
    const rect = range.getBoundingClientRect();
    const viewport = window.visualViewport;
    const viewportLeft = viewport?.offsetLeft ?? 0;
    const viewportTop = viewport?.offsetTop ?? 0;
    const viewportWidth = viewport?.width ?? window.innerWidth;
    const viewportHeight = viewport?.height ?? window.innerHeight;
    const viewportRight = viewportLeft + viewportWidth;
    const viewportBottom = viewportTop + viewportHeight;
    const spaceBelow = viewportBottom - rect.bottom;
    const spaceAbove = rect.top - viewportTop;
    const above = spaceBelow < MENU_MAX_HEIGHT && spaceAbove > spaceBelow;
    const availableHeight = Math.max(0, (above ? spaceAbove : spaceBelow) - 8);
    const left = Math.max(
        viewportLeft + 8,
        Math.min(rect.left, viewportRight - MENU_WIDTH - 8),
    );
    return {
        left,
        top: above ? undefined : rect.bottom + 6,
        bottom: above ? window.innerHeight - rect.top + 6 : undefined,
        maxHeight: Math.min(MENU_MAX_HEIGHT, availableHeight),
        above,
    };
}

export type MentionEditorHandle = {
    /**
     * Appends literal text to the end of the editable content as its own
     * paragraph and emits the serialized value. The append goes through the
     * editor's DOM — never through an external value push — so uncommitted
     * typing and the caret survive: the DOM stays the source of truth and the
     * next input's serialization includes the appended text.
     */
    appendParagraph: (text: string) => void;
    /**
     * Moves keyboard focus into the editable content and puts the caret at its end. Used by owners
     * that hand a composition back to the writer — a recovery affordance that pre-fills a message,
     * for example — so the writer lands where they can keep typing rather than on the control they
     * just pressed.
     */
    focus: () => void;
};

type Props = {
    id?: string;
    value: string;
    onChange: (value: string) => void;
    placeholder?: string;
    className?: string;
    excludeUserId?: number;
    ariaInvalid?: boolean;
    ariaDescribedby?: string;
    ariaLabel?: string;
    autoFocus?: boolean;
    /** Slash commands offered from the Stage-A `/` menu; when omitted, `/` stays literal text. */
    commands?: readonly SlashCommandDef[];
    /** Invoked when a `run-action` slash command is chosen, with the command's `actionId`. */
    onRunAction?: (actionId: string) => void;
    onSubmit?: () => void;
    mentionTypes?: Partial<Record<'@' | '#', readonly NoteReferenceType[]>>;
    /** Monotonic request value that opens the existing record picker at the editor end. */
    recordPickerRequest?: number;
    /** Request context inherited from the host overlay, including its workspace header and abort signal. */
    requestInit?: RequestInit;
    /** Receives the imperative {@link MentionEditorHandle} for caret-safe programmatic inserts. */
    handleRef?: Ref<MentionEditorHandle>;
};

/**
 * A contentEditable note composer with inline reference chips. The editable DOM
 * is driven imperatively (never re-rendered by React while focused) to keep the
 * caret stable; {@link serialize} converts it back to the `[Label](type:id)`
 * token string exposed via {@code onChange}. Typing `@` or `#` opens a picker — an
 * ARIA combobox popup, positioned from the caret and flipping above when there
 * isn't room below. `@` searches people (workspace members and contacts); `#`
 * searches records (deals and companies). A selection inserts a non-editable chip.
 *
 * When {@link Props.commands} are supplied, typing `/` at the start of a block or
 * after whitespace opens a two-stage menu: Stage A lists the commands, and choosing
 * an `insert-reference` command scopes Stage B to a record picker whose selection
 * inserts a chip; `run-action` commands hand off to {@link Props.onRunAction}.
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
    ariaLabel,
    autoFocus,
    commands,
    onRunAction,
    onSubmit,
    mentionTypes,
    recordPickerRequest,
    requestInit,
    handleRef,
}: Props) {
    const t = useTranslations('ActivityNotesEditor');
    const editorRef = useRef<HTMLDivElement>(null);
    const lastValue = useRef<string | null>(null);
    const savedRange = useRef<Range | null>(null);
    const composingRef = useRef(false);
    const handledRecordPickerRequest = useRef(recordPickerRequest ?? 0);
    const listboxId = useId();
    const reduceMotion = useReducedMotion();
    const activeWorkspaceKey = requestWorkspaceKey(requestInit);
    const [memberState, setMemberState] = useState<WorkspaceItems<WorkspaceMember> | null>(null);
    const [recordState, setRecordState] = useState<WorkspaceItems<Suggestion> | null>(null);
    const [remoteSearch, setRemoteSearch] = useState<RemoteSearchState>(null);
    const [searchAttempt, setSearchAttempt] = useState(0);
    const [query, setQuery] = useState<ActiveQuery | null>(null);
    const [pickerScope, setPickerScope] = useState<NoteReferenceType[] | null>(null);
    const [activeIndex, setActiveIndex] = useState(0);

    const hasCommands = (commands?.length ?? 0) > 0;
    const members = memberState?.workspaceKey === activeWorkspaceKey ? memberState.items : EMPTY_MEMBERS;
    const records = recordState?.workspaceKey === activeWorkspaceKey ? recordState.items : EMPTY_SUGGESTIONS;

    useEffect(() => {
        const workspaceKey = activeWorkspaceKey;
        let cancelled = false;
        loadMembers(requestInit).then((items) => {
            if (
                !cancelled &&
                !requestInit?.signal?.aborted &&
                requestWorkspaceKey(requestInit) === workspaceKey
            ) {
                setMemberState({ workspaceKey, items });
            }
        });
        return () => {
            cancelled = true;
        };
    }, [activeWorkspaceKey, requestInit]);

    const needsRecords =
        query?.trigger === '#' || (pickerScope?.some((type) => type === 'company' || type === 'deal') ?? false);
    useEffect(() => {
        if (!needsRecords) return;
        const workspaceKey = activeWorkspaceKey;
        let cancelled = false;
        loadRecords(requestInit).then((items) => {
            if (
                !cancelled &&
                !requestInit?.signal?.aborted &&
                requestWorkspaceKey(requestInit) === workspaceKey
            ) {
                setRecordState({ workspaceKey, items });
            }
        });
        return () => {
            cancelled = true;
        };
    }, [activeWorkspaceKey, needsRecords, requestInit]);

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

    const stageA = query?.trigger === '/' && pickerScope === null;
    const stageB = query?.trigger === '/' && pickerScope !== null;

    const queryText = query?.text ?? '';
    const searchTerm = queryText.trim();
    const queryWorkspaceMatches = query === null || query.workspaceKey === activeWorkspaceKey;
    const searchScopeKey = query?.trigger === '/'
        ? pickerScope?.join(',') ?? ''
        : query?.trigger ?? '';
    const remoteQueryKey = !stageA && query && queryWorkspaceMatches && searchTerm.length > 0
        ? [query.workspaceKey, searchScopeKey, searchTerm].join('\u0000')
        : null;
    const remoteRequestKey = remoteQueryKey === null
        ? null
        : `${remoteQueryKey}\u0000${searchAttempt}`;
    const queryWorkspaceKey = query?.workspaceKey ?? null;

    useEffect(() => {
        if (remoteRequestKey === null || queryWorkspaceKey === null) return;
        const controller = new AbortController();
        const upstreamSignal = requestInit?.signal;
        const abortFromUpstream = () => controller.abort(upstreamSignal?.reason);
        if (upstreamSignal?.aborted) {
            abortFromUpstream();
            return;
        }
        upstreamSignal?.addEventListener('abort', abortFromUpstream, { once: true });
        const workspaceKey = queryWorkspaceKey;
        const handle = window.setTimeout(() => {
            search(searchTerm, { ...requestInit, signal: controller.signal })
                .then((res) => {
                    if (controller.signal.aborted || requestWorkspaceKey(requestInit) !== workspaceKey) return;
                    setRemoteSearch({ key: remoteRequestKey, status: 'success', results: res });
                })
                .catch(() => {
                    if (controller.signal.aborted || requestWorkspaceKey(requestInit) !== workspaceKey) return;
                    setRemoteSearch({ key: remoteRequestKey, status: 'error' });
                });
        }, SEARCH_DEBOUNCE_MS);
        return () => {
            window.clearTimeout(handle);
            upstreamSignal?.removeEventListener('abort', abortFromUpstream);
            controller.abort();
        };
    }, [queryWorkspaceKey, remoteRequestKey, requestInit, searchTerm]);

    const remoteResults = remoteSearch?.key === remoteRequestKey && remoteSearch.status === 'success'
        ? remoteSearch.results
        : null;
    const remoteSearchFailed = remoteSearch?.key === remoteRequestKey && remoteSearch.status === 'error';

    const commandMatches = useMemo(() => {
        if (!stageA || !query || !commands) return [];
        const supported = commands.filter(
            (def) =>
                def.kind === 'insert-reference' || (def.kind === 'run-action' && onRunAction !== undefined),
        );
        return filterSlashCommands(supported, query.text, t);
    }, [stageA, query, commands, onRunAction, t]);

    const suggestions = useMemo(() => {
        if (!query) return [];
        const allowed = query.trigger === '/'
            ? pickerScope
            : mentionTypes?.[query.trigger] ?? MENTION_TRIGGER_TYPES[query.trigger];
        if (!allowed) return [];
        const needle = query.text.toLowerCase();
        const localPool: Suggestion[] = [
            ...(allowed.includes('user') ? memberSuggestions(members, excludeUserId) : []),
            ...(allowed.includes('company') || allowed.includes('deal') ? records : []),
        ];
        if (needle.length === 0) {
            return localPool.filter((suggestion) => allowed.includes(suggestion.type)).slice(0, MAX_SUGGESTIONS);
        }
        if (remoteResults) {
            return searchSuggestions(remoteResults, excludeUserId)
                .filter((suggestion) => allowed.includes(suggestion.type))
                .slice(0, MAX_SUGGESTIONS);
        }
        return localPool
            .filter(
                (suggestion) =>
                    allowed.includes(suggestion.type) &&
                    (suggestion.label.toLowerCase().includes(needle) || suggestion.sublabel.toLowerCase().includes(needle)),
            )
            .slice(0, MAX_SUGGESTIONS);
    }, [query, pickerScope, mentionTypes, members, records, remoteResults, excludeUserId]);

    const menuOpen =
        query !== null && queryWorkspaceMatches && (stageA ? commandMatches.length > 0 : stageB ? true : suggestions.length > 0);
    const optionCount = stageA ? commandMatches.length : suggestions.length;
    const boundedIndex = optionCount > 0 ? Math.min(activeIndex, optionCount - 1) : 0;
    const stateOptionId = `${listboxId}-state`;
    const activeOptionId = menuOpen
        ? optionCount > 0
            ? `${listboxId}-opt-${boundedIndex}`
            : stageB && remoteSearchFailed
                ? stateOptionId
                : undefined
        : undefined;

    const emit = useCallback(() => {
        const el = editorRef.current;
        if (!el) return;
        const serialized = serialize(el);
        lastValue.current = serialized;
        onChange(serialized);
    }, [onChange]);

    useImperativeHandle(
        handleRef,
        () => ({
            appendParagraph: (text: string) => {
                const el = editorRef.current;
                if (!el) return;
                const gap = serialize(el).trim().length > 0 ? '\n\n' : '';
                el.appendChild(document.createTextNode(gap + text));
                emit();
            },
            focus: () => {
                const el = editorRef.current;
                if (!el) return;
                el.focus();
                const selection = window.getSelection();
                if (!selection) return;
                const range = document.createRange();
                range.selectNodeContents(el);
                range.collapse(false);
                selection.removeAllRanges();
                selection.addRange(range);
            },
        }),
        [emit],
    );

    const closeMenu = useCallback(() => {
        setQuery(null);
        setPickerScope(null);
        setActiveIndex(0);
    }, []);

    const repositionMenu = useCallback(() => {
        const range = savedRange.current;
        const editor = editorRef.current;
        if (!range || !editor?.contains(range.startContainer)) return;
        const position = menuPosition(range);
        setQuery((current) => {
            if (!current) return current;
            if (
                current.left === position.left &&
                current.top === position.top &&
                current.bottom === position.bottom &&
                current.maxHeight === position.maxHeight &&
                current.above === position.above
            ) {
                return current;
            }
            return { ...current, ...position };
        });
    }, []);

    const menuVisible = query !== null;
    useEffect(() => {
        if (!menuVisible) return;
        const viewport = window.visualViewport;
        window.addEventListener('resize', repositionMenu);
        viewport?.addEventListener('resize', repositionMenu);
        viewport?.addEventListener('scroll', repositionMenu);
        return () => {
            window.removeEventListener('resize', repositionMenu);
            viewport?.removeEventListener('resize', repositionMenu);
            viewport?.removeEventListener('scroll', repositionMenu);
        };
    }, [menuVisible, repositionMenu]);

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
            if (char === '@' || char === '#' || char === '/') {
                if (char === '/' && !hasCommands) {
                    closeMenu();
                    return;
                }
                const preceding = index === 0 ? ' ' : textBefore[index - 1];
                if (/\s/.test(preceding) || (index === 0 && !(node.previousSibling instanceof Text))) {
                    savedRange.current = range.cloneRange();
                    setQuery({
                        text: handle,
                        trigger: char,
                        workspaceKey: requestWorkspaceKey(requestInit),
                        ...menuPosition(range),
                    });
                    if (char !== '/') setPickerScope(null);
                    setActiveIndex(0);
                    return;
                }
                closeMenu();
                return;
            }
            const isStageBSpace = pickerScope !== null && (char === ' ' || char === '　');
            if (!HANDLE.test(char) && !isStageBSpace) break;
            handle = char + handle;
            index -= 1;
        }
        closeMenu();
    }, [closeMenu, hasCommands, pickerScope, requestInit]);

    useEffect(() => {
        if (recordPickerRequest == null
                || recordPickerRequest <= handledRecordPickerRequest.current) return;
        handledRecordPickerRequest.current = recordPickerRequest;
        const editor = editorRef.current;
        if (!editor) return;
        editor.focus();
        const selection = window.getSelection();
        if (!selection) return;
        const range = document.createRange();
        range.selectNodeContents(editor);
        range.collapse(false);
        selection.removeAllRanges();
        selection.addRange(range);
        const serialized = serialize(editor);
        const prefix = serialized.length > 0 && !/\s$/.test(serialized) ? ' #' : '#';
        document.execCommand('insertText', false, prefix);
        emit();
        detectQuery();
    }, [detectQuery, emit, recordPickerRequest]);

    const handleInput = useCallback(() => {
        emit();
        if (!composingRef.current) detectQuery();
    }, [emit, detectQuery]);

    const insertReference = useCallback(
        (suggestion: Suggestion) => {
            const el = editorRef.current;
            if (!el) {
                closeMenu();
                return;
            }
            const selection = window.getSelection();
            if (selection && savedRange.current) {
                el.focus();
                selection.removeAllRanges();
                selection.addRange(savedRange.current);
            }
            if (!selection || selection.rangeCount === 0) {
                closeMenu();
                return;
            }
            const range = selection.getRangeAt(0);
            const node = range.startContainer;
            if (node.nodeType !== Node.TEXT_NODE || !el.contains(node)) {
                closeMenu();
                return;
            }

            const text = node.textContent ?? '';
            const caret = range.startOffset;
            const at = text.lastIndexOf(query?.trigger ?? '@', caret - 1);
            const parent = node.parentNode;
            if (at === -1 || !parent) {
                closeMenu();
                return;
            }

            const before = text.slice(0, at);
            const after = text.slice(caret);

            const fallback = suggestion.type === 'user' ? suggestion.sublabel.replace(/^@/, '') : suggestion.type;
            const label = sanitizeLabel(suggestion.label) || fallback;
            const chip = makeChip(suggestion.type, suggestion.id, label);
            const spacer = document.createTextNode(' ');
            const afterNode = document.createTextNode(after);
            const beforeNode = document.createTextNode(before);

            parent.replaceChild(afterNode, node);
            parent.insertBefore(spacer, afterNode);
            parent.insertBefore(chip, spacer);
            if (before.length > 0) parent.insertBefore(beforeNode, chip);

            if (!reduceMotion) {
                chip.animate([{ opacity: 0 }, { opacity: 1 }], {
                    duration: 120,
                    easing: 'cubic-bezier(0.23, 1, 0.32, 1)',
                });
            }

            const next = document.createRange();
            next.setStart(afterNode, 0);
            next.collapse(true);
            selection.removeAllRanges();
            selection.addRange(next);

            closeMenu();
            emit();
        },
        [closeMenu, emit, reduceMotion, query],
    );

    const rewriteSlashSpan = useCallback(
        (keepSlash: boolean): boolean => {
            const el = editorRef.current;
            if (!el) return false;
            const selection = window.getSelection();
            if (selection && savedRange.current) {
                el.focus();
                selection.removeAllRanges();
                selection.addRange(savedRange.current);
            }
            if (!selection || selection.rangeCount === 0) return false;
            const range = selection.getRangeAt(0);
            const node = range.startContainer;
            if (node.nodeType !== Node.TEXT_NODE || !el.contains(node)) return false;
            const text = node.textContent ?? '';
            const caret = range.startOffset;
            const at = text.lastIndexOf('/', caret - 1);
            if (at === -1) return false;
            const end = keepSlash ? at + 1 : at;
            node.textContent = text.slice(0, end) + text.slice(caret);
            const next = document.createRange();
            next.setStart(node, end);
            next.collapse(true);
            selection.removeAllRanges();
            selection.addRange(next);
            if (keepSlash) savedRange.current = next.cloneRange();
            emit();
            return true;
        },
        [emit],
    );

    const beginReferencePicker = useCallback(
        (def: SlashCommandDef) => {
            if (!rewriteSlashSpan(true)) {
                closeMenu();
                return;
            }
            setSearchAttempt((attempt) => attempt + 1);
            setActiveIndex(0);
            setQuery((current) => (current ? { ...current, text: '' } : current));
            setPickerScope(def.entityTypes ? [...def.entityTypes] : []);
        },
        [rewriteSlashSpan, closeMenu],
    );

    const selectCommand = useCallback(
        (def: SlashCommandDef) => {
            if (def.kind === 'insert-reference') {
                beginReferencePicker(def);
                return;
            }
            if (def.kind === 'run-action') {
                rewriteSlashSpan(false);
                closeMenu();
                if (def.actionId) onRunAction?.(def.actionId);
            }
        },
        [beginReferencePicker, rewriteSlashSpan, closeMenu, onRunAction],
    );

    const retrySearch = useCallback(() => {
        setSearchAttempt((attempt) => attempt + 1);
    }, []);

    const handleKeyDown = useCallback(
        (event: React.KeyboardEvent<HTMLDivElement>) => {
            if (composingRef.current || event.nativeEvent.isComposing || event.keyCode === 229) return;
            if (menuOpen) {
                if (optionCount > 0 && (event.key === 'ArrowDown' || (event.key === 'Tab' && !event.shiftKey))) {
                    event.preventDefault();
                    setActiveIndex((boundedIndex + 1) % optionCount);
                    return;
                }
                if (optionCount > 0 && (event.key === 'ArrowUp' || (event.key === 'Tab' && event.shiftKey))) {
                    event.preventDefault();
                    setActiveIndex((boundedIndex - 1 + optionCount) % optionCount);
                    return;
                }
                if (event.key === 'Enter' && stageA) {
                    event.preventDefault();
                    const def = commandMatches[boundedIndex];
                    if (def) selectCommand(def);
                    return;
                }
                if (event.key === 'Enter' && stageB && optionCount === 0) {
                    event.preventDefault();
                    if (remoteSearchFailed) retrySearch();
                    return;
                }
                if (event.key === 'Enter' && !stageA && suggestions[boundedIndex]) {
                    event.preventDefault();
                    insertReference(suggestions[boundedIndex]);
                    return;
                }
                if (event.key === ' ' && query?.trigger !== '/' && query.text.length >= 1) {
                    const suggestion = suggestions[boundedIndex];
                    if (suggestion) {
                        event.preventDefault();
                        insertReference(suggestion);
                        return;
                    }
                }
                if (event.key === 'Escape') {
                    event.preventDefault();
                    event.stopPropagation();
                    closeMenu();
                    return;
                }
            }
            if (event.key === 'Enter' && !event.shiftKey && !event.metaKey && !event.ctrlKey && onSubmit) {
                event.preventDefault();
                onSubmit();
                return;
            }
            if (event.key === 'Enter' && !event.metaKey && !event.ctrlKey) {
                event.preventDefault();
                document.execCommand('insertText', false, '\n');
                emit();
            }
        },
        [menuOpen, optionCount, stageA, stageB, commandMatches, remoteSearchFailed, suggestions, boundedIndex, selectCommand, retrySearch, insertReference, closeMenu, emit, query, onSubmit],
    );

    const showStageBState = stageB && optionCount === 0;
    const stageBSearchPending = stageB && searchTerm.length > 0 && remoteResults === null && !remoteSearchFailed;
    const stageBStateLabel = remoteSearchFailed
        ? t('slashPickerSearchError')
        : stageBSearchPending
          ? t('slashPickerSearching')
          : searchTerm.length < 1
            ? t('slashPickerPrompt')
            : optionCount > 0
              ? t('slashPickerResults', { count: optionCount })
              : t('slashPickerNoResults');

    return (
        <>
            <div
                id={id}
                ref={editorRef}
                role="combobox"
                aria-autocomplete="list"
                aria-haspopup="listbox"
                aria-expanded={menuOpen}
                aria-controls={menuOpen ? listboxId : undefined}
                aria-activedescendant={activeOptionId}
                aria-label={ariaLabel ?? placeholder ?? t('composerAria')}
                aria-invalid={ariaInvalid}
                aria-describedby={ariaDescribedby}
                contentEditable
                suppressContentEditableWarning
                spellCheck
                onInput={handleInput}
                onKeyDown={handleKeyDown}
                onCompositionStart={() => {
                    composingRef.current = true;
                }}
                onCompositionEnd={() => {
                    composingRef.current = false;
                    emit();
                    detectQuery();
                }}
                onBlur={() => window.setTimeout(closeMenu, 120)}
                data-placeholder={placeholder}
                className={cn(
                    'min-h-[8.5rem] whitespace-pre-wrap break-words outline-none',
                    'empty:before:pointer-events-none empty:before:text-muted-foreground empty:before:content-[attr(data-placeholder)]',
                    className,
                )}
            />
            <span className="sr-only" role="status" aria-live="polite" aria-atomic="true">
                {stageB ? stageBStateLabel : ''}
            </span>
            {typeof document !== 'undefined' &&
                createPortal(
                    <AnimatePresence>
                        {menuOpen && query && (
                    <motion.ul
                        key="mention-menu"
                        id={listboxId}
                        role="listbox"
                        aria-label={stageA ? t('slashCommandMenuAria') : t('slashReferencePickerAria')}
                        aria-busy={stageBSearchPending || undefined}
                        data-slot="editor-suggestion"
                        initial={reduceMotion ? { opacity: 0 } : { opacity: 0, scale: 0.96 }}
                        animate={reduceMotion ? { opacity: 1 } : { opacity: 1, scale: 1 }}
                        exit={reduceMotion ? { opacity: 0 } : { opacity: 0, scale: 0.98, transition: { duration: 0.1 } }}
                        transition={{ duration: 0.15, ease: [0.23, 1, 0.32, 1] }}
                        style={{
                            left: query.left,
                            top: query.top,
                            bottom: query.bottom,
                            maxHeight: query.maxHeight,
                            transformOrigin: query.above ? 'bottom left' : 'top left',
                        }}
                        className="pointer-events-auto fixed z-[100] max-h-80 w-72 overflow-y-auto rounded-md bg-popover p-1 text-popover-foreground shadow-md ring-1 ring-foreground/10"
                    >
                        {stageA
                            ? commandMatches.map((def, index) => {
                                  const optionId = `${listboxId}-opt-${index}`;
                                  const CommandIcon = def.icon;
                                  return (
                                      <li key={def.id} role="presentation">
                                          <button
                                              id={optionId}
                                              type="button"
                                              role="option"
                                              aria-selected={index === boundedIndex}
                                              tabIndex={-1}
                                              onPointerDown={(event) => {
                                                  if (event.pointerType === 'mouse' && event.button === 0) {
                                                      event.preventDefault();
                                                  }
                                              }}
                                              onClick={() => selectCommand(def)}
                                              onPointerEnter={() => setActiveIndex(index)}
                                              className={cn(
                                                  'flex w-full cursor-pointer items-center gap-2 rounded-sm px-2 py-1.5 text-left text-sm transition-colors duration-100',
                                                  index === boundedIndex
                                                      ? 'bg-brand-light/50 text-brand-dark'
                                                      : 'text-foreground',
                                              )}
                                          >
                                              <span className="flex size-6 shrink-0 items-center justify-center rounded-full bg-muted text-muted-foreground">
                                                  <CommandIcon className="size-3" />
                                              </span>
                                              <span className="min-w-0 flex-1 truncate font-medium">{t(def.labelKey)}</span>
                                              <span className="shrink-0 truncate text-xs text-muted-foreground">
                                                  {t(def.subtitleKey)}
                                              </span>
                                          </button>
                                      </li>
                                  );
                              })
                            : showStageBState
                              ? (
                                  <li role="presentation">
                                      {remoteSearchFailed ? (
                                          <button
                                              id={stateOptionId}
                                              type="button"
                                              role="option"
                                              aria-selected="true"
                                              tabIndex={-1}
                                              onPointerDown={(event) => {
                                                  if (event.pointerType === 'mouse' && event.button === 0) {
                                                      event.preventDefault();
                                                  }
                                              }}
                                              onClick={retrySearch}
                                              className="flex w-full cursor-pointer items-center gap-2 rounded-sm bg-brand-light/50 px-2 py-2 text-left text-sm text-brand-dark"
                                          >
                                              <span className="min-w-0 flex-1 truncate">{stageBStateLabel}</span>
                                              <span className="shrink-0 font-medium text-foreground">
                                                  {t('slashPickerRetry')}
                                              </span>
                                          </button>
                                      ) : (
                                          <div className="flex items-center gap-2 rounded-sm px-2 py-2 text-sm text-muted-foreground">
                                              {stageBSearchPending && (
                                                  <Loader2Icon
                                                      className={cn('size-3.5 shrink-0', !reduceMotion && 'animate-spin')}
                                                  />
                                              )}
                                              <span className="min-w-0 flex-1 truncate">{stageBStateLabel}</span>
                                          </div>
                                      )}
                                  </li>
                              )
                              : suggestions.map((suggestion, index) => {
                                    const optionId = `${listboxId}-opt-${index}`;
                                    const RecordIcon = suggestion.type === 'user' ? null : RECORD_ICON[suggestion.type];
                                    return (
                                        <li key={`${suggestion.type}-${suggestion.id}`} role="presentation">
                                            <button
                                                id={optionId}
                                                type="button"
                                                role="option"
                                                aria-selected={index === boundedIndex}
                                                tabIndex={-1}
                                                onPointerDown={(event) => {
                                                    if (event.pointerType === 'mouse' && event.button === 0) {
                                                        event.preventDefault();
                                                    }
                                                }}
                                                onClick={() => insertReference(suggestion)}
                                                onPointerEnter={() => setActiveIndex(index)}
                                                className={cn(
                                                    'flex w-full cursor-pointer items-center gap-2 rounded-sm px-2 py-1.5 text-left text-sm transition-colors duration-100',
                                                    index === boundedIndex
                                                        ? 'bg-brand-light/50 text-brand-dark'
                                                        : 'text-foreground',
                                                )}
                                            >
                                                {RecordIcon ? (
                                                    <span className="flex size-6 shrink-0 items-center justify-center rounded-full bg-muted text-muted-foreground">
                                                        <RecordIcon className="size-3" />
                                                    </span>
                                                ) : (
                                                    <Avatar size="sm" className="ring-1 ring-border">
                                                        {suggestion.avatarUrl ? (
                                                            <AvatarImage src={suggestion.avatarUrl} alt={suggestion.label} />
                                                        ) : (
                                                            <AvatarFallback>
                                                                <UserIcon className="size-3 text-muted-foreground" />
                                                            </AvatarFallback>
                                                        )}
                                                    </Avatar>
                                                )}
                                                <span className="min-w-0 flex-1 truncate font-medium">{suggestion.label}</span>
                                                <span className="shrink-0 truncate text-xs text-muted-foreground">
                                                    {suggestion.sublabel}
                                                </span>
                                            </button>
                                        </li>
                                    );
                                })}
                    </motion.ul>
                )}
                    </AnimatePresence>,
                    document.body,
                )}
        </>
    );
}
