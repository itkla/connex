'use client';

import { useTranslations } from 'next-intl';
import { SparklesIcon } from '@heroicons/react/24/outline';

import { useAskConnex } from '@/app/components/ask-connex/AskConnexProvider';
import { useAskConnexSkills } from '@/app/hooks/useAskConnexSkills';
import { askConnexJobs } from '@/app/lib/askConnexEntryPoints';
import type { AiChatPageContextKind } from '@/app/lib/types';
import { Button } from '@/components/ui/button';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
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
 */
export default function AskConnexRecordEntry({ kind }: { kind: AiChatPageContextKind }) {
    const t = useTranslations('AskConnex');
    const { openWithPrompt } = useAskConnex();
    const skills = useAskConnexSkills(kind);
    const jobs = askConnexJobs(skills, { kind, hasSubject: true });

    if (jobs.length === 0) return null;

    return (
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
            </DropdownMenuContent>
        </DropdownMenu>
    );
}
