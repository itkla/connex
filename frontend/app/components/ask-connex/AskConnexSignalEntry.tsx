'use client';

import { useTranslations } from 'next-intl';
import { SparklesIcon } from '@heroicons/react/24/outline';

import { useAskConnex } from '@/app/components/ask-connex/AskConnexProvider';
import { useAskConnexSkills } from '@/app/hooks/useAskConnexSkills';
import { askConnexMentionToken } from '@/app/lib/askConnex';
import { canExplainAskConnexSignal } from '@/app/lib/askConnexEntryPoints';
import type { RadarFamily, RadarSubject } from '@/app/lib/types';
import { DropdownMenuItem, DropdownMenuSeparator } from '@/components/ui/dropdown-menu';

/**
 * Ask Connex's one entry into a Radar signal: an explanation, on request.
 *
 * Radar owns what a signal is, when it fired, and the evidence beneath it — the card's own "Why"
 * disclosure is that truth and is not replaced here. This asks for the reading around it, and only
 * where a declared capability exists that reads the same ground the signal was raised from. A family
 * with no such capability offers nothing rather than handing the question to a general answer that
 * would restate the card back to the reader.
 *
 * The signal's subject travels as an ordinary reference chip, so what the question will read is
 * visible in the context strip and can be taken out there like anything else.
 */
export default function AskConnexSignalEntry({
    family,
    subject,
}: {
    family: RadarFamily;
    subject: RadarSubject;
}) {
    const t = useTranslations('AskConnex');
    const { openWithPrompt } = useAskConnex();
    const skills = useAskConnexSkills(subject.type);

    if (!canExplainAskConnexSignal(skills, family, subject.type)) return null;

    const token = askConnexMentionToken({
        kind: subject.type,
        id: subject.id,
        label: subject.label,
    });
    if (token.length === 0) return null;

    return (
        <>
            <DropdownMenuItem
                onSelect={() => openWithPrompt({
                    prompt: t(`signals.${family}.prompt`, { subject: token }),
                })}
            >
                <SparklesIcon aria-hidden />
                {t(`signals.${family}.label`)}
            </DropdownMenuItem>
            <DropdownMenuSeparator />
        </>
    );
}
