import {
    BriefcaseIcon,
    BuildingOffice2Icon,
    CheckCircleIcon,
    PaperClipIcon,
    UserIcon,
} from '@heroicons/react/24/outline';

import type { NoteReferenceType } from '@/app/lib/types';

/**
 * How a slash command behaves once chosen from the Stage-A menu:
 * - `insert-reference` opens a scoped record picker (Stage B) and inserts a `[Label](type:id)` chip.
 * - `run-action` hands control back to the host via `onRunAction` (e.g. reveal the follow-up panel).
 * - `insert-text` writes a fixed snippet at the caret (reserved for future commands).
 */
export type SlashKind = 'insert-reference' | 'run-action' | 'insert-text';

type SlashIcon = typeof UserIcon;

/**
 * A framework-neutral slash-command descriptor. Labels are stored as translation keys resolved by
 * the host at render time so the registry stays free of React and `next-intl` bindings.
 */
export type SlashCommandDef = {
    id: string;
    kind: SlashKind;
    labelKey: string;
    subtitleKey: string;
    aliases: readonly string[];
    icon: SlashIcon;
    /** Record types the Stage-B picker is scoped to, for `insert-reference` commands. */
    entityTypes?: readonly NoteReferenceType[];
    /** Opaque action identifier passed to the host `onRunAction`, for `run-action` commands. */
    actionId?: string;
};

/** Reveal the activity composer's follow-up task panel. */
export const FOLLOW_UP_COMMAND: SlashCommandDef = {
    id: 'follow-up',
    kind: 'run-action',
    labelKey: 'slashFollowUpCmd',
    subtitleKey: 'slashFollowUpCmdHint',
    aliases: ['follow-up', 'followup', 'フォローアップ'],
    icon: CheckCircleIcon,
    actionId: 'followUp',
};

/**
 * Create a standalone task. Registered for hosts that create tasks directly; intentionally omitted
 * from the activity composer, whose only task flow is the follow-up panel.
 */
export const TASK_COMMAND: SlashCommandDef = {
    id: 'task',
    kind: 'run-action',
    labelKey: 'slashTaskCmd',
    subtitleKey: 'slashTaskCmdHint',
    aliases: ['task', 'todo', 'タスク'],
    icon: CheckCircleIcon,
    actionId: 'task',
};

/** Link a company record. */
export const COMPANY_COMMAND: SlashCommandDef = {
    id: 'company',
    kind: 'insert-reference',
    labelKey: 'slashCompanyCmd',
    subtitleKey: 'slashCompanyCmdHint',
    aliases: ['company', '会社', '企業'],
    icon: BuildingOffice2Icon,
    entityTypes: ['company'],
};

/** Link a person — a contact or a workspace teammate. */
export const PERSON_COMMAND: SlashCommandDef = {
    id: 'person',
    kind: 'insert-reference',
    labelKey: 'slashPersonCmd',
    subtitleKey: 'slashPersonCmdHint',
    aliases: ['person', 'contact', '担当者', '連絡先'],
    icon: UserIcon,
    entityTypes: ['person', 'user'],
};

/** Link a deal record. */
export const DEAL_COMMAND: SlashCommandDef = {
    id: 'deal',
    kind: 'insert-reference',
    labelKey: 'slashDealCmd',
    subtitleKey: 'slashDealCmdHint',
    aliases: ['deal', '案件', '商談'],
    icon: BriefcaseIcon,
    entityTypes: ['deal'],
};

/** Link an existing file that is visible in the activity's workspace. */
export const FILE_COMMAND: SlashCommandDef = {
    id: 'file',
    kind: 'insert-reference',
    labelKey: 'slashFileCmd',
    subtitleKey: 'slashFileCmdHint',
    aliases: ['file', 'attachment', 'document', 'ファイル', '添付'],
    icon: PaperClipIcon,
    entityTypes: ['file'],
};

/** The record-reference commands (company, person, deal) as a stable, memo-friendly array. */
export const ENTITY_COMMANDS: readonly SlashCommandDef[] = [
    COMPANY_COMMAND,
    PERSON_COMMAND,
    DEAL_COMMAND,
];

/** The activity composer command set: reveal the follow-up panel, then the record references. */
export const ACTIVITY_COMMANDS: readonly SlashCommandDef[] = [
    FOLLOW_UP_COMMAND,
    ...ENTITY_COMMANDS,
    FILE_COMMAND,
];

type Translate = (key: string) => string;

/**
 * Filter slash commands by a query against each command's localized title and its aliases. An empty
 * query returns every command unchanged.
 */
export function filterSlashCommands(
    defs: readonly SlashCommandDef[],
    query: string,
    t: Translate,
): SlashCommandDef[] {
    const needle = query.trim().toLowerCase();
    if (!needle) return [...defs];
    return defs.filter((def) => {
        if (t(def.labelKey).toLowerCase().includes(needle)) return true;
        return def.aliases.some((alias) => alias.toLowerCase().includes(needle));
    });
}
