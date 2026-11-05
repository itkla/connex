'use client';

import { useRef } from 'react';
import { useTranslations } from 'next-intl';
import { motion, useReducedMotion } from 'motion/react';

import { actionLabel } from '@/app/lib/actions/actionLabels';
import { instant, springJiggle } from '@/app/lib/motion';
import type { AppAction } from '@/app/lib/actions/types';

const SELECTOR_LIST_VARIANTS = { hidden: {}, show: { transition: { staggerChildren: 0.035, delayChildren: 0.03 } } };
const SELECTOR_ITEM_VARIANTS = {
    hidden: { opacity: 0, y: 8, scale: 0.96 },
    show: { opacity: 1, y: 0, scale: 1, transition: springJiggle },
};

/** Registry-driven create-action selector shared by the desktop panel and mobile flow. */
export default function QuickCreateTypeSelector({
    actions,
    onSelect,
}: {
    actions: readonly AppAction[];
    onSelect: (action: AppAction) => void;
}) {
    const t = useTranslations('Actions');
    const tMessage = useTranslations();
    const reduceMotion = useReducedMotion() ?? false;
    const listRef = useRef<HTMLDivElement>(null);

    const handleKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
        if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
        event.preventDefault();
        const items = Array.from(listRef.current?.querySelectorAll<HTMLElement>('[role="option"]') ?? []);
        if (items.length === 0) return;
        const index = items.indexOf(document.activeElement as HTMLElement);
        const nextIndex = event.key === 'ArrowDown'
            ? (index + 1) % items.length
            : (index - 1 + items.length) % items.length;
        items[nextIndex]?.focus();
    };

    return (
        <motion.div
            ref={listRef}
            role="listbox"
            aria-label={t('quickCreate.title')}
            onKeyDown={handleKeyDown}
            variants={reduceMotion ? undefined : SELECTOR_LIST_VARIANTS}
            initial={reduceMotion ? undefined : 'hidden'}
            animate={reduceMotion ? undefined : 'show'}
            className="grid gap-1"
        >
            {actions.map((action, index) => {
                const Icon = action.icon;
                return (
                    <motion.button
                        key={action.id}
                        type="button"
                        role="option"
                        aria-selected={false}
                        tabIndex={index === 0 ? 0 : -1}
                        data-autofocus={index === 0 ? '' : undefined}
                        onClick={() => onSelect(action)}
                        variants={reduceMotion ? undefined : SELECTOR_ITEM_VARIANTS}
                        whileTap={reduceMotion ? undefined : { scale: 0.97 }}
                        transition={reduceMotion ? instant : springJiggle}
                        className="group flex items-center gap-3 rounded-xl px-2.5 py-2.5 text-left transition-colors duration-(--motion-micro) hover:bg-muted focus-visible:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand"
                    >
                        <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-muted text-muted-foreground ring-1 ring-border transition-colors group-hover:bg-brand-light group-hover:text-brand-dark group-hover:ring-transparent group-focus-visible:bg-brand-light group-focus-visible:text-brand-dark">
                            {Icon ? <Icon className="size-4" /> : null}
                        </span>
                        <span className="flex-1 text-sm font-medium text-foreground">
                            {actionLabel(action, t, tMessage)}
                        </span>
                    </motion.button>
                );
            })}
        </motion.div>
    );
}
