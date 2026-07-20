'use client';

import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { useTranslations } from 'next-intl';
import { Loader2Icon } from 'lucide-react';
import { BoltIcon, BriefcaseIcon, BuildingOffice2Icon, CheckCircleIcon, DocumentTextIcon, PaperClipIcon, UserIcon } from '@heroicons/react/24/outline';

import { getActiveWorkspaceMembers, getCompanies, getDeals, search } from '@/app/lib/api';
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

type Suggestion = {
    type: NoteReferenceType;
    id: number;
    label: string;
    sublabel: string;
    avatarUrl?: string | null;
};

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
    return [...users, ...people, ...deals, ...companies];
}

let recordsCache: { key: string; promise: Promise<Suggestion[]> } | null = null;
function loadRecords(): Promise<Suggestion[]> {
    const key = currentWorkspaceKey();
    if (!recordsCache || recordsCache.key !== key) {
        recordsCache = {
            key,
            promise: Promise.all([
                getCompanies().catch((): Company[] => []),
                getDeals().catch((): Deal[] => []),
            ])
                .then(([companies, deals]): Suggestion[] => [
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
                ])
                .catch(() => {
                    recordsCache = null;
                    return [];
                }),
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

type ActiveQuery = { text: string; trigger: Trigger; left: number; top?: number; bottom?: number; above: boolean };

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
    /** Slash commands offered from the Stage-A `/` menu; when omitted, `/` stays literal text. */
    commands?: readonly SlashCommandDef[];
    /** Invoked when a `run-action` slash command is chosen, with the command's `actionId`. */
    onRunAction?: (actionId: string) => void;
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
    autoFocus,
    commands,
    onRunAction,
}: Props) {
    const t = useTranslations('ActivityNotesEditor');
    const editorRef = useRef<HTMLDivElement>(null);
    const lastValue = useRef<string | null>(null);
    const savedRange = useRef<Range | null>(null);
    const composingRef = useRef(false);
    const listboxId = useId();
    const reduceMotion = useReducedMotion();
    const [members, setMembers] = useState<WorkspaceMember[]>([]);
    const [records, setRecords] = useState<Suggestion[]>([]);
    const [results, setResults] = useState<SearchResults | null>(null);
    const [query, setQuery] = useState<ActiveQuery | null>(null);
    const [pickerScope, setPickerScope] = useState<NoteReferenceType[] | null>(null);
    const [activeIndex, setActiveIndex] = useState(0);

    const hasCommands = (commands?.length ?? 0) > 0;

    useEffect(() => {
        let cancelled = false;
        loadMembers().then((list) => {
            if (!cancelled) setMembers(list);
        });
        return () => {
            cancelled = true;
        };
    }, []);

    const needsRecords =
        query?.trigger === '#' || (pickerScope?.some((type) => type === 'company' || type === 'deal') ?? false);
    useEffect(() => {
        if (!needsRecords) return;
        let cancelled = false;
        loadRecords().then((list) => {
            if (!cancelled) setRecords(list);
        });
        return () => {
            cancelled = true;
        };
    }, [needsRecords]);

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
    useEffect(() => {
        if (stageA || queryText.length < 1) return;
        let cancelled = false;
        const handle = window.setTimeout(() => {
            search(queryText)
                .then((res) => {
                    if (!cancelled) setResults(res);
                })
                .catch(() => {});
        }, SEARCH_DEBOUNCE_MS);
        return () => {
            cancelled = true;
            window.clearTimeout(handle);
        };
    }, [queryText, stageA]);

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
        const allowed =
            query.trigger === '/' ? pickerScope : MENTION_TRIGGER_TYPES[query.trigger];
        if (!allowed) return [];
        const needle = query.text.toLowerCase();
        const localPool: Suggestion[] = [
            ...(allowed.includes('user') ? memberSuggestions(members, excludeUserId) : []),
            ...(allowed.includes('company') || allowed.includes('deal') ? records : []),
        ];
        if (needle.length === 0) {
            return localPool.filter((suggestion) => allowed.includes(suggestion.type)).slice(0, MAX_SUGGESTIONS);
        }
        if (results) {
            return searchSuggestions(results, excludeUserId)
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
    }, [query, pickerScope, members, records, results, excludeUserId]);

    const menuOpen =
        query !== null && (stageA ? commandMatches.length > 0 : stageB ? true : suggestions.length > 0);
    const optionCount = stageA ? commandMatches.length : suggestions.length;
    const boundedIndex = optionCount > 0 ? Math.min(activeIndex, optionCount - 1) : 0;
    const activeOptionId = menuOpen && optionCount > 0 ? `${listboxId}-opt-${boundedIndex}` : undefined;

    const emit = useCallback(() => {
        const el = editorRef.current;
        if (!el) return;
        const serialized = serialize(el);
        lastValue.current = serialized;
        onChange(serialized);
    }, [onChange]);

    const closeMenu = useCallback(() => {
        setQuery(null);
        setPickerScope(null);
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
            if (char === '@' || char === '#' || char === '/') {
                if (char === '/' && !hasCommands) {
                    closeMenu();
                    return;
                }
                const preceding = index === 0 ? ' ' : textBefore[index - 1];
                if (/\s/.test(preceding) || (index === 0 && !(node.previousSibling instanceof Text))) {
                    const rect = range.getBoundingClientRect();
                    const spaceBelow = window.innerHeight - rect.bottom;
                    const above = spaceBelow < MENU_MAX_HEIGHT && rect.top > spaceBelow;
                    const left = Math.max(8, Math.min(rect.left, window.innerWidth - MENU_WIDTH - 8));
                    savedRange.current = range.cloneRange();
                    setQuery({
                        text: handle,
                        trigger: char,
                        left,
                        top: above ? undefined : rect.bottom + 6,
                        bottom: above ? window.innerHeight - rect.top + 6 : undefined,
                        above,
                    });
                    if (char !== '/') setPickerScope(null);
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
    }, [closeMenu, hasCommands]);

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
            setResults(null);
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
                if (event.key === 'Enter' && !stageA && suggestions[boundedIndex]) {
                    event.preventDefault();
                    insertReference(suggestions[boundedIndex]);
                    return;
                }
                if (event.key === ' ' && !stageA && query !== null && query.text.length >= 1) {
                    const suggestion = suggestions[boundedIndex];
                    if (suggestion) {
                        event.preventDefault();
                        insertReference(suggestion);
                        return;
                    }
                }
                if (event.key === 'Escape') {
                    event.preventDefault();
                    closeMenu();
                    return;
                }
            }
            if (event.key === 'Enter' && !event.metaKey && !event.ctrlKey) {
                event.preventDefault();
                document.execCommand('insertText', false, '\n');
                emit();
            }
        },
        [menuOpen, optionCount, stageA, commandMatches, suggestions, boundedIndex, selectCommand, insertReference, closeMenu, emit, query],
    );

    const showStageBState = stageB && optionCount === 0;
    const stageBSearchPending = stageB && queryText.length >= 1 && results === null;
    const stageBStateLabel = stageBSearchPending
        ? t('slashPickerSearching')
        : queryText.length < 1
          ? t('slashPickerPrompt')
          : t('slashPickerNoResults');

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
            {typeof document !== 'undefined' &&
                createPortal(
                    <AnimatePresence>
                        {menuOpen && query && (
                    <motion.ul
                        key="mention-menu"
                        id={listboxId}
                        role="listbox"
                        data-slot="editor-suggestion"
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
                        className="pointer-events-auto fixed z-[100] max-h-80 w-72 overflow-y-auto rounded-md bg-popover p-1 text-popover-foreground shadow-md ring-1 ring-foreground/10"
                    >
                        {stageA
                            ? commandMatches.map((def, index) => {
                                  const optionId = `${listboxId}-opt-${index}`;
                                  const CommandIcon = def.icon;
                                  return (
                                      <li
                                          key={def.id}
                                          id={optionId}
                                          role="option"
                                          aria-selected={index === boundedIndex}
                                      >
                                          <button
                                              type="button"
                                              tabIndex={-1}
                                              onMouseDown={(event) => {
                                                  event.preventDefault();
                                                  selectCommand(def);
                                              }}
                                              onMouseEnter={() => setActiveIndex(index)}
                                              className={cn(
                                                  'flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left text-sm transition-colors duration-100',
                                                  index === boundedIndex ? 'bg-brand-light/50 text-brand-dark' : 'text-foreground',
                                              )}
                                          >
                                              <span className="flex size-6 shrink-0 items-center justify-center rounded-full bg-muted text-muted-foreground">
                                                  <CommandIcon className="size-3" />
                                              </span>
                                              <span className="min-w-0 flex-1 truncate font-medium">{t(def.labelKey)}</span>
                                              <span className="shrink-0 truncate text-xs text-muted-foreground">{t(def.subtitleKey)}</span>
                                          </button>
                                      </li>
                                  );
                              })
                            : showStageBState
                              ? (
                                  <li role="presentation" className="flex items-center gap-2 px-2 py-2 text-sm text-muted-foreground">
                                      {stageBSearchPending && <Loader2Icon className="size-3.5 animate-spin" />}
                                      <span className="truncate">{stageBStateLabel}</span>
                                  </li>
                              )
                              : suggestions.map((suggestion, index) => {
                                    const optionId = `${listboxId}-opt-${index}`;
                                    const RecordIcon = suggestion.type === 'user' ? null : RECORD_ICON[suggestion.type];
                                    return (
                                        <li
                                            key={`${suggestion.type}-${suggestion.id}`}
                                            id={optionId}
                                            role="option"
                                            aria-selected={index === boundedIndex}
                                        >
                                            <button
                                                type="button"
                                                tabIndex={-1}
                                                onMouseDown={(event) => {
                                                    event.preventDefault();
                                                    insertReference(suggestion);
                                                }}
                                                onMouseEnter={() => setActiveIndex(index)}
                                                className={cn(
                                                    'flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left text-sm transition-colors duration-100',
                                                    index === boundedIndex ? 'bg-brand-light/50 text-brand-dark' : 'text-foreground',
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
                                                <span className="shrink-0 truncate text-xs text-muted-foreground">{suggestion.sublabel}</span>
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
