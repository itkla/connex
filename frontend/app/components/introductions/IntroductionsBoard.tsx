'use client';

import { useState } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { useTranslations } from 'next-intl';
import { ArrowsRightLeftIcon, PlusIcon } from '@heroicons/react/24/outline';

import SectionHeader from '@/app/components/dashboard/SectionHeader';
import Rise from '@/app/components/motion/Rise';
import { Button } from '@/components/ui/button';
import { dismissIntroSuggestion, recordIntroduction } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type { Contact, IntroSuggestion, IntroductionRecord } from '@/app/lib/types';

import IntroLineageList from './IntroLineageList';
import IntroSuggestionCard from './IntroSuggestionCard';
import LogIntroDialog from './LogIntroDialog';

const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];

function pairKey(personAId: number, personBId: number): string {
    return `${Math.min(personAId, personBId)}-${Math.max(personAId, personBId)}`;
}

/**
 * Client orchestration for the Introductions page: suggested intros (with optimistic record/dismiss)
 * and the lineage of intros made. Records made here also create the connection on the backend, so a
 * recorded pair simply leaves the suggestion list.
 */
export default function IntroductionsBoard({
    initialSuggestions,
    initialLineage,
    contacts,
}: {
    initialSuggestions: IntroSuggestion[];
    initialLineage: IntroductionRecord[];
    contacts: Contact[];
}) {
    const t = useTranslations('Introductions');
    const reduce = useReducedMotion() ?? false;
    const [suggestions, setSuggestions] = useState(initialSuggestions);
    const [lineage, setLineage] = useState(initialLineage);

    const indexOfSuggestion = (suggestion: IntroSuggestion) => {
        const key = pairKey(suggestion.personAId, suggestion.personBId);
        return suggestions.findIndex((s) => pairKey(s.personAId, s.personBId) === key);
    };

    const removeSuggestion = (personAId: number, personBId: number) => {
        const key = pairKey(personAId, personBId);
        setSuggestions((current) => current.filter((s) => pairKey(s.personAId, s.personBId) !== key));
    };

    const restoreSuggestion = (suggestion: IntroSuggestion, index: number) => {
        const key = pairKey(suggestion.personAId, suggestion.personBId);
        setSuggestions((current) => {
            if (current.some((s) => pairKey(s.personAId, s.personBId) === key)) return current;
            const next = [...current];
            next.splice(Math.min(Math.max(index, 0), next.length), 0, suggestion);
            return next;
        });
    };

    const record = async (suggestion: IntroSuggestion) => {
        const index = indexOfSuggestion(suggestion);
        removeSuggestion(suggestion.personAId, suggestion.personBId);
        try {
            const created = await recordIntroduction({
                personAId: suggestion.personAId,
                personBId: suggestion.personBId,
            });
            setLineage((current) => [created, ...current]);
            toastSuccess(t('recordedToast', { a: suggestion.personAName, b: suggestion.personBName }));
        } catch (err) {
            restoreSuggestion(suggestion, index);
            toastError(err instanceof Error ? err.message : t('recordFailed'));
        }
    };

    const dismiss = async (suggestion: IntroSuggestion) => {
        const index = indexOfSuggestion(suggestion);
        removeSuggestion(suggestion.personAId, suggestion.personBId);
        try {
            await dismissIntroSuggestion({
                personAId: suggestion.personAId,
                personBId: suggestion.personBId,
            });
        } catch (err) {
            restoreSuggestion(suggestion, index);
            toastError(err instanceof Error ? err.message : t('dismissFailed'));
        }
    };

    const logManual = async (personAId: number, personBId: number, note?: string) => {
        try {
            const created = await recordIntroduction({ personAId, personBId, note });
            setLineage((current) => [created, ...current.filter((i) => i.id !== created.id)]);
            removeSuggestion(personAId, personBId);
            toastSuccess(t('recordedManualToast'));
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('recordFailed'));
            throw err;
        }
    };

    return (
        <div className="flex flex-col gap-10">
            <Rise delay={0.06}>
                <section>
                    <SectionHeader
                        title={t('toMake')}
                        action={
                            <LogIntroDialog
                                contacts={contacts}
                                onRecord={logManual}
                                trigger={
                                    <Button variant="outline" size="sm">
                                        <PlusIcon className="size-4" />
                                        {t('logIntro')}
                                    </Button>
                                }
                            />
                        }
                    />
                    {suggestions.length === 0 ? (
                        <div className="flex flex-col items-center justify-center gap-2 rounded-2xl border border-dashed border-border bg-card px-6 py-16 text-center">
                            <ArrowsRightLeftIcon className="size-6 text-muted-foreground" aria-hidden />
                            <p className="text-sm font-medium text-foreground">{t('toMakeEmptyTitle')}</p>
                            <p className="max-w-md text-sm text-muted-foreground">{t('toMakeEmptyHint')}</p>
                        </div>
                    ) : (
                        <motion.div layout className="grid grid-cols-1 gap-4 lg:grid-cols-2">
                            <AnimatePresence mode="popLayout" initial={false}>
                                {suggestions.map((suggestion, index) => (
                                    <motion.div
                                        key={pairKey(suggestion.personAId, suggestion.personBId)}
                                        layout
                                        initial={reduce ? false : { opacity: 0, y: 12 }}
                                        animate={{ opacity: 1, y: 0 }}
                                        exit={reduce ? { opacity: 0 } : { opacity: 0, scale: 0.97 }}
                                        transition={{
                                            duration: reduce ? 0 : 0.22,
                                            ease: EASE_OUT,
                                            delay: reduce ? 0 : Math.min(index, 6) * 0.04,
                                        }}
                                    >
                                        <IntroSuggestionCard
                                            suggestion={suggestion}
                                            onRecord={() => void record(suggestion)}
                                            onDismiss={() => void dismiss(suggestion)}
                                        />
                                    </motion.div>
                                ))}
                            </AnimatePresence>
                        </motion.div>
                    )}
                </section>
            </Rise>

            <Rise delay={0.12}>
                <section>
                    <SectionHeader title={t('lineageTitle')} />
                    <IntroLineageList items={lineage} />
                </section>
            </Rise>
        </div>
    );
}
