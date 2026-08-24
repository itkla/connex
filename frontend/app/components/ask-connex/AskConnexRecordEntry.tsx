'use client';

import { useState } from 'react';
import { useTranslations } from 'next-intl';
import { BellAlertIcon, SparklesIcon } from '@heroicons/react/24/outline';

import { useAskConnex } from '@/app/components/ask-connex/AskConnexProvider';
import AskConnexWatchDialog from '@/app/components/ask-connex/AskConnexWatchDialog';
import { useAskConnexSkills } from '@/app/hooks/useAskConnexSkills';
import { useActions } from '@/app/hooks/useActions';
import { askConnexJobs } from '@/app/lib/askConnexEntryPoints';
import type { AiChatPageContextKind } from '@/app/lib/types';
import { Button } from '@/components/ui/button';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';

/**
 * The one Ask Connex entry point a record page carries.
 *
 * It sits in the record's own action cluster and behaves like every other menu there: normal action
 * language, the canonical menu button, no badge, and no second affordance elsewhere on the page.
 * What it offers is not written here — the jobs come from the server's capability directory for this
 * kind of record, so the menu lists the work Ask Connex can actually do on this page and nothing
 * else.
 *
 * When there is no such work — the member cannot use Ask Connex, or nothing in the catalog applies
 * here — the control is absent entirely. The record's own actions are untouched either way, so a
 * page whose assistant is unavailable is a page that simply has no assistant menu on it.
 *
 * Choosing a job writes the question into the composer and opens the panel. Nothing is asked until
 * the member sends it.
 *
 * The menu also carries the one write this entry point owns: creating a watch on this record. It is
 * separated from the questions because it is not a question — it opens the typed watch contract, and
 * nothing is saved until the member reads that contract and applies it. The contract dialog is
 * mounted as a sibling of the menu rather than inside it: menu content is portalled and unmounted on
 * close, so a dialog rendered within it would be torn down by the very selection that opened it.
 */
export default function AskConnexRecordEntry({ kind }: { kind: AiChatPageContextKind }) {
    const t = useTranslations('AskConnex');
    const { openWithPrompt } = useAskConnex();
    const { context } = useActions();
    const skills = useAskConnexSkills(kind);
    const jobs = askConnexJobs(skills, { kind, hasSubject: true });
    const [watchOpen, setWatchOpen] = useState(false);
    // The watched record is the one the page is already about, read from the same active-record
    // context the assistant itself anchors to; a numeric id is what the typed watch contract needs,
    // so a record whose id is not numeric simply offers no watch.
    const record = context.record;
    const subjectId = record === null ? Number.NaN : Number(record.id);
    const watchable = record !== null
        && record.type === kind
        && Number.isInteger(subjectId)
        && subjectId > 0;

    if (jobs.length === 0 && !watchable) return null;

    return (
        <>
            <DropdownMenu>
                <DropdownMenuTrigger asChild>
                    <Button variant="outline" size="toolbar" menu>
                        <SparklesIcon className="size-4" />
                        {t(`entryPoint.recordMenu.${kind}`)}
                    </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" className="w-64">
                    {jobs.map((job) => (
                        <DropdownMenuItem
                            key={job.id}
                            onSelect={() => openWithPrompt({ prompt: t(`jobs.${job.id}.prompt`) })}
                        >
                            <span className="min-w-0">{t(`jobs.${job.id}.label`)}</span>
                        </DropdownMenuItem>
                    ))}
                    {watchable ? (
                        <>
                            {jobs.length > 0 ? <DropdownMenuSeparator /> : null}
                            <DropdownMenuItem onSelect={() => setWatchOpen(true)}>
                                <BellAlertIcon aria-hidden />
                                <span className="min-w-0">{t('commandCenter.createMenuItem')}</span>
                            </DropdownMenuItem>
                        </>
                    ) : null}
                </DropdownMenuContent>
            </DropdownMenu>
            {watchable && record !== null ? (
                <AskConnexWatchDialog
                    open={watchOpen}
                    onOpenChange={setWatchOpen}
                    subjectKind={kind}
                    subjectId={subjectId}
                    subjectLabel={record.label}
                />
            ) : null}
        </>
    );
}
